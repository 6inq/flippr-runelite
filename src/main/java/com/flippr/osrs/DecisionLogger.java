package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines log of the engine's top-priority suggestion, one
// line per time it changes - not raw per-tick noise. Keeps a local history
// of the suggestions shown, for the player's own records.
@Slf4j
public class DecisionLogger
{
	private static final Path LOG_DIR = FlipprFiles.DIR;
	private static final Path LOG_FILE = FlipprFiles.DECISIONS;
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
			Files.createDirectories(LOG_DIR);
			try (FileWriter writer = new FileWriter(LOG_FILE.toFile(), true))
			{
				writer.write(gson.toJson(entry));
				writer.write(System.lineSeparator());
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to write decision log to {}", LOG_FILE, e);
		}
		log.info("Decision: {}", suggestion);
	}
}
