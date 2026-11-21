package com.flippr.osrs;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

// Requirement: the stats dropdown's time windows resolve to the right
// since-epoch lower bound for a fixed "now", and SessionStatsAggregator sums
// only flips at/after that bound for the selected account (or all accounts).
public class SessionStatsTest
{
	private static final long NOW = 1_800_000_000_000L;
	private static final long HOUR = 3_600_000L;

	// Req: each relative window subtracts its exact length from now.
	@Test
	public void relativeWindowsSubtractFromNow()
	{
		assertEquals(NOW - HOUR, SessionTimeRange.HOUR_1.sinceEpochMs(NOW, null));
		assertEquals(NOW - 4 * HOUR, SessionTimeRange.HOUR_4.sinceEpochMs(NOW, null));
		assertEquals(NOW - 12 * HOUR, SessionTimeRange.HOUR_12.sinceEpochMs(NOW, null));
		assertEquals(NOW - 24 * HOUR, SessionTimeRange.DAY_1.sinceEpochMs(NOW, null));
		assertEquals(NOW - 7 * 24 * HOUR, SessionTimeRange.WEEK_1.sinceEpochMs(NOW, null));
		assertEquals(NOW - 30 * 24 * HOUR, SessionTimeRange.MONTH_1.sinceEpochMs(NOW, null));
	}

	// Req: All time and Session have no lower bound (0).
	@Test
	public void unboundedWindowsAreZero()
	{
		assertEquals(0, SessionTimeRange.ALL_TIME.sinceEpochMs(NOW, null));
		assertEquals(0, SessionTimeRange.SESSION.sinceEpochMs(NOW, null));
	}

	// Req: picked start date = midnight America/New_York (EST and EDT).
	@Test
	public void pickedStartDateIsNewYorkMidnight()
	{
		// 2026-01-15 00:00 EST = 05:00Z
		assertEquals(1_768_453_200_000L, SessionTimeRange.PICK_START_DATE.sinceEpochMs(NOW, LocalDate.of(2026, 1, 15)));
		// 2026-07-01 00:00 EDT = 04:00Z
		assertEquals(1_782_878_400_000L, SessionTimeRange.PICK_START_DATE.sinceEpochMs(NOW, LocalDate.of(2026, 7, 1)));
	}

	// Req (edge): picked-date option with no date chosen -> no lower bound.
	@Test
	public void pickedStartDateWithoutDateIsZero()
	{
		assertEquals(0, SessionTimeRange.PICK_START_DATE.sinceEpochMs(NOW, null));
	}

	private static String flipLine(long timestamp, String rsn, long profit)
	{
		CompletedFlip flip = new CompletedFlip();
		flip.timestamp = timestamp;
		flip.rsn = rsn;
		flip.profit = profit;
		return new Gson().toJson(flip);
	}

	private final SessionStatsAggregator aggregator = newAggregator();

	// The plugin gets RuneLite's shared Gson injected; tests supply one.
	private static SessionStatsAggregator newAggregator()
	{
		SessionStatsAggregator aggregator = new SessionStatsAggregator();
		aggregator.gson = new Gson();
		return aggregator;
	}

	// Req: only flips inside the window count; bound is inclusive.
	@Test
	public void aggregateRespectsWindow()
	{
		long since = SessionTimeRange.HOUR_1.sinceEpochMs(NOW, null);
		List<String> lines = Arrays.asList(
			flipLine(NOW - 2 * HOUR, "Alice", 1_000),
			flipLine(since, "Alice", 200),
			flipLine(NOW - HOUR / 2, "Alice", 300));

		SessionStatsAggregator.Stats stats = aggregator.aggregate(lines, since, null);
		assertEquals(2, stats.getFlipCount());
		assertEquals(500, stats.getProfit());
		assertEquals(since, stats.getEarliestTimestamp());
	}

	// Req: account filter keeps only that rsn; null filter = all accounts.
	@Test
	public void aggregateFiltersByAccount()
	{
		List<String> lines = Arrays.asList(
			flipLine(NOW - 3, "Alice", 100),
			flipLine(NOW - 2, "Bob", -40),
			flipLine(NOW - 1, null, 7));

		SessionStatsAggregator.Stats alice = aggregator.aggregate(lines, 0, "Alice");
		assertEquals(1, alice.getFlipCount());
		assertEquals(100, alice.getProfit());

		SessionStatsAggregator.Stats all = aggregator.aggregate(lines, 0, null);
		assertEquals(3, all.getFlipCount());
		assertEquals(67, all.getProfit());
		assertEquals(NOW - 3, all.getEarliestTimestamp());
	}

	// Req (error handling): blank and malformed lines are skipped, not fatal.
	@Test
	public void aggregateSkipsBlankAndMalformedLines()
	{
		List<String> lines = Arrays.asList("", "   ", "{not json", flipLine(NOW, "Alice", 50));
		SessionStatsAggregator.Stats stats = aggregator.aggregate(lines, 0, null);
		assertEquals(1, stats.getFlipCount());
		assertEquals(50, stats.getProfit());
	}

	// Req (edge): no flips -> zeros.
	@Test
	public void aggregateEmptyIsZero()
	{
		SessionStatsAggregator.Stats stats = aggregator.aggregate(Collections.emptyList(), 0, null);
		assertEquals(0, stats.getFlipCount());
		assertEquals(0, stats.getProfit());
		assertEquals(0, stats.getEarliestTimestamp());
	}
}
