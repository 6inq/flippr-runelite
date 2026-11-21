package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

// Requirement: GE guidance points at the next thing to use for the current
// suggestion, one step at a time - the slot's Buy button (or the item to
// sell), the item in the search, price and quantity until they match the
// suggestion, then Confirm; Back for the wrong item / side; Collect; abort
// (red) / modify (amber) a slot; the clerk or a banker away from the GE.
// Nothing is drawn when guidance is off or there's nothing to do.
public class GeGuideTest
{
	private static final int WHIP = 4151;
	private static final int BOND = 13190;

	private static GeGuide.Target buy(int slot, int itemId, Long price, Long qty)
	{
		return new GeGuide.Target(SuggestionType.START_NEW_FLIP, slot, itemId, false, price, qty);
	}

	private static GeGuide.Screen home()
	{
		GeGuide.Screen s = new GeGuide.Screen();
		s.geOpen = true;
		return s;
	}

	private static GeGuide.Screen setup(boolean sell, int itemId, long price, long qty)
	{
		GeGuide.Screen s = home();
		s.setupOpen = true;
		s.openSlot = 2;
		s.setupSell = sell;
		s.setupItemId = itemId;
		s.setupPrice = price;
		s.setupQuantity = qty;
		return s;
	}

	private static GeGuide.Step step(GeGuide.Element e, GeGuide.Tone t)
	{
		return new GeGuide.Step(e, t, -1, false);
	}

	private static final GeGuide.Tone NEXT = GeGuide.Tone.NEXT;

	// Req: config off -> nothing, whatever the screen.
	@Test
	public void offDrawsNothing()
	{
		assertTrue(GeGuide.plan(false, buy(2, WHIP, 100L, 5L), home()).isEmpty());
		assertTrue(GeGuide.plan(true, null, home()).isEmpty());
	}

	// Req: home screen - Buy button of the slot, Collect, red abort, amber modify, sell item.
	@Test
	public void homeScreen()
	{
		assertEquals(Collections.singletonList(new GeGuide.Step(GeGuide.Element.SLOT_BUY_BUTTON, NEXT, 2, false)),
			GeGuide.plan(true, buy(2, WHIP, 100L, 5L), home()));
		assertEquals(Collections.singletonList(new GeGuide.Step(GeGuide.Element.SLOT_BUY_BUTTON, NEXT, 4, true)),
			GeGuide.plan(true, new GeGuide.Target(SuggestionType.DUMP_BUY, 4, WHIP, false, 90L, 3L), home()));
		assertEquals(Collections.singletonList(step(GeGuide.Element.COLLECT, NEXT)),
			GeGuide.plan(true, new GeGuide.Target(SuggestionType.COLLECT, 1, WHIP, false, null, null), home()));
		assertEquals(Collections.singletonList(new GeGuide.Step(GeGuide.Element.SLOT_BOX, GeGuide.Tone.ABORT, 6, false)),
			GeGuide.plan(true, new GeGuide.Target(SuggestionType.ABANDON, 6, WHIP, false, null, null), home()));
		assertEquals(Collections.singletonList(new GeGuide.Step(GeGuide.Element.SLOT_BOX, GeGuide.Tone.ATTENTION, 3, false)),
			GeGuide.plan(true, new GeGuide.Target(SuggestionType.MODIFY, 3, WHIP, true, 120L, null), home()));

		GeGuide.Target sell = new GeGuide.Target(SuggestionType.SELL, -1, WHIP, true, 120L, 5L);
		GeGuide.Screen s = home();
		assertTrue("nothing to sell in the inventory", GeGuide.plan(true, sell, s).isEmpty());
		s.targetInInventory = true;
		assertEquals(Collections.singletonList(step(GeGuide.Element.INVENTORY_ITEM, NEXT)), GeGuide.plan(true, sell, s));

		assertTrue("HOLD needs nothing", GeGuide.plan(true, new GeGuide.Target(SuggestionType.HOLD, 1, WHIP, true, null, null), home()).isEmpty());
	}

