package com.flippr.osrs;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

// Reports a Plan position's GE offer as listed, with its exact price, the
// way the RS3 Alt1 app does once it has read the offer off the screen:
// POST /api/v1/osrs/listings [{positionId, side, ts, price}]. RuneLite
// gives every slot's exact item, side and
// price, so no reading is needed - a slot whose open offer is the only one
// for that item and side, matching the only position on that leg, is that
// position's offer. The site then moves pending -> buying / bought ->
// selling (never backwards), takes the listed price for that leg and clears
// a price alert it satisfies.
//
// Fills are not reported here: they keep flowing through OfferSync
// (/offers), and a listing never changes counts, so nothing is counted
// twice. Only what the site doesn't have yet is sent: the status would move
// on, or the listed price differs from the plan's - so a re-listed or
// updated offer at a new price is sent again. Each (position, side, price)
// is sent at most once per RESEND_AFTER_MS while the plan hasn't caught up.
//
// Runs on the client thread (game tick); the POST is OkHttp enqueue.
@Slf4j
@Singleton
public class ListingReporter
{
	static final String PATH = "/api/v1/osrs/listings";
	// The site takes at most 16 per request.
	static final int MAX_PER_REQUEST = 16;
	static final long RESEND_AFTER_MS = 60_000;
	static final long RETRY_AFTER_FAILURE_MS = 10_000;

	// One GE slot as RuneLite reports it.
	static final class SlotOffer
	{
		// 0-7.
		final int slot;
		final int itemId;
		// "buy" / "sell".
		final String side;
		final long price;
		// An open offer (buying / selling), not a finished or cancelled one.
		final boolean open;
		// Finished (bought / sold) and not collected yet.
		final boolean completed;

		SlotOffer(int slot, int itemId, String side, long price, boolean open)
		{
			this(slot, itemId, side, price, open, false);
		}

