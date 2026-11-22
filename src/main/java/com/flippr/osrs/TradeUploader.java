package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

// Uploads this character's completed flips to the website's Tracker
// (POST /api/v1/osrs/trades). Each flip FlipLogger writes is also appended,
// as the same CompletedFlip JSON, to upload-queue.jsonl in the plugin's data folder; a
// background flush (every FLUSH_INTERVAL_MS, and NEW_FLIP_DELAY_MS after a
// new flip) sends up to BATCH_SIZE lines per request and removes them only
// after a 2xx, rewriting the queue atomically (temp file + move). Anything
// not sent stays queued across restarts. The server dedupes by
// externalId = "<timestamp>:<itemId>", so a
// resend can't create duplicates. Only queued while paired with sync on;
// "Upload history" queues past flips.jsonl lines for one RSN.
//
// File work runs on RuneLite's shared executor, network via OkHttp enqueue -
// never on the client thread.
@Slf4j
@Singleton
public class TradeUploader
{
	static final String PATH = "/api/v1/osrs/trades";
	static final int BATCH_SIZE = 200;
	static final long FLUSH_INTERVAL_MS = 60_000;
	static final long NEW_FLIP_DELAY_MS = 5_000;

	@Inject
	private WebsiteConnection website;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	FlipprFiles files;

	LongSupplier clock = System::currentTimeMillis;

	private final Object fileLock = new Object();
	private final Backoff backoff = new Backoff(30_000, 600_000);
	private volatile long nextFlushAt;
	private volatile boolean inFlight;
	private volatile boolean flushQueued;
	private volatile int queuedCount;
	private volatile long lastUploadAt;

	// Counts what's left over from an earlier session.
	public void init()
	{
		executor.execute(() -> queuedCount = readLines().size());
	}

	// Called by FlipLogger for every completed flip.
	public void enqueue(CompletedFlip flip)
	{
		if (!website.isSyncEnabled())
		{
			return;
		}
		String line = gson.toJson(flip);
		executor.execute(() ->
		{
			appendLines(Collections.singletonList(line));
			nextFlushAt = Math.min(nextFlushAt, clock.getAsLong() + NEW_FLIP_DELAY_MS);
		});
	}

