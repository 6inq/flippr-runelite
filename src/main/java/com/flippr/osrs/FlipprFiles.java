package com.flippr.osrs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

// Every local file this plugin reads or writes lives in the plugin's own
// data folder, handed over by FlipprPlugin.startUp() before anything reads
// a file. Until then, or if the folder could not be opened, reads come back
// empty and writes are skipped, so the rest of the plugin keeps working
// without its file features.
@Slf4j
@Singleton
public class FlipprFiles
{
	public static final String FLIPS = "flips.jsonl";
	public static final String DECISIONS = "decisions.jsonl";
	public static final String INVENTORY = "inventory.jsonl";
	public static final String SESSIONS = "sessions.jsonl";
	public static final String KNOWN_ITEMS = "known-items.json";
	public static final String UPLOAD_QUEUE = "upload-queue.jsonl";

	private volatile Filepath root;

	public void setRoot(@Nullable Filepath root)
	{
		this.root = root;
	}

	public boolean isAvailable()
	{
		return root != null;
	}

	// The named file inside the data folder, or null while no folder is set.
	@Nullable
	public Filepath get(String name)
	{
		Filepath dir = root;
		return dir == null ? null : dir.join(name);
	}

	public List<String> readLines(String name) throws IOException
	{
		return readLines(get(name));
	}

	// The whole file as text, or null when it doesn't exist.
	@Nullable
	public String readString(String name) throws IOException
	{
		return readString(get(name));
	}

	public void writeString(String name, String text) throws IOException
	{
		Filepath file = get(name);
		if (file != null)
		{
			writeString(file, text);
		}
	}

	// Appends one line (plus the platform line separator).
	public void appendLine(String name, String line) throws IOException
	{
		append(name, line + System.lineSeparator());
	}

	public void append(String name, String text) throws IOException
	{
		Filepath file = get(name);
		if (file != null)
		{
			append(file, text);
		}
	}

	// Every line of file, or an empty list when file is null or missing.
	public static List<String> readLines(@Nullable Filepath file) throws IOException
	{
		if (file == null || !file.exists())
		{
			return Collections.emptyList();
		}
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = file.openBufferedReader())
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				lines.add(line);
			}
		}
		return lines;
	}

	@Nullable
	public static String readString(@Nullable Filepath file) throws IOException
	{
		if (file == null || !file.exists())
		{
			return null;
		}
		StringBuilder text = new StringBuilder();
		try (BufferedReader reader = file.openBufferedReader())
		{
			char[] buffer = new char[8192];
			int n;
			while ((n = reader.read(buffer)) != -1)
			{
				text.append(buffer, 0, n);
			}
		}
		return text.toString();
	}

	// Replaces file's contents, creating its folder if needed.
	public static void writeString(Filepath file, String text) throws IOException
	{
		createParent(file);
		file.write(text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	// Adds text to the end of file, creating the file and its folder if needed.
	public static void append(Filepath file, String text) throws IOException
	{
		createParent(file);
		try (Writer writer = file.openWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			writer.write(text);
		}
	}

	// A file picked in a file dialog is its own root, so it has no parent to
	// create; its folder already exists.
	private static void createParent(Filepath file) throws IOException
	{
		if (!file.isRoot())
		{
			file.getParent().createDirectories();
		}
	}
}
