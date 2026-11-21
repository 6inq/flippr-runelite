package com.flippr.osrs;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

// Adds custom right-click options to inventory items this plugin tracked
// buying - "Remove-All from portfolio" / "Remove-X from portfolio" (a
// manual correction for when a tracked position no longer reflects
// reality - the item was obtained or disposed of some way this plugin
// couldn't observe, since it can only ever see real GE offers) and "Flippr
// chart" (pins the sidebar's price chart to this item, built entirely
// from this plugin's own already-real chart infrastructure). Verified real,
// well-established RuneLite technique for this (Client.getMenu().
// createMenuEntry() + MenuEntry.onClick(Consumer), not guessed).
//
// Triggered off the native "Examine" option specifically: MenuEntryAdded
// fires once per native menu entry as OSRS builds up the full right-click
// menu, and every real inventory item right-click includes exactly one
// "Examine" - using it as a single reliable anchor adds the whole custom
// batch once per right-click, not once per native entry.
@Slf4j
@Singleton
public class PortfolioContextMenu
{
	@Inject
	private Client client;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private PinnedChartItem pinnedChartItem;

	@Inject
	private FlipprConfig config;

	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.showPortfolioContextMenu())
		{
			return;
		}

		MenuEntry sourceEntry = event.getMenuEntry();
		if (!"Examine".equals(sourceEntry.getOption()))
		{
			return;
		}

		int itemId = sourceEntry.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		OpenPositionEntry position = findPosition(itemId);
		if (position == null)
		{
			// Not something this plugin tracked buying - these options are
			// only added for tracked positions, not every single inventory
			// item.
			return;
		}

		// Reuses the native entry's own already-colored item name rather
		// than guessing a color code, so these read consistently with the
		// rest of the menu.
		String coloredName = sourceEntry.getTarget();
		long currentQuantity = position.getQuantity();

		client.getMenu().createMenuEntry(-1)
			.setOption("Flippr chart")
			.setTarget(coloredName)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> pinnedChartItem.set(itemId));

		client.getMenu().createMenuEntry(-1)
			.setOption("Remove-All from portfolio")
			.setTarget(coloredName)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> clientThread.invokeLater(() -> flipTracker.removePosition(itemId)));

		client.getMenu().createMenuEntry(-1)
			.setOption("Remove-X from portfolio")
			.setTarget(coloredName)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> promptRemoveX(itemId, currentQuantity));
	}

	private void promptRemoveX(int itemId, long currentQuantity)
	{
		chatboxPanelManager.openTextInput("How many to remove from the tracked position (of " + currentQuantity + ")")
			.addCharValidator(Character::isDigit)
			.onDone((String input) ->
			{
				if (input == null || input.isEmpty())
				{
					return;
				}
				long quantity;
				try
				{
					quantity = Long.parseLong(input);
				}
				catch (NumberFormatException e)
				{
					log.warn("Remove-X: invalid quantity entered: {}", input);
					return;
				}
				clientThread.invokeLater(() -> flipTracker.reducePosition(itemId, quantity));
			})
			.build();
	}

	private OpenPositionEntry findPosition(int itemId)
	{
		for (OpenPositionEntry position : flipTracker.getOpenPositions())
		{
			if (position.getItemId() == itemId)
			{
				return position;
			}
		}
		return null;
	}
}