	// "Upload history": queues every flips.jsonl line for this RSN (case-
	// insensitive, like the game). Lines without an rsn are skipped - they
	// can't be shown to belong to this character. Blocking file I/O: run
	// off the client thread and the EDT. Returns how many were queued.
	public int queueHistory(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return 0;
		}
		List<String> matching = new ArrayList<>();
		try
		{
			for (String line : files.readLines(FlipprFiles.FLIPS))
			{
				JsonObject flip = parseObject(line);
				if (flip != null && flip.has("rsn") && flip.get("rsn").isJsonPrimitive()
					&& rsn.equalsIgnoreCase(flip.get("rsn").getAsString()))
				{
					matching.add(line.trim());
				}
			}
		}
		catch (IOException e)
		{
			log.warn("TradeUploader: could not read {}", FlipprFiles.FLIPS, e);
			return 0;
		}
		appendLines(matching);
		nextFlushAt = 0;
		return matching.size();
	}

	public void onGameTick()
	{
		long now = clock.getAsLong();
		if (inFlight || flushQueued || now < nextFlushAt || queuedCount == 0)
		{
			return;
		}
		if (!website.isSyncEnabled() || website.isInMaintenance() || backoff.isWaiting(now))
		{
			return;
		}
		nextFlushAt = now + FLUSH_INTERVAL_MS;
		flushQueued = true;
		executor.execute(this::flush);
	}

	// Sends the oldest batch; on success removes it and continues with the
	// next one until the queue is empty or a request fails.
	void flush()
	{
		flushQueued = false;
		if (inFlight || !website.isSyncEnabled() || website.isInMaintenance())
		{
			return;
		}

		List<String> lines = readLines();
		List<String> batch = new ArrayList<>();
		List<String> malformed = new ArrayList<>();
		JsonArray body = new JsonArray();
		for (String line : lines)
		{
			JsonObject flip = parseObject(line);
			if (flip == null)
			{
				malformed.add(line);
			}
			else if (batch.size() < BATCH_SIZE)
			{
				batch.add(line);
				body.add(flip);
			}
		}
		if (!malformed.isEmpty())
		{
			// Can never be sent; flips.jsonl still has the original record.
			log.warn("TradeUploader: dropping {} unreadable queue line(s)", malformed.size());
			removeLines(malformed);
		}
		if (batch.isEmpty())
		{
			return;
		}

		inFlight = true;
		website.postJsonAsync(PATH, body.toString(), response -> onSent(batch, response));
	}

	private void onSent(List<String> batch, WebsiteConnection.ApiResponse response)
	{
		inFlight = false;
		long now = clock.getAsLong();
		if (response.outcome == WebsiteConnection.Outcome.OK)
		{
			backoff.reset();
			lastUploadAt = now;
			removeLines(batch);
			logResult(batch.size(), response.body);
			if (queuedCount > 0)
			{
				flushQueued = true;
				executor.execute(this::flush);
			}
			return;
		}
		if (response.outcome == WebsiteConnection.Outcome.FAILED)
		{
			long delay = backoff.fail(now);
			nextFlushAt = now + delay;
			log.debug("TradeUploader: upload failed (HTTP {}), {} kept, retrying in {}s",
				response.code, batch.size(), delay / 1000);
		}
		// MAINTENANCE / UNAUTHORIZED: kept; WebsiteConnection holds the pause.
	}

	private void logResult(int sent, String body)
	{
		try
		{
			UploadResult result = gson.fromJson(body, UploadResult.class);
			if (result != null)
			{
				int rejected = result.rejected == null ? 0 : result.rejected.size();
				log.info("TradeUploader: sent {} flip(s) - {} new, {} already there, {} rejected",
					sent, result.inserted, result.duplicates, rejected);
				if (rejected > 0)
				{
					log.warn("TradeUploader: rejected by the server: {}", result.rejected);
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("TradeUploader: unreadable upload response");
		}
	}

	private JsonObject parseObject(String line)
	{
		if (line == null || line.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement element = gson.fromJson(line, JsonElement.class);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	void appendLines(List<String> lines)
	{
		if (lines.isEmpty())
		{
			return;
		}
		synchronized (fileLock)
		{
			try
			{
				StringBuilder text = new StringBuilder();
				for (String line : lines)
				{
					text.append(line).append('\n');
				}
				files.append(FlipprFiles.UPLOAD_QUEUE, text.toString());
				queuedCount = countLines();
			}
			catch (IOException e)
			{
				log.warn("TradeUploader: could not write {}", FlipprFiles.UPLOAD_QUEUE, e);
			}
		}
	}

	List<String> readLines()
	{
		synchronized (fileLock)
		{
			try
			{
				List<String> lines = new ArrayList<>(files.readLines(FlipprFiles.UPLOAD_QUEUE));
				lines.removeIf(String::isEmpty);
				return lines;
			}
			catch (IOException e)
			{
				log.warn("TradeUploader: could not read {}", FlipprFiles.UPLOAD_QUEUE, e);
				return new ArrayList<>();
			}
		}
	}

	// Removes one occurrence of each given line (lines appended meanwhile
	// stay), writing a temp file and moving it over the queue.
	private void removeLines(List<String> toRemove)
	{
		synchronized (fileLock)
		{
			Map<String, Integer> remove = new HashMap<>();
			for (String line : toRemove)
			{
				remove.merge(line, 1, Integer::sum);
			}
			List<String> kept = new ArrayList<>();
			for (String line : readLines())
			{
				Integer n = remove.get(line);
				if (n != null && n > 0)
				{
					remove.put(line, n - 1);
				}
				else
				{
					kept.add(line);
				}
			}
			Filepath queueFile = files.get(FlipprFiles.UPLOAD_QUEUE);
			Filepath temp = files.get(FlipprFiles.UPLOAD_QUEUE + ".tmp");
			if (queueFile == null || temp == null)
			{
				return;
			}
			try
			{
				StringBuilder text = new StringBuilder();
				for (String line : kept)
				{
					text.append(line).append('\n');
				}
				FlipprFiles.writeString(temp, text.toString());
				try
				{
					temp.moveTo(queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (AtomicMoveNotSupportedException e)
				{
					temp.moveTo(queueFile, StandardCopyOption.REPLACE_EXISTING);
				}
				queuedCount = kept.size();
			}
			catch (IOException e)
			{
				log.warn("TradeUploader: could not rewrite {}", FlipprFiles.UPLOAD_QUEUE, e);
			}
		}
	}

	private int countLines()
	{
		return readLines().size();
	}

	public int getQueuedCount()
	{
		return queuedCount;
	}

	public long getLastUploadAt()
	{
		return lastUploadAt;
	}

	public long getRetryRemainingMs()
	{
		return backoff.isFailing() ? backoff.remainingMs(clock.getAsLong()) : 0;
	}

	private static class UploadResult
	{
		int inserted;
		int duplicates;
		List<Object> rejected;
	}
}
