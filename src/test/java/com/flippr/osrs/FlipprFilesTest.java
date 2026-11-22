package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// Requirement: every file goes through the data folder handed to
// FlipprFiles. Before a folder is set (or when it could not be opened)
// reads are empty and writes are skipped; once set, lines append, whole
// files replace, and a nested folder is created on first write. A temp
// folder stands in for the plugin's data folder.
public class FlipprFilesTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private FlipprFiles files;
	private Path root;

	@Before
	public void setUp()
	{
		files = new FlipprFiles();
		root = folder.getRoot().toPath();
	}

	private void useFolder()
	{
		files.setRoot(Filepath.Unchecked.getRooted(root));
	}

	private String read(String name) throws Exception
	{
		return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
	}

	// Req: no folder -> empty reads, skipped writes, nothing on disk.
	@Test
	public void withoutAFolderNothingIsReadOrWritten() throws Exception
	{
		assertFalse(files.isAvailable());
		assertNull(files.get(FlipprFiles.FLIPS));
		assertTrue(files.readLines(FlipprFiles.FLIPS).isEmpty());
		assertNull(files.readString(FlipprFiles.KNOWN_ITEMS));

		files.appendLine(FlipprFiles.FLIPS, "{}");
		files.writeString(FlipprFiles.KNOWN_ITEMS, "[]");
		assertFalse(Files.exists(root.resolve(FlipprFiles.FLIPS)));
		assertFalse(Files.exists(root.resolve(FlipprFiles.KNOWN_ITEMS)));
	}

	// Req: missing files read as empty / null.
	@Test
	public void missingFilesReadEmpty() throws Exception
	{
		useFolder();
		assertTrue(files.isAvailable());
		assertTrue(files.readLines(FlipprFiles.SESSIONS).isEmpty());
		assertNull(files.readString(FlipprFiles.KNOWN_ITEMS));
	}

	// Req: appendLine adds one line per call and keeps what was there.
	@Test
	public void appendKeepsEarlierLines() throws Exception
	{
		useFolder();
		files.appendLine(FlipprFiles.FLIPS, "{\"a\":1}");
		files.appendLine(FlipprFiles.FLIPS, "{\"b\":2}");
		files.append(FlipprFiles.FLIPS, "{\"c\":3}\n");

		assertEquals(Arrays.asList("{\"a\":1}", "{\"b\":2}", "{\"c\":3}"), files.readLines(FlipprFiles.FLIPS));
		assertTrue(read(FlipprFiles.FLIPS).startsWith("{\"a\":1}" + System.lineSeparator()));
	}

	// Req: writeString replaces the whole file (a shorter write leaves no
	// trailing bytes behind).
	@Test
	public void writeStringReplaces() throws Exception
	{
		useFolder();
		files.writeString(FlipprFiles.KNOWN_ITEMS, "[1,2,3,4,5]");
		files.writeString(FlipprFiles.KNOWN_ITEMS, "[9]");
		assertEquals("[9]", files.readString(FlipprFiles.KNOWN_ITEMS));
		assertEquals("[9]", read(FlipprFiles.KNOWN_ITEMS));
	}

	// Req: non-ASCII text round-trips.
	@Test
	public void textRoundTrips() throws Exception
	{
		useFolder();
		files.writeString(FlipprFiles.KNOWN_ITEMS, "Ahrim's · ✓");
		assertEquals("Ahrim's · ✓", files.readString(FlipprFiles.KNOWN_ITEMS));
	}

	// Req: a folder that doesn't exist yet is created on the first write.
	@Test
	public void createsTheFolderOnFirstWrite() throws Exception
	{
		root = root.resolve("nested").resolve("flippr");
		useFolder();
		files.appendLine(FlipprFiles.SESSIONS, "{}");
		assertEquals(Arrays.asList("{}"), files.readLines(FlipprFiles.SESSIONS));
	}

	// Req: a standalone file (an export picked in a dialog) reads and writes
	// without a data folder.
	@Test
	public void standaloneFile() throws Exception
	{
		Filepath export = Filepath.Unchecked.getRooted(root.resolve("export.json"));
		FlipprFiles.writeString(export, "{\"rsn\":\"Alice\"}");
		assertEquals("{\"rsn\":\"Alice\"}", FlipprFiles.readString(export));
		assertNull(FlipprFiles.readString(Filepath.Unchecked.getRooted(root.resolve("missing.json"))));
	}
}
