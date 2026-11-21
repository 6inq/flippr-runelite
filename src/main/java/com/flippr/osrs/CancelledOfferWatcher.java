package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;

// Reconciles Plan positions with buy offers the player cancelled in game
// (CancelledBuys has the rules). Each game tick, once the GE has loaded
// after login (LOAD_TICKS logged-in ticks) and with fresh plan data: a
// confirmed finding is fixed straight away in sell-only mode (Drop, or
// Stop & sell when something was bought - POST positions/{id}/stop), else
// offered on the position's card as one click ("Buy offer cancelled in the
// GE - Drop"). Client thread; the request is OkHttp enqueue.
@Slf4j
@Singleton
public class CancelledOfferWatcher
{
	// Logged-in ticks before the GE slots count as loaded (~6s: the offers
	// arrive in the first ticks after login / a world hop).
	static final int LOAD_TICKS = 10;
	static final String SEEN_KEY = "geSeenPositions";
	private static final Type LIST = new TypeToken<List<String>>()
	{
	}.getType();

	@Inject
	private Client client;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private ConfigManager configManager;

	@Inject
	private WebsiteConnection website;

	@Inject
	private Gson gson;

	private CancelledBuys buys;
	private int loggedInTicks;
	private String character;
	// Findings waiting for the player (not sell-only), by position id.
	private volatile Map<String, CancelledBuys.Finding> prompts = Collections.emptyMap();

	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			loggedInTicks = 0;
			prompts = Collections.emptyMap();
			return;
		}
		CancelledBuys b = buys();
		String now = website.getCharacter();
		if (loggedInTicks == 0 || !java.util.Objects.equals(now, character))
		{
			character = now;
			synchronized (this)
			{
				b.resetConfirmations();
			}
		}
		loggedInTicks++;
		ServerSuggestions.Data data = serverSuggestions.getData();
		if (data == null || !data.hasPlan())
		{
			prompts = Collections.emptyMap();
			return;
		}
		boolean loaded = loggedInTicks >= LOAD_TICKS && client.getGrandExchangeOffers() != null;
		List<ListingReporter.SlotOffer> slots = ListingReporter.slotsOf(client.getGrandExchangeOffers());
		List<CancelledBuys.Finding> found;
		synchronized (this)
		{
			found = b.update(data.planPositions, slots, loaded, data.fetchedAt);
		}
		saveSeen(b);
		if (serverSuggestions.isSellOnly())
		{
			for (CancelledBuys.Finding f : found)
			{
				log.info("Flippr: buy offer for {} is gone from the GE - {} (sell-only)", f.name, f.fix);
				fix(f.positionId, r ->
				{
				});
			}
			prompts = Collections.emptyMap();
			return;
		}
		Map<String, CancelledBuys.Finding> next = new LinkedHashMap<>();
		for (CancelledBuys.Finding f : found)
		{
			next.put(f.positionId, f);
		}
		prompts = next;
	}

	// The card's prompt for this position, or null.
	public CancelledBuys.Finding promptFor(String positionId)
	{
		return prompts.get(positionId);
	}

	public java.util.Set<String> promptIds()
	{
		return prompts.keySet();
	}

	// Drop / Stop & sell (the card's button, or sell-only). done runs on an
	// OkHttp thread.
	public void fix(String positionId, Consumer<ServerSuggestions.StopResult> done)
	{
		CancelledBuys b = buys();
		synchronized (this)
		{
			b.handled(positionId);
			Map<String, CancelledBuys.Finding> next = new LinkedHashMap<>(prompts);
			next.remove(positionId);
			prompts = next;
		}
		serverSuggestions.stopPosition(positionId, result ->
		{
			if (!result.ok)
			{
				synchronized (this)
				{
					b.retry(positionId);
				}
				log.info("Flippr: couldn't fix the cancelled buy ({})", result.error);
			}
			done.accept(result);
		});
	}

	private synchronized CancelledBuys buys()
	{
		if (buys == null)
		{
			List<String> saved = null;
			try
			{
				String json = configManager.getConfiguration("flippr", SEEN_KEY);
				saved = json == null || json.isEmpty() ? null : gson.fromJson(json, LIST);
			}
			catch (RuntimeException e)
			{
				log.debug("CancelledOfferWatcher: unreadable seen list, starting fresh");
			}
			buys = new CancelledBuys(saved);
		}
		return buys;
	}

	private void saveSeen(CancelledBuys b)
	{
		List<String> seen;
		synchronized (this)
		{
			seen = b.takeSeenToSave();
		}
		if (seen != null)
		{
			configManager.setConfiguration("flippr", SEEN_KEY, gson.toJson(seen));
		}
	}
}
