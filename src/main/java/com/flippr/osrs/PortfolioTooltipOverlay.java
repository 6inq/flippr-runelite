package com.flippr.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

// Hovering an inventory item this plugin tracked buying shows a real,
// live-priced readout of that position (inventory count, time held, avg
// buy price, total value, unrealized profit, unrealized ROI), built
// entirely from this plugin's own already-tracked data (FlipTracker's open
// positions, InventoryTracker's real item counts, PortfolioMath's price
// logic).
//
// Deliberately GROSS, not tax-adjusted - see PortfolioMath's own comment:
// "Unrealized Profit" here is a clean price-difference with no tax
// term, a real, deliberate distinction from a specific active GE offer's
// "if this fills, you'll walk away with X after tax" figure. This is a
// mark-to-market READOUT of stock, not a promise about what an actual sale
// would net.
//
// Purely informational - reads widget/inventory/price state, never
// modifies anything - so this stays on even during Watch Mode, unlike the
// UI-touching classes Watch Mode gates (SearchSuggestionInjector,
// QuickFillHandler, etc.): "watching the portfolio" is explicitly the one
// thing Watch Mode is supposed to keep doing.
public class PortfolioTooltipOverlay extends Overlay
{
	// Own distinct blue, matching SearchSuggestionInjector's own marker
	// color (<col=40a0ff>) for a consistent "this is Flippr" visual
	// identity across features.
	private static final Color BADGE_COLOR = new Color(0x40, 0xa0, 0xff);
	private static final int BADGE_DIAMETER = 8;

	@Inject
	private Client client;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private InventoryTracker inventoryTracker;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private FlipprConfig config;

	@Inject
	private TooltipManager tooltipManager;

	@Inject
	public PortfolioTooltipOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showPortfolioTooltip())
		{
			return null;
		}

		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden())
		{
			return null;
		}

		Widget[] children = inventory.getChildren();
		if (children == null)
		{
			return null;
		}

		Set<Integer> trackedItemIds = new HashSet<>();
		for (OpenPositionEntry position : flipTracker.getOpenPositions())
		{
			trackedItemIds.add(position.getItemId());
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Point mouse = client.getMouseCanvasPosition();
		for (Widget child : children)
		{
			if (child == null || child.isHidden() || child.getItemId() <= 0)
			{
				continue;
			}

			// A persistent, always-visible marker on every tracked item -
			// distinguishes "Flippr bought this and can sell it" from "the
			// player pulled this from the bank manually" - not the
			// hover-only tooltip below, which only shows on mouseover.
			if (trackedItemIds.contains(child.getItemId()))
			{
				drawBadge(graphics, child.getBounds());
			}

			if (mouse != null && child.getBounds().contains(mouse.getX(), mouse.getY()))
			{
				showTooltipFor(child.getItemId());
			}
		}

		return null;
	}

	private static void drawBadge(Graphics2D graphics, Rectangle bounds)
	{
		int x = bounds.x + bounds.width - BADGE_DIAMETER - 1;
		int y = bounds.y + 1;
		graphics.setColor(BADGE_COLOR);
		graphics.fillOval(x, y, BADGE_DIAMETER, BADGE_DIAMETER);
		graphics.setColor(Color.WHITE);
		graphics.drawOval(x, y, BADGE_DIAMETER, BADGE_DIAMETER);
	}

	private void showTooltipFor(int itemId)
	{
		OpenPositionEntry position = findPosition(itemId);
		if (position == null)
		{
			// Not something this plugin tracked buying - this only shows
			// for tracked positions, not every single inventory item.
			return;
		}

		int inventoryCount = inventoryTracker.getItemCount(itemId);
		if (inventoryCount <= 0)
		{
			return;
		}

		StringBuilder text = new StringBuilder();
		text.append("Flippr position</br>");
		text.append(escapeHtml(position.getItemName())).append("</br>");
		text.append("Inventory: ").append(inventoryCount).append("</br>");
		text.append("Time held: ").append(formatDuration(System.currentTimeMillis() - position.getFirstBuyTime())).append("</br>");
		text.append("Avg buy price: ").append(QuantityFormatter.formatNumber(position.getAvgBuyPrice())).append(" gp");

		Long marketPrice = PortfolioMath.getMarketPrice(decisionEngine, priceFeed, itemId);
		if (marketPrice != null)
		{
			long costBasis = position.getAvgBuyPrice() * inventoryCount;
			long totalValue = marketPrice * inventoryCount;
			long unrealizedProfit = totalValue - costBasis;
			double roiPercent = costBasis == 0 ? 0 : (unrealizedProfit * 100.0) / costBasis;
			Color profitColor = unrealizedProfit >= 0 ? config.profitColor() : config.lossColor();
			String hex = toHex(profitColor);

			text.append("</br>Total value: ").append(QuantityFormatter.formatNumber(totalValue)).append(" gp");
			text.append("</br><col=").append(hex).append(">Unrealized Profit: ")
				.append(unrealizedProfit >= 0 ? "+" : "-")
				.append(QuantityFormatter.formatNumber(Math.abs(unrealizedProfit))).append(" gp</col>");
			text.append("</br><col=").append(hex).append(">Unrealized ROI: ")
				.append(unrealizedProfit >= 0 ? "+" : "-")
				.append(String.format("%.2f", Math.abs(roiPercent))).append("%</col>");
		}
		else
		{
			text.append("</br>No live price data for this item yet");
		}

		tooltipManager.add(new Tooltip(text.toString()));
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

	private static String formatDuration(long ms)
	{
		long totalMinutes = ms / 60_000;
		if (totalMinutes < 1)
		{
			return "<1m";
		}
		long hours = totalMinutes / 60;
		long minutes = totalMinutes % 60;
		return hours > 0 ? String.format("%dh%dm", hours, minutes) : String.format("%dm", minutes);
	}

	private static String toHex(Color color)
	{
		return String.format("%06x", color.getRGB() & 0xFFFFFF);
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
