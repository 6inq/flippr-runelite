package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;

// OSRS-side equivalent of the Flippr website's "new-item volatility
// flipping" detection - a newly added item has no price history and can
// swing wildly before the
// market settles, which is itself a real opportunity worth flagging. The
// website scans a weekly cron diff against its own Postgres-backed item
// catalog; this plugin has no database, so it diffs against a small local
// known-items.json file instead, reusing the same PriceFeed catalog
// snapshot DumpDetector already scans. Only the DETECTION half is built
// here, matching the website's own scoping exactly - the ELIGIBILITY
// GATING half (whether a brand-new item can be suggested right away vs.
// waiting a day for the initial chaos to settle) isn't built on either
// side yet.
@Slf4j
@Singleton
public class NewItemDetector
{
	private static final Path KNOWN_ITEMS_FILE = FlipprFiles.KNOWN_ITEMS;
	private static final Type ID_SET_TYPE = new TypeToken<Set<Integer>>()
	{
	}.getType();
	// Re-checking every PriceFeed poll (10s) would just repeat the same diff
	// against data that hasn't meaningfully changed - this only needs to run
	// occasionally.
	private static final long CHECK_INTERVAL_MS = 60_000;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private FlipprConfig config;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;
	private Set<Integer> knownItemIds;
	private long lastCheckTime;

	public void onGameTick()
	{
		// Same Watch Mode override as DumpDetector's own onGameTick() -
		// see its comment. Skipping the check entirely (not just the alert)
		// under Watch Mode does mean the known-items baseline doesn't
		// advance during that stretch, so a real backlog of genuinely-new
		// items could surface as one batch the next time this runs with
		// Watch Mode off - an acceptable, honest tradeoff, not a
		// correctness bug: the same thing already happens after any stretch
		// the client wasn't running at all.
		if (!config.newItemAlertsEnabled() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastCheckTime < CHECK_INTERVAL_MS)
		{
			return;
		}
		lastCheckTime = now;

		if (knownItemIds == null)
		{
			knownItemIds = load();
		}

		Set<Integer> currentIds = new HashSet<>();
		for (String idString : priceFeed.getSnapshot().keySet())
		{
			try
			{
				currentIds.add(Integer.parseInt(idString));
			}
			catch (NumberFormatException e)
			{
				// Skip, same tolerance as DumpDetector's own catalog scan.
			}
		}

		if (currentIds.isEmpty())
		{
			// PriceFeed hasn't polled yet this session - nothing to diff.
			return;
		}

		// A genuinely empty known-items file (first ever run) would
		// otherwise flag the entire game's item catalog as "new" all at
		// once - this establishes the baseline silently instead, matching
		// the website's own "first seen" semantics only meaning something
		// once there's a real prior catalog to diff against.
		boolean firstRun = knownItemIds.isEmpty();
		for (Integer itemId : currentIds)
		{
			if (!knownItemIds.contains(itemId))
			{
				knownItemIds.add(itemId);
				if (!firstRun)
				{
					announce(itemId);
				}
			}
		}

		if (firstRun)
		{
			log.info(
				"NewItemDetector: seeded {} with {} known items, no alerts on this baseline run",
				KNOWN_ITEMS_FILE, currentIds.size()
			);
		}

		save(knownItemIds);
	}

	private void announce(int itemId)
	{
		String itemName = itemManager.getItemComposition(itemId).getName();
		String text = itemName + " is a new item on the GE - no price history yet, can swing wildly before the "
			+ "market settles";
		log.info("NewItemDetector: {}", text);

		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("Flippr new item: ")
			.append(ChatColorType.NORMAL)
			.append(text)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}

	private Set<Integer> load()
	{
		try
		{
			if (Files.exists(KNOWN_ITEMS_FILE))
			{
				String json = Files.readString(KNOWN_ITEMS_FILE);
				Set<Integer> loaded = gson.fromJson(json, ID_SET_TYPE);
				if (loaded != null)
				{
					return new HashSet<>(loaded);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("NewItemDetector: failed to read {}, starting fresh", KNOWN_ITEMS_FILE, e);
		}
		return new HashSet<>();
	}

	private void save(Set<Integer> ids)
	{
		try
		{
			Files.createDirectories(KNOWN_ITEMS_FILE.getParent());
			Files.writeString(KNOWN_ITEMS_FILE, gson.toJson(ids));
		}
		catch (IOException e)
		{
			log.warn("NewItemDetector: failed to write {}", KNOWN_ITEMS_FILE, e);
		}
	}
}
