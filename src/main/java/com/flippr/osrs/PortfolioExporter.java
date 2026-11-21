package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;

// One character's full exportable state, scoped by rsn as requested -
// portfolio (open positions), trade history, and session history - to a
// single human-readable JSON file the player can save/load by hand. Real
// bedrock constraint on the portfolio half: FlipTracker's open positions
// only ever exist as live in-memory state for whichever account THIS
// plugin instance is currently running as (see FlipTracker's own header
// comment) - there is no durable record of another account's open
// positions anywhere on disk to export, so openPositions is only ever
// populated when the requested rsn matches the CURRENTLY logged-in
// character. Trade/session history has no such limit: flips.jsonl/
// sessions.jsonl are shared, durable, and already rsn-stamped per record
// (see FlipLogger/SessionLogger), so any character's history that ever
// ran on this machine can be exported regardless of who's logged in now.
@Slf4j
@Singleton
public class PortfolioExporter
{
	private static final Path FLIPS_FILE = FlipprFiles.FLIPS;
	private static final Path SESSIONS_FILE = FlipprFiles.SESSIONS;

	// A plain DTO rather than reusing OpenPositionEntry directly - that
	// class's fields are final with no no-arg constructor, which forces
	// Gson onto its Unsafe-allocator fallback path to deserialize; a
	// dedicated mutable record avoids relying on that for a file format
	// meant to be stable and hand-inspectable.
	private static class PositionRecord
	{
		int itemId;
		String itemName;
		long quantity;
		long avgBuyPrice;
		long firstBuyTime;
	}

	private static class ExportBundle
	{
		String rsn;
		long exportedAt;
		List<PositionRecord> openPositions = new ArrayList<>();
		List<CompletedFlip> flips = new ArrayList<>();
		List<SessionSummary> sessions = new ArrayList<>();
	}

	public static class ImportResult
	{
		public final String rsn;
		public final int flipsAdded;
		public final int sessionsAdded;
		public final int positionsRestored;

		ImportResult(String rsn, int flipsAdded, int sessionsAdded, int positionsRestored)
		{
			this.rsn = rsn;
			this.flipsAdded = flipsAdded;
			this.sessionsAdded = sessionsAdded;
			this.positionsRestored = positionsRestored;
		}
	}

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private Client client;

	// RuneLite's shared Gson; exports are pretty-printed via a derived copy.
	@Inject
	private Gson gson;

	// Same "own private lookup, don't share a getter across classes for
	// this" pattern SessionStatsAggregator/SessionHistoryReader already
	// established - every class that needs the current RSN just reads it
	// from Client itself.
	private String getCurrentRsn()
	{
		Player localPlayer = client.getLocalPlayer();
		return localPlayer == null ? null : localPlayer.getName();
	}

