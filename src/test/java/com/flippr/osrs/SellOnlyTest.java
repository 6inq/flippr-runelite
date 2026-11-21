package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: sell-only mode - the plan's setting (GET /plan
// plan.sellOnly, PATCH /plan/settings {sellOnly}) or the config without a
// plan. While on: the plan's suggestions contain no buys (sells still come),
// buy MODIFY targets are dropped, GE guidance and the fill line only guide
// sells, buy notifications are silenced (and not replayed later), Pending
// not-listed positions show "Paused (sell-only)". Toggling it off resumes.
public class SellOnlyTest
{
	private static final int WHIP = 4151;
	private static final int BOND = 13190;

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
	private final List<String> patches = new ArrayList<>();
	private WebsiteConnection.ApiResponse patchAnswer;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		suggestions.clock = () -> now;
		when(website.isSyncEnabled()).thenReturn(true);
		when(config.reserveFloorGp()).thenReturn(0);
		when(config.minPredictedProfit()).thenReturn(MinPredictedProfit.AUTO);
		when(config.riskLevel()).thenReturn(RiskLevel.MEDIUM);
		when(flipTracker.getOpenPositions()).thenReturn(Collections.emptyList());
		// Held: the bond (to sell), none of the whip (to buy).
		when(inventoryTracker.getHeldCount(anyInt())).thenAnswer(inv -> (int) inv.getArgument(0) == BOND ? 3 : 0);
		doAnswer(inv ->
		{
			WebsiteConnection.ApiCallback cb = inv.getArgument(1);
			cb.onResult(new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, responses.get((String) inv.getArgument(0))));
			return null;
		}).when(website).getAsync(anyString(), any());
		patchAnswer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, "{}");
		doAnswer(inv ->
		{
			patches.add(inv.getArgument(0) + " " + inv.getArgument(1));
			((WebsiteConnection.ApiCallback) inv.getArgument(2)).onResult(patchAnswer);
			return null;
		}).when(website).patchJsonAsync(anyString(), anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private void plan(Boolean sellOnly)
	{
		responses.put(ServerSuggestions.PLAN_PATH, "{\"plan\":{\"id\":\"p1\",\"capital\":100000000" + (sellOnly == null ? "" : ",\"sellOnly\":" + sellOnly) + "},"
			+ "\"positions\":["
			+ "{\"id\":\"buy\",\"itemId\":" + WHIP + ",\"name\":\"Abyssal whip\",\"status\":\"pending\",\"buyPrice\":1500000,\"sellPrice\":1600000,\"targetQty\":5,\"boughtQty\":0,\"soldQty\":0},"
			+ "{\"id\":\"sell\",\"itemId\":" + BOND + ",\"name\":\"Old school bond\",\"status\":\"bought\",\"buyPrice\":10000000,\"sellPrice\":10500000,\"targetQty\":3,\"boughtQty\":3,\"soldQty\":0}"
			+ "],\"freeSlots\":[]}");
		suggestions.refreshNow();
		now += 3_000;
		suggestions.maybeFetch();
	}

	private List<ServerSuggestions.Pick> picks()
	{
		return suggestions.currentPicks(Collections.emptySet(), 8);
	}

	// Req: plan suggestions contain no buys while sell-only; off -> buys again.
	@Test
	public void suggestionsHoldNoBuys()
	{
		plan(null);
		assertFalse("absent = off", suggestions.isSellOnly());
		assertEquals(2, picks().size());

		plan(true);
		assertTrue(suggestions.isSellOnly());
		List<ServerSuggestions.Pick> sells = picks();
		assertEquals(1, sells.size());
		assertTrue(sells.get(0).sell);
		assertEquals(BOND, sells.get(0).itemId);
		assertNull("no buy price target", suggestions.priceFor(WHIP, true));
		assertEquals(Long.valueOf(10_500_000L), suggestions.priceFor(BOND, false));

		plan(false);
		assertEquals("toggling back resumes", 2, picks().size());
	}

	// Req: without a plan the config decides.
	@Test
	public void configWithoutPlan()
	{
		assertNull(suggestions.planSellOnly());
		when(config.sellOnlyMode()).thenReturn(true);
		assertTrue(suggestions.isSellOnly());
		List<ServerSuggestions.StopResult> r = new ArrayList<>();
		suggestions.setSellOnly(false, r::add);
		assertTrue("nothing to send without a plan", patches.isEmpty());
		assertTrue(r.get(0).ok);
	}

	// Req: the toggle PATCHes {sellOnly} and shows the choice until the plan read agrees.
	@Test
	public void toggleSendsAndHoldsTheChoice()
	{
		plan(false);
		List<ServerSuggestions.StopResult> r = new ArrayList<>();
		suggestions.setSellOnly(true, r::add);
		assertEquals("/api/v1/osrs/plan/settings {\"sellOnly\":true}", patches.get(0));
		assertTrue(suggestions.isSellOnly());
		assertEquals(1, picks().size());
		plan(true);
		assertTrue(suggestions.isSellOnly());

		// A refused change goes back to the plan's value.
		patchAnswer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.FAILED, 400, "{\"error\":\"Build a plan first.\"}");
		suggestions.setSellOnly(false, r::add);
		assertFalse(r.get(1).ok);
		assertEquals("Build a plan first.", r.get(1).error);
		assertTrue(suggestions.isSellOnly());

		// A pending choice expires if the plan never agrees.
		patchAnswer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, "{}");
		suggestions.setSellOnly(false, r::add);
		assertFalse(suggestions.isSellOnly());
		now += ServerSuggestions.SELL_ONLY_PENDING_MS + 1;
		assertTrue(suggestions.isSellOnly());
	}

	// Req: config <-> plan sync never loops.
	@Test
	public void configSyncRules()
	{
		assertTrue("changed in RuneLite settings: send", SellOnlyControl.shouldSend(false, false, true));
		assertFalse("already in effect: nothing", SellOnlyControl.shouldSend(true, true, true));
		assertFalse("no plan: config only", SellOnlyControl.shouldSend(null, false, true));
		assertTrue("plan changed on the website: mirror", SellOnlyControl.shouldMirror(true, true, false));
		assertFalse("a pending choice: don't overwrite", SellOnlyControl.shouldMirror(false, true, true));
		assertFalse("already equal", SellOnlyControl.shouldMirror(true, true, true));
	}

	// Req: GE guidance only guides sells.
	@Test
	public void guidanceOnlySells()
	{
		GeGuide.Screen home = new GeGuide.Screen();
		home.geOpen = true;
		home.targetInInventory = true;
		GeGuide.Target buy = new GeGuide.Target(SuggestionType.START_NEW_FLIP, 2, WHIP, false, 1_500_000L, 5L);
		GeGuide.Target dump = new GeGuide.Target(SuggestionType.DUMP_BUY, 2, WHIP, false, 1_400_000L, 5L);
		GeGuide.Target raiseBuy = new GeGuide.Target(SuggestionType.MODIFY, 1, WHIP, false, 1_510_000L, null);
		GeGuide.Target sell = new GeGuide.Target(SuggestionType.SELL, -1, BOND, true, 10_500_000L, 3L);
		GeGuide.Target lowerSell = new GeGuide.Target(SuggestionType.MODIFY, 3, BOND, true, 10_400_000L, null);
		GeGuide.Target collect = new GeGuide.Target(SuggestionType.COLLECT, 0, BOND, true, null, null);
		for (GeGuide.Target t : new GeGuide.Target[]{buy, dump, raiseBuy})
		{
			assertTrue(t.type + " guided while sell-only", GeGuide.plan(true, true, t, home).isEmpty());
			assertFalse(t.type + " guided normally", GeGuide.plan(true, false, t, home).isEmpty());
		}
		assertEquals(GeGuide.Element.INVENTORY_ITEM, GeGuide.plan(true, true, sell, home).get(0).element);
		assertEquals(GeGuide.Element.SLOT_BOX, GeGuide.plan(true, true, lowerSell, home).get(0).element);
		assertEquals(GeGuide.Element.COLLECT, GeGuide.plan(true, true, collect, home).get(0).element);

		DecisionEngine.Suggestion buyS = new DecisionEngine.Suggestion("START_NEW_FLIP:2", "", SuggestionType.START_NEW_FLIP, 2, WHIP, "Abyssal whip", 1_500_000L, 5L, false);
		DecisionEngine.Suggestion sellS = new DecisionEngine.Suggestion("SELL:3", "", SuggestionType.SELL, 3, BOND, "Old school bond", 10_500_000L, 3L, true);
		DecisionEngine.Suggestion[] all = {null, null, buyS, sellS, null, null, null, null};
		assertEquals(sellS, GeGuideController.chooseTarget(all, home, true));
		GeGuide.Screen searching = new GeGuide.Screen();
		searching.geOpen = true;
		searching.setupOpen = true;
		searching.openSlot = 2;
		searching.setupItemId = -1;
		assertEquals("no buy to preset in the search", sellS, GeGuideController.chooseTarget(all, searching, true));
	}

	// Req: the fill line / key give nothing for a buy box while sell-only.
	@Test
	public void fillOnlySells()
	{
		DecisionEngine.Suggestion[] all = {
			new DecisionEngine.Suggestion("START_NEW_FLIP:0", "", SuggestionType.START_NEW_FLIP, 0, WHIP, "Abyssal whip", 1_500_000L, 5L, false),
			new DecisionEngine.Suggestion("SELL:1", "", SuggestionType.SELL, 1, BOND, "Old school bond", 10_500_000L, 3L, true),
		};
		assertNull(GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, false, true));
		assertEquals(Long.valueOf(1_500_000L), GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, false, false));
		assertEquals(Long.valueOf(10_500_000L), GeChatInput.valueFor(all, GeChatInput.Box.PRICE, BOND, true, true));
	}

	private static ServerSuggestions.PlanPosition pos(String id, String status, int target, int bought, int sold)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.id = id;
		p.itemId = WHIP;
		p.name = "Abyssal whip";
		p.status = status;
		p.targetQty = target;
		p.boughtQty = bought;
		p.soldQty = sold;
		p.buyPrice = 1_000;
		p.sellPrice = 1_100;
		return p;
	}

	// Req: buy notifications silenced (new flip, raise buy, buy fills, free
	// slots), sells still notify, and silenced ones aren't replayed later.
	@Test
	public void buyNotificationsSilenced()
	{
		PlanNotifications.Settings on = new PlanNotifications.Settings(true, true, true, true, true, true, true);
		PlanNotifications.Settings off = new PlanNotifications.Settings(true, true, true, true, true, true, false);
		ServerSuggestions.PlanInfo plan = new ServerSuggestions.PlanInfo();
		plan.createdAt = 0.0;
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.emptyList(), null, plan, on, 0, null);

		ServerSuggestions.PlanPosition newFlip = pos("new", "pending", 5, 0, 0);
		newFlip.slot = 2;
		newFlip.createdAt = (double) PlanNotifications.NEW_FLIP_AFTER_BUILD_MS + 1;
		ServerSuggestions.PlanPosition raiseBuy = pos("rb", "buying", 5, 1, 0);
		raiseBuy.alertKind = "raise_buy";
		raiseBuy.suggestedPrice = 1_010.0;
		raiseBuy.alertAt = 5.0;
		ServerSuggestions.PlanPosition sold = pos("s", "done", 5, 5, 5);
		ServerSuggestions.FreeSlot free = new ServerSuggestions.FreeSlot();
		free.slot = 4;
		List<PlanNotifications.Event> sent = n.check(Arrays.asList(newFlip, raiseBuy, sold), Collections.singletonList(free), plan, on, 0, null);
		List<String> keys = new ArrayList<>();
		for (PlanNotifications.Event e : sent)
		{
			keys.add(e.key);
		}
		// Offers already up keep being tracked: a finished buy / sell still notifies.
		assertEquals(Arrays.asList("bought:s:5", "sold:s:5"), keys);

		assertTrue("turning it off doesn't replay them",
			n.check(Arrays.asList(newFlip, raiseBuy, sold), Collections.singletonList(free), plan, off, 0, null).isEmpty());
		assertTrue(PlanNotifications.isBuyEvent(new PlanNotifications.Event("alert:x:lower_buy:1", PlanNotifications.Type.PRICE_ALERTS, "", "", "x")));
		assertFalse(PlanNotifications.isBuyEvent(new PlanNotifications.Event("alert:x:lower_sell:1", PlanNotifications.Type.PRICE_ALERTS, "", "", "x")));
	}

	// Req: Pending positions not yet listed show "Paused (sell-only)".
	@Test
	public void pausedPositions()
	{
		ServerSuggestions.PlanPosition pending = pos("p", "pending", 5, 0, 0);
		assertTrue(PlanText.isPaused(pending, true, false));
		assertFalse("not sell-only", PlanText.isPaused(pending, false, false));
		assertFalse("already in a GE slot", PlanText.isPaused(pending, true, true));
		assertFalse("buying keeps filling", PlanText.isPaused(pos("b", "buying", 5, 2, 0), true, true));
		assertFalse(PlanText.isPaused(pos("s", "bought", 5, 5, 0), true, false));
		assertEquals("Paused (sell-only)", PlanText.PAUSED_LABEL);
	}
}
