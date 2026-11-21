package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

// Requirement: a Plan position whose buy offer was listed but is now in no
// GE slot (cancelled in game) is reconciled - nothing bought -> Drop,
// something bought -> Stop & sell - but only once the GE has loaded, only
// after 2 consecutive plan reads agree, never for a Pending position that
// was never listed, never while the buy (or its uncollected finish) is still
// in a slot, and never twice.
public class CancelledBuysTest
{
	private static final int WHIP = 4151;

	private static ServerSuggestions.PlanPosition pos(String id, String status, int target, int bought)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.id = id;
		p.itemId = WHIP;
		p.name = "Abyssal whip";
		p.status = status;
		p.targetQty = target;
		p.boughtQty = bought;
		return p;
	}

	private static final List<ListingReporter.SlotOffer> EMPTY = Collections.emptyList();

	private static List<ListingReporter.SlotOffer> buying()
	{
		return Collections.singletonList(new ListingReporter.SlotOffer(2, WHIP, "buy", 1_000, true));
	}

	// Req: a listed buy (status buying) gone from the GE: Drop after 2 plan reads.
	@Test
	public void droppedAfterTwoConfirmations()
	{
		CancelledBuys b = new CancelledBuys(null);
		List<ServerSuggestions.PlanPosition> plan = Collections.singletonList(pos("a", "buying", 10, 0));
		assertTrue("first read: not yet", b.update(plan, EMPTY, true, 1).isEmpty());
		assertTrue("same plan read again: still one confirmation", b.update(plan, EMPTY, true, 1).isEmpty());
		List<CancelledBuys.Finding> f = b.update(plan, EMPTY, true, 2);
		assertEquals(1, f.size());
		assertEquals(CancelledBuys.Fix.DROP, f.get(0).fix);
		assertEquals("Drop", f.get(0).action());
		assertEquals("Buy offer cancelled in the GE", f.get(0).text());
	}

	// Req: something bought -> Stop & sell n.
	@Test
	public void partlyBoughtIsStopAndSell()
	{
		CancelledBuys b = new CancelledBuys(null);
		List<ServerSuggestions.PlanPosition> plan = Collections.singletonList(pos("a", "buying", 10, 4));
		b.update(plan, EMPTY, true, 1);
		CancelledBuys.Finding f = b.update(plan, EMPTY, true, 2).get(0);
		assertEquals(CancelledBuys.Fix.STOP_AND_SELL, f.fix);
		assertEquals("Stop & sell 4", f.action());
	}

	// Req: never before the GE state has loaded (login), and the count starts then.
	@Test
	public void nothingBeforeGeLoaded()
	{
		CancelledBuys b = new CancelledBuys(null);
		List<ServerSuggestions.PlanPosition> plan = Collections.singletonList(pos("a", "buying", 10, 0));
		assertTrue(b.update(plan, EMPTY, false, 1).isEmpty());
		assertTrue(b.update(plan, EMPTY, false, 2).isEmpty());
		assertTrue("first loaded read", b.update(plan, EMPTY, true, 3).isEmpty());
		assertEquals(1, b.update(plan, EMPTY, true, 4).size());
	}

	// Req: the offer back in a slot (re-listed) resets the count; an
	// uncollected finished buy counts as still there; a cancelled one doesn't.
	@Test
	public void offerStillThere()
	{
		CancelledBuys b = new CancelledBuys(null);
		List<ServerSuggestions.PlanPosition> plan = Collections.singletonList(pos("a", "buying", 10, 0));
		b.update(plan, EMPTY, true, 1);
		assertTrue("re-listed", b.update(plan, buying(), true, 2).isEmpty());
		assertTrue("count restarted", b.update(plan, EMPTY, true, 3).isEmpty());
		assertEquals(1, b.update(plan, EMPTY, true, 4).size());

		CancelledBuys c = new CancelledBuys(null);
		List<ListingReporter.SlotOffer> bought = Collections.singletonList(new ListingReporter.SlotOffer(2, WHIP, "buy", 1_000, false, true));
		c.update(plan, bought, true, 1);
		assertTrue("bought, waiting to collect", c.update(plan, bought, true, 2).isEmpty());
		List<ListingReporter.SlotOffer> cancelled = Collections.singletonList(new ListingReporter.SlotOffer(2, WHIP, "buy", 1_000, false, false));
		c.update(plan, cancelled, true, 3);
		assertEquals("cancelled, not yet collected: the offer is gone", 1, c.update(plan, cancelled, true, 4).size());
	}

	// Req: a Pending position never listed is left alone; one seen in a slot
	// before (the site set it back to Pending on the cancel) is reconciled -
	// and the seen list survives a restart.
	@Test
	public void pendingOnlyWhenSeenListed()
	{
		List<ServerSuggestions.PlanPosition> pending = Collections.singletonList(pos("p", "pending", 10, 0));
		CancelledBuys never = new CancelledBuys(null);
		never.update(pending, EMPTY, true, 1);
		assertTrue("never listed: untouched", never.update(pending, EMPTY, true, 2).isEmpty());

		CancelledBuys b = new CancelledBuys(null);
		b.update(pending, buying(), true, 1);
		List<String> saved = b.takeSeenToSave();
		assertEquals(Collections.singletonList("p"), saved);
		assertNull("unchanged: nothing to save", b.takeSeenToSave());
		b.update(pending, EMPTY, true, 2);
		assertEquals(1, b.update(pending, EMPTY, true, 3).size());

		CancelledBuys restarted = new CancelledBuys(saved);
		restarted.update(pending, EMPTY, true, 1);
		assertEquals(1, restarted.update(pending, EMPTY, true, 2).size());
	}

	// Req: only the buy leg - a bought / selling / done position is never reconciled.
	@Test
	public void onlyBuyLeg()
	{
		CancelledBuys b = new CancelledBuys(Arrays.asList("a", "b", "c"));
		List<ServerSuggestions.PlanPosition> plan = Arrays.asList(pos("a", "bought", 10, 10), pos("b", "selling", 10, 10), pos("c", "buying", 10, 10));
		b.update(plan, EMPTY, true, 1);
		assertTrue(b.update(plan, EMPTY, true, 2).isEmpty());
	}

	// Req: never twice; a failed fix may be offered again after fresh confirmations.
	@Test
	public void handledOnceRetryAfterFailure()
	{
		CancelledBuys b = new CancelledBuys(null);
		List<ServerSuggestions.PlanPosition> plan = Collections.singletonList(pos("a", "buying", 10, 0));
		b.update(plan, EMPTY, true, 1);
		assertEquals(1, b.update(plan, EMPTY, true, 2).size());
		b.handled("a");
		assertTrue(b.update(plan, EMPTY, true, 3).isEmpty());
		assertTrue(b.update(plan, EMPTY, true, 4).isEmpty());
		b.retry("a");
		assertTrue(b.update(plan, EMPTY, true, 5).isEmpty());
		assertEquals(1, b.update(plan, EMPTY, true, 6).size());
	}

	// Req: the seen list is capped.
	@Test
	public void seenCapped()
	{
		CancelledBuys b = new CancelledBuys(null);
		for (int i = 0; i < CancelledBuys.MAX_SEEN + 20; i++)
		{
			b.update(Collections.singletonList(pos("p" + i, "pending", 10, 0)), buying(), true, i);
		}
		List<String> saved = b.takeSeenToSave();
		assertEquals(CancelledBuys.MAX_SEEN, saved.size());
		assertEquals("newest kept", "p" + (CancelledBuys.MAX_SEEN + 19), saved.get(saved.size() - 1));
	}
}
