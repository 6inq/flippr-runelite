package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: OfferSync sends GE slot snapshots in the exact shape POST
// /api/v1/osrs/offers parses, coalesces non-final updates per slot for
// 250ms, flushes final states (bought/sold/cancelled/empty) at once, keeps
// only the latest snapshot per slot, retries with backoff and pauses for
// maintenance. Fake clock; the website and the executor are mocks, so
// nothing touches the network.
public class OfferSyncTest
{
	private static final int WHIP = 4151;

	@Mock
	private WebsiteConnection website;
	@Mock
	private ScheduledExecutorService executor;
	@Mock
	private Client client;
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private OfferSync sync;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;
	// Every POST body, and the callbacks still waiting for a response.
	private final List<String> posted = new ArrayList<>();
	private final List<WebsiteConnection.ApiCallback> callbacks = new ArrayList<>();

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		sync.clock = () -> now;
		when(website.isSyncEnabled()).thenReturn(true);
		when(website.isInMaintenance()).thenReturn(false);
		doAnswer(inv ->
		{
			posted.add(inv.getArgument(1));
			callbacks.add(inv.getArgument(2));
			return null;
		}).when(website).postJsonAsync(eq(OfferSync.PATH), anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId, int filled, int total, long price, long spent)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getQuantitySold()).thenReturn(filled);
		when(offer.getTotalQuantity()).thenReturn(total);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getSpent()).thenReturn(spent);
		return offer;
	}

	private void respond(int index, WebsiteConnection.Outcome outcome, int code)
	{
		callbacks.get(index).onResult(new WebsiteConnection.ApiResponse(outcome, code, "{}"));
	}

	private Runnable scheduledFlush()
	{
		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).schedule(captor.capture(), eq(OfferSync.COALESCE_MS), eq(TimeUnit.MILLISECONDS));
		return captor.getValue();
	}

	private static JsonArray body(String json)
	{
		return new Gson().fromJson(json, JsonArray.class);
	}

	// Req: field names, lower-case state, cumulative numbers, epoch-ms ts.
	@Test
	public void snapshotMatchesTheSiteContract()
	{
		sync.onOfferChanged(2, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 1_500_000, 14_900_000));

		JsonObject snap = body(posted.get(0)).get(0).getAsJsonObject();
		assertEquals(2, snap.get("slot").getAsInt());
		assertEquals(WHIP, snap.get("itemId").getAsInt());
		assertEquals("bought", snap.get("state").getAsString());
		assertEquals(10, snap.get("qtyFilled").getAsInt());
		assertEquals(10, snap.get("qtyTotal").getAsInt());
		assertEquals(1_500_000, snap.get("price").getAsLong());
		assertEquals(14_900_000, snap.get("spent").getAsLong());
		assertEquals(now, snap.get("ts").getAsLong());
	}

	// Req: an emptied slot is sent with no itemId (the site treats it as null).
	@Test
	public void emptySlotHasNoItemId()
	{
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));

		JsonObject snap = body(posted.get(0)).get(0).getAsJsonObject();
		assertEquals("empty", snap.get("state").getAsString());
		assertTrue(snap.get("itemId") == null || snap.get("itemId").isJsonNull());
	}

	// Req: several partial fills inside 250ms become one request carrying
	// only the latest state of that slot.
	@Test
	public void partialFillsAreCoalescedPerSlot()
	{
		sync.onOfferChanged(1, offer(GrandExchangeOfferState.BUYING, WHIP, 1, 10, 100, 100));
		sync.onOfferChanged(1, offer(GrandExchangeOfferState.BUYING, WHIP, 4, 10, 100, 400));
		sync.onOfferChanged(1, offer(GrandExchangeOfferState.BUYING, WHIP, 6, 10, 100, 600));

		assertTrue("nothing is sent before the window closes", posted.isEmpty());
		Runnable flush = scheduledFlush();
		flush.run();

		assertEquals(1, posted.size());
		JsonArray sent = body(posted.get(0));
		assertEquals(1, sent.size());
		assertEquals(6, sent.get(0).getAsJsonObject().get("qtyFilled").getAsInt());
	}

	// Req: a final state flushes at once, together with other slots' pending updates.
	@Test
	public void finalStateFlushesImmediately()
	{
		sync.onOfferChanged(3, offer(GrandExchangeOfferState.SELLING, WHIP, 2, 5, 200, 400));
		sync.onOfferChanged(1, offer(GrandExchangeOfferState.SOLD, 995, 5, 5, 1, 5));

		assertEquals(1, posted.size());
		JsonArray sent = body(posted.get(0));
		assertEquals(2, sent.size());
		assertEquals(1, sent.get(0).getAsJsonObject().get("slot").getAsInt());
		assertEquals(3, sent.get(1).getAsJsonObject().get("slot").getAsInt());
	}

	// Req: a newer snapshot that arrives while a request is in flight is
	// not lost when that request succeeds - it's sent next.
	@Test
	public void newerSnapshotDuringFlightIsSentAfterwards()
	{
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 100, 1_000));
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
		assertEquals("second final waits for the first request", 1, posted.size());

		respond(0, WebsiteConnection.Outcome.OK, 200);

		assertEquals(2, posted.size());
		assertEquals("empty", body(posted.get(1)).get(0).getAsJsonObject().get("state").getAsString());
		respond(1, WebsiteConnection.Outcome.OK, 200);
		assertEquals(0, sync.getPendingCount());
		assertEquals(now, sync.getLastSentAt());
	}

	// Req: a failed send is kept and retried with backoff, not hammered.
	@Test
	public void failedSendIsRetriedAfterBackoff()
	{
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 100, 1_000));
		respond(0, WebsiteConnection.Outcome.FAILED, 0);
		assertEquals(1, sync.getPendingCount());

		sync.onGameTick();
		assertEquals("still inside the backoff window", 1, posted.size());

		now += 2_000;
		sync.onGameTick();
		assertEquals(2, posted.size());
		respond(1, WebsiteConnection.Outcome.OK, 200);
		assertEquals(0, sync.getPendingCount());
	}

	// Req: while the site is in maintenance nothing is sent; the latest
	// state goes out once it ends.
	@Test
	public void maintenancePausesSending()
	{
		when(website.isInMaintenance()).thenReturn(true);
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 100, 1_000));
		sync.onGameTick();
		assertTrue(posted.isEmpty());

		when(website.isInMaintenance()).thenReturn(false);
		sync.onGameTick();
		assertEquals(1, posted.size());
	}

	// Req: not paired / sync off -> nothing queued or sent.
	@Test
	public void nothingIsSentWhenSyncIsOff()
	{
		when(website.isSyncEnabled()).thenReturn(false);
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 100, 1_000));
		sync.onGameTick();

		verify(website, never()).postJsonAsync(anyString(), anyString(), any());
		assertEquals(0, sync.getPendingCount());
		verify(executor, never()).schedule(any(Runnable.class), anyLong(), any());
	}

	// Req: login sends all 8 slots once the offers have loaded.
	@Test
	public void loginSendsAllEightSlots()
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		for (int i = 0; i < offers.length; i++)
		{
			offers[i] = offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		}
		offers[4] = offer(GrandExchangeOfferState.SELLING, WHIP, 1, 3, 2_000_000, 2_000_000);
		when(client.getGrandExchangeOffers()).thenReturn(offers);

		sync.onLoggedIn();
		for (int tick = 0; tick < OfferSync.LOGIN_SNAPSHOT_DELAY_TICKS; tick++)
		{
			sync.onGameTick();
		}
		assertTrue(posted.isEmpty());
		sync.onGameTick();

		JsonArray sent = body(posted.get(0));
		assertEquals(8, sent.size());
		assertEquals("selling", sent.get(4).getAsJsonObject().get("state").getAsString());
		sync.onGameTick();
		assertEquals("sent once per login", 1, posted.size());
	}

	// Req: a 400 (server can't parse it) is dropped rather than retried forever.
	@Test
	public void rejectedSnapshotsAreDropped()
	{
		sync.onOfferChanged(0, offer(GrandExchangeOfferState.BOUGHT, WHIP, 10, 10, 100, 1_000));
		respond(0, WebsiteConnection.Outcome.FAILED, 400);
		assertEquals(0, sync.getPendingCount());
		assertEquals(0, sync.getRetryRemainingMs());
	}

	@Test
	public void finalStatesAreRecognised()
	{
		for (String state : new String[]{"bought", "sold", "cancelled_buy", "cancelled_sell", "empty"})
		{
			assertTrue(state, OfferSync.isFinal(state));
		}
		assertFalse(OfferSync.isFinal("buying"));
		assertFalse(OfferSync.isFinal("selling"));
		assertNull(OfferSync.snapshotOf(0, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0), now).itemId);
		verify(website, times(0)).getAsync(anyString(), any());
	}
}
