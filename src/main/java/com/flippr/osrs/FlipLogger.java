package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines file, deliberately not a database - JSONL is
// trivially readable by any tool the player wants to use on their own
// records, without this plugin also carrying a JDBC driver it doesn't
// otherwise need. Lives in its own flippr folder
// under RuneLite's directory (~/.runelite/flippr) so it's clearly Flippr's
// data, not RuneLite's.
@Slf4j
@Singleton
public class FlipLogger
{
	private static final Path LOG_DIR = FlipprFiles.DIR;
	private static final Path LOG_FILE = FlipprFiles.FLIPS;
	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	// Every recorded flip is also queued for the website Tracker (only
	// while paired with sync on - see TradeUploader).
	@Inject
	private TradeUploader tradeUploader;

	public void log(CompletedFlip flip)
	{
		try
		{
			Files.createDirectories(LOG_DIR);
			try (FileWriter writer = new FileWriter(LOG_FILE.toFile(), true))
			{
				writer.write(gson.toJson(flip));
				writer.write(System.lineSeparator());
			}
			log.info(
				"Flip recorded: {}x {} - profit {} (buy {} / sell {} / tax {}) - total {}s, buy fill {}s, sell fill {}s",
				flip.quantity, flip.itemName, flip.profit, flip.avgBuyPrice, flip.avgSellPrice, flip.tax,
				flip.totalDurationMs / 1000, flip.buyFillTimeMs / 1000, flip.sellFillTimeMs / 1000
			);
		}
		catch (IOException e)
		{
			log.warn("Failed to write flip log to {}", LOG_FILE, e);
		}
		tradeUploader.enqueue(flip);
	}
}
