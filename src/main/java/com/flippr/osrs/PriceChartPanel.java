package com.flippr.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

// Hand-painted line chart (no new chart-library dependency, same "own the
// implementation" approach as SlotTimerOverlay/OfferSetupOverlay elsewhere
// in this plugin) plotting real recent price history from
// PriceFeed.getTimeseries() - the instant-buy line (avgHighPrice, what a
// flipper targets for a SELL) and instant-sell line (avgLowPrice, what a
// flipper targets for a BUY), same field semantics PriceFeed's own header
// comment already documents. A real glance-at-the-trend chart, not a
// placeholder sparkline drawn from guessed numbers.
public class PriceChartPanel extends JPanel
{
	private static final int HEIGHT = 70;
	private static final Color BUY_LINE_COLOR = new Color(100, 200, 255);
	private static final Color SELL_LINE_COLOR = new Color(255, 200, 0);

	private List<PriceFeed.TimeseriesPoint> points = List.of();

	public PriceChartPanel()
	{
		setPreferredSize(new Dimension(1, HEIGHT));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
	}

	public void setPoints(List<PriceFeed.TimeseriesPoint> points)
	{
		this.points = points == null ? List.of() : points;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);

		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int width = getWidth();
		int height = getHeight();

		if (points.size() < 2)
		{
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.setFont(FontManager.getRunescapeSmallFont());
			String message = "No price history yet";
			int textWidth = g2.getFontMetrics().stringWidth(message);
			g2.drawString(message, Math.max(0, (width - textWidth) / 2), height / 2);
			return;
		}

		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (PriceFeed.TimeseriesPoint point : points)
		{
			if (point.avgHighPrice != null)
			{
				min = Math.min(min, point.avgHighPrice);
				max = Math.max(max, point.avgHighPrice);
			}
			if (point.avgLowPrice != null)
			{
				min = Math.min(min, point.avgLowPrice);
				max = Math.max(max, point.avgLowPrice);
			}
		}
		if (min == Long.MAX_VALUE)
		{
			return;
		}
		// A perfectly flat price (min == max) would divide by zero below -
		// widen the range by 1 so a flat line still renders centered rather
		// than crashing or being skipped.
		long range = Math.max(1, max - min);

		int padTop = 4;
		int padBottom = 16;
		int plotHeight = height - padTop - padBottom;

		drawLine(g2, points, true, width, padTop, plotHeight, min, range, BUY_LINE_COLOR);
		drawLine(g2, points, false, width, padTop, plotHeight, min, range, SELL_LINE_COLOR);

		g2.setFont(FontManager.getRunescapeSmallFont());
		g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g2.drawString(QuantityFormatter.formatNumber(max), 2, padTop + 8);
		g2.drawString(QuantityFormatter.formatNumber(min), 2, height - padBottom + 4);
	}

	private void drawLine(Graphics2D g2, List<PriceFeed.TimeseriesPoint> points, boolean useHigh,
		int width, int padTop, int plotHeight, long min, long range, Color color)
	{
		g2.setColor(color);
		Integer prevX = null;
		Integer prevY = null;
		for (int i = 0; i < points.size(); i++)
		{
			Long value = useHigh ? points.get(i).avgHighPrice : points.get(i).avgLowPrice;
			if (value == null)
			{
				prevX = null;
				prevY = null;
				continue;
			}
			int x = points.size() <= 1 ? 0 : (int) ((long) i * width / (points.size() - 1));
			int y = padTop + plotHeight - (int) ((value - min) * plotHeight / range);
			if (prevX != null)
			{
				g2.drawLine(prevX, prevY, x, y);
			}
			prevX = x;
			prevY = y;
		}
	}
}
