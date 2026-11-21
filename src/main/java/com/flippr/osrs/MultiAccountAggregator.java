package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;

// Multi-account session aggregation. Each
// RuneLite client process is its own separate plugin instance/JVM, one
// account per client - FlipTracker only ever sees the one account its own
// JVM is logged in as. But every plugin instance on this machine appends to
// the SAME shared ~/.runelite/flippr/flips.jsonl (see FlipLogger's own header
// comment) - so a combined "how's everything doing today" view across
// accounts running alongside each other just means reading that shared file
// back, not building a cross-process message bus for something a shared
// file already solves. Requires CompletedFlip.rsn (new field, stamped by
// FlipTracker) to attribute each record to an account at all.
@Slf4j
@Singleton
public class MultiAccountAggregator
{
	public static class AccountSummary
	{
		final String rsn;
		final int flipCount;
		final long profit;

		AccountSummary(String rsn, int flipCount, long profit)
		{
			this.rsn = rsn;
			this.flipCount = flipCount;
			this.profit = profit;
		}

		public String getRsn()
		{
			return rsn;
		}

		public int getFlipCount()
		{
			return flipCount;
		}

		public long getProfit()
		{
			return profit;
		}
	}

	private static final Path LOG_FILE = FlipprFiles.FLIPS;
	// Matches FlipTracker's own convention for dayOfWeek/hourOfDay - "today"
	// means the same fixed zone throughout this plugin, not the JVM's
	// possibly-different default zone.
	private static final ZoneId FLIP_TIME_ZONE = ZoneId.of("America/New_York");
	// A full-file read-back is real I/O, unlike everything else on the tick
	// heartbeat - throttled well below the 0.6s tick rate since flips.jsonl
	// only ever gains a new line on a completed flip, not every tick.
	private static final long REFRESH_INTERVAL_MS = 15_000;

	@Inject
	private Client client;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;
	private volatile List<AccountSummary> lastSummaries = List.of();
	private long lastRefreshTime;

	public void onGameTick()
	{
		long now = System.currentTimeMillis();
		if (now - lastRefreshTime < REFRESH_INTERVAL_MS)
		{
			return;
		}
		lastRefreshTime = now;
		refresh();
	}

	public List<AccountSummary> getAccountSummaries()
	{
		return lastSummaries;
	}

	// The RSN this specific plugin instance's account is playing as right
	// now - real display name, null in the brief window before the player's
	// loaded into the world. Deliberately doesn't fall back to the
	// deprecated Client.getUsername() - see FlipTracker.getCurrentRsn()'s
	// comment for why. Used by FlipprPanel to highlight which aggregated row
	// is "you"; a null here just means no row highlights this cycle; it does
	// not affect the aggregation logic above, which uses each flip's own
	// already-recorded CompletedFlip.rsn.
	public String getCurrentRsn()
	{
		Player localPlayer = client.getLocalPlayer();
		return localPlayer == null ? null : localPlayer.getName();
	}

	private void refresh()
	{
		if (!Files.exists(LOG_FILE))
		{
			lastSummaries = List.of();
			return;
		}

		LocalDate today = LocalDate.now(FLIP_TIME_ZONE);
		Map<String, long[]> totals = new LinkedHashMap<>();

		try
		{
			List<String> lines = Files.readAllLines(LOG_FILE);
			for (String line : lines)
			{
				if (line.isBlank())
				{
					continue;
				}
				CompletedFlip flip = gson.fromJson(line, CompletedFlip.class);
				if (flip == null || flip.rsn == null)
				{
					// Records written before this field existed, or a flip
					// finalized in the split second before FlipTracker could
					// resolve any RSN at all - neither is attributable, so
					// they're excluded rather than lumped under a fake label.
					continue;
				}
				LocalDate flipDate = Instant.ofEpochMilli(flip.timestamp).atZone(FLIP_TIME_ZONE).toLocalDate();
				if (!flipDate.equals(today))
				{
					continue;
				}
				long[] agg = totals.computeIfAbsent(flip.rsn, k -> new long[2]);
				agg[0] += 1;
				agg[1] += flip.profit;
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to read {} for multi-account aggregation", LOG_FILE, e);
			return;
		}
		catch (Exception e)
		{
			// A malformed/partial line (e.g. this file getting read the exact
			// instant another instance is mid-write to it) shouldn't take
			// down the whole read-back - just skip this cycle and retry on
			// the next one REFRESH_INTERVAL_MS later.
			log.warn("Failed to parse {} for multi-account aggregation", LOG_FILE, e);
			return;
		}

		List<AccountSummary> summaries = new ArrayList<>();
		for (Map.Entry<String, long[]> entry : totals.entrySet())
		{
			summaries.add(new AccountSummary(entry.getKey(), (int) entry.getValue()[0], entry.getValue()[1]));
		}
		summaries.sort(Comparator.comparingLong((AccountSummary s) -> s.profit).reversed());
		lastSummaries = summaries;
	}
}
