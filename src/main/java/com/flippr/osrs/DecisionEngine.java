package com.flippr.osrs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;

// Read-only decision logic - classifies each of the 8 GE slots and logs the
// action that slot's own offer needs, without acting on anything. Nothing
// here clicks: the plugin only highlights, it never automates
// (SuggestionOverlay draws what this decides).
//
// Each of the 8 slots gets its OWN independent suggestion (COLLECT >
// ABANDON > MODIFY > START_NEW_FLIP, in that priority order, since a single
// slot can only be in one of those states at a time). This used to be a
// single GLOBAL "top suggestion across all slots" instead - found to be a
// real bug by comparing decisions.jsonl against a real session where the
// player modified offers repeatedly but zero MODIFY suggestions were ever
// logged: with several slots stuck at 0%-fill simultaneously (a common,
// ordinary state during a busy session), ABANDON was true SOMEWHERE on
// almost every evaluation, and a single global winner meant ABANDON on one
// slot permanently hid a real, simultaneously-true MODIFY on a different
// slot - not because nothing was happening, but because only one thing
// was ever allowed to be reported at a time. Per-slot evaluation removes
// that masking entirely: slot 3 being ABANDON-due can never suppress slot 5
// being MODIFY-due again.
//
// Dump Mode is deliberately scoped down (see SuggestionType.DUMP_BUY): a
// live dump only ever claims a slot that's ALREADY empty, never suggests
// sacrificing a healthy flip to manufacture a reserve - slots are never
// forcibly or artificially freed for dumps, and there is no maintained
// standing reserve slot. What to buy into a
// freed slot outside of a live dump comes from the Flippr website when the
// plugin is paired (ServerSuggestions: the player's plan, or the server's
// ranked candidates) - START_NEW_FLIP then carries a real item, price and
// quantity, and held plan/portfolio stock gets a SELL; unpaired or offline,
// a free slot is just "free", as before. Live price-following (the
// modify-due check below), though, runs on PriceFeed - the OSRS Wiki's
// real-time prices API - rather than elapsed time alone. Elapsed time is
// kept only as a fallback for items PriceFeed hasn't observed yet.
//
// Elapsed-time/price-move thresholds are user-configurable (FlipprConfig) but
// still one fixed number applied to every item, standing in for real
// per-item fill-time data this plugin doesn't have.
// @Singleton matters here and is not optional: this class is @Inject'd into
// three different places (FlipprPlugin, SuggestionOverlay, ChatNotifier).
// RuneLite's plugin injector applies NO default singleton scope to plain
// classes (confirmed against its own PluginManager source - plugins get a
// Guice child injector with no scope override, so unscoped JIT bindings
// follow plain Guice semantics: a fresh instance per injection point, not
// shared automatically just because they're in the same plugin). Without
// this annotation, FlipprPlugin's copy is the only one that ever receives
// onOfferChanged()/onGameTick() - SuggestionOverlay and ChatNotifier would
// each hold their OWN separate, permanently-empty DecisionEngine, silently
// never seeing a single real suggestion. This is very likely the actual
// root cause of "no highlights ever appear," not a config-flag or rendering
// bug - SuggestionOverlay's render() pipeline, widget lookup, and even the
// config gate were all independently confirmed correct earlier; the one
// thing never checked was whether its own decisionEngine field was even the
// same object FlipprPlugin was feeding events into. It wasn't.
@Slf4j
@Singleton
public class DecisionEngine
{
	private static final int SLOT_COUNT = 8;
	private static final double NEAR_COMPLETE_FILL_RATIO = 0.99;

	private static class SlotState
	{
		GrandExchangeOfferState offerState = GrandExchangeOfferState.EMPTY;
		int itemId;
		String itemName;
		long quantitySold;
		long totalQuantity;
		// The offer's own currently-listed unit price - unlike
		// referencePriceAtStart below (stamped once, at offer-open), this
		// updates on every real state change including price edits.
		long offerPrice;
		long offerStartTime;
		// The live instant price (instant-sell for a buy offer, instant-buy
		// for a sell offer) observed via PriceFeed at the moment this offer
		// started - null if PriceFeed had no data for the item yet. The
		// modify-due check compares the CURRENT live price against this
		// baseline, not against the offer's own listed price.
		Long referencePriceAtStart;
		// Stamped once, at the same isNewOffer moment as referencePriceAtStart:
		// was this item actively dumping (per DumpDetector) the instant this
		// buy was placed? Drives the much tighter dump-buy abandonment clock
		// below - true regardless of whether the player bought in because of
		// this plugin's own DUMP_BUY suggestion or reacted to the alert
		// themselves - any dump-buy gets the tight clock, not just ones this
		// plugin directly suggested.
		boolean isDumpBuy;
	}

