package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: ServerSuggestions turns the website's plan (when one is
// active) or ranked candidates into concrete buy/sell picks for free GE
// slots - plan positions are the source of truth; otherwise the best
// candidates not already held, sized by server quantity, remaining 4h buy
// limit and an equal share of spendable coins above the reserve floor.
// gated / 401 / maintenance / network errors fall back to local behaviour.
// The website is a mock; responses are canned JSON in the site's shape.
public class ServerSuggestionsTest
{
	private static final int BOND = 13190;
	private static final int WHIP = 4151;
	private static final int LOBSTER = 379;

	@Mock
	private WebsiteConnection website;
	@Mock
	private Client client;
	@Mock
	private FlipprConfig config;
	@Mock
	private InventoryTracker inventoryTracker;
	@Mock
	private FlipTracker flipTracker;
	@Mock
	private PriceFeed priceFeed;
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private ServerSuggestions suggestions;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;
	private final Map<String, String> responses = new HashMap<>();
	private final Map<String, Integer> codes = new HashMap<>();

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		suggestions.clock = () -> now;
		when(website.isSyncEnabled()).thenReturn(true);
		when(website.isInMaintenance()).thenReturn(false);
		when(config.reserveFloorGp()).thenReturn(0);
		when(config.minPredictedProfit()).thenReturn(MinPredictedProfit.AUTO);
		when(config.riskLevel()).thenReturn(RiskLevel.MEDIUM);
		when(flipTracker.getOpenPositions()).thenReturn(Collections.emptyList());
		when(priceFeed.getBuyLimit(anyInt())).thenReturn(null);
		doAnswer(inv ->
		{
			String path = inv.getArgument(0);
			WebsiteConnection.ApiCallback callback = inv.getArgument(1);
			int code = codes.getOrDefault(path, 200);
			WebsiteConnection.Outcome outcome = code == 200 ? WebsiteConnection.Outcome.OK
				: code == 401 ? WebsiteConnection.Outcome.UNAUTHORIZED
				: code == 503 ? WebsiteConnection.Outcome.MAINTENANCE
				: WebsiteConnection.Outcome.FAILED;
			callback.onResult(new WebsiteConnection.ApiResponse(outcome, code, responses.get(path)));
			return null;
		}).when(website).getAsync(anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private static ServerSuggestions.Candidate candidate(int id, String name, double buy, double sell, double qty, Double limit)
	{
		ServerSuggestions.Candidate c = new ServerSuggestions.Candidate();
		c.id = id;
		c.name = name;
		c.buyPrice = buy;
		c.sellPrice = sell;
		c.profitPerUnit = sell * 0.98 - buy;
		c.quantity = qty;
		c.buyLimit = limit;
		c.confidence = 0.8;
		return c;
	}

	private static ServerSuggestions.PlanPosition position(int itemId, String status, int target, int bought, int sold)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.itemId = itemId;
		p.name = "Item " + itemId;
		p.status = status;
		p.buyPrice = 100;
		p.sellPrice = 120;
		p.targetQty = target;
		p.boughtQty = bought;
		p.soldQty = sold;
		return p;
	}

	// Req: plan positions -> sells for held, unsold units (plan sellPrice,
	// boughtQty - soldQty capped by what's held) then buys for pending/buying
	// positions (plan buyPrice, targetQty - boughtQty); items already in a GE
	// slot are skipped.
	@Test
	public void planPositionsAreTheSourceOfTruth()
	{
		List<ServerSuggestions.PlanPosition> plan = Arrays.asList(
			position(1, "pending", 50, 0, 0),
			position(2, "buying", 50, 20, 0),
			position(3, "bought", 40, 40, 0),
			position(4, "selling", 40, 40, 30),
			position(5, "pending", 10, 0, 0));
		Map<Integer, Integer> held = new HashMap<>();
		held.put(3, 25);
		held.put(4, 10);

		List<ServerSuggestions.Pick> picks = ServerSuggestions.planPicks(plan, Collections.singleton(5),
			id -> held.getOrDefault(id, 0));

		assertEquals(4, picks.size());
		assertPick(picks.get(0), 3, true, 120, 25);
		assertPick(picks.get(1), 4, true, 120, 10);
		assertPick(picks.get(2), 1, false, 100, 50);
		assertPick(picks.get(3), 2, false, 100, 30);
	}

	// Req: a bought position whose units aren't in the inventory can't be sold yet.
	@Test
	public void planSellNeedsTheItemsInTheInventory()
	{
		List<ServerSuggestions.Pick> picks = ServerSuggestions.planPicks(
			Collections.singletonList(position(3, "bought", 40, 40, 0)), Collections.emptySet(), id -> 0);
		assertTrue(picks.isEmpty());
	}

