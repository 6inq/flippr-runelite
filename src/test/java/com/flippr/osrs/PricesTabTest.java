package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

// Requirement: the Prices tab's numbers - margin after the OSRS GE tax
// (2% rounded down, nothing under 50gp, 5,000,000 cap per item, exempt
// items 0), item search ranking for the autocomplete, and 24h volume from
// the wiki timeseries.
public class PricesTabTest
{
	// Req: OSRS tax per item.
	@Test
	public void osrsTax()
	{
		assertEquals(0, GeTax.taxPerUnit("Abyssal whip", 49));
		assertEquals(1, GeTax.taxPerUnit("Abyssal whip", 50));
		assertEquals(2, GeTax.taxPerUnit("Abyssal whip", 149));
		assertEquals(20_000, GeTax.taxPerUnit("Abyssal whip", 1_000_000));
		assertEquals("5M cap", 5_000_000, GeTax.taxPerUnit("Twisted bow", 1_500_000_000L));
		assertEquals("exempt item", 0, GeTax.taxPerUnit("Old school bond", 10_000_000));
		assertEquals(20_000, GeTax.taxPerUnit(null, 1_000_000));
	}

	// Req: margin = instant buy - tax on it - instant sell; unknown -> null.
	@Test
	public void marginAfterTax()
	{
		assertEquals(Long.valueOf(1_030_000 - 20_600 - 1_000_000), GeTax.margin("Abyssal whip", 1_030_000L, 1_000_000L));
		assertEquals(Long.valueOf(45 - 40), GeTax.margin("Feather", 45L, 40L));
		assertNull(GeTax.margin("Abyssal whip", null, 1_000L));
		assertNull(GeTax.margin("Abyssal whip", 1_000L, 0L));
	}

	private static PriceFeed.MappingEntry item(int id, String name)
	{
		PriceFeed.MappingEntry e = new PriceFeed.MappingEntry();
		e.id = id;
		e.name = name;
		return e;
	}

	// Req: autocomplete - exact, then starts-with, then word-start, then contains; 2+ letters.
	@Test
	public void searchRanking()
	{
		List<PriceFeed.MappingEntry> rows = Arrays.asList(
			item(1, "Super dragon bones"), item(2, "Dragon bones"), item(3, "Babydragon bones"),
			item(4, "Dragon"), item(5, "Dragon bones (noted)"), item(6, "Coal"));
		List<PriceFeed.MappingEntry> hits = PriceFeed.searchItems(rows, "dragon", 10);
		List<Integer> ids = new ArrayList<>();
		for (PriceFeed.MappingEntry e : hits)
		{
			ids.add(e.id);
		}
		assertEquals(Arrays.asList(4, 2, 5, 1, 3), ids);
		assertEquals(2, PriceFeed.searchItems(rows, "dragon", 2).size());
		assertTrue(PriceFeed.searchItems(rows, "d", 10).isEmpty());
		assertEquals(Arrays.asList(2), idsOf(PriceFeed.searchItems(rows, "  DRAGON   BONES", 1)));
	}

	private static List<Integer> idsOf(List<PriceFeed.MappingEntry> list)
	{
		List<Integer> ids = new ArrayList<>();
		for (PriceFeed.MappingEntry e : list)
		{
			ids.add(e.id);
		}
		return ids;
	}

	// Req: volume = items traded (both sides) in the newest buckets.
	@Test
	public void volumeFromTimeseries()
	{
		List<PriceFeed.TimeseriesPoint> points = new ArrayList<>();
		for (int i = 0; i < 300; i++)
		{
			PriceFeed.TimeseriesPoint p = new PriceFeed.TimeseriesPoint();
			p.highPriceVolume = 2L;
			p.lowPriceVolume = i % 2 == 0 ? null : 1L;
			points.add(p);
		}
		assertEquals(Long.valueOf(288 * 2 + 144), PriceFeed.volume(points, 288));
		assertNull(PriceFeed.volume(new ArrayList<>(), 288));
	}
}
