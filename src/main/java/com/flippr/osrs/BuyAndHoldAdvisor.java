package com.flippr.osrs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

// Buy-and-hold: if historical intraday data shows an item's price reliably
// recovers later in the day/week, and selling right now would realize a
// loss vs. that expected recovery, hold instead of force-selling. This needs
// real historical depth, which the OSRS Wiki's /timeseries endpoint provides:
// it always returns exactly 365 buckets regardless of requested bucket size
// (confirmed live via curl), so 6h buckets cover ~91 real days / 13 weeks,
// not just a few days.
//
// Deliberately conservative rather than a full forecast: this doesn't
// predict WHEN or WHETHER an item recovers - it only answers "is the current
// price unusually low specifically for this exact day-of-week/time-of-day
// slot, compared to that same slot's own real recent history?" A real,
// backward-looking statistical comparison (median, not mean - resistant to
// one outlier week skewing the whole comparison), not a fabricated
// confidence score. Only ever consulted on the SELL-side stale-abandon path
// (DecisionEngine.computeSlotSuggestion) - never touches BUY-side
// abandonment, which is a completely different concern (failing to acquire a
// position, not failing to dispose of one already held).
@Slf4j
@Singleton
public class BuyAndHoldAdvisor
{
	// 6h buckets over the endpoint's fixed 365-bucket window is ~91 real
	// days / 13 weeks - enough real historical weeks that a single
	// (day-of-week, time-of-day) slot has a real several-sample basis for a
	// median, not just one or two data points.
	private static final String PATTERN_TIMESTEP = "6h";
	private static final int BUCKET_HOURS = 6;
	private static final int MIN_SAMPLES = 4;
	private static final ZoneId ZONE = ZoneId.of("America/New_York");

	public static class HoldSignal
	{
		final long historicalMedian;
		final long currentPrice;
		final double belowPercent;
		final int sampleCount;

		HoldSignal(long historicalMedian, long currentPrice, double belowPercent, int sampleCount)
		{
			this.historicalMedian = historicalMedian;
			this.currentPrice = currentPrice;
			this.belowPercent = belowPercent;
			this.sampleCount = sampleCount;
		}

		public long getHistoricalMedian()
		{
			return historicalMedian;
		}

		public double getBelowPercent()
		{
			return belowPercent;
		}

		public int getSampleCount()
		{
			return sampleCount;
		}
	}

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private FlipprConfig config;

	// Self-throttled by PriceFeed's own cache/TTL - safe (and intended) to
	// call unconditionally for every active sell slot on every tick, so the
	// 6h history is already warm by the time a sell might actually reach the
	// abandon threshold (which takes real minutes per staleFillMinutes),
	// rather than only starting the fetch at the exact moment it's needed
	// and racing the HTTP round trip.
	public void prefetch(int itemId)
	{
		priceFeed.maybeFetchTimeseries(itemId, PATTERN_TIMESTEP);
	}

	// Null if buy-and-hold is disabled, there's not enough real historical
	// data yet for this exact slot, or the current price isn't meaningfully
	// below that slot's historical median.
	public HoldSignal checkHoldSignal(int itemId)
	{
		if (!config.buyAndHoldEnabled())
		{
			return null;
		}

		Long currentPrice = priceFeed.getInstantSellPrice(itemId);
		if (currentPrice == null)
		{
			return null;
		}

		List<PriceFeed.TimeseriesPoint> points = priceFeed.getTimeseries(itemId, PATTERN_TIMESTEP);
		if (points.isEmpty())
		{
			return null;
		}

		ZonedDateTime now = ZonedDateTime.now(ZONE);
		int currentDayOfWeek = now.getDayOfWeek().getValue();
		int currentBucket = now.getHour() / BUCKET_HOURS;

		List<Long> samples = new ArrayList<>();
		for (PriceFeed.TimeseriesPoint point : points)
		{
			if (point.avgLowPrice == null)
			{
				continue;
			}
			ZonedDateTime pointTime = Instant.ofEpochSecond(point.timestamp).atZone(ZONE);
			if (pointTime.getDayOfWeek().getValue() == currentDayOfWeek && pointTime.getHour() / BUCKET_HOURS == currentBucket)
			{
				samples.add(point.avgLowPrice);
			}
		}

		if (samples.size() < MIN_SAMPLES)
		{
			return null;
		}

		long median = median(samples);
		if (median <= 0)
		{
			return null;
		}

		double belowPercent = (median - currentPrice) * 100.0 / median;
		double threshold = config.buyAndHoldThresholdPercent();
		if (belowPercent < threshold)
		{
			return null;
		}

		return new HoldSignal(median, currentPrice, belowPercent, samples.size());
	}

	private static long median(List<Long> values)
	{
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int mid = sorted.size() / 2;
		return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2 : sorted.get(mid);
	}
}
