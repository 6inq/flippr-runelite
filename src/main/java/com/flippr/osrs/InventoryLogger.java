package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines file of inventory snapshots, separate from
// flips.jsonl since this is periodic/event-driven context data, not one
// record per completed flip.
@Slf4j
public class InventoryLogger
{
	private static final Path LOG_DIR = FlipprFiles.DIR;
	private static final Path LOG_FILE = FlipprFiles.INVENTORY;
	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	public void log(InventorySnapshot snapshot)
	{
		try
		{
			Files.createDirectories(LOG_DIR);
			try (FileWriter writer = new FileWriter(LOG_FILE.toFile(), true))
			{
				writer.write(gson.toJson(snapshot));
				writer.write(System.lineSeparator());
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to write inventory snapshot to {}", LOG_FILE, e);
		}
	}
}
