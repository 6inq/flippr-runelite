package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;

// Real, file-based aggregation over the shared flips.jsonl
// for FlipprPanel's session time-range/account dropdowns - everything
// except the "Session" time-range option (which stays on FlipTracker's own
// live in-memory counters, zero regression from existing behavior) needs to
// look further back than "since this plugin instance last started," which
// only a real read-back of the durable file can answer.
@Slf4j
@Singleton
public class SessionStatsAggregator
{
	private static final String LOG_FILE = FlipprFiles.FLIPS;

	public static class Stats
	{
		final int flipCount;
		final long profit;
		// The oldest matching flip's own timestamp - used to show a real
		// elapsed span for "All time" rather than an arbitrary one, since
		// there's no fixed start for that range the way -1h/-1d/etc have.
		final long earliestTimestamp;

		Stats(int flipCount, long profit, long earliestTimestamp)
		{
			this.flipCount = flipCount;
			this.profit = profit;
			this.earliestTimestamp = earliestTimestamp;
		}

		public int getFlipCount()
		{
			return flipCount;
		}

		public long getProfit()
		{
			return profit;
		}

		public long getEarliestTimestamp()
		{
			return earliestTimestamp;
		}
	}

	@Inject
	private Client client;

	@Inject
	private FlipprFiles files;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	// Real flip count/profit for every record with timestamp >= sinceEpochMs
	// (pass 0 for no lower bound - "All time") whose rsn matches rsnFilter,
	// or every account's if rsnFilter is null ("All accounts").
	public Stats aggregate(long sinceEpochMs, String rsnFilter)
	{
		List<String> lines;
		try
		{
			lines = files.readLines(LOG_FILE);
		}
		catch (IOException e)
		{
			log.warn("Failed to read {} for session stats", LOG_FILE, e);
			return new Stats(0, 0, 0);
		}
		return aggregate(lines, sinceEpochMs, rsnFilter);
	}

	// The pure filtering/summing half of aggregate() - package-visible so it
	// can be unit-tested against in-memory JSONL lines.
	Stats aggregate(List<String> lines, long sinceEpochMs, String rsnFilter)
	{
		int flipCount = 0;
		long profit = 0;
		long earliest = 0;
		for (String line : lines)
		{
			if (line.isBlank())
			{
				continue;
			}
			CompletedFlip flip;
			try
			{
				flip = gson.fromJson(line, CompletedFlip.class);
			}
			catch (Exception e)
			{
				continue;
			}
			if (flip == null || flip.timestamp < sinceEpochMs)
			{
				continue;
			}
			if (rsnFilter != null && !rsnFilter.equals(flip.rsn))
			{
				continue;
			}
			flipCount++;
			profit += flip.profit;
			if (earliest == 0 || flip.timestamp < earliest)
			{
				earliest = flip.timestamp;
			}
		}
		return new Stats(flipCount, profit, earliest);
	}

	// Every real account name that's ever logged a flip on this machine
	// (flips.jsonl is shared across every plugin instance - see
	// FlipLogger's own header comment), current account first if present,
	// for the Accounts dropdown - never a guessed/hardcoded list.
	public List<String> getKnownAccounts()
	{
		Set<String> accounts = new LinkedHashSet<>();
		String currentRsn = getCurrentRsn();
		if (currentRsn != null)
		{
			accounts.add(currentRsn);
		}

		try
		{
			List<String> lines = files.readLines(LOG_FILE);
			for (String line : lines)
			{
				if (line.isBlank())
				{
					continue;
				}
				try
				{
					CompletedFlip flip = gson.fromJson(line, CompletedFlip.class);
					if (flip != null && flip.rsn != null)
					{
						accounts.add(flip.rsn);
					}
				}
				catch (Exception e)
				{
					// Skip a malformed line rather than aborting the
					// whole scan over it.
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to read {} for known accounts", LOG_FILE, e);
		}

		return List.copyOf(accounts);
	}

	public String getCurrentRsn()
	{
		Player localPlayer = client.getLocalPlayer();
		return localPlayer == null ? null : localPlayer.getName();
	}
}
