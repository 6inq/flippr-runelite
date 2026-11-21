package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

// Requirement: the GE guidance can't be silently off - a watch mode or the
// engine off shows the reason; the price / quantity prompt is detected; the
// Flippr search row handles the game's previous search and only ever
// touches its own row; highlights cover every step of the
// plan's next action; the guided target comes from the plan's suggestions,
// with Collect for a finished offer.
public class GeGuidanceTest
{
	private static final int WHIP = 4151;
	private static final int BOND = 13190;
	private static final int COAL = 453;

	// Req: what blocks guidance, and the banner text for it.
	@Test
	public void guidanceBlockedReasonAndBanner()
	{
		assertEquals(GuidanceGate.Block.NONE, GuidanceGate.blockOf(true, false, false));
		assertEquals(GuidanceGate.Block.WATCH_MODE, GuidanceGate.blockOf(true, true, false));
		assertEquals("the owner's profile: watch mode on, engine off",
			GuidanceGate.Block.WATCH_MODE, GuidanceGate.blockOf(false, true, false));
		assertEquals(GuidanceGate.Block.WATCH_MODE_DUMP, GuidanceGate.blockOf(true, false, true));
		assertEquals(GuidanceGate.Block.ENGINE_OFF, GuidanceGate.blockOf(false, false, false));

		assertNull("no banner when nothing blocks", GuidanceGate.bannerText(GuidanceGate.Block.NONE));
		assertEquals("GE guidance is off: Watch mode is on", GuidanceGate.bannerText(GuidanceGate.Block.WATCH_MODE));
		assertEquals("GE guidance is off: Watch mode (Dump) is on", GuidanceGate.bannerText(GuidanceGate.Block.WATCH_MODE_DUMP));
		assertEquals("GE guidance is off: Suggestion engine is off", GuidanceGate.bannerText(GuidanceGate.Block.ENGINE_OFF));
	}

	// Req: the log helper fires once per state change.
	@Test
	public void changeLogOncePerState()
	{
		ChangeLog c = new ChangeLog();
		assertTrue(c.changed("a"));
		assertFalse(c.changed("a"));
		assertTrue(c.changed("b"));
		c.reset();
		assertTrue(c.changed("b"));
	}

	// Req: prompt open = numeric input + chatbox title + GE setup container + open slot;
	// price vs quantity from the title (price also needs "Buy offer" / "Sell offer").
	@Test
	public void priceVsQuantityDetection()
	{
		assertTrue(GeChatInput.inputOpen(7, true, true, true));
		assertTrue("64-bit price input", GeChatInput.inputOpen(30, true, true, true));
		assertFalse("item search", GeChatInput.inputOpen(14, true, true, true));
		assertFalse("no chatbox title", GeChatInput.inputOpen(7, false, true, true));
		assertFalse("not the GE setup screen", GeChatInput.inputOpen(7, true, false, true));
		assertFalse("no slot open", GeChatInput.inputOpen(7, true, true, false));

		assertEquals(GeChatInput.Box.PRICE, GeChatInput.boxOf(30, "Set a price for each item:", "Buy offer"));
		assertEquals(GeChatInput.Box.PRICE, GeChatInput.boxOf(30, "Set a price for each item:", "Sell offer"));
		assertEquals("price title with the item description instead of the offer text", GeChatInput.Box.NONE,
			GeChatInput.boxOf(30, "Set a price for each item:", "A weapon from the abyss."));
		assertEquals(GeChatInput.Box.QUANTITY, GeChatInput.boxOf(7, "How many do you wish to buy?", null));
		assertEquals(GeChatInput.Box.QUANTITY, GeChatInput.boxOf(7, "How many do you wish to sell?", "Sell offer"));
	}

