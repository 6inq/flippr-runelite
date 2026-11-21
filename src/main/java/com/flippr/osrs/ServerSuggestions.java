package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;

// What to buy (or sell) next, from the Flippr website's engine - the gap
// DecisionEngine's START_NEW_FLIP always had ("slot N is free" with no item).
//
// Every REFRESH_INTERVAL_MS while logged in and paired (and soon after a slot
// empties), fetches:
// - GET /api/v1/osrs/plan: when the player has an active plan, its positions
//   are the source of truth - pending/buying ones not already in a GE slot
//   become buys at the plan's buyPrice for targetQty - boughtQty, and
//   bought/selling ones whose units are in the inventory become sells at the
//   plan's sellPrice. Each position is one trade (one buy offer, then one
//   sell offer). When the plan lists freeSlots (slots whose trade completed,
//   with the gp the next flip may use: the original share, plus the slot's
//   profit when the plan reinvests), /flips is fetched too and those slots
//   get server candidates sized to that gp until the plan replaces them.
// - otherwise GET /api/v1/osrs/flips: ranked candidates. Free accounts get
//   F2P items only (the server gates them and says gated: true).
// The plugin never decides what is Premium - it shows what the server sends.
//
// 401 pauses everything until re-paired, a maintenance 503 pauses every
// website call (WebsiteConnection.handle), and network errors back off from
// 30s to 10min. In every one of those cases getData() returns null, so
// DecisionEngine falls back to its local behaviour.
@Slf4j
@Singleton
public class ServerSuggestions
{
	static final long REFRESH_INTERVAL_MS = 30_000;
	// With an active plan the board changes on its own (auto-filled free
	// slots, auto prices, alerts), so it's read more often.
	static final long PLAN_REFRESH_INTERVAL_MS = 15_000;
	// "Refresh now" requests (a slot just emptied) are spaced at least this far.
	static final long MIN_ON_DEMAND_GAP_MS = 5_000;
	// refreshNow (the player just changed the board) is spaced at least this far.
	static final long MIN_REFRESH_NOW_GAP_MS = 2_000;
	// Older data than this is not used for suggestions.
	static final long MAX_DATA_AGE_MS = 5 * 60_000;
	static final long BUY_LIMIT_WINDOW_MS = 4 * 60 * 60_000L;
	static final String PLAN_PATH = "/api/v1/osrs/plan";
	static final String FLIPS_PATH = "/api/v1/osrs/flips?limit=50&account=both";
	static final String STOP_PATH = "/api/v1/osrs/positions/%s/stop";
	// RiskLevel.LOW skips candidates the server is less sure about; Medium and
	// High take the server's ranking as is.
	static final double LOW_RISK_MIN_CONFIDENCE = 0.5;

	// One ranked candidate from /flips. Numbers as double: the site sends
	// plain JSON numbers.
	static class Candidate
	{
		int id;
		String name;
		boolean members;
		Double buyLimit;
		double buyPrice;
		double sellPrice;
		double profitPerUnit;
		double quantity;
		double expectedProfit;
		double confidence;
	}

	static class FlipsResponse
	{
		boolean gated;
		List<Candidate> flips;
		// Set when this character has a seat but the website's in-game
		// name check is why P2P items were left out.
		WebsiteConnection.PremiumBlocked premiumBlocked;
	}

	// One open plan position (as
	// v1Positions sends it). Numbers as double: plain JSON numbers; epoch
	// ms timestamps fit a double exactly.
	static class PlanPosition
	{
		String id;
		int itemId;
		String name;
		boolean members;
		// The plan's slot number (1-8), not the in-game GE slot.
		Integer slot;
		double buyPrice;
		double sellPrice;
		double profitPerUnit;
		int targetQty;
		int boughtQty;
		int soldQty;
		String status;
		double expectedProfit;
		Double estFillHours;
		double realisedProfit;
		Double createdAt;
		Double previousSellPrice;
		Double sellPriceUpdatedAt;
		boolean sellBelowBreakEven;
		Double previousBuyPrice;
		Double buyPriceUpdatedAt;
		// Price-move alert on a listed offer.
		Double suggestedPrice;
		String alertKind;
		Double alertAt;
	}

