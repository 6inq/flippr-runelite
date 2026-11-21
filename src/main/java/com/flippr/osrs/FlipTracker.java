package com.flippr.osrs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.client.game.ItemManager;

// Matches completed buys against completed sells, per item, to produce
// CompletedFlip records - real flip history, not just raw event logs.
//
// GrandExchangeOfferChanged fires repeatedly as an offer fills incrementally,
// with getQuantitySold()/getSpent() as CUMULATIVE totals for that offer, not
// per-event deltas - so a naive "add on every event" would massively
// double-count. Only a genuinely new terminal state (BOUGHT, CANCELLED_BUY,
// SOLD, CANCELLED_SELL) per slot is processed, once, via a per-slot
// dedup key.
//
// GE tax: 2% of the realized sell price per unit, floored, capped at
// 5,000,000 per unit - confirmed against the OSRS Wiki's "Grand Exchange"
// article (tax rate raised from 1% to 2% on 29 May 2025). The same article's
// "Exempt from tax" table (fetched live, not from memory) lists items that
// pay zero tax regardless of price - see TAX_EXEMPT_NAMES.
//
// A sell you price-edit mid-fill isn't one offer, mechanically: OSRS cancels
// the unfilled remainder and expects a fresh offer for the leftover qty. See
// SellEpisode - fragments across that kind of edit are accumulated and only
// become one CompletedFlip once the item is actually fully sold, so a single
// real sale doesn't get split into disconnected flip records. A partial
// sell that's abandoned entirely (cancelled, collected, remainder never
// re-listed within staleSellFlushMinutes) is closed out as a flip for the
// units that sold, and so is any open episode at logout/shutdown; the unsold
// units stay an open position.
// @Singleton: only ever @Inject'd into FlipprPlugin today, but see
// DecisionEngine's class comment for why every stateful tracker class in
// this plugin gets this annotation regardless of current injection-site
// count - a second injection site added later (a future overlay reading
// session totals directly, say) would otherwise silently fork this
// tracker's state with no compile error and no obvious symptom beyond
// "the numbers are wrong."
@Slf4j
@Singleton
public class FlipTracker
{
	private static class OpenPosition
	{
		String itemName;
		long qtyBought;
		long totalSpent;
		long firstBuyTime;
		// Stamped on every buy contribution, including top-ups after a
		// cancel-and-retry - always reflects the most recent moment buying
		// activity happened, i.e. when the position finished accumulating.
		long lastBuyTime;
		// Live market price (PriceFeed) at firstBuyTime - same "only stamped
		// on first contribution" rule as firstBuyTime itself, for the same
		// reason: this describes when the FLIP ATTEMPT began, not any later
		// top-up.
		Long referenceBuyPrice;
	}

	// A sell that's still in progress across one or more price edits. RuneLite/
	// OSRS mechanically cancels the unfilled remainder and opens a brand-new
	// offer when you edit the price of a partially-filled sell - that shows up
	// here as a terminal CANCELLED_SELL with quantitySold > 0 followed by a
	// fresh SELLING for the leftover qty. Fragments accumulate here and only
	// become a CompletedFlip once the item is actually fully sold, so one
	// real-world sale doesn't get split into disconnected flip records.
	private static class SellEpisode
	{
		long qtySold;
		long totalReceived;
		long totalTax;
		// Snapshot of the flip's details as of the latest fragment, so a
		// cancelled partial sell that's never re-listed can still be closed
		// out later (closeSellEpisode) after the position has moved on.
		String itemName;
		long avgBuyPrice;
		long firstBuyTime;
		long lastBuyTime;
		Long referenceBuyPrice;
		Long referenceSellPrice;
		long sellStartTime;
		// Set once the cancelled offer's slot is collected: close the
		// episode at this time unless the item is listed for sale again
		// first. 0 = no deadline.
		long closeAt;
	}

	// One finished buy contribution, for the rolling 4h GE buy-limit window.
	private static class BuyRecord
	{
		final int itemId;
		final long quantity;
		final long time;

