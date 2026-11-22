package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines log of the engine's top-priority suggestion, one
// line per time it changes - not raw per-tick noise. Keeps a local history
// of the suggestions shown, for the player's own records.
@Slf4j
public class DecisionLogger
{
	private static final String LOG_FILE = FlipprFiles.DECISIONS;

	@Inject
	private FlipprFiles files;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	private static class DecisionEntry
	{
		long timestamp;
		String suggestion;
	}

	public void log(String suggestion)
	{
		DecisionEntry entry = new DecisionEntry();
		entry.timestamp = System.currentTimeMillis();
		entry.suggestion = suggestion;
		try
		{
			files.appendLine(LOG_FILE, gson.toJson(entry));
		}
		catch (IOException e)
		{
			log.warn("Failed to write decision log to {}", LOG_FILE, e);
		}
		log.info("Decision: {}", suggestion);
	}
}
