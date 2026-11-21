package com.flippr.osrs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

// Every local file this plugin reads or writes lives in one folder under
// RuneLite's own directory (~/.runelite/flippr) - the single place every
// logger/reader points at.
@Slf4j
public final class FlipprFiles
{
	public static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("flippr");

	public static final Path FLIPS = DIR.resolve("flips.jsonl");
	public static final Path DECISIONS = DIR.resolve("decisions.jsonl");
	public static final Path INVENTORY = DIR.resolve("inventory.jsonl");
	public static final Path SESSIONS = DIR.resolve("sessions.jsonl");
	public static final Path KNOWN_ITEMS = DIR.resolve("known-items.json");
	public static final Path UPLOAD_QUEUE = DIR.resolve("upload-queue.jsonl");

	// Where older versions of the plugin kept the same files.
	static final Path LEGACY_DIR = Paths.get(System.getProperty("user.home"), ".flippr-osrs");

	private FlipprFiles()
	{
	}

	// One-time move to the new folder: copies each file from the old
	// ~/.flippr-osrs folder that doesn't exist here yet. Never deletes or
	// overwrites anything, and never throws - an IO problem is logged and
	// the plugin starts regardless.
	public static void migrateLegacyFiles()
	{
		migrate(LEGACY_DIR, DIR);
	}

	// Returns how many files were copied.
	static int migrate(Path source, Path target)
	{
		if (!Files.isDirectory(source))
		{
			return 0;
		}

		int copied = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(source, Files::isRegularFile))
		{
			for (Path file : files)
			{
				Path dest = target.resolve(file.getFileName().toString());
				if (Files.exists(dest))
				{
					continue;
				}
				try
				{
					Files.createDirectories(target);
					Files.copy(file, dest);
					copied++;
					log.info("Flippr: copied {} to {}", file, dest);
				}
				catch (IOException e)
				{
					log.warn("Flippr: could not copy {} to {}", file, dest, e);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Flippr: could not migrate files from {}", source, e);
		}
		return copied;
	}
}