		BuyRecord(int itemId, long quantity, long time)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.time = time;
		}
	}

	private static final long BUY_HISTORY_MAX_AGE_MS = 4 * 60 * 60_000L;

	private static final long TAX_RATE_PERCENT = 2;
	private static final long TAX_CAP_PER_UNIT = 5_000_000;

	// Fixed zone for dayOfWeek/hourOfDay on CompletedFlip - America/New_York,
	// so time-of-day patterns line up the same way whatever the player's own
	// time zone is.
	private static final ZoneId FLIP_TIME_ZONE = ZoneId.of("America/New_York");

	// Verbatim from the OSRS Wiki's "Grand Exchange" article, "Exempt from
	// tax" section, fetched live. Matched by exact display name rather than
	// hardcoded item ID because several of these names resolve to more than
	// one real item ID (e.g. quest-only vs GE-tradeable copies of the same
	// named item share a display name) - name matching is correct for
	// whichever one actually shows up in a real GE offer. Games necklace and
	// Ring of dueling are exempt only at their (8)-charge name specifically,
	// per the wiki - not all charge counts, so those keep the exact suffix.
	private static final Set<String> TAX_EXEMPT_NAMES = Set.of(
		"Old school bond",
		"Bronze arrow", "Bronze dart", "Iron arrow", "Iron dart", "Mind rune", "Steel arrow", "Steel dart",
		"Bass", "Bread", "Cake", "Cooked chicken", "Cooked meat", "Herring", "Lobster", "Mackerel",
		"Meat pie", "Pike", "Salmon", "Shrimps", "Tuna",
		"Ardougne teleport", "Camelot teleport", "Civitas illa fortis teleport", "Falador teleport",
		"Games necklace(8)", "Kourend castle teleport", "Lumbridge teleport", "Ring of dueling(8)",
		"Teleport to house", "Varrock teleport",
		"Chisel", "Gardening trowel", "Glassblowing pipe", "Hammer", "Needle", "Pestle and mortar",
		"Rake", "Saw", "Secateurs", "Seed dibber", "Shears", "Spade", "Watering can"
	);

	// Energy potion is exempt at every dose (the wiki lists it without a dose
	// suffix, and the GE lists each dose as a separately-named item) - prefix
	// match rather than enumerating "Energy potion(1)".."(4)" individually.
	private static boolean isTaxExempt(String itemName)
	{
		return TAX_EXEMPT_NAMES.contains(itemName) || itemName.startsWith("Energy potion");
	}

	// Package-visible, not private: DecisionEngine's min-predicted-profit
	// gate needs the exact same real tax math this class already uses for
	// completed flips (same rate, same cap, same exemption list) to
	// estimate a real, realized-if-sold profit for a DUMP_BUY candidate -
	// one source of truth for this formula rather than a second copy that
	// could drift.
	static long computeTaxPerUnit(String itemName, long sellPricePerUnit)
	{
		if (isTaxExempt(itemName))
		{
			return 0;
		}
		return Math.min((sellPricePerUnit * TAX_RATE_PERCENT) / 100, TAX_CAP_PER_UNIT);
	}

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	@Inject
	private FlipLogger flipLogger;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private SessionLogger sessionLogger;

	@Inject
	private FlipprConfig config;

	// Overridable in tests.
	LongSupplier clock = System::currentTimeMillis;

	private final Map<Integer, OpenPosition> openPositions = new HashMap<>();
	private final Map<Integer, SellEpisode> sellEpisodes = new HashMap<>();
	private final Deque<BuyRecord> buyHistory = new ArrayDeque<>();
	// Latest "itemId:STATE" per slot, to tell whether an item is still
	// listed for sale anywhere.
	private final Map<Integer, String> lastStateBySlot = new HashMap<>();
	// Slot -> item of a cancelled sell whose episode is still open.
	private final Map<Integer, Integer> cancelledSellItemBySlot = new HashMap<>();
	private volatile String lastKnownRsn;
	private final Map<Integer, String> lastFinalizedKeyBySlot = new HashMap<>();

	// Per-slot: which (item, side) is currently active, and when the plugin
	// first observed it - i.e. the true offer-start timestamp. Separate from
	// lastFinalizedKeyBySlot above: this tracks on EVERY event (including
	// non-terminal ones like a fresh BUYING with 0 fill), specifically so the
	// start time is captured at the earliest possible moment, not backed into
	// from the completion event.
	private final Map<Integer, String> activeKeyBySlot = new HashMap<>();
	private final Map<Integer, Long> offerStartTimeBySlot = new HashMap<>();
	// Captured at the exact same moment as offerStartTimeBySlot, for the same
	// slot, and reused across the same episode's price-edit fragments the
	// same way - PriceFeed's live instant price for this item the moment
	// this buy/sell episode began. Null if PriceFeed had no data yet.
	private final Map<Integer, Long> referencePriceBySlot = new HashMap<>();

	// Running totals for this plugin session only (resets on client restart,
	// never persisted) - what FlipprPanel's sidebar shows. The real history
	// lives in flips.jsonl; this is just a quick at-a-glance readout.
	private volatile int sessionFlipCount;
	private volatile long sessionProfit;

	// Stamped at construction (i.e. plugin startup) and again on every
	// resetSession() call - not on the first flip - so "session time"/
	// "hourly profit" reflect real elapsed time since the session actually
	// began, matching what a player means by "session," the same way
	// FlipprPanel's other session stats already do. Not final: resetSession()
	// re-stamps it.
	private volatile long sessionStartTime = System.currentTimeMillis();

	// Most recent completed flips, newest first, capped - the sidebar's
	// history list. Same "quick readout, not the real record" relationship
	// to flips.jsonl as the session totals above. Writes are rare (one per
	// completed flip, on the game thread), reads happen from the Swing EDT
	// on every panel refresh - synchronized on both sides rather than a
	// concurrent collection, simplest correct option at this frequency.
	private static final int RECENT_FLIPS_LIMIT = 25;
	private final Deque<CompletedFlip> recentFlips = new ArrayDeque<>(RECENT_FLIPS_LIMIT);

	// The RSN of the account this JVM/plugin instance is currently running
	// as - real display name, resolved from the local player. Null only in
	// the narrow window before the player's actually loaded into the world -
	// which never actually coincides with a completed flip in practice,
	// since placing/filling a real GE offer requires being logged in and
	// rendered already. Deliberately doesn't fall back to Client.getUsername()
	// - confirmed deprecated in this RuneLite version. This is what makes
	// MultiAccountAggregator's read-back of the
	// shared flips.jsonl/sessions.jsonl actually attributable per account -
	// see MultiAccountAggregator, which already skips any record with a null
	// rsn rather than mis-attributing it.
	private String getCurrentRsn()
	{
		Player localPlayer = client.getLocalPlayer();
		String name = localPlayer == null ? null : localPlayer.getName();
		if (name != null)
		{
			lastKnownRsn = name;
			return name;
		}
		// At logout the local player is already gone, but a cancelled sell
		// closed out then still belongs to the character that just left.
		return lastKnownRsn;
	}

	// The current (or most recent) character's name; null before the first
	// login of this session.
	public String getLastKnownRsn()
	{
		return getCurrentRsn();
	}

	private long now()
	{
		return clock.getAsLong();
	}

	// Units of an item this character finished buying within the last
	// windowMs - the GE buy limit counts every buy in a rolling 4h window.
	// Only buys seen by this session count (not persisted across restarts).
	public synchronized long getBoughtInWindow(int itemId, long now, long windowMs)
	{
		long total = 0;
		for (BuyRecord record : buyHistory)
		{
			if (record.itemId == itemId && now - record.time < windowMs)
			{
				total += record.quantity;
			}
		}
		return total;
	}

	public int getSessionFlipCount()
	{
		return sessionFlipCount;
	}

	public long getSessionProfit()
	{
		return sessionProfit;
	}

	public long getSessionDurationMs()
	{
		return System.currentTimeMillis() - sessionStartTime;
	}

	// Draws a real, durable boundary between "this session" and "the next
	// one" - logs the just-ended session's totals to sessions.jsonl (see
	// SessionLogger) before zeroing everything, rather than silently
	// discarding them. The real per-flip history in flips.jsonl is
	// untouched either way; this only resets the in-memory quick-readout
	// totals FlipprPanel's sidebar shows.
	public synchronized void resetSession()
	{
		SessionSummary summary = new SessionSummary();
		summary.startTime = sessionStartTime;
		summary.endTime = System.currentTimeMillis();
		summary.rsn = getCurrentRsn();
		summary.dumpModeSession = config.watchModeDumpEnabled();
		summary.flipCount = sessionFlipCount;
		summary.profit = sessionProfit;
		sessionLogger.log(summary);

		sessionStartTime = System.currentTimeMillis();
		sessionFlipCount = 0;
		sessionProfit = 0;
		recentFlips.clear();
	}

	public synchronized List<CompletedFlip> getRecentFlips()
	{
		return Collections.unmodifiableList(new ArrayList<>(recentFlips));
	}

	// Snapshot of every currently-open (bought, not yet sold) position -
	// the Portfolio view's data, computed from real tracked
	// positions rather than a live inventory scan. Synchronized for the same
	// reason getRecentFlips() is: openPositions is written from the game
	// thread (recordBuy/matchSell) and this has to be safely readable from
	// the Swing EDT (FlipprPlugin.updatePanel() calls this on the game
	// thread and hands the resulting snapshot list to the panel, same
	// established pattern as recentFlips - the panel never touches
	// openPositions directly).
	public synchronized List<OpenPositionEntry> getOpenPositions()
	{
		List<OpenPositionEntry> entries = new ArrayList<>();
		for (Map.Entry<Integer, OpenPosition> entry : openPositions.entrySet())
		{
			OpenPosition position = entry.getValue();
			if (position.qtyBought <= 0)
			{
				continue;
			}
			entries.add(new OpenPositionEntry(
				entry.getKey(),
				position.itemName,
				position.qtyBought,
				position.totalSpent / position.qtyBought,
				position.firstBuyTime
			));
		}
		return entries;
	}

	// Manual correction for a tracked position that no longer reflects
	// reality - the item was obtained or disposed of some way this plugin
	// couldn't observe (not via a GE offer), or the player just wants a
	// clean slate for it. Wired to the "Remove-All from portfolio"
	// right-click option (PortfolioContextMenu). Synchronized to match
	// getOpenPositions()'s own discipline - openPositions is normally only
	// ever touched from the game thread, but this is invoked from a
	// chatbox/menu-click callback whose exact thread isn't guaranteed the
	// same way, so this is real defense, not redundant caution.
	public synchronized void removePosition(int itemId)
	{
		openPositions.remove(itemId);
	}

	// Same manual-correction rationale as removePosition(), but for a
	// partial amount - "Remove-X from portfolio". Reduces the tracked
	// quantity by up to `quantity`, keeping the same average buy price for
	// whatever remains (proportionally reduces totalSpent) rather than
	// recomputing it from nothing. Removes the position entirely once the
	// remaining quantity would hit zero or below.
	public synchronized void reducePosition(int itemId, long quantity)
	{
		OpenPosition position = openPositions.get(itemId);
		if (position == null || quantity <= 0)
		{
			return;
		}
		if (quantity >= position.qtyBought)
		{
			openPositions.remove(itemId);
			return;
		}
		long avgBuyPrice = position.totalSpent / position.qtyBought;
		position.qtyBought -= quantity;
		position.totalSpent -= avgBuyPrice * quantity;
	}

	// Restores a portfolio position from an imported export (see
	// PortfolioExporter) - same merge-by-weighted-average behavior a real
	// recordBuy() would produce if this exact stock had been bought fresh
	// just now, so importing on top of positions this session already
	// tracked combines cleanly rather than overwriting. Synchronized to
	// match every other public mutator here (removePosition/
	// reducePosition) - called from a UI action (Import button), never
	// from process() on the game thread.
	public synchronized void restoreOpenPosition(int itemId, String itemName, long quantity, long avgBuyPrice, long firstBuyTime)
	{
		if (quantity <= 0)
		{
			return;
		}
		OpenPosition position = openPositions.computeIfAbsent(itemId, id ->
		{
			OpenPosition p = new OpenPosition();
			p.itemName = itemName;
			p.firstBuyTime = firstBuyTime;
			return p;
		});
		position.qtyBought += quantity;
		position.totalSpent += avgBuyPrice * quantity;
		position.lastBuyTime = now();
	}

	public synchronized void process(int slot, GrandExchangeOffer offer)
	{
		GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			activeKeyBySlot.remove(slot);
			offerStartTimeBySlot.remove(slot);
			referencePriceBySlot.remove(slot);
			return;
		}

		boolean isBuy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;

		lastStateBySlot.put(slot, offer.getItemId() + ":" + state);
		if (state == GrandExchangeOfferState.SELLING)
		{
			// Listed for sale again: an open episode for this item keeps
			// going (stitched) instead of being closed out.
			SellEpisode episode = sellEpisodes.get(offer.getItemId());
			if (episode != null)
			{
				episode.closeAt = 0;
			}
		}

		// itemId + side (not the exact state) - BUYING -> BOUGHT is the same
		// offer continuing, not a new one starting, so this must NOT reset the
		// start time on that transition. A genuinely new offer (different item,
		// or same item re-listed after the slot went EMPTY in between) gets a
		// different key and a fresh start time.
		String activeKey = offer.getItemId() + ":" + (isBuy ? "BUY" : "SELL");
		if (!activeKey.equals(activeKeyBySlot.get(slot)))
		{
			activeKeyBySlot.put(slot, activeKey);
			offerStartTimeBySlot.put(slot, now());
			Long referencePrice = isBuy
				? priceFeed.getInstantSellPrice(offer.getItemId())
				: priceFeed.getInstantBuyPrice(offer.getItemId());
			if (referencePrice == null)
			{
				referencePriceBySlot.remove(slot);
			}
			else
			{
				referencePriceBySlot.put(slot, referencePrice);
			}
		}

		boolean terminal = state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_SELL;

		if (!terminal || offer.getQuantitySold() == 0)
		{
			return;
		}

		String finalizedKey = offer.getItemId() + ":" + state + ":" + offer.getQuantitySold();
		if (finalizedKey.equals(lastFinalizedKeyBySlot.get(slot)))
		{
			return;
		}
		lastFinalizedKeyBySlot.put(slot, finalizedKey);

		long offerStartTime = offerStartTimeBySlot.getOrDefault(slot, now());
		Long referencePrice = referencePriceBySlot.get(slot);
		if (isBuy)
		{
			recordBuy(offer, offerStartTime, referencePrice);
		}
		else
		{
			matchSell(slot, offer, offerStartTime, state, referencePrice);
		}
	}

	private void recordBuy(GrandExchangeOffer offer, long offerStartTime, Long referencePrice)
	{
		OpenPosition position = openPositions.computeIfAbsent(offer.getItemId(), id ->
		{
			OpenPosition p = new OpenPosition();
			p.itemName = itemManager.getItemComposition(id).getName();
			// Only stamped on the position's first-ever contribution, so a
			// later top-up buy on the same item doesn't push this forward -
			// firstBuyTime is "when this flip attempt began," not "when the
			// most recent buy began."
			p.firstBuyTime = offerStartTime;
			p.referenceBuyPrice = referencePrice;
			return p;
		});
		position.qtyBought += offer.getQuantitySold();
		position.totalSpent += offer.getSpent();
		position.lastBuyTime = now();
		recordBuyHistory(offer.getItemId(), offer.getQuantitySold());
	}

	private void recordBuyHistory(int itemId, long quantity)
	{
		long now = now();
		buyHistory.addLast(new BuyRecord(itemId, quantity, now));
		while (!buyHistory.isEmpty() && now - buyHistory.peekFirst().time >= BUY_HISTORY_MAX_AGE_MS)
		{
			buyHistory.removeFirst();
		}
	}

	private void matchSell(int slot, GrandExchangeOffer offer, long sellStartTime, GrandExchangeOfferState state, Long referenceSellPrice)
	{
		OpenPosition position = openPositions.get(offer.getItemId());
		if (position == null || position.qtyBought == 0)
		{
			// No tracked cost basis for this item - likely pre-owned stock
			// sold before the plugin ever saw it bought. Nothing to match,
			// no flip record without a real buy price to compare against.
			log.debug("Sell of itemId={} with no open buy position - skipping", offer.getItemId());
			return;
		}

		String itemName = itemManager.getItemComposition(offer.getItemId()).getName();

		long soldQty = offer.getQuantitySold();
		long matchedQty = Math.min(soldQty, position.qtyBought);

		long avgBuyPrice = position.totalSpent / position.qtyBought;
		long fragmentSellPrice = offer.getSpent() / soldQty;

		long perUnitTax = computeTaxPerUnit(itemName, fragmentSellPrice);
		long fragmentTax = perUnitTax * matchedQty;

		SellEpisode episode = sellEpisodes.computeIfAbsent(offer.getItemId(), id -> new SellEpisode());
		if (episode.qtySold == 0)
		{
			// First fragment: when this stock started trying to sell.
			episode.sellStartTime = sellStartTime;
		}
		episode.qtySold += matchedQty;
		episode.totalReceived += fragmentSellPrice * matchedQty;
		episode.totalTax += fragmentTax;
		episode.itemName = itemName;
		episode.avgBuyPrice = avgBuyPrice;
		episode.firstBuyTime = position.firstBuyTime;
		episode.lastBuyTime = position.lastBuyTime;
		episode.referenceBuyPrice = position.referenceBuyPrice;
		episode.referenceSellPrice = referenceSellPrice;

		position.qtyBought -= matchedQty;
		position.totalSpent -= avgBuyPrice * matchedQty;
		boolean positionExhausted = position.qtyBought <= 0;
		if (positionExhausted)
		{
			openPositions.remove(offer.getItemId());
		}

		// SOLD means this specific offer filled completely - done. A
		// CANCELLED_SELL only closes out the episode if there's nothing left
		// in the position to continue selling; otherwise this was a
		// partial-fill price edit (GE cancels the remainder and expects a
		// fresh offer for it) and the episode stays open, waiting for that
		// continuation instead of emitting a fragment as its own flip. If
		// the remainder is never re-listed, onSlotEmptied()/onGameTick()
		// close it after staleSellFlushMinutes, or logout does.
		boolean episodeFinal = state == GrandExchangeOfferState.SOLD || positionExhausted;
		if (!episodeFinal)
		{
			if (state == GrandExchangeOfferState.CANCELLED_SELL)
			{
				cancelledSellItemBySlot.put(slot, offer.getItemId());
			}
			return;
		}
		sellEpisodes.remove(offer.getItemId());
		emitFlip(offer.getItemId(), episode);
	}

	// The cancelled sell in this slot was collected. Unless the item is
	// listed again within staleSellFlushMinutes, its episode is closed out
	// as a finished flip for the units that did sell.
	public synchronized void onSlotEmptied(int slot)
	{
		lastStateBySlot.remove(slot);
		Integer itemId = cancelledSellItemBySlot.remove(slot);
		if (itemId == null)
		{
			return;
		}
		SellEpisode episode = sellEpisodes.get(itemId);
		if (episode != null && !isListedForSale(itemId))
		{
			episode.closeAt = now() + Math.max(0, config.staleSellFlushMinutes()) * 60_000L;
		}
	}

	public void onGameTick()
	{
		getCurrentRsn();
		closeStaleSellEpisodes();
	}

	synchronized void closeStaleSellEpisodes()
	{
		long now = now();
		Iterator<Map.Entry<Integer, SellEpisode>> it = sellEpisodes.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, SellEpisode> entry = it.next();
			SellEpisode episode = entry.getValue();
			if (episode.closeAt > 0 && now >= episode.closeAt && !isListedForSale(entry.getKey()))
			{
				it.remove();
				emitFlip(entry.getKey(), episode);
			}
		}
	}

	// Logout or plugin shutdown: every partly sold episode is recorded now
	// with the quantity sold so far; unsold units stay open positions.
	public synchronized void closeAllSellEpisodes()
	{
		Iterator<Map.Entry<Integer, SellEpisode>> it = sellEpisodes.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, SellEpisode> entry = it.next();
			it.remove();
			if (entry.getValue().qtySold > 0)
			{
				emitFlip(entry.getKey(), entry.getValue());
			}
		}
		cancelledSellItemBySlot.clear();
	}

	private boolean isListedForSale(int itemId)
	{
		return lastStateBySlot.containsValue(itemId + ":" + GrandExchangeOfferState.SELLING);
	}

	private void emitFlip(int itemId, SellEpisode episode)
	{
		long sellCompletedTime = now();
		long avgSellPrice = episode.totalReceived / episode.qtySold;

		CompletedFlip flip = new CompletedFlip();
		flip.timestamp = sellCompletedTime;
		flip.rsn = getCurrentRsn();
		flip.dumpModeSession = config.watchModeDumpEnabled();
		flip.itemId = itemId;
		flip.itemName = episode.itemName;
		flip.quantity = (int) episode.qtySold;
		flip.avgBuyPrice = episode.avgBuyPrice;
		flip.avgSellPrice = avgSellPrice;
		flip.tax = episode.totalTax;
		flip.profit = episode.totalReceived - episode.totalTax - (episode.avgBuyPrice * episode.qtySold);
		flip.firstBuyTime = episode.firstBuyTime;
		flip.lastSellTime = sellCompletedTime;
		flip.referenceBuyPrice = episode.referenceBuyPrice;
		flip.referenceSellPrice = episode.referenceSellPrice;
		// Total wall-clock time from placing the first buy to the sell
		// finishing - the flip's overall duration (Regular Flipping aims for
		// roughly 5-10 minutes).
		flip.totalDurationMs = sellCompletedTime - episode.firstBuyTime;
		// How long it took to actually acquire the position, start to finish,
		// including any cancel-and-retry buy fragments along the way - mirrors
		// sellFillTimeMs but for the buy side.
		flip.buyFillTimeMs = episode.lastBuyTime - episode.firstBuyTime;
		// How long this stock sat trying to sell, start to finish, including
		// any price-edit fragments along the way - sellStartTime is the first
		// fragment's start, so this spans the whole episode, not just the
		// final fragment - the item's real fill time.
		flip.sellFillTimeMs = sellCompletedTime - episode.sellStartTime;

		ZonedDateTime completedAt = Instant.ofEpochMilli(sellCompletedTime).atZone(FLIP_TIME_ZONE);
		flip.dayOfWeek = completedAt.getDayOfWeek().toString();
		flip.hourOfDay = completedAt.getHour();

		sessionFlipCount++;
		sessionProfit += flip.profit;
		addRecentFlip(flip);

		flipLogger.log(flip);
	}

	private synchronized void addRecentFlip(CompletedFlip flip)
	{
		recentFlips.addFirst(flip);
		while (recentFlips.size() > RECENT_FLIPS_LIMIT)
		{
			recentFlips.removeLast();
		}
	}
}
