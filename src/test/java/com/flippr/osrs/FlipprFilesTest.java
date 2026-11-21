package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// The one-time ~/.flippr-osrs -> ~/.runelite/flippr copy, against temp
// folders standing in for both.
public class FlipprFilesTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static void write(Path file, String text) throws Exception
	{
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(Path file) throws Exception
	{
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	@Test
	public void copiesFilesMissingFromTarget() throws Exception
	{
		Path source = folder.newFolder("old").toPath();
		Path target = folder.getRoot().toPath().resolve("new");
		write(source.resolve("flips.jsonl"), "flip");
		write(source.resolve("sessions.jsonl"), "session");

		assertEquals(2, FlipprFiles.migrate(source, target));
		assertEquals("flip", read(target.resolve("flips.jsonl")));
		assertEquals("session", read(target.resolve("sessions.jsonl")));
		// Source is left untouched.
		assertEquals("flip", read(source.resolve("flips.jsonl")));
	}

	@Test
	public void neverOverwritesExistingTargetFile() throws Exception
	{
		Path source = folder.newFolder("old").toPath();
		Path target = folder.newFolder("new").toPath();
		write(source.resolve("flips.jsonl"), "old flips");
		write(source.resolve("inventory.jsonl"), "old inventory");
		write(target.resolve("flips.jsonl"), "new flips");

		assertEquals(1, FlipprFiles.migrate(source, target));
		assertEquals("new flips", read(target.resolve("flips.jsonl")));
		assertEquals("old inventory", read(target.resolve("inventory.jsonl")));
	}

	@Test
	public void missingSourceIsNoOp() throws Exception
	{
		Path source = folder.getRoot().toPath().resolve("does-not-exist");
		Path target = folder.getRoot().toPath().resolve("new");

		assertEquals(0, FlipprFiles.migrate(source, target));
		assertFalse(Files.exists(target));
	}

	@Test
	public void runningTwiceCopiesNothingNew() throws Exception
	{
		Path source = folder.newFolder("old").toPath();
		Path target = folder.getRoot().toPath().resolve("new");
		write(source.resolve("known-items.json"), "[1,2]");

		assertEquals(1, FlipprFiles.migrate(source, target));
		assertEquals(0, FlipprFiles.migrate(source, target));
		assertTrue(Files.exists(target.resolve("known-items.json")));
	}
}
