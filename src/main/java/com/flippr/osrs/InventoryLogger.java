package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines file of inventory snapshots, separate from
// flips.jsonl since this is periodic/event-driven context data, not one
// record per completed flip.
@Slf4j
public class InventoryLogger
{
	private static final String LOG_FILE = FlipprFiles.INVENTORY;

	@Inject
	private FlipprFiles files;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	public void log(InventorySnapshot snapshot)
	{
		try
		{
			files.appendLine(LOG_FILE, gson.toJson(snapshot));
		}
		catch (IOException e)
		{
			log.warn("Failed to write inventory snapshot to {}", LOG_FILE, e);
		}
	}
}
