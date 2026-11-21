package com.flippr.osrs;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

// Live GE slot sync for the website's Plan board: every
// GrandExchangeOfferChanged becomes one slot snapshot in exactly the shape
// POST /api/v1/osrs/offers parses: {slot, itemId, state, qtyFilled,
// qtyTotal, price, spent, ts}, state lower-cased, itemId null for an empty
// slot.
//
// One fill can fire several events, so non-final states are coalesced per
// slot for COALESCE_MS; a final state (bought, sold, cancelled, emptied)
// flushes at once. Only the latest snapshot per slot is kept, so the
// pending map never holds more than 8 entries even while offline, and a
// resend is harmless: snapshots are cumulative and the server ignores
// repeats. Sent with OkHttp enqueue via WebsiteConnection - never blocks
// the client thread.
@Slf4j
@Singleton
public class OfferSync
{
	static final String PATH = "/api/v1/osrs/offers";
	static final long COALESCE_MS = 250;
	static final int SLOT_COUNT = 8;
	// Game ticks to wait after login before reading all 8 slots, so the
	// offers the server sends on login have loaded.
	static final int LOGIN_SNAPSHOT_DELAY_TICKS = 2;

	// One GE slot as the site's parseOfferSnapshots expects it.
	static final class Snapshot
	{
		final int slot;
		final Integer itemId;
		final String state;
		final long qtyFilled;
		final long qtyTotal;
		final long price;
		final long spent;
		final long ts;