	// Req: the values filled - plan buy price + target quantity for a buy, sell price + held quantity for a sell.
	@Test
	public void fillValuesFromPlanSuggestions()
	{
		DecisionEngine.Suggestion buy = new DecisionEngine.Suggestion("START_NEW_FLIP:0:" + WHIP, "", SuggestionType.START_NEW_FLIP,
			0, WHIP, "Abyssal whip", 1_500_000L, 8L, false);
		DecisionEngine.Suggestion sell = new DecisionEngine.Suggestion("SELL:1:" + BOND, "", SuggestionType.SELL,
			1, BOND, "Old school bond", 9_000_000L, 2L, true);
		DecisionEngine.Suggestion[] all = {buy, sell, null, null, null, null, null, null};
		assertEquals(Long.valueOf(1_500_000L), GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, false));
		assertEquals(Long.valueOf(8L), GeChatInput.valueFor(all, GeChatInput.Box.QUANTITY, WHIP, false));
		assertEquals(Long.valueOf(9_000_000L), GeChatInput.valueFor(all, GeChatInput.Box.PRICE, BOND, true));
		assertEquals(Long.valueOf(2L), GeChatInput.valueFor(all, GeChatInput.Box.QUANTITY, BOND, true));
		assertNull("wrong side", GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, true));
		assertNull("other item", GeChatInput.valueFor(all, GeChatInput.Box.PRICE, COAL, false));
		assertNull("no prompt", GeChatInput.valueFor(all, GeChatInput.Box.NONE, WHIP, false));
	}

	private static SearchSuggestionInjector.Action row(String whyNot, boolean empty, String rowName, int rowItemId,
		int lastSearched, boolean showLast)
	{
		return SearchSuggestionInjector.decide(whyNot, empty, rowName, rowItemId, lastSearched, showLast).action;
	}

	// Req: search row - an empty search, "Show last searched" on/off
	// with/without a previous search; only rows with Flippr's marker are ever
	// updated or cleared, any other row is left alone whatever it says.
	@Test
	public void searchRowDecide()
	{
		String ours = "<col=40a0ff>Abyssal whip</col>";
		String previous = "<col=ff9040>Coal</col>";
		String unmarked = "<col=ff9040>Abyssal whip</col>";
		SearchSuggestionInjector.Action create = SearchSuggestionInjector.Action.CREATE;

		assertEquals("empty search, nothing there", create, row(null, true, null, -1, -1, true));
		assertEquals("show last searched off, previous search set (game shows none)", create, row(null, true, null, -1, COAL, false));
		assertEquals("show last searched on, no previous search", create, row(null, true, "", -1, -1, true));
		assertEquals("show last searched on, previous search shown", SearchSuggestionInjector.Action.OVERWRITE_NATIVE,
			row(null, true, previous, COAL, COAL, true));
		assertEquals("unknown content, no native previous search", SearchSuggestionInjector.Action.NONE,
			row(null, true, previous, COAL, COAL, false));
		assertEquals(SearchSuggestionInjector.Action.UPDATE_OWN, row(null, true, ours, WHIP, COAL, true));
		assertEquals("a row without Flippr's marker that isn't the previous search", SearchSuggestionInjector.Action.NONE,
			row(null, true, unmarked, WHIP, COAL, true));
		assertEquals("a row without Flippr's marker, no previous search", SearchSuggestionInjector.Action.NONE,
			row(null, true, unmarked, WHIP, -1, true));
		assertEquals("typing", SearchSuggestionInjector.Action.CLEAR_OWN, row(null, false, ours, WHIP, COAL, true));
		assertEquals("typing: a row without the marker is never cleared", SearchSuggestionInjector.Action.NONE,
			row(null, false, unmarked, WHIP, COAL, true));
		assertEquals("guidance blocked", SearchSuggestionInjector.Action.CLEAR_OWN,
			row("GE guidance is off: Watch mode is on", true, ours, WHIP, -1, true));
		assertEquals("nothing to preset: a row without the marker is never cleared", SearchSuggestionInjector.Action.NONE,
			row("nothing", true, unmarked, WHIP, COAL, true));
		assertEquals(SearchSuggestionInjector.Action.NONE, row("nothing", true, previous, COAL, COAL, true));

		SearchSuggestionInjector.Decision d = SearchSuggestionInjector.decide("nothing", true, null, -1, -1, true);
		assertEquals("nothing", d.reason);
		assertTrue(SearchSuggestionInjector.isOwnRow(ours));
		assertFalse(SearchSuggestionInjector.isOwnRow(unmarked));
		assertFalse(SearchSuggestionInjector.isOwnRow(null));
	}

	private static GeGuide.Screen setup(boolean sell, int itemId, long price, long qty)
	{
		GeGuide.Screen s = new GeGuide.Screen();
		s.geOpen = true;
		s.setupOpen = true;
		s.openSlot = 3;
		s.setupSell = sell;
		s.setupItemId = itemId;
		s.setupPrice = price;
		s.setupQuantity = qty;
		return s;
	}

	private static GeGuide.Step step(GeGuide.Element e)
	{
		return new GeGuide.Step(e, GeGuide.Tone.NEXT, -1, false);
	}

	// Req: highlight step per GE screen state for the plan's next buy and sell.
	@Test
	public void highlightStepPerScreen()
	{
		GeGuide.Target buy = new GeGuide.Target(SuggestionType.START_NEW_FLIP, 3, WHIP, false, 1_500_000L, 8L);
		GeGuide.Screen home = new GeGuide.Screen();
		home.geOpen = true;
		assertEquals(Collections.singletonList(new GeGuide.Step(GeGuide.Element.SLOT_BUY_BUTTON, GeGuide.Tone.NEXT, 3, false)),
			GeGuide.plan(true, buy, home));

		GeGuide.Screen search = setup(false, -1, 0, 0);
		search.searchOpen = true;
		assertEquals(Collections.singletonList(step(GeGuide.Element.SEARCH_RESULT)), GeGuide.plan(true, buy, search));
		GeGuide.Screen reSearch = setup(false, COAL, 0, 0);
		reSearch.searchOpen = true;
		assertEquals("wrong item, search open again", Collections.singletonList(step(GeGuide.Element.SEARCH_RESULT)), GeGuide.plan(true, buy, reSearch));
		assertEquals(Arrays.asList(step(GeGuide.Element.PRICE_BUTTON), step(GeGuide.Element.QUANTITY_BUTTON)),
			GeGuide.plan(true, buy, setup(false, WHIP, 1, 1)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.CONFIRM)), GeGuide.plan(true, buy, setup(false, WHIP, 1_500_000, 8)));

		GeGuide.Target sell = new GeGuide.Target(SuggestionType.SELL, 3, BOND, true, 9_000_000L, 2L);
		GeGuide.Screen sellAll = setup(true, BOND, 9_000_000, 1);
		sellAll.heldCount = 2;
		assertEquals("selling everything held: the All button", Collections.singletonList(step(GeGuide.Element.QUANTITY_ALL_BUTTON)),
			GeGuide.plan(true, sell, sellAll));
		GeGuide.Screen sellSome = setup(true, BOND, 9_000_000, 1);
		sellSome.heldCount = 5;
		assertEquals(Collections.singletonList(step(GeGuide.Element.QUANTITY_BUTTON)), GeGuide.plan(true, sell, sellSome));

		GeGuide.Target modifyBuy = new GeGuide.Target(SuggestionType.MODIFY, 3, WHIP, false, 1_600_000L, null);
		assertEquals("modify never re-searches", Collections.singletonList(step(GeGuide.Element.BACK)),
			GeGuide.plan(true, modifyBuy, setup(false, COAL, 1, 1)));

		GeGuide.Target collect = new GeGuide.Target(SuggestionType.COLLECT, 5, WHIP, false, null, null);
		assertEquals(Collections.singletonList(step(GeGuide.Element.COLLECT)), GeGuide.plan(true, collect, home));
	}

	private static DecisionEngine.Suggestion sug(SuggestionType type, int slot, int itemId, boolean sell, Long price, Long qty)
	{
		return new DecisionEngine.Suggestion(type + ":" + slot, "", type, slot, itemId, "Item " + itemId, price, qty, sell);
	}

	// Req: the target comes from the plan's suggestions - Collect for a finished
	// offer first, then the plan's sells and buys; the search row's buy is the
	// open slot's, whatever the screen widgets say.
	@Test
	public void targetFromPlanSuggestions()
	{
		DecisionEngine.Suggestion collect = sug(SuggestionType.COLLECT, 0, COAL, false, null, null);
		DecisionEngine.Suggestion sellBond = sug(SuggestionType.SELL, 1, BOND, true, 9_000_000L, 2L);
		DecisionEngine.Suggestion buyWhip = sug(SuggestionType.START_NEW_FLIP, 2, WHIP, false, 1_500_000L, 8L);
		DecisionEngine.Suggestion buyCoal = sug(SuggestionType.START_NEW_FLIP, 3, COAL, false, 150L, 13_000L);
		DecisionEngine.Suggestion[] all = {collect, sellBond, buyWhip, buyCoal, null, null, null, null};

		GeGuide.Screen home = new GeGuide.Screen();
		home.geOpen = true;
		assertSame("a finished offer is collected first", collect, GeGuideController.chooseTarget(all, home));
		DecisionEngine.Suggestion[] noCollect = {null, sellBond, buyWhip, buyCoal, null, null, null, null};
		assertSame("then the plan's sell", sellBond, GeGuideController.chooseTarget(noCollect, home));
		DecisionEngine.Suggestion[] buysOnly = {null, null, buyWhip, buyCoal, null, null, null, null};
		assertSame("then the first plan buy", buyWhip, GeGuideController.chooseTarget(buysOnly, home));

		assertSame("search in slot 3: slot 3's buy", buyCoal, GeGuideController.searchTarget(all, 3, false));
		assertSame("search in a slot with no buy: a plan buy", buyWhip, GeGuideController.searchTarget(all, 6, false));
		assertNull("sell-only: no search row", GeGuideController.searchTarget(all, 3, true));
		DecisionEngine.Suggestion freeSlot = sug(SuggestionType.START_NEW_FLIP, 4, 0, false, null, null);
		assertNull("no item to preset", GeGuideController.searchTarget(new DecisionEngine.Suggestion[]{freeSlot}, 4, false));
	}

	// Req: a finished or cancelled offer waiting in its slot is collectable.
	@Test
	public void collectableStates()
	{
		assertTrue(DecisionEngine.isCollectableState(GrandExchangeOfferState.BOUGHT));
		assertTrue(DecisionEngine.isCollectableState(GrandExchangeOfferState.SOLD));
		assertTrue(DecisionEngine.isCollectableState(GrandExchangeOfferState.CANCELLED_BUY));
		assertTrue(DecisionEngine.isCollectableState(GrandExchangeOfferState.CANCELLED_SELL));
		assertFalse(DecisionEngine.isCollectableState(GrandExchangeOfferState.BUYING));
		assertFalse(DecisionEngine.isCollectableState(GrandExchangeOfferState.SELLING));
		assertFalse(DecisionEngine.isCollectableState(GrandExchangeOfferState.EMPTY));

		DecisionEngine.Suggestion done = new DecisionEngine.Suggestion(DecisionEngine.COLLECT_DONE_KEY + 2, "", SuggestionType.COLLECT,
			2, WHIP, "Abyssal whip", null, null, false);
		assertEquals("Slot 2: collect Abyssal whip", ChatNotifier.chatText(done));
	}
}