	// The active plan; only what the
	// sidebar shows.
	static class PlanInfo
	{
		String id;
		double capital;
		int slots;
		Double maxPerSlotPct;
		double realisedProfit;
		Double createdAt;
		// The setup it was built with (the Plan setup form opens with these).
		String style;
		Double fillWindowHours;
		String account;
		boolean reinvest;
		// "Auto-update prices" / "Auto-fill free slots"; null = on.
		Boolean autoSellPrice;
		Boolean autoFill;
		// Sell-only mode: sell what's held, start no new buys; null = off.
		Boolean sellOnly;
	}

	// A plan slot whose trade completed.
	static class FreeSlot
	{
		int slot;
		double capital;
		double realisedProfit;
		// Why auto-fill left it free (nothing qualified yet), when it says.
		String reason;
	}

	// POST /positions/{id}/stop's outcome, as far as the plugin needs it.
	static final class StopResult
	{
		final boolean ok;
		final String error;

		StopResult(boolean ok, String error)
		{
			this.ok = ok;
			this.error = error;
		}
	}

	static class PlanResponse
	{
		JsonElement plan;
		List<PlanPosition> positions;
		List<FreeSlot> freeSlots;
	}

	// What the last successful fetch returned.
	static final class Data
	{
		// Null when the player has no active plan.
		final List<PlanPosition> planPositions;
		// The active plan itself (null without one).
		PlanInfo planInfo;
		// Plan slots whose trade completed (empty without a plan).
		final List<FreeSlot> planFreeSlots;
		// Server candidates: without a plan, or for a plan's free slots.
		final List<Candidate> candidates;
		final boolean gated;
		final long fetchedAt;

		Data(List<PlanPosition> planPositions, List<FreeSlot> planFreeSlots, List<Candidate> candidates, boolean gated, long fetchedAt)
		{
			this.planPositions = planPositions;
			this.planFreeSlots = planFreeSlots;
			this.candidates = candidates;
			this.gated = gated;
			this.fetchedAt = fetchedAt;
		}

		boolean hasPlan()
		{
			return planPositions != null;
		}
	}

	// One concrete suggestion for a free GE slot.
	static final class Pick
	{
		final int itemId;
		final String name;
		final long price;
		final long quantity;
		final boolean sell;
		// "plan" or "server", for the decision log.
		final String source;

		Pick(int itemId, String name, long price, long quantity, boolean sell, String source)
		{
			this.itemId = itemId;
			this.name = name;
			this.price = price;
			this.quantity = quantity;
			this.sell = sell;
			this.source = source;
		}
	}

	@Inject
	private WebsiteConnection website;

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private FlipprConfig config;

	@Inject
	private InventoryTracker inventoryTracker;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private PriceFeed priceFeed;

	LongSupplier clock = System::currentTimeMillis;

	private volatile Data data;
	// Sticky across plan/no-plan fetches: the panel's Premium hint.
	private volatile boolean gated;
	private final Backoff backoff = new Backoff(30_000, 600_000);
	private long nextFetchAt;
	private long lastFetchStartedAt;
	private boolean inFlight;

	// Fresh data, or null when suggestions should fall back to local logic
	// (not paired, sync off, maintenance, too old after errors).
	public Data getData()
	{
		Data d = data;
		if (d == null || !website.isSyncEnabled() || website.isInMaintenance())
		{
			return null;
		}
		return clock.getAsLong() - d.fetchedAt <= MAX_DATA_AGE_MS ? d : null;
	}

	public boolean isGated()
	{
		return gated && website.isSyncEnabled();
	}

	public synchronized long getRetryRemainingMs()
	{
		return backoff.isFailing() ? backoff.remainingMs(clock.getAsLong()) : 0;
	}