		Snapshot(int slot, Integer itemId, String state, long qtyFilled, long qtyTotal, long price, long spent, long ts)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.state = state;
			this.qtyFilled = qtyFilled;
			this.qtyTotal = qtyTotal;
			this.price = price;
			this.spent = spent;
			this.ts = ts;
		}
	}

	@Inject
	private WebsiteConnection website;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Client client;

	@Inject
	private ServerSuggestions serverSuggestions;

	LongSupplier clock = System::currentTimeMillis;

	// Latest unsent snapshot per slot, in slot order.
	private final Map<Integer, Snapshot> pending = new TreeMap<>();
	private final Backoff backoff = new Backoff(2_000, 120_000);
	private boolean flushScheduled;
	private boolean inFlight;
	private int loginSnapshotTicks = -1;
	private volatile long lastSentAt;

	static Snapshot snapshotOf(int slot, GrandExchangeOffer offer, long ts)
	{
		GrandExchangeOfferState state = offer.getState();
		if (state == null || state == GrandExchangeOfferState.EMPTY)
		{
			return new Snapshot(slot, null, "empty", 0, 0, 0, 0, ts);
		}
		return new Snapshot(
			slot,
			offer.getItemId(),
			state.name().toLowerCase(),
			offer.getQuantitySold(),
			offer.getTotalQuantity(),
			offer.getPrice(),
			offer.getSpent(),
			ts
		);
	}

	static boolean isFinal(String state)
	{
		switch (state)
		{
			case "bought":
			case "sold":
			case "cancelled_buy":
			case "cancelled_sell":
			case "empty":
				return true;
			default:
				return false;
		}
	}

	public void onOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (slot < 0 || slot >= SLOT_COUNT)
		{
			return;
		}
		queue(snapshotOf(slot, offer, clock.getAsLong()));
	}

	synchronized void queue(Snapshot snapshot)
	{
		if (!website.isSyncEnabled())
		{
			pending.clear();
			return;
		}
		pending.put(snapshot.slot, snapshot);
		if (isFinal(snapshot.state))
		{
			flush();
			return;
		}
		if (!flushScheduled)
		{
			flushScheduled = true;
			executor.schedule(this::scheduledFlush, COALESCE_MS, TimeUnit.MILLISECONDS);
		}
	}

	void scheduledFlush()
	{
		synchronized (this)
		{
			flushScheduled = false;
		}
		flush();
	}

	// Sends every pending snapshot in one request, unless one is already in
	// flight (its callback flushes again) or the website is paused.
	synchronized void flush()
	{
		if (pending.isEmpty() || inFlight)
		{
			return;
		}
		if (!website.isSyncEnabled())
		{
			pending.clear();
			return;
		}
		long now = clock.getAsLong();
		if (website.isInMaintenance() || backoff.isWaiting(now))
		{
			return;
		}

		List<Snapshot> batch = new ArrayList<>(pending.values());
		inFlight = true;
		website.postJsonAsync(PATH, gson.toJson(batch), response -> onSent(batch, response));
	}

	private static final class OffersResult
	{
		int positionsUpdated;
	}

	// The site moved a Plan position (a fill, a finished trade - which may
	// auto-fill the freed slot): read the plan again so the sidebar, the
	// highlights and the next suggestion catch up at once.
	static boolean movedPositions(Gson gson, String body)
	{
		try
		{
			OffersResult result = body == null ? null : gson.fromJson(body, OffersResult.class);
			return result != null && result.positionsUpdated > 0;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private void onSent(List<Snapshot> batch, WebsiteConnection.ApiResponse response)
	{
		if (response.outcome == WebsiteConnection.Outcome.OK && serverSuggestions != null && movedPositions(gson, response.body))
		{
			serverSuggestions.refreshNow();
		}
		synchronized (this)
		{
			inFlight = false;
			long now = clock.getAsLong();
			switch (response.outcome)
			{
				case OK:
					backoff.reset();
					lastSentAt = now;
					// Only drop what was sent: a newer snapshot for the same
					// slot that arrived meanwhile stays queued.
					for (Snapshot sent : batch)
					{
						pending.remove(sent.slot, sent);
					}
					break;
				case UNAUTHORIZED:
					pending.clear();
					return;
				case MAINTENANCE:
					// Kept; onGameTick retries once maintenance ends.
					return;
				case FAILED:
				default:
					if (response.code == 400)
					{
						// The server can't parse these - resending won't help.
						log.warn("OfferSync: server rejected slot snapshots: {}", response.body);
						for (Snapshot sent : batch)
						{
							pending.remove(sent.slot, sent);
						}
						break;
					}
					long delay = backoff.fail(now);
					log.debug("OfferSync: send failed (HTTP {}), retrying in {}ms", response.code, delay);
					return;
			}
			if (!pending.isEmpty())
			{
				flush();
			}
		}
	}

	// GameState LOGGED_IN after a login or world hop: send all 8 slots a
	// couple of ticks later, once the offers have loaded.
	public synchronized void onLoggedIn()
	{
		loginSnapshotTicks = LOGIN_SNAPSHOT_DELAY_TICKS;
	}

	public void onGameTick()
	{
		boolean sendAll;
		synchronized (this)
		{
			sendAll = loginSnapshotTicks == 0;
			if (loginSnapshotTicks >= 0)
			{
				loginSnapshotTicks--;
			}
		}
		if (sendAll)
		{
			queueAllSlots(client.getGrandExchangeOffers());
		}
		// Retries after a failure or maintenance; flush() itself checks
		// the backoff and maintenance windows.
		flush();
	}

	synchronized void queueAllSlots(GrandExchangeOffer[] offers)
	{
		if (offers == null || !website.isSyncEnabled())
		{
			return;
		}
		long now = clock.getAsLong();
		for (int slot = 0; slot < Math.min(SLOT_COUNT, offers.length); slot++)
		{
			if (offers[slot] != null)
			{
				pending.put(slot, snapshotOf(slot, offers[slot], now));
			}
		}
		flush();
	}

	public long getLastSentAt()
	{
		return lastSentAt;
	}

	public synchronized int getPendingCount()
	{
		return pending.size();
	}

	public synchronized long getRetryRemainingMs()
	{
		return backoff.isFailing() ? backoff.remainingMs(clock.getAsLong()) : 0;
	}
}
