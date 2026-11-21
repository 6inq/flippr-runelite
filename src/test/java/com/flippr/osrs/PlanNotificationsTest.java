package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

// Requirement: Plan notifications match the RS3 app -
// price alerts, finished offers, partial fills (max 1 per position per 10
// min), auto price changes / below break-even on unlisted legs only, and a
// new flip in a free slot ("Slot N: new flip - buy X x Item at P"). Each is
// sent once (key = position + kind + count), nothing on the first check
// after start (no storm on login), sent keys survive a restart (size-capped),
// and the master switch and per-type toggles apply.
public class PlanNotificationsTest
{
	private static final long T0 = 1_700_000_000_000L;
	private static final PlanNotifications.Settings ALL = new PlanNotifications.Settings(true, true, true, true, true, true);

	private static ServerSuggestions.PlanPosition pos(String id, String status, int target, int bought, int sold)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.id = id;
		p.itemId = 4151;
		p.name = "Abyssal whip";
		p.status = status;
		p.targetQty = target;
		p.boughtQty = bought;
		p.soldQty = sold;
		p.buyPrice = 1_230_000;
		p.sellPrice = 1_300_000;
		return p;
	}

	private static ServerSuggestions.PlanInfo plan()
	{
		ServerSuggestions.PlanInfo plan = new ServerSuggestions.PlanInfo();
		plan.createdAt = (double) T0;
		return plan;
	}

	private static List<String> messages(List<PlanNotifications.Event> events)
	{
		List<String> out = new ArrayList<>();
		for (PlanNotifications.Event e : events)
		{
			out.add(e.message());
		}
		return out;
	}

	// Req: no storm - the first check only remembers what is already true.
	@Test
	public void firstCheckIsSilent()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		ServerSuggestions.PlanPosition p = pos("a", "bought", 10, 10, 0);
		p.alertKind = "lower_sell";
		p.suggestedPrice = 1_290_000.0;
		assertTrue(n.check(Collections.singletonList(p), null, plan(), ALL, T0, null).isEmpty());
		assertTrue("nothing changed - still nothing", n.check(Collections.singletonList(p), null, plan(), ALL, T0, null).isEmpty());
	}

	// Req: a finished buy / sell notifies once, with the exact wording.
	@Test
	public void completedOffersNotifyOnce()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.singletonList(pos("a", "buying", 10, 3, 0)), null, plan(), ALL, T0, null);

		List<PlanNotifications.Event> sent = n.check(Collections.singletonList(pos("a", "bought", 10, 10, 0)), null, plan(), ALL, T0, null);
		assertEquals(Collections.singletonList("Flippr: buy finished - Bought 10 x Abyssal whip - list the sell at 1,300,000"), messages(sent));
		assertTrue(n.check(Collections.singletonList(pos("a", "bought", 10, 10, 0)), null, plan(), ALL, T0, null).isEmpty());

		ServerSuggestions.PlanPosition done = pos("a", "done", 10, 10, 10);
		done.realisedProfit = 450_000;
		sent = n.check(Collections.singletonList(done), null, plan(), ALL, T0, null);
		assertEquals(Collections.singletonList("Flippr: sell finished - Sold 10 x Abyssal whip - +450K profit"), messages(sent));
	}

	// Req: price alerts use the site's wording with exact gp and the OSRS hint.
	@Test
	public void priceAlertText()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		ServerSuggestions.PlanPosition p = pos("a", "buying", 10, 0, 0);
		n.check(Collections.singletonList(p), null, plan(), ALL, T0, null);
		p.alertKind = "raise_buy";
		p.suggestedPrice = 1_234_567.0;
		p.alertAt = (double) T0 + 5;
		List<PlanNotifications.Event> sent = n.check(Collections.singletonList(p), null, plan(), ALL, T0, null);
		assertEquals(Collections.singletonList("Flippr: Abyssal whip - Set buy to 1,234,567 - then update the offer in the GE"), messages(sent));
	}

	// Req: partial fills are off by default and at most one per position per 10 minutes.
	@Test
	public void partialFillsCooldownAndDefaultOff()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.singletonList(pos("a", "buying", 10, 0, 0)), null, plan(), ALL, T0, null);
		assertEquals(1, n.check(Collections.singletonList(pos("a", "buying", 10, 2, 0)), null, plan(), ALL, T0, null).size());
		assertTrue("within 10 min", n.check(Collections.singletonList(pos("a", "buying", 10, 4, 0)), null, plan(), ALL, T0 + 60_000, null).isEmpty());
		assertEquals(1, n.check(Collections.singletonList(pos("a", "buying", 10, 6, 0)), null, plan(), ALL,
			T0 + PlanNotifications.PARTIAL_COOLDOWN_MS + 60_000, null).size());

		PlanNotifications d = new PlanNotifications(null, null);
		d.check(Collections.singletonList(pos("a", "buying", 10, 0, 0)), null, plan(), PlanNotifications.Settings.defaults(), T0, null);
		assertTrue(d.check(Collections.singletonList(pos("a", "buying", 10, 2, 0)), null, plan(), PlanNotifications.Settings.defaults(), T0, null).isEmpty());
	}

	// Req: auto price changes and below break-even only for unlisted legs;
	// below break-even notifies again after it ended.
	@Test
	public void autoPriceOnlyUnlistedAndStickyBreakEven()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		ServerSuggestions.PlanPosition listed = pos("a", "selling", 10, 10, 0);
		listed.previousSellPrice = 1_310_000.0;
		listed.sellPriceUpdatedAt = (double) T0;
		listed.sellBelowBreakEven = true;
		n.check(Collections.emptyList(), null, plan(), ALL, T0, null);
		for (PlanNotifications.Event e : n.check(Collections.singletonList(listed), null, plan(), ALL, T0, null))
		{
			assertTrue("no auto price event for a listed sell: " + e.key, e.type != PlanNotifications.Type.AUTO_PRICE);
		}

		ServerSuggestions.PlanPosition unlisted = pos("b", "bought", 10, 10, 0);
		unlisted.previousSellPrice = 1_310_000.0;
		unlisted.sellPriceUpdatedAt = (double) T0;
		unlisted.sellBelowBreakEven = true;
		List<String> sent = messages(n.check(Collections.singletonList(unlisted), null, plan(), ALL, T0, null));
		assertTrue(sent.contains("Flippr: Abyssal whip - Sell price updated to 1,300,000 (was 1,310,000)"));
		assertTrue(sent.contains("Flippr: Abyssal whip - Market below break-even - sell held at 1,300,000"));

		unlisted.sellBelowBreakEven = false;
		n.check(Collections.singletonList(unlisted), null, plan(), ALL, T0, null);
		unlisted.sellBelowBreakEven = true;
		assertEquals(1, n.check(Collections.singletonList(unlisted), null, plan(), ALL, T0, null).size());
	}

	// Req: a flip auto-filled into a free slot: "Slot N: new flip - buy X x Item at P".
	@Test
	public void newFlipInFreeSlot()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.emptyList(), null, plan(), ALL, T0, null);
		ServerSuggestions.PlanPosition p = pos("new", "pending", 120, 0, 0);
		p.slot = 3;
		p.buyPrice = 1_234;
		p.createdAt = (double) T0 + PlanNotifications.NEW_FLIP_AFTER_BUILD_MS + 1;
		List<String> sent = messages(n.check(Collections.singletonList(p), null, plan(), ALL, T0, null));
		assertEquals(Collections.singletonList("Flippr: new flip - Slot 3: new flip - buy 120 x Abyssal whip at 1,234"), sent);

		// Built with the plan: not news.
		ServerSuggestions.PlanPosition original = pos("orig", "pending", 5, 0, 0);
		original.slot = 1;
		original.createdAt = (double) T0;
		assertTrue(n.check(Arrays.asList(p, original), null, plan(), ALL, T0, null).isEmpty());
	}

	// Req: master switch and per-type toggles; a toggled-off event isn't sent later either.
	@Test
	public void togglesApply()
	{
		PlanNotifications.Settings off = new PlanNotifications.Settings(false, true, true, true, true, true);
		PlanNotifications.Settings noCompleted = new PlanNotifications.Settings(true, true, false, true, true, true);
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.singletonList(pos("a", "buying", 10, 3, 3)), null, plan(), ALL, T0, null);
		assertTrue(n.check(Collections.singletonList(pos("a", "bought", 10, 10, 3)), null, plan(), off, T0, null).isEmpty());
		assertTrue(n.check(Collections.singletonList(pos("a", "done", 10, 10, 10)), null, plan(), noCompleted, T0, null).isEmpty());
		assertTrue(n.check(Collections.singletonList(pos("a", "done", 10, 10, 10)), null, plan(), ALL, T0, null).isEmpty());
	}

	// Req: sent keys are saved and restored, so a restart repeats nothing;
	// the saved list is capped.
	@Test
	public void persistedKeysSurviveRestartAndAreCapped()
	{
		List<List<String>> saves = new ArrayList<>();
		PlanNotifications n = new PlanNotifications(null, saves::add);
		n.check(Collections.singletonList(pos("a", "buying", 10, 3, 0)), null, plan(), ALL, T0, null);
		n.check(Collections.singletonList(pos("a", "bought", 10, 10, 0)), null, plan(), ALL, T0, null);
		List<String> saved = saves.get(saves.size() - 1);
		assertTrue(saved.contains("bought:a:10"));

		PlanNotifications restarted = new PlanNotifications(saved, null);
		restarted.check(Collections.emptyList(), null, plan(), ALL, T0, null);
		assertTrue(restarted.check(Collections.singletonList(pos("a", "bought", 10, 10, 0)), null, plan(), ALL, T0, null).isEmpty());

		List<String> many = new ArrayList<>();
		for (int i = 0; i < PlanNotifications.MAX_SENT + 50; i++)
		{
			many.add("bought:x" + i + ":1");
		}
		PlanNotifications big = new PlanNotifications(many, saves::add);
		big.check(Collections.singletonList(pos("z", "bought", 1, 1, 0)), null, plan(), ALL, T0, null);
		assertEquals(PlanNotifications.MAX_SENT, big.sentKeys().size());
		assertEquals("newest kept", "bought:z:1", big.sentKeys().get(PlanNotifications.MAX_SENT - 1));
	}

	// Req: switching character re-seeds - another plan's state is not news.
	@Test
	public void unseedSilencesNextCheck()
	{
		PlanNotifications n = new PlanNotifications(null, null);
		n.check(Collections.emptyList(), null, plan(), ALL, T0, null);
		n.unseed();
		assertTrue(n.check(Collections.singletonList(pos("other", "bought", 5, 5, 0)), null, plan(), ALL, T0, null).isEmpty());
	}
}