	// key is stable for dedup (slot + action, no live-changing numbers);
	// message is the human-readable line that actually gets logged, and can
	// safely embed elapsed time/etc since it's only ever compared by key.
	// type/slot are the structured form SuggestionOverlay reads to pick which
	// real GE widget to highlight - package-visible (not private) so the
	// overlay, a different class in the same package, can read them directly
	// without re-parsing the message text.
	static class Suggestion
	{
		final String key;
		final String message;
		final SuggestionType type;
		final int slot;
		// 0/null for START_NEW_FLIP on an empty slot - there's no item yet.
		// Lets FlipprPanel show a real item icon instead of a generic one.
		final int itemId;
		final String itemName;
		// A concrete target, when there is one: DUMP_BUY (live dump price),
		// START_NEW_FLIP/SELL from the website (ServerSuggestions - the
		// player's plan, or the server's ranked candidates), and MODIFY's new
		// price when the plan/server has one for that item. Null otherwise.
		// Read by QuickFillHandler to fill the GE quantity/price boxes on a
		// keypress - never written automatically, the player still presses
		// the key and Enter themselves for real.
		final Long suggestedPrice;
		final Long suggestedQuantity;
		// True when this is about a sell offer (SELL, or MODIFY/ABANDON/HOLD
		// of a sell) - QuickFillHandler only fills a box on the same side.
		final boolean sellSide;

		Suggestion(String key, String message, SuggestionType type, int slot, int itemId, String itemName)
		{
			this(key, message, type, slot, itemId, itemName, null, null);
		}

		Suggestion(String key, String message, SuggestionType type, int slot, int itemId, String itemName,
			Long suggestedPrice, Long suggestedQuantity)
		{
			this(key, message, type, slot, itemId, itemName, suggestedPrice, suggestedQuantity, type == SuggestionType.SELL);
		}

		Suggestion(String key, String message, SuggestionType type, int slot, int itemId, String itemName,
			Long suggestedPrice, Long suggestedQuantity, boolean sellSide)
		{
			this.key = key;
			this.message = message;
			this.type = type;
			this.slot = slot;
			this.itemId = itemId;
			this.itemName = itemName;
			this.suggestedPrice = suggestedPrice;
			this.suggestedQuantity = suggestedQuantity;
			this.sellSide = sellSide;
		}
	}

	@Inject
	private ItemManager itemManager;

	@Inject
	private DecisionLogger decisionLogger;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private DumpDetector dumpDetector;

	@Inject
	private InventoryTracker inventoryTracker;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private BuyAndHoldAdvisor buyAndHoldAdvisor;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private FlipprConfig config;

	private final SlotState[] slots = new SlotState[SLOT_COUNT];
	// Per-slot dedup - each slot only re-logs when ITS OWN suggestion
	// changes, independent of what every other slot is doing.
	private final String[] lastLoggedKeyBySlot = new String[SLOT_COUNT];
	// Index i is slot i's current suggestion, or null if nothing's due there.
	// Rebuilt wholesale each evaluate() and published via a single volatile
	// write, so SuggestionOverlay's render() always sees a consistent
	// snapshot rather than a partially-updated array.
	private volatile Suggestion[] currentSuggestions = new Suggestion[SLOT_COUNT];
	// Sell-only mode for this evaluation (ServerSuggestions.isSellOnly): no
	// dump-buys, no buy price nudges, no "start a flip" on an empty slot.
	// Buy offers already up keep filling (and can still be abandoned).
	private boolean sellOnlyNow;

	// Read by SuggestionOverlay every render() call.
	public Suggestion[] getCurrentSuggestions()
	{
		return currentSuggestions;
	}

