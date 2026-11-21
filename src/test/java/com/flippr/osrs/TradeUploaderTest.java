package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: TradeUploader keeps completed flips in an on-disk queue
// (upload-queue.jsonl) until the website accepts them, POSTs batches of at
// most 200 in the CompletedFlip JSON the site's /trades route maps, removes
// only lines that were sent successfully, skips malformed lines, and
// "Upload history" queues only the given character's past flips. A temp
// folder stands in for the real data folders; the executor runs tasks inline.
public class TradeUploaderTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Mock
	private WebsiteConnection website;
	@Mock
	private ScheduledExecutorService executor;
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private TradeUploader uploader;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;
	private final List<String> posted = new ArrayList<>();
	// Outcome for each successive POST; OK once the list runs out.
	private final List<WebsiteConnection.Outcome> outcomes = new ArrayList<>();

	@Before
	public void setUp() throws IOException
	{
		mocks = MockitoAnnotations.openMocks(this);
		uploader.clock = () -> now;
		uploader.queueFile = folder.getRoot().toPath().resolve("upload-queue.jsonl");
		uploader.flipsFile = folder.getRoot().toPath().resolve("flips.jsonl");
		when(website.isSyncEnabled()).thenReturn(true);
		when(website.isInMaintenance()).thenReturn(false);
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		doAnswer(inv ->
		{
			posted.add(inv.getArgument(1));
			WebsiteConnection.Outcome outcome = outcomes.isEmpty() ? WebsiteConnection.Outcome.OK : outcomes.remove(0);
			WebsiteConnection.ApiCallback callback = inv.getArgument(2);
			callback.onResult(new WebsiteConnection.ApiResponse(outcome, outcome == WebsiteConnection.Outcome.OK ? 200 : 0,
				"{\"inserted\":1,\"duplicates\":0,\"rejected\":[]}"));
			return null;
		}).when(website).postJsonAsync(eq(TradeUploader.PATH), anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private static CompletedFlip flip(long timestamp, int itemId, String rsn)
	{
		CompletedFlip flip = new CompletedFlip();
		flip.timestamp = timestamp;
		flip.rsn = rsn;
		flip.itemId = itemId;
		flip.itemName = "Item " + itemId;
		flip.quantity = 10;
		flip.avgBuyPrice = 100;
		flip.avgSellPrice = 120;
		flip.tax = 20;
		flip.profit = 180;
		flip.firstBuyTime = timestamp - 60_000;
		flip.lastSellTime = timestamp;
		flip.buyFillTimeMs = 1_000;
		flip.sellFillTimeMs = 2_000;
		return flip;
	}

	private void writeQueue(int count) throws IOException
	{
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			lines.add(gson.toJson(flip(now - i, 4151, "Alice")));
		}
		Files.write(uploader.queueFile, lines, StandardCharsets.UTF_8);
		uploader.init();
	}

	private List<String> queueLines() throws IOException
	{
		return Files.exists(uploader.queueFile) ? Files.readAllLines(uploader.queueFile, StandardCharsets.UTF_8) : new ArrayList<>();
	}

	private JsonArray body(int index)
	{
		return gson.fromJson(posted.get(index), JsonArray.class);
	}

	// Req: a recorded flip is queued on disk (surviving a restart) in the
	// field names the site's parseCompletedFlip reads.
	@Test
	public void flipsArePersistedInTheSiteShape() throws IOException
	{
		when(website.isSyncEnabled()).thenReturn(true);
		uploader.enqueue(flip(now, 4151, "Alice"));

		List<String> lines = queueLines();
		assertEquals(1, lines.size());
		JsonObject json = gson.fromJson(lines.get(0), JsonObject.class);
		for (String field : Arrays.asList("rsn", "itemId", "itemName", "quantity", "avgBuyPrice", "avgSellPrice", "tax",
			"profit", "firstBuyTime", "lastSellTime", "buyFillTimeMs", "sellFillTimeMs", "dumpModeSession", "timestamp"))
		{
			assertTrue(field, json.has(field));
		}

		TradeUploader restarted = new TradeUploader();
		restarted.queueFile = uploader.queueFile;
		assertEquals(1, restarted.readLines().size());
	}

	// Req: nothing is queued unless paired with sync on.
	@Test
	public void nothingQueuedWhenSyncIsOff() throws IOException
	{
		when(website.isSyncEnabled()).thenReturn(false);
		uploader.enqueue(flip(now, 4151, "Alice"));
		assertTrue(queueLines().isEmpty());
	}

	// Req: batches of at most 200; every successful batch is removed and
	// the next one follows until the queue is empty.
	@Test
	public void sendsInBatchesOf200() throws IOException
	{
		writeQueue(450);

		uploader.onGameTick();

		assertEquals(3, posted.size());
		assertEquals(200, body(0).size());
		assertEquals(200, body(1).size());
		assertEquals(50, body(2).size());
		assertTrue(queueLines().isEmpty());
		assertEquals(0, uploader.getQueuedCount());
	}

	// Req: a failed batch stays queued; already-sent batches are gone, and
	// the retry waits for the backoff.
	@Test
	public void partialFailureKeepsUnsentLines() throws IOException
	{
		writeQueue(450);
		outcomes.add(WebsiteConnection.Outcome.OK);
		outcomes.add(WebsiteConnection.Outcome.FAILED);

		uploader.onGameTick();

		assertEquals(2, posted.size());
		List<String> left = queueLines();
		assertEquals(250, left.size());
		// The failed batch (lines 200-399) is still there, oldest first.
		assertEquals(body(1).get(0).toString(), gson.fromJson(left.get(0), JsonObject.class).toString());

		uploader.onGameTick();
		assertEquals("waits for the backoff", 2, posted.size());

		now += 30_000;
		uploader.onGameTick();
		assertTrue(queueLines().isEmpty());
	}

	// Req: malformed lines are never sent (and dropped from the queue), the
	// good ones still go out.
	@Test
	public void malformedLinesAreSkipped() throws IOException
	{
		String good = gson.toJson(flip(now, 4151, "Alice"));
		Files.write(uploader.queueFile, Arrays.asList("{not json", good, "", "42", "[1,2]"), StandardCharsets.UTF_8);
		uploader.init();

		uploader.onGameTick();

		assertEquals(1, posted.size());
		assertEquals(1, body(0).size());
		assertEquals(4151, body(0).get(0).getAsJsonObject().get("itemId").getAsInt());
		assertTrue(queueLines().isEmpty());
	}

	// Req: maintenance or sync off -> nothing is sent and nothing is lost.
	@Test
	public void pausedWhileInMaintenance() throws IOException
	{
		writeQueue(3);
		when(website.isInMaintenance()).thenReturn(true);
		uploader.onGameTick();
		assertTrue(posted.isEmpty());
		assertEquals(3, queueLines().size());
	}

	// Req: "Upload history" queues only this character's flips (rsn compared
	// case-insensitively), never other accounts' or unattributed ones.
	@Test
	public void historyQueuesOnlyThisCharacter() throws IOException
	{
		Files.write(uploader.flipsFile, Arrays.asList(
			gson.toJson(flip(now - 3, 1, "Alice")),
			gson.toJson(flip(now - 2, 2, "Bob")),
			gson.toJson(flip(now - 1, 3, null)),
			"garbage",
			gson.toJson(flip(now, 4, "ALICE"))), StandardCharsets.UTF_8);

		int queued = uploader.queueHistory("alice");

		assertEquals(2, queued);
		List<String> lines = queueLines();
		assertEquals(2, lines.size());
		assertFalse(lines.get(0).contains("Bob"));
		assertEquals(2, uploader.getQueuedCount());
	}
}