	// Req: quantity = min(server quantity, remaining buy limit, spendable /
	// buyPrice), spendable = (coins - reserve floor) / free slots.
	@Test
	public void candidatesAreSizedByQuantityLimitAndCoins()
	{
		List<ServerSuggestions.Candidate> list = Arrays.asList(
			candidate(BOND, "Old school bond", 10_592_523, 11_000_000, 100, 100d),
			candidate(WHIP, "Abyssal whip", 1_500_000, 1_600_000, 70, 70d),
			candidate(LOBSTER, "Lobster", 150, 170, 20_000, 13_000d));
		Map<Integer, Long> remaining = new HashMap<>();
		remaining.put(BOND, 97L);
		remaining.put(WHIP, 3L);
		remaining.put(LOBSTER, 13_000L);

		// 3 free slots, 3.1B coins, 100M floor -> 1B per slot.
		List<ServerSuggestions.Pick> picks = ServerSuggestions.candidatePicks(list, new HashSet<>(), 3,
			3_100_000_000L, 100_000_000L, c -> remaining.get(c.id), 0, 0);

		assertEquals(3, picks.size());
		assertPick(picks.get(0), BOND, false, 10_592_523, 94);
		assertPick(picks.get(1), WHIP, false, 1_500_000, 3);
		assertPick(picks.get(2), LOBSTER, false, 150, 13_000);
	}

	// Req: held/occupied items, sold-out buy limits, unaffordable items and
	// profits under the configured minimum are skipped; the next candidate
	// fills the slot instead.
	@Test
	public void candidatesAreFilteredAndBackfilled()
	{
		ServerSuggestions.Candidate weak = candidate(5, "Weak", 100, 101, 10, null);
		weak.profitPerUnit = 1;
		List<ServerSuggestions.Candidate> list = Arrays.asList(
			candidate(BOND, "Old school bond", 10_000_000, 11_000_000, 10, null),
			candidate(WHIP, "Abyssal whip", 1_500_000, 1_600_000, 70, null),
			candidate(1, "Expensive", 900_000_000, 950_000_000, 1, null),
			weak,
			candidate(LOBSTER, "Lobster", 150, 170, 20_000, null));
		Set<Integer> excluded = new HashSet<>(Collections.singleton(BOND));
		Map<Integer, Long> remaining = new HashMap<>();
		remaining.put(WHIP, 0L);

		List<ServerSuggestions.Pick> picks = ServerSuggestions.candidatePicks(list, excluded, 1,
			100_000_000L, 0, c -> remaining.get(c.id), 50_000, 0);

		assertEquals(1, picks.size());
		assertPick(picks.get(0), LOBSTER, false, 150, 20_000);
	}

	// Req: Low risk skips candidates the server isn't confident about.
	@Test
	public void lowRiskSkipsLowConfidence()
	{
		ServerSuggestions.Candidate shaky = candidate(WHIP, "Abyssal whip", 100, 200, 10, null);
		shaky.confidence = 0.3;
		List<ServerSuggestions.Pick> picks = ServerSuggestions.candidatePicks(Collections.singletonList(shaky),
			new HashSet<>(), 1, 10_000, 0, c -> null, 0, ServerSuggestions.LOW_RISK_MIN_CONFIDENCE);
		assertTrue(picks.isEmpty());
	}

	// Req: with no plan, a tracked position sitting in the inventory gets a
	// sell at the server's price for that item.
	@Test
	public void heldPositionsGetServerSellPrice()
	{
		OpenPositionEntry whip = new OpenPositionEntry(WHIP, "Abyssal whip", 5, 1_500_000, now);
		List<ServerSuggestions.Pick> picks = ServerSuggestions.heldSellPicks(Collections.singletonList(whip),
			Collections.singletonList(candidate(WHIP, "Abyssal whip", 1_500_000, 1_620_000, 70, null)),
			Collections.emptySet(), id -> 3);
		assertEquals(1, picks.size());
		assertPick(picks.get(0), WHIP, true, 1_620_000, 3);
	}