	// The real, currently-listed unit price of an active SELL offer for this
	// item, if one exists right now - null otherwise. For FlipprPanel's
	// Portfolio tab: a position that already has a real sell offer open
	// should value against THAT actual committed price, not an external wiki
	// reference number, since the offer's own price is what will actually be
	// received if/when it fills - strictly more accurate than any index for
	// a position the player has already decided the price on.
	public Long getActiveSellPrice(int itemId)
	{
		for (SlotState slot : slots)
		{
			if (slot.offerState == GrandExchangeOfferState.SELLING && slot.itemId == itemId && slot.offerPrice > 0)
			{
				return slot.offerPrice;
			}
		}
		return null;
	}

	public DecisionEngine()
	{
		for (int i = 0; i < slots.length; i++)
		{
			slots[i] = new SlotState();
		}
	}

	public void onOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (slot < 0 || slot >= slots.length)
		{
			return;
		}

		SlotState state = slots[slot];
		GrandExchangeOfferState newState = offer.getState();

		if (newState == GrandExchangeOfferState.EMPTY)
		{
			state.offerState = GrandExchangeOfferState.EMPTY;
			state.offerStartTime = 0;
		}
		else
		{
			// A genuinely new offer (slot was empty, or a different item) gets
			// a fresh start time - a state transition on the SAME offer
			// (BUYING -> BOUGHT, or a price edit) does not, same reasoning as
			// FlipTracker's offerStartTimeBySlot.
			boolean isNewOffer = state.offerState == GrandExchangeOfferState.EMPTY
				|| state.itemId != offer.getItemId();
			boolean isBuy = newState == GrandExchangeOfferState.BUYING
				|| newState == GrandExchangeOfferState.BOUGHT
				|| newState == GrandExchangeOfferState.CANCELLED_BUY;
			state.offerState = newState;
			state.itemId = offer.getItemId();
			state.itemName = itemManager.getItemComposition(offer.getItemId()).getName();
			state.quantitySold = offer.getQuantitySold();
			state.totalQuantity = offer.getTotalQuantity();
			state.offerPrice = offer.getPrice();
			if (isNewOffer)
			{
				state.offerStartTime = System.currentTimeMillis();
				state.referencePriceAtStart = isBuy
					? priceFeed.getInstantSellPrice(offer.getItemId())
					: priceFeed.getInstantBuyPrice(offer.getItemId());
				state.isDumpBuy = isBuy && dumpDetector.isDumping(offer.getItemId());
			}
		}

