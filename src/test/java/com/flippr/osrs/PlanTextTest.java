package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

// Requirement: the sidebar's Plan cards say what the website says -
// alert text (exact gp, "then update the offer
// in the GE" for OSRS), status labels, stop button labels, progress,
// allocation, summary strip and gp formats.
public class PlanTextTest
{
	private static ServerSuggestions.PlanPosition pos(String status, int target, int bought, int sold)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.id = "p";
		p.name = "Abyssal whip";
		p.status = status;
		p.targetQty = target;
		p.boughtQty = bought;
		p.soldQty = sold;
		p.buyPrice = 1_000_000;
		p.sellPrice = 1_100_000;
		return p;
	}

	// Req: alert text, every kind, exact gp + the OSRS hint.
	@Test
	public void alertTextMatchesSiteWithOsrsHint()
	{
		assertEquals("Set buy to 47,040,818 - then update the offer in the GE", PlanText.alertText("raise_buy", 47_040_818.0));
		assertEquals("Set buy to 1,000 - then update the offer in the GE", PlanText.alertText("lower_buy", 1_000.0));
		assertEquals("Set sell to 1,250,000 - then update the offer in the GE", PlanText.alertText("lower_sell", 1_250_000.0));
		assertEquals("Can raise sell to 99 - then update the offer in the GE", PlanText.alertText("raise_sell", 99.0));
		assertEquals("Market below break-even - set sell to 1,020,409 (lowest price without a loss), then update the offer in the GE",
			PlanText.alertText("below_break_even", 1_020_409.0));
		assertEquals("Market below break-even", PlanText.alertText("below_break_even", null));
		assertEquals("Set buy", PlanText.alertText("raise_buy", null));
		assertEquals(PlanText.NO_MARGIN_TEXT, PlanText.alertText("no_margin", 5.0));
		assertNull(PlanText.alertText(null, 5.0));
		assertNull(PlanText.alertText("something_new", 5.0));
	}

	// Req: gp formats - exact with commas; compact like the site's card.
	@Test
	public void gpFormats()
	{
		assertEquals("47,040,818", PlanText.exactGp(47_040_818));
		assertEquals("47M", PlanText.compactGp(47_040_818));
		assertEquals("312.4K", PlanText.compactGp(312_490));
		assertEquals("9,950", PlanText.compactGp(9_950));
		assertEquals("1.2B", PlanText.compactGp(1_299_000_000));
		assertEquals("-1.5M", PlanText.compactGp(-1_500_000));
		assertEquals("+450K gp", PlanText.signedGp(450_000));
		assertEquals("0 gp", PlanText.signedGp(0));
		assertEquals("25m", PlanText.hours(0.41));
		assertEquals("3.5h", PlanText.hours(3.5));
		assertEquals("2h", PlanText.hours(2.0));
		assertEquals("14h", PlanText.hours(13.6));
		assertEquals("-", PlanText.hours(null));
	}

	// Req: Drop & recycle with nothing bought; Stop & sell n while buying; none once the buy is done.
	@Test
	public void stopButtonLabels()
	{
		assertEquals("Drop & recycle", PlanText.stopLabel(pos("pending", 10, 0, 0)));
		assertEquals("Stop & sell 1,200", PlanText.stopLabel(pos("buying", 5000, 1200, 0)));
		assertEquals("Stop & sell 7", PlanText.stopLabel(pos("bought", 10, 7, 0)));
		assertNull(PlanText.stopLabel(pos("bought", 10, 10, 0)));
		assertNull(PlanText.stopLabel(pos("selling", 10, 10, 2)));
	}

	// Req: progress is bought of target, then sold of bought; labels and pills match the site.
	@Test
	public void progressAndStatus()
	{
		PlanText.Progress buying = PlanText.progress(pos("buying", 10, 4, 0));
		assertEquals(4, buying.done);
		assertEquals(10, buying.of);
		assertEquals("bought", buying.label);
		PlanText.Progress selling = PlanText.progress(pos("selling", 10, 8, 2));
		assertEquals(2, selling.done);
		assertEquals(8, selling.of);
		assertEquals("sold", selling.label);
		assertEquals("Selling", PlanText.statusLabel("selling"));
		assertEquals("Pending", PlanText.statusLabel("pending"));
	}

	// Req: net/unit after OSRS tax; allocation capped by max per slot; summary strip.
	@Test
	public void netAllocationAndSummary()
	{
		ServerSuggestions.PlanPosition p = pos("buying", 10, 4, 0);
		assertEquals(1_100_000 - 22_000 - 1_000_000, PlanText.netPerUnit(p));

		ServerSuggestions.PlanInfo plan = new ServerSuggestions.PlanInfo();
		plan.capital = 50_000_000;
		plan.slots = 8;
		assertEquals(10_000_000, PlanText.allocation(p, plan), 0);
		plan.maxPerSlotPct = 10.0;
		assertEquals(5_000_000, PlanText.allocation(p, plan), 0);

		ServerSuggestions.PlanPosition pending = pos("pending", 5, 0, 0);
		pending.expectedProfit = 100;
		pending.estFillHours = 2.0;
		p.expectedProfit = 400;
		p.realisedProfit = 50;
		p.estFillHours = 4.0;
		PlanText.Summary s = PlanText.summarize(plan, Arrays.asList(p, pending));
		assertEquals(15_000_000, s.deployed, 0);
		assertEquals(1, s.slotsFilled);
		assertEquals(8, s.slotsTotal);
		assertEquals(500, s.expectedProfit, 0);
		assertEquals(50, s.realisedProfit, 0);
		assertEquals(3.0, s.avgFillHours, 0);
		assertEquals(4.0, s.maxFillHours, 0);
	}

	// Req: "price updated from X" line, both legs.
	@Test
	public void priceUpdatedLine()
	{
		ServerSuggestions.PlanPosition p = pos("pending", 10, 0, 0);
		long now = 1_700_000_000_000L;
		assertNull(PlanText.priceUpdatedLine(p, now));
		p.previousBuyPrice = 990_000.0;
		p.buyPriceUpdatedAt = (double) (now - 4 * 60_000);
		p.previousSellPrice = 1_120_000.0;
		p.sellPriceUpdatedAt = (double) (now - 2 * 3_600_000);
		assertEquals("Buy updated from 990,000 (4m ago) · Sell updated from 1,120,000 (2h ago)", PlanText.priceUpdatedLine(p, now));
	}
}