	// Writes one character's full export (portfolio if it's the currently
	// logged-in character, plus every real flip/session record on file for
	// that rsn) to destination as pretty-printed JSON - a one-off save
	// file, not an append-only stream, so there's no reason to keep it
	// JSONL-compact like flips.jsonl itself.
	public void export(String rsn, Path destination) throws IOException
	{
		ExportBundle bundle = new ExportBundle();
		bundle.rsn = rsn;
		bundle.exportedAt = System.currentTimeMillis();

		if (rsn != null && rsn.equals(getCurrentRsn()))
		{
			for (OpenPositionEntry entry : flipTracker.getOpenPositions())
			{
				PositionRecord record = new PositionRecord();
				record.itemId = entry.getItemId();
				record.itemName = entry.getItemName();
				record.quantity = entry.getQuantity();
				record.avgBuyPrice = entry.getAvgBuyPrice();
				record.firstBuyTime = entry.getFirstBuyTime();
				bundle.openPositions.add(record);
			}
		}

		bundle.flips = readFiltered(FLIPS_FILE, CompletedFlip.class, flip -> rsn.equals(flip.rsn));
		bundle.sessions = readFiltered(SESSIONS_FILE, SessionSummary.class, session -> rsn.equals(session.rsn));

		Path parent = destination.toAbsolutePath().getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}
		Files.writeString(destination, gson.newBuilder().setPrettyPrinting().create().toJson(bundle));
	}

	// Merges an exported bundle back in. Flips/sessions are appended to the
	// shared JSONL files, skipping any record that's already present
	// (matched by rsn+timestamp+itemId for a flip, rsn+startTime for a
	// session - the natural key for each, since nothing else uniquely
	// identifies one) so re-importing the same file twice is a harmless
	// no-op rather than a duplicate flood. Open positions are only ever
	// restored into FlipTracker's own live state, and only when the file's
	// rsn matches the CURRENTLY logged-in character - restoring another
	// account's stock into this one's live tracker would silently corrupt
	// real cost-basis math for whoever actually happens to be logged in.
	public ImportResult importFrom(Path source) throws IOException
	{
		String json = Files.readString(source);
		ExportBundle bundle = gson.fromJson(json, ExportBundle.class);
		if (bundle == null)
		{
			throw new IOException("Not a valid Flippr export file");
		}

		int flipsAdded = appendMissing(FLIPS_FILE, CompletedFlip.class, bundle.flips,
			(existing, incoming) -> existing.timestamp == incoming.timestamp
				&& existing.itemId == incoming.itemId
				&& Objects.equals(existing.rsn, incoming.rsn));

		int sessionsAdded = appendMissing(SESSIONS_FILE, SessionSummary.class, bundle.sessions,
			(existing, incoming) -> existing.startTime == incoming.startTime
				&& Objects.equals(existing.rsn, incoming.rsn));

		int positionsRestored = 0;
		String currentRsn = getCurrentRsn();
		if (bundle.openPositions != null && !bundle.openPositions.isEmpty()
			&& bundle.rsn != null && bundle.rsn.equals(currentRsn))
		{
			for (PositionRecord record : bundle.openPositions)
			{
				flipTracker.restoreOpenPosition(record.itemId, record.itemName, record.quantity,
					record.avgBuyPrice, record.firstBuyTime);
				positionsRestored++;
			}
		}

		return new ImportResult(bundle.rsn, flipsAdded, sessionsAdded, positionsRestored);
	}

	private <T> List<T> readFiltered(Path file, Class<T> type, java.util.function.Predicate<T> filter) throws IOException
	{
		List<T> results = new ArrayList<>();
		if (!Files.exists(file))
		{
			return results;
		}
		for (String line : Files.readAllLines(file))
		{
			if (line.isBlank())
			{
				continue;
			}
			T record;
			try
			{
				record = gson.fromJson(line, type);
			}
			catch (Exception e)
			{
				log.warn("Skipping malformed line in {}", file, e);
				continue;
			}
			if (record != null && filter.test(record))
			{
				results.add(record);
			}
		}
		return results;
	}

	// Appends every incoming record that doesn't already match an existing
	// one (by matchesKey) to file, returning how many were actually new.
	// Reads the whole existing file to dedup - the same "personal-machine
	// scale, not big data" assumption SessionStatsAggregator already makes
	// scanning the same files on every dropdown change.
	private <T> int appendMissing(Path file, Class<T> type, List<T> incomingRecords, BiPredicate<T, T> matchesKey) throws IOException
	{
		if (incomingRecords == null || incomingRecords.isEmpty())
		{
			return 0;
		}

		List<T> existing = readFiltered(file, type, ignored -> true);
		List<T> toAppend = new ArrayList<>();
		for (T incoming : incomingRecords)
		{
			boolean alreadyPresent = existing.stream().anyMatch(e -> matchesKey.test(e, incoming));
			if (!alreadyPresent)
			{
				toAppend.add(incoming);
			}
		}

		if (toAppend.isEmpty())
		{
			return 0;
		}

		Files.createDirectories(file.getParent());
		StringBuilder builder = new StringBuilder();
		for (T record : toAppend)
		{
			builder.append(gson.toJson(record)).append(System.lineSeparator());
		}
		Files.writeString(file, builder.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		return toAppend.size();
	}
}