	// Req: setup screen, step by step - search result, price and quantity until they match, then Confirm.
	@Test
	public void setupStepsUntilConfirm()
	{
		GeGuide.Target t = buy(2, WHIP, 1_234_567L, 10L);
		GeGuide.Screen searching = setup(false, -1, 0, 0);
		assertEquals("search closed: the item box that opens it", Collections.singletonList(step(GeGuide.Element.ITEM_SEARCH_BUTTON, NEXT)),
			GeGuide.plan(true, t, searching));
		searching.searchOpen = true;
		assertEquals(Collections.singletonList(step(GeGuide.Element.SEARCH_RESULT, NEXT)), GeGuide.plan(true, t, searching));

		assertEquals(Arrays.asList(step(GeGuide.Element.PRICE_BUTTON, NEXT), step(GeGuide.Element.QUANTITY_BUTTON, NEXT)),
			GeGuide.plan(true, t, setup(false, WHIP, 1_200_000, 1)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.QUANTITY_BUTTON, NEXT)),
			GeGuide.plan(true, t, setup(false, WHIP, 1_234_567, 1)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.PRICE_BUTTON, NEXT)),
			GeGuide.plan(true, t, setup(false, WHIP, 1, 10)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.CONFIRM, NEXT)),
			GeGuide.plan(true, t, setup(false, WHIP, 1_234_567, 10)));
	}

	// Req: Back when the wrong side (or a sell's wrong item) is set up; a buy's
	// wrong item points at the item box.
	@Test
	public void backForWrongItemOrSide()
	{
		GeGuide.Target t = buy(2, WHIP, 100L, 10L);
		assertEquals("wrong item on a buy: the item box re-opens the search",
			Collections.singletonList(step(GeGuide.Element.ITEM_SEARCH_BUTTON, NEXT)), GeGuide.plan(true, t, setup(false, BOND, 100, 10)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.BACK, NEXT)), GeGuide.plan(true, t, setup(true, WHIP, 100, 10)));
		GeGuide.Target sell = new GeGuide.Target(SuggestionType.SELL, 2, WHIP, true, 100L, 10L);
		assertEquals("wrong item on a sell", Collections.singletonList(step(GeGuide.Element.BACK, NEXT)), GeGuide.plan(true, sell, setup(true, BOND, 100, 10)));
		// Collect / abort are done from the home screen.
		assertEquals(Collections.singletonList(step(GeGuide.Element.BACK, NEXT)),
			GeGuide.plan(true, new GeGuide.Target(SuggestionType.COLLECT, 0, 0, false, null, null), setup(false, BOND, 1, 1)));
	}

	// Req: modifying an offer - Modify on its details screen, the price (amber) until it matches, then Confirm.
	@Test
	public void modifyFlow()
	{
		GeGuide.Target t = new GeGuide.Target(SuggestionType.MODIFY, 2, WHIP, true, 1_300_000L, null);
		GeGuide.Screen details = home();
		details.detailsOpen = true;
		details.openSlot = 2;
		assertEquals(Collections.singletonList(step(GeGuide.Element.MODIFY_BUTTON, GeGuide.Tone.ATTENTION)), GeGuide.plan(true, t, details));
		details.openSlot = 5;
		assertEquals("another slot's details: back", Collections.singletonList(step(GeGuide.Element.BACK, NEXT)), GeGuide.plan(true, t, details));

		assertEquals(Collections.singletonList(step(GeGuide.Element.PRICE_BUTTON, GeGuide.Tone.ATTENTION)),
			GeGuide.plan(true, t, setup(true, WHIP, 1_350_000, 4)));
		assertEquals(Collections.singletonList(step(GeGuide.Element.CONFIRM, NEXT)), GeGuide.plan(true, t, setup(true, WHIP, 1_300_000, 4)));
	}

	// Req: a free slot with no item from Flippr leads nowhere - no guidance on
	// any screen (it used to point at Buy, then go silent in the setup).
	@Test
	public void noItemNoGuidance()
	{
		GeGuide.Screen s = setup(false, -1, 0, 0);
		s.searchOpen = true;
		assertTrue(GeGuide.plan(true, buy(2, 0, null, null), s).isEmpty());
		assertTrue("home screen doesn't point at an unplanned slot", GeGuide.plan(true, buy(2, 0, null, null), home()).isEmpty());
	}

	// Req: away from the GE - the clerk only when nearby; a banker when the buy
	// needs more gp; the bank's close button once gp isn't the problem.
	@Test
	public void awayFromGe()
	{
		GeGuide.Target t = buy(1, WHIP, 1_000L, 10L);
		GeGuide.Screen s = new GeGuide.Screen();
		s.coins = 50_000;
		assertTrue("not at the GE: nothing", GeGuide.plan(true, t, s).isEmpty());
		s.clerkNearby = true;
		assertEquals(Collections.singletonList(step(GeGuide.Element.CLERK, NEXT)), GeGuide.plan(true, t, s));

		s.coins = 5_000;
		assertEquals("needs gp, no banker near: still the clerk", Collections.singletonList(step(GeGuide.Element.CLERK, NEXT)), GeGuide.plan(true, t, s));
		s.bankerNearby = true;
		assertEquals(Collections.singletonList(step(GeGuide.Element.BANKER, GeGuide.Tone.ATTENTION)), GeGuide.plan(true, t, s));

		GeGuide.Screen bank = new GeGuide.Screen();
		bank.bankOpen = true;
		bank.coins = 5_000;
		assertTrue("withdrawing gp: leave the bank alone", GeGuide.plan(true, t, bank).isEmpty());
		bank.coins = 20_000;
		assertEquals(Collections.singletonList(step(GeGuide.Element.BANK_CLOSE, NEXT)), GeGuide.plan(true, t, bank));
	}

	private static DecisionEngine.Suggestion sug(SuggestionType type, int slot, int itemId, boolean sell, Long price, Long qty)
	{
		return new DecisionEngine.Suggestion(type + ":" + slot, "", type, slot, itemId, "Item " + itemId, price, qty, sell);
	}

	// Req: the guided suggestion - the item being set up wins; before an item is
	// chosen, the buy for the opened slot; elsewhere the lead suggestion.
	@Test
	public void chooseTarget()
	{
		DecisionEngine.Suggestion collect = sug(SuggestionType.COLLECT, 0, WHIP, false, null, null);
		DecisionEngine.Suggestion buyBond = sug(SuggestionType.START_NEW_FLIP, 2, BOND, false, 10_000_000L, 1L);
		DecisionEngine.Suggestion buyWhip = sug(SuggestionType.START_NEW_FLIP, 5, WHIP, false, 1_000_000L, 3L);
		DecisionEngine.Suggestion[] all = {collect, null, buyBond, null, null, buyWhip, null, null};

		assertSame(collect, GeGuideController.chooseTarget(all, home()));
		assertSame(buyWhip, GeGuideController.chooseTarget(all, setup(false, WHIP, 0, 0)));
		GeGuide.Screen noItem = setup(false, -1, 0, 0);
		noItem.openSlot = 2;
		assertSame(buyBond, GeGuideController.chooseTarget(all, noItem));
		noItem.openSlot = 7;
		assertTrue(GeGuideController.chooseTarget(all, noItem) == buyBond || GeGuideController.chooseTarget(all, noItem) == buyWhip);
	}

	// Req: colours - tone picks the configured colour; steady when flash is off,
	// pulsing within range when on, a dump pulses faster.
	@Test
	public void colours()
	{
		java.awt.Color blue = java.awt.Color.BLUE;
		java.awt.Color amber = java.awt.Color.ORANGE;
		java.awt.Color red = java.awt.Color.RED;
		assertSame(blue, GuideColors.base(GeGuide.Tone.NEXT, blue, amber, red));
		assertSame(amber, GuideColors.base(GeGuide.Tone.ATTENTION, blue, amber, red));
		assertSame(red, GuideColors.base(GeGuide.Tone.ABORT, blue, amber, red));
		assertEquals(GuideColors.STEADY_ALPHA, GuideColors.alpha(false, true, 123));
		assertEquals(GuideColors.MIN_ALPHA, GuideColors.alpha(true, false, 0));
		assertEquals(GuideColors.MAX_ALPHA, GuideColors.alpha(true, false, GuideColors.PULSE_MS / 2));
		assertEquals(GuideColors.MAX_ALPHA, GuideColors.alpha(true, true, GuideColors.URGENT_PULSE_MS / 2));
		for (long t = 0; t < 3000; t += 37)
		{
			int a = GuideColors.alpha(true, false, t);
			assertTrue(a >= GuideColors.MIN_ALPHA && a <= GuideColors.MAX_ALPHA);
		}
		List<java.awt.Color> c = Collections.singletonList(GuideColors.fill(red, false, false, 0));
		assertEquals(255, c.get(0).getRed());
		assertEquals(GuideColors.STEADY_ALPHA, c.get(0).getAlpha());
	}
}