	public synchronized boolean isFailing()
	{
		return backoff.isFailing();
	}

	// The last data even when it's too old for suggestions - the sidebar
	// keeps showing the plan (with its age) rather than going blank. Null
	// before the first fetch or while sync is off.
	public Data getLastData()
	{
		return website.isSyncEnabled() ? data : null;
	}

	// A slot just emptied: fetch again soon rather than waiting up to 30s.
	public synchronized void requestRefresh()
	{
		long now = clock.getAsLong();
		long soonest = Math.max(now, lastFetchStartedAt + MIN_ON_DEMAND_GAP_MS);
		if (!backoff.isWaiting(now) && soonest < nextFetchAt)
		{
			nextFetchAt = soonest;
		}
	}

	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		maybeFetch();
	}

	synchronized void maybeFetch()
	{
		if (!website.isSyncEnabled())
		{
			data = null;
			return;
		}
		long now = clock.getAsLong();
		if (inFlight || now < nextFetchAt || website.isInMaintenance() || backoff.isWaiting(now))
		{
			return;
		}
		inFlight = true;
		lastFetchStartedAt = now;
		Data last = data;
		nextFetchAt = now + (last != null && last.hasPlan() ? PLAN_REFRESH_INTERVAL_MS : REFRESH_INTERVAL_MS);
		website.getAsync(PLAN_PATH, this::onPlan);
	}

	private void onPlan(WebsiteConnection.ApiResponse response)
	{
		if (response.outcome != WebsiteConnection.Outcome.OK)
		{
			onFailed(response);
			return;
		}
		PlanResponse plan;
		try
		{
			plan = gson.fromJson(response.body, PlanResponse.class);
		}
		catch (RuntimeException e)
		{
			log.debug("ServerSuggestions: unreadable plan response - {}", e.toString());
			onFailed(response);
			return;
		}
		if (plan != null && plan.plan != null && !plan.plan.isJsonNull())
		{
			List<PlanPosition> positions = plan.positions == null ? Collections.emptyList() : plan.positions;
			List<FreeSlot> freeSlots = plan.freeSlots == null ? Collections.emptyList() : plan.freeSlots;
			PlanInfo info = planInfoOf(plan.plan);
			if (!freeSlots.isEmpty())
			{
				website.getAsync(FLIPS_PATH, r -> onFlips(r, positions, freeSlots, info));
				return;
			}
			synchronized (this)
			{
				data = new Data(positions, freeSlots, Collections.emptyList(), false, clock.getAsLong());
				data.planInfo = info;
				backoff.reset();
				inFlight = false;
			}
			return;
		}
		website.getAsync(FLIPS_PATH, r -> onFlips(r, null, Collections.emptyList(), null));
	}

	private PlanInfo planInfoOf(JsonElement plan)
	{
		try
		{
			return plan.isJsonObject() ? gson.fromJson(plan, PlanInfo.class) : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// planPositions: null without a plan.
	private void onFlips(WebsiteConnection.ApiResponse response, List<PlanPosition> planPositions, List<FreeSlot> freeSlots,
		PlanInfo planInfo)
	{
		if (response.outcome != WebsiteConnection.Outcome.OK)
		{
			onFailed(response);
			return;
		}
		FlipsResponse flips;
		try
		{
			flips = gson.fromJson(response.body, FlipsResponse.class);
		}
		catch (RuntimeException e)
		{
			log.debug("ServerSuggestions: unreadable flips response - {}", e.toString());
			onFailed(response);
			return;
		}
		List<Candidate> candidates = flips == null || flips.flips == null ? Collections.emptyList() : flips.flips;
		website.notePremiumBlocked(flips == null ? null : flips.premiumBlocked);
		synchronized (this)
		{
			gated = flips != null && flips.gated;
			data = new Data(planPositions, freeSlots, candidates, gated, clock.getAsLong());
			data.planInfo = planInfo;
			backoff.reset();
			inFlight = false;
		}
	}

	private synchronized void onFailed(WebsiteConnection.ApiResponse response)
	{
		inFlight = false;
		if (response.outcome == WebsiteConnection.Outcome.UNAUTHORIZED)
		{
			data = null;
			return;
		}
		if (response.outcome == WebsiteConnection.Outcome.MAINTENANCE)
		{
			// WebsiteConnection holds the pause; the next tick after it ends fetches.
			nextFetchAt = 0;
			return;
		}
		long delay = backoff.fail(clock.getAsLong());
		log.debug("ServerSuggestions: fetch failed (HTTP {}), retrying in {}s", response.code, delay / 1000);
	}

	// "Drop & recycle" / "Stop & sell n" (POST /positions/{id}/stop): the
	// site stops buying, keeps what was bought to sell, and auto-fills the
	// freed slot. The plan is read again on the next tick. done runs on an
	// OkHttp thread.
	public void stopPosition(String positionId, Consumer<StopResult> done)
	{
		if (!website.isSyncEnabled())
		{
			done.accept(new StopResult(false, "Not connected to Flippr for this character."));
			return;
		}
		website.postJsonAsync(stopPath(positionId), "{}", response ->
		{
			if (response.outcome == WebsiteConnection.Outcome.OK)
			{
				refreshNow();
				done.accept(new StopResult(true, null));
				return;
			}
			done.accept(new StopResult(false, errorOf(response, "Couldn't stop it")));
		});
	}

	// Something the player did just changed the board (a stop, a listing
	// the site applied, a fill that moved a position): read it on the next
	// tick, at most once per MIN_REFRESH_NOW_GAP_MS.
	public synchronized void refreshNow()
	{
		long now = clock.getAsLong();
		if (!backoff.isWaiting(now))
		{
			nextFetchAt = Math.min(nextFetchAt, Math.max(now, lastFetchStartedAt + MIN_REFRESH_NOW_GAP_MS));
		}
	}

	// Build / rebuild the plan (POST /api/v1/osrs/plan/build, the website's
	// "Rebuild plan"), then read it again. done runs on an OkHttp thread.
	public void buildPlan(java.util.Map<String, Object> body, Consumer<StopResult> done)
	{
		send(body, PlanSetup.BUILD_PATH, false, "Couldn't build the plan", done);
	}

	// One plan switch (PATCH /api/v1/osrs/plan/settings), then read it again.
	public void setPlanSetting(String field, boolean on, Consumer<StopResult> done)
	{
		send(PlanSetup.settingBody(field, on), PlanSetup.SETTINGS_PATH, true, "Couldn't change the setting", done);
	}

	private void send(java.util.Map<String, Object> body, String path, boolean patch, String failure, Consumer<StopResult> done)
	{
		if (!website.isSyncEnabled())
		{
			done.accept(new StopResult(false, "Not connected to Flippr for this character."));
			return;
		}
		WebsiteConnection.ApiCallback callback = response ->
		{
			if (response.outcome == WebsiteConnection.Outcome.OK)
			{
				refreshNow();
				done.accept(new StopResult(true, null));
				return;
			}
			done.accept(new StopResult(false, errorOf(response, failure)));
		};
		String json = gson.toJson(body);
		if (patch)
		{
			website.patchJsonAsync(path, json, callback);
		}
		else
		{
			website.postJsonAsync(path, json, callback);
		}
	}

	static String stopPath(String positionId)
	{
		try
		{
			return String.format(STOP_PATH, URLEncoder.encode(positionId, "UTF-8").replace("+", "%20"));
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private String errorOf(WebsiteConnection.ApiResponse response, String failure)
	{
		switch (response.outcome)
		{
			case MAINTENANCE:
				return "Flippr is under maintenance - try again in a few minutes.";
			case UNAUTHORIZED:
				return "This plugin key is no longer valid - pair again with a new code.";
			default:
				break;
		}
		try
		{
			JsonObject o = response.body == null ? null : gson.fromJson(response.body, JsonObject.class);
			if (o != null && o.has("error") && o.get("error").isJsonPrimitive())
			{
				return o.get("error").getAsString();
			}
		}
		catch (RuntimeException ignored)
		{
			// Not JSON.
		}
		return response.code == 0 ? "Couldn't reach Flippr - check your connection." : failure + " (HTTP " + response.code + ").";
	}

	// Picks for `freeSlots` empty GE slots right now, sells first.
	// itemsInSlots: every item currently in any GE slot (never suggested twice).
	public List<Pick> currentPicks(Set<Integer> itemsInSlots, int freeSlots)
	{
		List<Pick> picks = allPicks(itemsInSlots, freeSlots);
		return isSellOnly() ? sellsOnly(picks) : picks;
	}

	// Sell-only: the sells of a pick list (no buys).
	static List<Pick> sellsOnly(List<Pick> picks)
	{
		List<Pick> out = new ArrayList<>();
		for (Pick p : picks)
		{
			if (p.sell)
			{
				out.add(p);
			}
		}
		return out;
	}

	// ---- Sell-only mode ----
	// The plan's setting (GET /plan plan.sellOnly) when there is a plan, the
	// player's just-flipped choice until the plan read back agrees (or
	// SELL_ONLY_PENDING_MS passes), and config.sellOnlyMode without a plan
	// (website flips). When on, no buy is suggested anywhere: picks, MODIFY
	// targets for buys, guidance, the fill line, notifications.
	static final long SELL_ONLY_PENDING_MS = 20_000;
	private volatile Boolean pendingSellOnly;
	private volatile long pendingSellOnlyUntil;

	// The plan's own value, or null without a plan.
	public Boolean planSellOnly()
	{
		Data d = getLastData();
		return d != null && d.hasPlan() ? PlanSetup.sellOnly(d.planInfo) : null;
	}

	public boolean isSellOnly()
	{
		Boolean pending = pendingSellOnly;
		Boolean plan = planSellOnly();
		if (pending != null)
		{
			if (clock.getAsLong() <= pendingSellOnlyUntil && !pending.equals(plan))
			{
				return pending;
			}
			pendingSellOnly = null;
		}
		if (plan != null)
		{
			return plan;
		}
		return config != null && config.sellOnlyMode();
	}

	// Turn sell-only on / off for the active plan (PATCH /plan/settings);
	// without a plan only the config applies. done runs on an OkHttp thread
	// (or at once when there's nothing to send).
	public void setSellOnly(boolean on, Consumer<StopResult> done)
	{
		if (planSellOnly() == null)
		{
			done.accept(new StopResult(true, null));
			return;
		}
		pendingSellOnly = on;
		pendingSellOnlyUntil = clock.getAsLong() + SELL_ONLY_PENDING_MS;
		setPlanSetting("sellOnly", on, result ->
		{
			if (!result.ok)
			{
				pendingSellOnly = null;
			}
			done.accept(result);
		});
	}

	private List<Pick> allPicks(Set<Integer> itemsInSlots, int freeSlots)
	{
		Data d = getData();
		if (d == null || freeSlots <= 0)
		{
			return Collections.emptyList();
		}
		IntUnaryOperator held = inventoryTracker::getHeldCount;
		if (d.hasPlan())
		{
			List<Pick> picks = new ArrayList<>(planPicks(d.planPositions, itemsInSlots, held));
			int left = freeSlots - picks.size();
			if (left > 0 && !d.planFreeSlots.isEmpty())
			{
				Set<Integer> excluded = new HashSet<>(itemsInSlots);
				for (PlanPosition p : d.planPositions)
				{
					excluded.add(p.itemId);
				}
				long now = clock.getAsLong();
				picks.addAll(freeSlotPicks(
					d.candidates, excluded, d.planFreeSlots, left, inventoryTracker.getCoins(), config.reserveFloorGp(),
					c -> remainingBuyLimit(c, now),
					config.minPredictedProfit().getThreshold(),
					config.riskLevel() == RiskLevel.LOW ? LOW_RISK_MIN_CONFIDENCE : 0
				));
			}
			return limit(picks, freeSlots);
		}

		Set<Integer> excluded = new HashSet<>(itemsInSlots);
		List<OpenPositionEntry> open = flipTracker.getOpenPositions();
		List<Pick> picks = new ArrayList<>(heldSellPicks(open, d.candidates, itemsInSlots, held));
		for (OpenPositionEntry position : open)
		{
			excluded.add(position.getItemId());
		}
		for (Pick pick : picks)
		{
			excluded.add(pick.itemId);
		}
		int buySlots = freeSlots - picks.size();
		if (buySlots > 0)
		{
			long now = clock.getAsLong();
			picks.addAll(candidatePicks(
				d.candidates, excluded, buySlots, inventoryTracker.getCoins(), config.reserveFloorGp(),
				c -> remainingBuyLimit(c, now),
				config.minPredictedProfit().getThreshold(),
				config.riskLevel() == RiskLevel.LOW ? LOW_RISK_MIN_CONFIDENCE : 0
			));
		}
		return limit(picks, freeSlots);
	}

	// The plan's or the server's price for an item already in a slot, for
	// MODIFY: the plan position's price when there is a plan, else the
	// candidate's. Null when the server has nothing for this item.
	public Long priceFor(int itemId, boolean buy)
	{
		Data d = getData();
		if (d == null || (buy && isSellOnly()))
		{
			return null;
		}
		if (d.hasPlan())
		{
			for (PlanPosition p : d.planPositions)
			{
				if (p.itemId == itemId)
				{
					return (long) (buy ? p.buyPrice : p.sellPrice);
				}
			}
		}
		// No plan, or a plan's free slot filled from server candidates.
		for (Candidate c : d.candidates)
		{
			if (c.id == itemId)
			{
				return (long) (buy ? c.buyPrice : c.sellPrice);
			}
		}
		return null;
	}

	private Long remainingBuyLimit(Candidate c, long now)
	{
		Long limit = c.buyLimit != null ? Long.valueOf(c.buyLimit.longValue()) : null;
		if (limit == null)
		{
			Integer known = priceFeed.getBuyLimit(c.id);
			limit = known == null ? null : known.longValue();
		}
		if (limit == null)
		{
			return null;
		}
		return Math.max(0, limit - flipTracker.getBoughtInWindow(c.id, now, BUY_LIMIT_WINDOW_MS));
	}

	private static List<Pick> limit(List<Pick> picks, int max)
	{
		return picks.size() <= max ? picks : new ArrayList<>(picks.subList(0, max));
	}

	// Plan mode: sells for held units first (they free up capital), then buys.
	static List<Pick> planPicks(List<PlanPosition> positions, Set<Integer> itemsInSlots, IntUnaryOperator heldCount)
	{
		List<Pick> sells = new ArrayList<>();
		List<Pick> buys = new ArrayList<>();
		Set<Integer> used = new HashSet<>(itemsInSlots);
		for (PlanPosition p : positions)
		{
			if (used.contains(p.itemId) || p.status == null)
			{
				continue;
			}
			switch (p.status)
			{
				case "bought":
				case "selling":
				{
					long unsold = p.boughtQty - p.soldQty;
					long held = heldCount.applyAsInt(p.itemId);
					long qty = Math.min(unsold, held);
					if (qty > 0 && p.sellPrice > 0)
					{
						sells.add(new Pick(p.itemId, p.name, (long) p.sellPrice, qty, true, "plan"));
						used.add(p.itemId);
					}
					break;
				}
				case "pending":
				case "buying":
				{
					long remaining = p.targetQty - p.boughtQty;
					if (remaining > 0 && p.buyPrice > 0)
					{
						buys.add(new Pick(p.itemId, p.name, (long) p.buyPrice, remaining, false, "plan"));
						used.add(p.itemId);
					}
					break;
				}
				default:
					break;
			}
		}
		sells.addAll(buys);
		return sells;
	}

	// No plan: sell tracked positions sitting in the inventory at the
	// server's sell price for that item, when the server ranks it.
	static List<Pick> heldSellPicks(List<OpenPositionEntry> open, List<Candidate> candidates, Set<Integer> itemsInSlots,
		IntUnaryOperator heldCount)
	{
		List<Pick> picks = new ArrayList<>();
		for (OpenPositionEntry position : open)
		{
			int itemId = position.getItemId();
			if (itemsInSlots.contains(itemId))
			{
				continue;
			}
			long qty = Math.min(position.getQuantity(), heldCount.applyAsInt(itemId));
			if (qty <= 0)
			{
				continue;
			}
			for (Candidate c : candidates)
			{
				if (c.id == itemId && c.sellPrice > 0)
				{
					picks.add(new Pick(itemId, position.getItemName(), (long) c.sellPrice, qty, true, "server"));
					break;
				}
			}
		}
		return picks;
	}

	// Plan mode, slots whose trade completed: the best-ranked candidate per
	// slot (up to maxSlots), each sized like candidatePicks on that slot's gp
	// (capped by the coins above the reserve floor still unspent).
	static List<Pick> freeSlotPicks(List<Candidate> candidates, Set<Integer> excluded, List<FreeSlot> freeSlots, int maxSlots,
		long coins, long reserveFloor, Function<Candidate, Long> remainingLimitOrNull, long minProfit, double minConfidence)
	{
		List<Pick> picks = new ArrayList<>();
		Set<Integer> taken = new HashSet<>(excluded);
		long unspent = Math.max(0, coins - reserveFloor);
		for (FreeSlot slot : freeSlots)
		{
			if (picks.size() >= maxSlots)
			{
				break;
			}
			long budget = Math.min((long) slot.capital, unspent);
			List<Pick> one = candidatePicks(candidates, taken, 1, budget, 0, remainingLimitOrNull, minProfit, minConfidence);
			if (one.isEmpty())
			{
				continue;
			}
			Pick pick = one.get(0);
			picks.add(pick);
			taken.add(pick.itemId);
			unspent -= pick.price * pick.quantity;
		}
		return picks;
	}

	// No plan: the best-ranked candidates not already held, one per free
	// slot, each sized to min(server quantity, remaining buy limit, what an
	// equal share of spendable coins buys). remainingLimit returns null when
	// the buy limit is unknown. minProfit 0 = no minimum.
	static List<Pick> candidatePicks(List<Candidate> candidates, Set<Integer> excluded, int freeSlots, long coins,
		long reserveFloor, Function<Candidate, Long> remainingLimitOrNull, long minProfit, double minConfidence)
	{
		List<Pick> picks = new ArrayList<>();
		if (freeSlots <= 0)
		{
			return picks;
		}
		long spendable = Math.max(0, coins - reserveFloor) / freeSlots;
		Set<Integer> taken = new HashSet<>(excluded);
		for (Candidate c : candidates)
		{
			if (picks.size() >= freeSlots)
			{
				break;
			}
			long buyPrice = (long) c.buyPrice;
			if (taken.contains(c.id) || buyPrice <= 0 || c.profitPerUnit <= 0 || c.confidence < minConfidence)
			{
				continue;
			}
			long qty = Math.min((long) c.quantity, spendable / buyPrice);
			Long remaining = remainingLimitOrNull.apply(c);
			if (remaining != null)
			{
				qty = Math.min(qty, remaining);
			}
			if (qty < 1)
			{
				continue;
			}
			if (minProfit > 0 && c.profitPerUnit * qty < minProfit)
			{
				continue;
			}
			picks.add(new Pick(c.id, c.name, buyPrice, qty, false, "server"));
			taken.add(c.id);
		}
		return picks;
	}
}