		evaluate();
	}

	public void onGameTick()
	{
		priceFeed.maybePoll();
		priceFeed.maybeFetchMapping();
		// Elapsed-time/price-move thresholds (stale-buy, modify-due) need
		// re-checking even when nothing changed - GrandExchangeOfferChanged
		// only fires on an actual GE state change, so a buy silently sitting
		// at 0% (or a live price quietly drifting) would never get
		// re-evaluated without this.
		evaluate();
	}

	private void evaluate()
	{
		// Both watch modes amount to the same "no suggestions at all"
		// outcome as engineEnabled being off - checked together here so
		// each is a single real switch, not something the player also has
		// to remember to separately pause the engine for. Watch Mode
		// (Dump) is NOT a "dump-catching stays active" mode - it's
		// identical silence to plain Watch Mode; the only difference is
		// the dumpModeSession tag stamped on whatever gets logged (see
		// FlipTracker): in both, Flippr only records in the
		// background, never alerts or suggests.
		if (!config.engineEnabled() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			// Master pause - clear any stale suggestions from before the
			// engine was paused so the overlay/panel/log all reflect "nothing
			// due" immediately, rather than freezing on whatever was last
			// computed.
			currentSuggestions = new Suggestion[SLOT_COUNT];
			return;
		}

		long now = System.currentTimeMillis();
		Suggestion[] suggestions = new Suggestion[SLOT_COUNT];
		sellOnlyNow = serverSuggestions != null && serverSuggestions.isSellOnly();

		// Dump Mode's slot claim (see SuggestionType.DUMP_BUY and the class
		// comment): find the first slot that's ALREADY empty and pair it with
		// the first currently-dumping item that isn't already held somewhere
		// else - never sacrifices a healthy flip to manufacture a slot.
		// "isn't already held somewhere else" covers BOTH an item currently
		// occupying a different GE slot AND an item already bought and
		// collected into inventory but not yet sold (FlipTracker's open
		// positions) - the "one slot per item" rule: the engine should carry a
		// given item through
		// one full buy-then-sell cycle before ever buying more of it, not
		// let a second round start while the first is still unresolved,
		// whether or not a GE slot happens to be open for it right now.
		int dumpBuySlot = -1;
		int dumpBuyItemId = 0;
		Set<Integer> activeDumps = dumpDetector.getActiveDumpItemIds();
		if (!activeDumps.isEmpty() && !sellOnlyNow)
		{
			Set<Integer> itemsAlreadyHeld = new HashSet<>();
			for (SlotState slotState : slots)
			{
				if (slotState.offerState != GrandExchangeOfferState.EMPTY)
				{
					itemsAlreadyHeld.add(slotState.itemId);
				}
			}
			for (OpenPositionEntry position : flipTracker.getOpenPositions())
			{
				itemsAlreadyHeld.add(position.getItemId());
			}

			// Reserved slots (see ReservedDumpSlots) get first refusal on an
			// empty slot for dump-buying - the LAST config.reservedDumpSlots()
			// slot indices are checked first, falling back to any empty slot
			// only if none of the reserved ones are currently free. Checked
			// once here (a property of "which slot," not "which item"),
			// rather than duplicated per candidate below.
			int reservedCount = config.reservedDumpSlots().getCount();
			int candidateSlot = -1;
			if (reservedCount > 0)
			{
				for (int i = Math.max(0, SLOT_COUNT - reservedCount); i < SLOT_COUNT; i++)
				{
					if (slots[i].offerState == GrandExchangeOfferState.EMPTY)
					{
						candidateSlot = i;
						break;
					}
				}
			}
			if (candidateSlot == -1)
			{
				for (int i = 0; i < slots.length; i++)
				{
					if (slots[i].offerState == GrandExchangeOfferState.EMPTY)
					{
						candidateSlot = i;
						break;
					}
				}
			}

			if (candidateSlot != -1)
			{
				for (Integer itemId : activeDumps)
				{
					if (itemsAlreadyHeld.contains(itemId))
					{
						continue;
					}
					// minPredictedProfit (see MinPredictedProfit): a real,
					// currently-observable spread check, not a forecast -
					// AUTO/0 always passes, matching existing behavior.
					if (!meetsMinPredictedProfit(itemId))
					{
						continue;
					}
					dumpBuySlot = candidateSlot;
					dumpBuyItemId = itemId;
					break;
				}
			}
		}

		ServerSuggestions.Pick[] pickBySlot = assignServerPicks(dumpBuySlot);

		for (int i = 0; i < slots.length; i++)
		{
			Suggestion suggestion = i == dumpBuySlot
				? buildDumpBuySuggestion(i, dumpBuyItemId)
				: computeSlotSuggestion(i, slots[i], now, pickBySlot[i]);
			suggestions[i] = suggestion;

			String key = suggestion == null ? null : suggestion.key;
			if (Objects.equals(key, lastLoggedKeyBySlot[i]))
			{
				continue;
			}
			lastLoggedKeyBySlot[i] = key;
			if (suggestion != null)
			{
				decisionLogger.log(suggestion.message);
			}
			// suggestion == null means this slot's prior suggestion just
			// resolved (collected/abandoned/modified/filled) - nothing new
			// to log, the absence from future lines already says that.
		}

		currentSuggestions = suggestions;
	}

	// DUMP_BUY is decided separately, before this ever runs, at the evaluate()
	// level - it needs cross-slot context (which slot is empty, which items
	// are already held elsewhere) this per-slot function deliberately doesn't
	// have. Priority order for everything else, the Regular Flipping loop:
	// finish off a near-complete offer, then cut a stale one,
	// then nudge one that's overdue for a price modification, then start a
	// new flip on an empty slot. A single slot can only ever be in one of
	// these states at once, so this ordering is just "which check applies,"
	// not a cross-slot priority fight.
	//
	// Returns null if nothing is due for this slot right now.
	// Website picks (plan positions or ranked candidates, sells first) for
	// the empty slots START_NEW_FLIP would otherwise only call "free" -
	// skipping reserved slots and the slot DUMP_BUY claimed. Null entries
	// fall back to the plain empty-slot suggestion.
	private ServerSuggestions.Pick[] assignServerPicks(int dumpBuySlot)
	{
		ServerSuggestions.Pick[] bySlot = new ServerSuggestions.Pick[SLOT_COUNT];
		Set<Integer> itemsInSlots = new HashSet<>();
		List<Integer> freeSlots = new ArrayList<>();
		int reservedCount = config.reservedDumpSlots().getCount();
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			if (slots[i].offerState != GrandExchangeOfferState.EMPTY)
			{
				itemsInSlots.add(slots[i].itemId);
			}
			else if (i != dumpBuySlot && !(reservedCount > 0 && i >= SLOT_COUNT - reservedCount))
			{
				freeSlots.add(i);
			}
		}
		if (freeSlots.isEmpty())
		{
			return bySlot;
		}
		List<ServerSuggestions.Pick> picks = serverSuggestions.currentPicks(itemsInSlots, freeSlots.size());
		for (int n = 0; n < picks.size() && n < freeSlots.size(); n++)
		{
			bySlot[freeSlots.get(n)] = picks.get(n);
		}
		return bySlot;
	}

	// The plan's/server's price for MODIFY, only when it differs from what
	// the offer is listed at now.
	private Long modifyTargetPrice(SlotState s, boolean buy)
	{
		Long price = serverSuggestions.priceFor(s.itemId, buy);
		return price != null && price > 0 && price != s.offerPrice ? price : null;
	}

	private Suggestion computeSlotSuggestion(int i, SlotState s, long now, ServerSuggestions.Pick pick)
	{
		// Warmed unconditionally for every active sell, not just ones already
		// at the abandon threshold - self-throttled by PriceFeed's own TTL,
		// so the 6h history BuyAndHoldAdvisor needs is already cached by the
		// time (real minutes later) a sell might actually reach that
		// threshold, rather than starting the fetch at the exact moment it's
		// needed and racing the HTTP round trip.
		if (s.offerState == GrandExchangeOfferState.SELLING)
		{
			buyAndHoldAdvisor.prefetch(s.itemId);
		}

		// A finished (or cancelled) offer waits in its slot until collected:
		// collecting frees the slot and brings a bought item to the
		// inventory, where the plan's sell for it picks up.
		if (isCollectableState(s.offerState))
		{
			return new Suggestion(
				COLLECT_DONE_KEY + i,
				String.format("slot %d: COLLECT (%s, %s - collect it)", i, s.itemName, describeDone(s.offerState)),
				SuggestionType.COLLECT, i, s.itemId, s.itemName, null, null, isSellState(s.offerState)
			);
		}

		if (isFillableState(s.offerState) && s.totalQuantity > 0 && s.quantitySold < s.totalQuantity
			&& (double) s.quantitySold / s.totalQuantity >= NEAR_COMPLETE_FILL_RATIO)
		{
			return new Suggestion(
				"COLLECT:" + i,
				String.format(
					"slot %d: COLLECT (%s, %d/%d filled - finish it off rather than chase the last sliver)",
					i, s.itemName, s.quantitySold, s.totalQuantity
				),
				SuggestionType.COLLECT, i, s.itemId, s.itemName
			);
		}

		long staleZeroFillThresholdMs = (long) (config.riskLevel().scaleThreshold(config.staleFillMinutes()) * 60_000L);

		// Dynamic sell-price stepping: a sell
		// sitting at 0% filled gets a periodic MODIFY nudge to lower its
		// price, on a faster/separate cadence from - and strictly before -
		// the ABANDON give-up threshold right below. Still the same MODIFY
		// suggestion type since it's the same real action (adjust the
		// price), just triggered by elapsed time with zero progress rather
		// than a live market-price move. The key embeds a step NUMBER
		// (elapsed time / step interval), not raw elapsed time, so it only
		// changes - and only re-fires/re-logs - once each new step interval
		// actually begins, not on every single evaluate() tick.
		boolean isSell = s.offerState == GrandExchangeOfferState.SELLING;
		long stepIntervalMs = (long) (config.riskLevel().scaleThreshold(config.sellPriceStepMinutes()) * 60_000L);
		if (isSell && s.quantitySold == 0 && s.offerStartTime > 0
			&& now - s.offerStartTime < staleZeroFillThresholdMs
			&& now - s.offerStartTime >= stepIntervalMs)
		{
			long stepNumber = (now - s.offerStartTime) / stepIntervalMs;
			Long target = modifyTargetPrice(s, false);
			// Stepping lowers the price - a higher server price is not a step down.
			if (target != null && target > s.offerPrice)
			{
				target = null;
			}
			// No new price = nothing to change: opening the offer would only
			// re-list it at the same price and lose its place in the queue.
			if (target != null)
			{
				return new Suggestion(
					"MODIFY_STEP:" + i + ":" + stepNumber,
					String.format(
						"slot %d: MODIFY (%s, 0%% filled after %ds - step the price down to help it clear, to %d gp)",
						i, s.itemName, (now - s.offerStartTime) / 1000, target
					),
					SuggestionType.MODIFY, i, s.itemId, s.itemName, target, null, true
				);
			}
		}

		// A dump-buy gets a much tighter zero-fill clock than an ordinary
		// stale buy: most real dumps get fully absorbed by
		// other buyers within seconds, so a dump-buy that hasn't picked up
		// even 1 unit yet is almost certainly gone, not just slow.
		// The tight clock only applies once the dump is really over: the
		// market's instant-sell price has recovered above our buy price, so
		// sellers no longer come down to us. While the price is still at or
		// below our offer (or unknown), the dump may still fill us - it waits
		// on the ordinary stale-buy clock instead of a buy-then-abort churn.
		boolean dumpMissed = s.isDumpBuy && dumpBuyMissed(s.offerPrice, priceFeed.getInstantSellPrice(s.itemId));
		long effectiveAbandonThresholdMs = dumpMissed
			? (long) (config.riskLevel().scaleThreshold(config.dumpBuyAbandonSeconds()) * 1000L)
			: staleZeroFillThresholdMs;

		if (isSell && s.quantitySold == 0 && now - s.offerStartTime >= effectiveAbandonThresholdMs)
		{
			BuyAndHoldAdvisor.HoldSignal holdSignal = buyAndHoldAdvisor.checkHoldSignal(s.itemId);
			if (holdSignal != null)
			{
				return new Suggestion(
					"HOLD:" + i,
					String.format(
						"slot %d: HOLD (%s, 0%% filled after %ds - currently %.1f%% below its typical price for "
							+ "this day/time over the last %d comparable weeks, historically not this low - "
							+ "holding rather than selling into an unusual dip)",
						i, s.itemName, (now - s.offerStartTime) / 1000, holdSignal.getBelowPercent(), holdSignal.getSampleCount()
					),
					SuggestionType.HOLD, i, s.itemId, s.itemName, null, null, true
				);
			}
		}

		if (isFillableState(s.offerState) && s.quantitySold == 0
			&& now - s.offerStartTime >= effectiveAbandonThresholdMs)
		{
			String reason = dumpMissed
				? "missed dump-buy - the price has recovered above the offer"
				: "stale, move to a different item";
			return new Suggestion(
				"ABANDON:" + i,
				String.format(
					"slot %d: ABANDON (%s, 0%% filled after %ds - %s)",
					i, s.itemName, (now - s.offerStartTime) / 1000, reason
				),
				SuggestionType.ABANDON, i, s.itemId, s.itemName, null, null, s.offerState == GrandExchangeOfferState.SELLING
			);
		}

		if (isFillableState(s.offerState) && s.quantitySold < s.totalQuantity
			&& !(sellOnlyNow && s.offerState == GrandExchangeOfferState.BUYING))
		{
			boolean isBuy = s.offerState == GrandExchangeOfferState.BUYING;
			Long currentPrice = isBuy ? priceFeed.getInstantSellPrice(s.itemId) : priceFeed.getInstantBuyPrice(s.itemId);

			if (currentPrice != null && s.referencePriceAtStart != null && s.referencePriceAtStart > 0)
			{
				double change = Math.abs(currentPrice - s.referencePriceAtStart) / (double) s.referencePriceAtStart;
				double priceMoveThresholdRatio = config.riskLevel().scaleThreshold(config.priceMoveThresholdPercent()) / 100.0;
				Long target = change >= priceMoveThresholdRatio ? modifyTargetPrice(s, isBuy) : null;
				if (target != null)
				{
					return new Suggestion(
						"MODIFY:" + i,
						String.format(
							"slot %d: MODIFY (%s, live price moved %.1f%% since this offer opened - nudge %s%s)",
							i, s.itemName, change * 100, isBuy ? "up" : "down", target == null ? "" : ", to " + target + " gp"
						),
						SuggestionType.MODIFY, i, s.itemId, s.itemName, target, null, !isBuy
					);
				}
			}
			else if (now - s.offerStartTime >= (long) (config.riskLevel().scaleThreshold(config.modifyFallbackMinutes()) * 60_000L)
				&& modifyTargetPrice(s, isBuy) != null)
			{
				Long target = modifyTargetPrice(s, isBuy);
				return new Suggestion(
					"MODIFY:" + i,
					String.format(
						"slot %d: MODIFY (%s, open %dm without a price nudge - no live price data for this item yet, "
							+ "elapsed-time fallback%s)",
						i, s.itemName, (now - s.offerStartTime) / 60_000, target == null ? "" : ", to " + target + " gp"
					),
					SuggestionType.MODIFY, i, s.itemId, s.itemName, target, null, !isBuy
				);
			}
		}

		if (s.offerState == GrandExchangeOfferState.EMPTY)
		{
			// Reserved slots (see ReservedDumpSlots) don't get invited into
			// a regular flip - no suggestion at all here (not a highlight
			// with different wording, since that could still read as "go
			// ahead and use this slot," the opposite of what "reserved"
			// means) rather than the usual START_NEW_FLIP.
			int reservedCount = config.reservedDumpSlots().getCount();
			if (reservedCount > 0 && i >= SLOT_COUNT - reservedCount)
			{
				return null;
			}

			if (pick != null && pick.sell)
			{
				return new Suggestion(
					"SELL:" + i + ":" + pick.itemId,
					String.format("slot %d: SELL (%d x %s at %d gp - %s)", i, pick.quantity, pick.name, pick.price, pick.source),
					SuggestionType.SELL, i, pick.itemId, pick.name, pick.price, pick.quantity, true
				);
			}
			if (pick != null)
			{
				return new Suggestion(
					"START_NEW_FLIP:" + i + ":" + pick.itemId,
					String.format("slot %d: START_NEW_FLIP (buy %d x %s at %d gp - %s)", i, pick.quantity, pick.name, pick.price, pick.source),
					SuggestionType.START_NEW_FLIP, i, pick.itemId, pick.name, pick.price, pick.quantity, false
				);
			}

			// Sell-only: an empty slot stays empty.
			if (sellOnlyNow)
			{
				return null;
			}
			// Not paired, offline, or nothing affordable: no item to name.
			return new Suggestion(
				"START_NEW_FLIP:" + i,
				String.format(
					"slot %d: START_NEW_FLIP (empty - no website suggestion available, pick something to flip)",
					i
				),
				SuggestionType.START_NEW_FLIP, i, 0, null
			);
		}

		return null;
	}

	// Dump Mode's actual suggestion (see SuggestionType.DUMP_BUY): highest
	// priority of anything this plugin surfaces (see DISPLAY_PRIORITY in
	// SuggestionOverlay) - catching a live dump is genuinely the most
	// time-limited, highest-conviction opportunity the engine can point at.
	// Targets the buy icon on an already-empty slot the same way
	// START_NEW_FLIP does, just with a real item attached instead of none -
	// the one case where this plugin's suggestions carry a specific item to
	// buy, and only because a live, confirmed, self-selecting dump makes
	// that call obviously good, unlike routine "what should I buy" which
	// still needs market-wide item ranking this plugin doesn't do itself.
	// A real, principled quantity - not a guess: capped by BOTH the item's
	// actual GE buy limit (PriceFeed.getBuyLimit, fetched from the wiki's
	// /mapping endpoint) and real available liquid GP (InventoryTracker.
	// getCoins minus the reserve floor - the reserve floor always sits
	// outside this budget regardless of mode, and is never touched no matter
	// how good the dump looks), scaled by dumpBuyCapitalFraction so a single
	// dump-buy doesn't risk the whole available stack. The other half of
	// GP budgeting - a diversified spread across
	// multiple regular flips - still needs real item ranking this
	// plugin doesn't have (there's only ever one thing to spend on:
	// whatever DUMP_BUY is currently suggesting), so that half stays out
	// of scope; this is specifically the floor-enforcement half. Shared by
	// buildDumpBuySuggestion() and meetsMinPredictedProfit() so both agree
	// on the same real quantity, not two separately-computed estimates.
	private Long estimateDumpBuyQuantity(int itemId, long currentPrice)
	{
		long availableCapital = Math.max(0, inventoryTracker.getCoins() - config.reserveFloorGp());
		long affordable = (long) (availableCapital * config.dumpBuyCapitalFraction() / currentPrice);
		Integer buyLimit = priceFeed.getBuyLimit(itemId);
		long cap = buyLimit != null ? Math.min(affordable, buyLimit) : affordable;
		return cap > 0 ? cap : null;
	}

	// minPredictedProfit's real gate (see MinPredictedProfit's own comment):
	// buying at the item's own real crashed instant-sell price and
	// reselling at its own real CURRENT instant-buy price - what buyers
	// are already paying RIGHT NOW, today, no future price movement
	// assumed - minus real GE tax, times the same quantity
	// buildDumpBuySuggestion would actually suggest. A live, observable
	// spread-capture estimate, not a forecast.
	private boolean meetsMinPredictedProfit(int itemId)
	{
		long threshold = config.minPredictedProfit().getThreshold();
		if (threshold <= 0)
		{
			return true;
		}

		Long buyAt = priceFeed.getInstantSellPrice(itemId);
		Long sellAt = priceFeed.getInstantBuyPrice(itemId);
		if (buyAt == null || sellAt == null || buyAt <= 0 || sellAt <= buyAt)
		{
			// No real, currently-favorable spread to estimate a profit
			// from - can't confirm it clears the bar, so don't claim it
			// does.
			return false;
		}

		Long quantity = estimateDumpBuyQuantity(itemId, buyAt);
		if (quantity == null || quantity <= 0)
		{
			return false;
		}

		String itemName = itemManager.getItemComposition(itemId).getName();
		long taxPerUnit = FlipTracker.computeTaxPerUnit(itemName, sellAt);
		long profitPerUnit = (sellAt - taxPerUnit) - buyAt;
		return profitPerUnit * quantity >= threshold;
	}

	private Suggestion buildDumpBuySuggestion(int slot, int itemId)
	{
		String itemName = itemManager.getItemComposition(itemId).getName();
		Long currentPrice = priceFeed.getInstantSellPrice(itemId);
		Long suggestedQuantity = currentPrice != null && currentPrice > 0
			? estimateDumpBuyQuantity(itemId, currentPrice)
			: null;

		String priceText = currentPrice == null ? "price crashed" : currentPrice + " gp";
		String quantityText = suggestedQuantity == null ? "" : String.format(" - buy %d", suggestedQuantity);
		return new Suggestion(
			"DUMP_BUY:" + slot + ":" + itemId,
			String.format(
				"slot %d: DUMP_BUY (%s just dumped - buy in now at %s%s)",
				slot, itemName, priceText, quantityText
			),
			SuggestionType.DUMP_BUY, slot, itemId, itemName, currentPrice, suggestedQuantity
		);
	}

	static final String COLLECT_DONE_KEY = "COLLECT_DONE:";

	static boolean isCollectableState(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	private static boolean isSellState(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.SOLD || state == GrandExchangeOfferState.SELLING
			|| state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	private static String describeDone(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BOUGHT:
				return "buy finished";
			case SOLD:
				return "sell finished";
			default:
				return "cancelled";
		}
	}

	// A dump-buy is missed once the instant-sell price is back above the
	// offer: sellers won't come down to it any more. Unknown price = not
	// missed (no evidence; the ordinary stale clock still applies).
	static boolean dumpBuyMissed(long offerPrice, Long instantSellPrice)
	{
		return offerPrice > 0 && instantSellPrice != null && instantSellPrice > offerPrice;
	}

	private static boolean isFillableState(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
	}
}