		SlotOffer(int slot, int itemId, String side, long price, boolean open, boolean completed)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.side = side;
			this.price = price;
			this.open = open;
			this.completed = completed;
		}
	}

	// One listing in the site's shape.
	static final class Listing
	{
		final String positionId;
		final String side;
		final long ts;
		final long price;

		Listing(String positionId, String side, long ts, long price)
		{
			this.positionId = positionId;
			this.side = side;
			this.ts = ts;
			this.price = price;
		}

		String key()
		{
			return positionId + ":" + side + ":" + price;
		}
	}

	private static final class ListingsResult
	{
		int applied;
	}

	@Inject
	private Client client;

	@Inject
	private WebsiteConnection website;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private Gson gson;

	LongSupplier clock = System::currentTimeMillis;

	// key -> when it was sent; forgotten after RESEND_AFTER_MS.
	private final Map<String, Long> sent = new HashMap<>();
	private boolean inFlight;
	private long retryAt;
	// positionId -> in-game GE slots (1-8) holding that item, for the cards.
	private volatile Map<String, List<Integer>> geSlots = Collections.emptyMap();

	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ServerSuggestions.Data data = serverSuggestions.getData();
		if (data == null || !data.hasPlan())
		{
			geSlots = Collections.emptyMap();
			return;
		}
		List<SlotOffer> slots = slotsOf(client.getGrandExchangeOffers());
		geSlots = geSlotsByPosition(slots, data.planPositions);
		report(listingsFor(slots, data.planPositions, clock.getAsLong()));
	}

	// The in-game GE slots (1-8) a position's item is in, for its card
	// ("GE 3"); empty when none.
	public List<Integer> geSlotsFor(String positionId)
	{
		List<Integer> list = geSlots.get(positionId);
		return list == null ? Collections.emptyList() : list;
	}

	static List<SlotOffer> slotsOf(GrandExchangeOffer[] offers)
	{
		List<SlotOffer> out = new ArrayList<>();
		if (offers == null)
		{
			return out;
		}
		for (int i = 0; i < Math.min(8, offers.length); i++)
		{
			GrandExchangeOffer o = offers[i];
			GrandExchangeOfferState state = o == null ? null : o.getState();
			String side = sideOf(state);
			if (side == null)
			{
				continue;
			}
			boolean open = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
			boolean completed = state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD;
			out.add(new SlotOffer(i, o.getItemId(), side, o.getPrice(), open, completed));
		}
		return out;
	}

	static String sideOf(GrandExchangeOfferState state)
	{
		if (state == null)
		{
			return null;
		}
		switch (state)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return "buy";
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return "sell";
			default:
				return null;
		}
	}

	// Is the position on this side's leg now? Buy: something still to buy
	// and the buy not yet behind it. Sell: something bought and unsold.
	static boolean onLeg(ServerSuggestions.PlanPosition p, String side)
	{
		if (p.status == null)
		{
			return false;
		}
		if ("buy".equals(side))
		{
			return ("pending".equals(p.status) || "buying".equals(p.status)) && p.boughtQty < p.targetQty;
		}
		return ("buying".equals(p.status) || "bought".equals(p.status) || "selling".equals(p.status)) && p.boughtQty > p.soldQty;
	}

	// The open offer of each position whose match is unambiguous: exactly
	// one open offer for that item and side, and exactly one position on
	// that leg for the item. Anything else (two slots buying the same item,
	// two positions for it) is left alone - no guess is reported. Keyed by
	// "positionId:side": a position can have a buy and a sell offer up.
	static Map<String, SlotOffer> matchOffers(List<SlotOffer> slots, List<ServerSuggestions.PlanPosition> positions)
	{
		Map<String, SlotOffer> out = new LinkedHashMap<>();
		for (SlotOffer slot : slots)
		{
			if (!slot.open)
			{
				continue;
			}
			int sameOffers = 0;
			for (SlotOffer other : slots)
			{
				if (other.open && other.itemId == slot.itemId && other.side.equals(slot.side))
				{
					sameOffers++;
				}
			}
			ServerSuggestions.PlanPosition match = null;
			int onLeg = 0;
			for (ServerSuggestions.PlanPosition p : positions)
			{
				if (p.itemId == slot.itemId && p.id != null && onLeg(p, slot.side))
				{
					onLeg++;
					match = p;
				}
			}
			if (sameOffers == 1 && onLeg == 1)
			{
				out.put(match.id + ":" + slot.side, slot);
			}
		}
		return out;
	}

	// What to send now: matched offers whose status would move on or whose
	// price differs from the plan's price for that leg.
	static List<Listing> listingsFor(List<SlotOffer> slots, List<ServerSuggestions.PlanPosition> positions, long now)
	{
		Map<String, SlotOffer> matched = matchOffers(slots, positions);
		List<Listing> out = new ArrayList<>();
		for (ServerSuggestions.PlanPosition p : positions)
		{
			for (String side : new String[]{"buy", "sell"})
			{
				SlotOffer slot = matched.get(p.id + ":" + side);
				if (slot != null && slot.price > 0)
				{
					Listing l = listingFor(p, slot, now);
					if (l != null)
					{
						out.add(l);
					}
				}
			}
		}
		return out;
	}

	private static Listing listingFor(ServerSuggestions.PlanPosition p, SlotOffer slot, long now)
	{
		boolean buy = "buy".equals(slot.side);
		boolean wouldMove = buy ? "pending".equals(p.status) : !"selling".equals(p.status);
		long planPrice = Math.round(buy ? p.buyPrice : p.sellPrice);
		return wouldMove || slot.price != planPrice ? new Listing(p.id, slot.side, now, slot.price) : null;
	}

	// positionId -> GE slots (1-8) holding its item (any state but empty).
	static Map<String, List<Integer>> geSlotsByPosition(List<SlotOffer> slots, List<ServerSuggestions.PlanPosition> positions)
	{
		Map<String, List<Integer>> out = new HashMap<>();
		for (ServerSuggestions.PlanPosition p : positions)
		{
			if (p.id == null)
			{
				continue;
			}
			List<Integer> list = new ArrayList<>();
			for (SlotOffer slot : slots)
			{
				if (slot.itemId == p.itemId)
				{
					list.add(slot.slot + 1);
				}
			}
			if (!list.isEmpty())
			{
				out.put(p.id, list);
			}
		}
		return out;
	}

	synchronized void report(List<Listing> listings)
	{
		long now = clock.getAsLong();
		for (Iterator<Map.Entry<String, Long>> it = sent.entrySet().iterator(); it.hasNext(); )
		{
			if (now - it.next().getValue() >= RESEND_AFTER_MS)
			{
				it.remove();
			}
		}
		if (inFlight || now < retryAt || !website.isSyncEnabled() || website.isInMaintenance())
		{
			return;
		}
		List<Listing> batch = new ArrayList<>();
		for (Listing l : listings)
		{
			if (!sent.containsKey(l.key()) && batch.size() < MAX_PER_REQUEST)
			{
				batch.add(l);
			}
		}
		if (batch.isEmpty())
		{
			return;
		}
		for (Listing l : batch)
		{
			sent.put(l.key(), now);
		}
		inFlight = true;
		log.debug("ListingReporter: reporting {} listing(s)", batch.size());
		website.postJsonAsync(PATH, gson.toJson(batch), response -> onSent(batch, response));
	}

	private void onSent(List<Listing> batch, WebsiteConnection.ApiResponse response)
	{
		boolean applied = false;
		synchronized (this)
		{
			inFlight = false;
			if (response.outcome == WebsiteConnection.Outcome.OK)
			{
				try
				{
					ListingsResult result = gson.fromJson(response.body, ListingsResult.class);
					applied = result != null && result.applied > 0;
				}
				catch (RuntimeException e)
				{
					applied = true;
				}
			}
			else if (response.code == 400)
			{
				// The site can't parse these - sending again won't help.
				log.warn("ListingReporter: listings rejected: {}", response.body);
			}
			else
			{
				// Network, maintenance, 5xx: try again shortly.
				for (Listing l : batch)
				{
					sent.remove(l.key());
				}
				retryAt = clock.getAsLong() + RETRY_AFTER_FAILURE_MS;
			}
		}
		if (applied)
		{
			serverSuggestions.refreshNow();
		}
	}
}