	// Req: active plan -> its positions drive the picks; /flips isn't fetched.
	@Test
	public void activePlanIsUsed()
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"game\":\"osrs\",\"plan\":{\"id\":\"p1\",\"capital\":1000},"
			+ "\"positions\":[{\"id\":\"a\",\"itemId\":4151,\"name\":\"Abyssal whip\",\"status\":\"pending\","
			+ "\"buyPrice\":1500000,\"sellPrice\":1600000,\"targetQty\":7,\"boughtQty\":2,\"soldQty\":0}]}");
		when(inventoryTracker.getHeldCount(anyInt())).thenReturn(0);

		suggestions.maybeFetch();

		ServerSuggestions.Data data = suggestions.getData();
		assertNotNull(data);
		assertTrue(data.hasPlan());
		List<ServerSuggestions.Pick> picks = suggestions.currentPicks(Collections.emptySet(), 2);
		assertEquals(1, picks.size());
		assertPick(picks.get(0), WHIP, false, 1_500_000, 5);
		assertEquals(Long.valueOf(1_600_000), suggestions.priceFor(WHIP, false));
	}

	// Req: one trade per position - a pending/buying position is one buy offer
	// for what is left of targetQty, a bought/selling one is one sell offer for
	// the units held; a done position is not in the plan's open list at all.
	@Test
	public void eachPositionIsOneBuyThenOneSell()
	{
		List<ServerSuggestions.Pick> buying = ServerSuggestions.planPicks(
			Collections.singletonList(position(1, "buying", 300, 120, 0)), Collections.emptySet(), id -> 0);
		assertEquals(1, buying.size());
		assertPick(buying.get(0), 1, false, 100, 180);
		List<ServerSuggestions.Pick> selling = ServerSuggestions.planPicks(
			Collections.singletonList(position(1, "bought", 300, 300, 0)), Collections.emptySet(), id -> 300);
		assertEquals(1, selling.size());
		assertPick(selling.get(0), 1, true, 120, 300);
	}

	private static ServerSuggestions.FreeSlot freeSlot(int slot, double capital)
	{
		ServerSuggestions.FreeSlot f = new ServerSuggestions.FreeSlot();
		f.slot = slot;
		f.capital = capital;
		return f;
	}

	// Req: a completed plan slot gets the best server candidate sized to that
	// slot's gp (sent by the site: original share, + realised profit when the
	// plan reinvests), never more than the unspent coins above the floor.
	@Test
	public void freeSlotPicksAreSizedToEachSlotCapital()
	{
		List<ServerSuggestions.Candidate> list = Arrays.asList(
			candidate(WHIP, "Abyssal whip", 1_500_000, 1_600_000, 1_000, null),
			candidate(LOBSTER, "Lobster", 150, 170, 1_000_000, null));
		// Slot 1: 10.29M (10M share + 290k profit); slot 2: 10M; 15M coins.
		List<ServerSuggestions.Pick> picks = ServerSuggestions.freeSlotPicks(list, new HashSet<>(),
			Arrays.asList(freeSlot(1, 10_290_000), freeSlot(2, 10_000_000)), 2, 15_000_000L, 0, c -> null, 0, 0);

		assertEquals(2, picks.size());
		assertPick(picks.get(0), WHIP, false, 1_500_000, 6);
		// 15M - 9M spent = 6M left for slot 2.
		assertPick(picks.get(1), LOBSTER, false, 150, 40_000);
	}

	// Req: an active plan with a completed slot -> /flips is fetched too, the
	// plan's own positions come first, and the free slot gets a server pick.
	@Test
	public void activePlanWithACompletedSlotFillsItFromServerFlips()
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"game\":\"osrs\",\"plan\":{\"id\":\"p1\",\"capital\":30000000,\"reinvest\":true},"
			+ "\"positions\":[{\"id\":\"a\",\"itemId\":4151,\"name\":\"Abyssal whip\",\"status\":\"pending\","
			+ "\"buyPrice\":1500000,\"sellPrice\":1600000,\"targetQty\":7,\"boughtQty\":2,\"soldQty\":0,\"realisedProfit\":0}],"
			+ "\"freeSlots\":[{\"slot\":2,\"capital\":10290000,\"realisedProfit\":290000}]}");
		responses.put(ServerSuggestions.FLIPS_PATH, "{\"game\":\"osrs\",\"gated\":false,"
			+ "\"flips\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"members\":true,\"buyLimit\":70,"
			+ "\"buyPrice\":1500000,\"sellPrice\":1600000,\"profitPerUnit\":68000,\"quantity\":70,"
			+ "\"expectedProfit\":4760000,\"confidence\":0.8},"
			+ "{\"id\":379,\"name\":\"Lobster\",\"members\":false,\"buyLimit\":13000,"
			+ "\"buyPrice\":150,\"sellPrice\":170,\"profitPerUnit\":17,\"quantity\":100000,"
			+ "\"expectedProfit\":1700000,\"confidence\":0.8}]}");
		when(inventoryTracker.getHeldCount(anyInt())).thenReturn(0);
		when(inventoryTracker.getCoins()).thenReturn(1_000_000_000L);

		suggestions.maybeFetch();

		ServerSuggestions.Data data = suggestions.getData();
		assertNotNull(data);
		assertTrue(data.hasPlan());
		List<ServerSuggestions.Pick> picks = suggestions.currentPicks(Collections.emptySet(), 3);
		assertEquals(2, picks.size());
		assertPick(picks.get(0), WHIP, false, 1_500_000, 5);
		// The whip is already planned, so the free slot gets lobsters: within
		// the 13,000 limit (10.29M would buy 68,600).
		assertPick(picks.get(1), LOBSTER, false, 150, 13_000);
		assertEquals(Long.valueOf(170), suggestions.priceFor(LOBSTER, false));
	}

	// Req: a plan without completed slots never fetches /flips.
	@Test
	public void activePlanWithoutFreeSlotsSkipsFlips()
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"plan\":{\"id\":\"p1\"},\"positions\":[],\"freeSlots\":[]}");
		suggestions.maybeFetch();
		assertNotNull(suggestions.getData());
		verify(website, never()).getAsync(eq(ServerSuggestions.FLIPS_PATH), any());
	}

	// Req: no plan -> /flips candidates; gated: true is remembered for the panel.
	@Test
	public void noPlanFallsBackToFlipsAndRemembersGating()
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"game\":\"osrs\",\"plan\":null,\"positions\":[]}");
		responses.put(ServerSuggestions.FLIPS_PATH, "{\"game\":\"osrs\",\"gated\":true,\"reason\":\"p2p_requires_premium\","
			+ "\"flips\":[{\"id\":13190,\"name\":\"Old school bond\",\"members\":false,\"buyLimit\":100,"
			+ "\"buyPrice\":10592523,\"sellPrice\":11000000,\"profitPerUnit\":187477,\"quantity\":100,"
			+ "\"expectedProfit\":18747700,\"confidence\":0.71}]}");
		when(inventoryTracker.getCoins()).thenReturn(1_100_000_000L);
		when(flipTracker.getBoughtInWindow(eq(BOND), anyLong(), anyLong())).thenReturn(3L);

		suggestions.maybeFetch();

		assertTrue(suggestions.isGated());
		List<ServerSuggestions.Pick> picks = suggestions.currentPicks(Collections.emptySet(), 1);
		assertPick(picks.get(0), BOND, false, 10_592_523, 97);
	}

	// Req: 401 / maintenance / network errors leave no data, so DecisionEngine
	// falls back to its local suggestions; errors back off before retrying.
	@Test
	public void failuresFallBackToLocalAndBackOff()
	{
		codes.put(ServerSuggestions.PLAN_PATH, 500);
		suggestions.maybeFetch();
		assertNull(suggestions.getData());
		assertTrue(suggestions.isFailing());
		assertEquals(30_000, suggestions.getRetryRemainingMs());

		now += 31_000;
		codes.remove(ServerSuggestions.PLAN_PATH);
		responses.put(ServerSuggestions.PLAN_PATH, "{\"plan\":{},\"positions\":[]}");
		suggestions.maybeFetch();
		assertNotNull(suggestions.getData());
		assertFalse(suggestions.isFailing());

		when(website.isInMaintenance()).thenReturn(true);
		assertNull("maintenance -> local fallback", suggestions.getData());
		when(website.isInMaintenance()).thenReturn(false);
		when(website.isSyncEnabled()).thenReturn(false);
		assertNull("unpaired (e.g. after a 401) -> local fallback", suggestions.getData());
		assertTrue(suggestions.currentPicks(Collections.emptySet(), 8).isEmpty());
	}

	// Req: data is refreshed every 30s, sooner on demand when a slot empties.
	@Test
	public void refreshCadence()
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"plan\":{},\"positions\":[]}");
		suggestions.maybeFetch();
		long first = suggestions.getData().fetchedAt;

		now += 10_000;
		suggestions.maybeFetch();
		assertEquals(first, suggestions.getData().fetchedAt);

		suggestions.requestRefresh();
		suggestions.maybeFetch();
		assertEquals(now, suggestions.getData().fetchedAt);
	}

	private static void assertPick(ServerSuggestions.Pick pick, int itemId, boolean sell, long price, long quantity)
	{
		assertEquals(itemId, pick.itemId);
		assertEquals(sell, pick.sell);
		assertEquals(price, pick.price);
		assertEquals(quantity, pick.quantity);
	}
}
