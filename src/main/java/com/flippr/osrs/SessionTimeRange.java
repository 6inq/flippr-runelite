package com.flippr.osrs;

import java.time.LocalDate;
import java.time.ZoneId;

// The session/time-range picker's option set (-1h/-4h/-12h/-1d/-1w/-1m/
// Session/All time/Pick start date). Pure view state, not a behavioral
// @ConfigItem - this only changes what FlipprPanel's stats block is
// currently showing, it doesn't change any real engine behavior, so it's
// not persisted the way actual settings are.
//
// SESSION is the only option backed by FlipTracker's own live in-memory
// counters (identical to this plugin's existing default behavior, zero
// regression) - every other option triggers a real file-based aggregation
// over flips.jsonl via SessionStatsAggregator, since they all need to look
// further back than "since the plugin last started."
public enum SessionTimeRange
{
	HOUR_1,
	HOUR_4,
	HOUR_12,
	DAY_1,
	WEEK_1,
	MONTH_1,
	SESSION,
	ALL_TIME,
	PICK_START_DATE;

	private static final long HOUR_MS = 3_600_000L;

	// Lower bound (epoch ms) for SessionStatsAggregator.aggregate - 0 means
	// no lower bound. Pure (clock passed in) so it's unit-testable; moved
	// verbatim from FlipprPanel.computeSinceEpochMs.
	long sinceEpochMs(long nowMs, LocalDate customStartDate)
	{
		switch (this)
		{
			case HOUR_1:
				return nowMs - HOUR_MS;
			case HOUR_4:
				return nowMs - 4 * HOUR_MS;
			case HOUR_12:
				return nowMs - 12 * HOUR_MS;
			case DAY_1:
				return nowMs - 24 * HOUR_MS;
			case WEEK_1:
				return nowMs - 7L * 24 * HOUR_MS;
			case MONTH_1:
				return nowMs - 30L * 24 * HOUR_MS;
			case PICK_START_DATE:
				return customStartDate == null
					? 0
					: customStartDate.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
			case ALL_TIME:
			case SESSION:
			default:
				return 0;
		}
	}

	@Override
	public String toString()
	{
		switch (this)
		{
			case HOUR_1:
				return "-1h (Past Hour)";
			case HOUR_4:
				return "-4h (Past 4 Hours)";
			case HOUR_12:
				return "-12h (Past 12 Hours)";
			case DAY_1:
				return "-1d (Past Day)";
			case WEEK_1:
				return "-1w (Past Week)";
			case MONTH_1:
				return "-1m (Past Month)";
			case SESSION:
				return "Session";
			case ALL_TIME:
				return "All time";
			case PICK_START_DATE:
			default:
				return "Pick start date";
		}
	}
}
