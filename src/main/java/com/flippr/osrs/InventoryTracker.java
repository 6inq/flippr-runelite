package com.flippr.osrs;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;

// Snapshots liquid GP (coins) and full inventory contents both on every real
// inventory change (ItemContainerChanged - covers GE collects, drops,
// pickups, bank withdrawals, anything that actually moves items) and on a
// wall-clock heartbeat, so capital/stock-on-hand context exists even during
// idle stretches between changes, not just at the moments something moved.
// @Singleton - see DecisionEngine's class comment for why every stateful
// tracker in this plugin gets this regardless of current injection-site
// count.
@Slf4j
@Singleton
public class InventoryTracker
{
	private static final long HEARTBEAT_INTERVAL_MS = 30_000;
	private static final int INVENTORY_CAPACITY = 28;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private InventoryLogger inventoryLogger;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private FlipprConfig config;

	private long lastHeartbeatTime;

	// Dedup for the budget alert below - only fires once when free space
	// first drops to/below the threshold, clears once it recovers, so it
	// can fire again on a future squeeze instead of just once ever.
	private boolean budgetAlertActive;

	// Last-observed liquid GP - for DUMP_BUY's suggested-quantity calculation
	// (DecisionEngine), which needs to know real available capital, not just
	// the buy limit, to avoid suggesting a quantity the player can't actually
	// afford. Updated on every snapshot() call, same cadence as everything
	// else this tracker observes.
	private volatile long lastCoins;

	public long getCoins()
	{
		return lastCoins;
	}

	public void onInventoryChanged()
	{
		snapshot("inventory_changed");
	}

	public void onGameTick()
	{
		long now = System.currentTimeMillis();
		if (now - lastHeartbeatTime >= HEARTBEAT_INTERVAL_MS)
		{
			lastHeartbeatTime = now;
			snapshot("heartbeat");
		}
	}

	// Inventory-space budgeting: a full inventory blocks collecting the next
	// flip regardless of whether a GE slot is free, so it needs to be treated
	// as a real finite resource the same way GP and GE slots already are. This
	// plugin doesn't enforce a "stop chasing anything new" rule from it, only
	// warns.
	public int getFreeSlotCount()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return INVENTORY_CAPACITY;
		}

		int used = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				used++;
			}
		}
		return INVENTORY_CAPACITY - used;
	}

	// Real current count of this item across the whole inventory right now
	// - for PortfolioTooltipOverlay's "Inventory: N" readout. Sums across
	// multiple slots for an unstackable item (each occupying its own slot
	// at quantity 1) the same way a stackable item's single slot already
	// holds its full count.
	public int getItemCount(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return 0;
		}

		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() == itemId)
			{
				count += item.getQuantity();
			}
		}
		return count;
	}

	// Units of an item in the inventory, noted copies included - both can be
	// offered on the GE. Client thread only (reads the item definition).
	public int getHeldCount(int itemId)
	{
		int count = getItemCount(itemId);
		int notedId = itemManager.getItemComposition(itemId).getLinkedNoteId();
		if (notedId > 0 && notedId != itemId)
		{
			count += getItemCount(notedId);
		}
		return count;
	}

	private void checkInventoryBudget()
	{
		// Watch Mode override, same as DumpDetector/NewItemDetector - see
		// their comments. Doesn't touch snapshot()'s own logging call right
		// above this in the call chain, only this alert.
		if (!config.inventoryBudgetAlertEnabled() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			budgetAlertActive = false;
			return;
		}

		int freeSlots = getFreeSlotCount();
		boolean overThreshold = freeSlots <= config.inventoryFreeSlotThreshold();

		if (overThreshold && !budgetAlertActive)
		{
			budgetAlertActive = true;
			String text = String.format(
				"Only %d free inventory slot%s left - sell something before starting a new flip",
				freeSlots, freeSlots == 1 ? "" : "s"
			);
			log.info("InventoryTracker: {}", text);

			String formatted = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("Flippr: ")
				.append(ChatColorType.NORMAL)
				.append(text)
				.build();
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(formatted)
				.build());
		}
		else if (!overThreshold)
		{
			budgetAlertActive = false;
		}
	}

	private void snapshot(String trigger)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return;
		}

		long coins = 0;
		List<InventoryItemEntry> items = new ArrayList<>();
		for (Item item : inventory.getItems())
		{
			// Empty slots are padded with a sentinel (id <= 0 and/or
			// quantity 0 depending on RuneLite version) - skip both cases
			// rather than assuming one specific sentinel value.
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			if (item.getId() == ItemID.COINS)
			{
				coins += item.getQuantity();
				continue;
			}
			items.add(new InventoryItemEntry(
				item.getId(),
				itemManager.getItemComposition(item.getId()).getName(),
				item.getQuantity()
			));
		}

		lastCoins = coins;

		InventorySnapshot snapshot = new InventorySnapshot();
		snapshot.timestamp = System.currentTimeMillis();
		snapshot.trigger = trigger;
		snapshot.coins = coins;
		snapshot.items = items;
		inventoryLogger.log(snapshot);

		checkInventoryBudget();
	}
}
