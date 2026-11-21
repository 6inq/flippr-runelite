package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.Instant;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

// The Tracker tab's website part (GET /api/v1/osrs/tracker): realised
// profit today / 7 days / 30 days / all time, the best items and the
// finished flips of the chosen range, paged, as in the RS3 Alt1 app.
// Fetched when the tab opens, on a range or
// page change and every REFRESH_MS while it is showing. EDT only.
public class TrackerView extends JPanel
{
	static final long REFRESH_MS = 60_000;

	@Inject
	private WebsiteViews views;

	@Inject
	private FlipprConfig config;

	@Inject
	private ItemManager itemManager;

	private final JPanel body = Ui.column(Ui.BOX);
	private String range = "30d";
	private int page = 1;
	private WebsiteViews.Tracker data;
	private String error;
	private boolean loading;
	private long fetchedAt;

	public TrackerView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		body.setOpaque(false);
		add(body, BorderLayout.CENTER);
	}

	// Called while the tab is showing (about once a second).
	void refresh()
	{
		if (!loading && System.currentTimeMillis() - fetchedAt >= REFRESH_MS)
		{
			load();
		}
	}

	void load()
	{
		loading = true;
		fetchedAt = System.currentTimeMillis();
		if (data == null)
		{
			render();
		}
		String r = range;
		int pg = page;
		views.fetchTracker(r, pg, result -> SwingUtilities.invokeLater(() ->
		{
			loading = false;
			if (!r.equals(range) || pg != page)
			{
				return;
			}
			error = result.error;
			if (result.value != null)
			{
				data = result.value;
			}
			render();
		}));
	}

	private void render()
	{
		body.removeAll();
		if (data == null)
		{
			JPanel card = Ui.card();
			card.add(Ui.wrapped(error != null ? error : "Loading your Tracker...", Ui.MUTED));
			body.add(card);
			finish();
			return;
		}
		if (error != null)
		{
			body.add(Ui.wrapped(error + " (showing the last data)", config.lossColor()));
			body.add(Ui.gap(4));
		}

		Component[] tiles = new Component[WebsiteViews.RANGES.length];
		for (int i = 0; i < tiles.length; i++)
		{
			WebsiteViews.Summary t = data.totals == null ? null : data.totals.get(WebsiteViews.RANGES[i]);
			double profit = t == null ? 0 : t.profit;
			int flips = t == null ? 0 : t.flips;
			Double roi = t == null ? null : t.roi;
			tiles[i] = Ui.stat(WebsiteViews.RANGE_LABELS[i], PlanText.signedGp(profit), colorOf(profit),
				PlanText.exactGp(flips) + " flips · ROI " + pct(roi));
		}
		body.add(Ui.grid(2, tiles));
		body.add(Ui.gap(4));

		JPanel ranges = new JPanel(new GridLayout(1, 4, 2, 0));
		ranges.setOpaque(false);
		ranges.setAlignmentX(Component.LEFT_ALIGNMENT);
		String[] shortLabels = {"Today", "7d", "30d", "All"};
		for (int i = 0; i < WebsiteViews.RANGES.length; i++)
		{
			String id = WebsiteViews.RANGES[i];
			JButton b = Ui.button(shortLabels[i]);
			b.setEnabled(!id.equals(data.range));
			b.setToolTipText("Best items and flips for " + WebsiteViews.RANGE_LABELS[i].toLowerCase());
			b.addActionListener(e ->
			{
				range = id;
				page = 1;
				load();
			});
			ranges.add(b);
		}
		body.add(Ui.fitHeight(ranges));

		body.add(Ui.header("Best items"));
		JPanel best = Ui.card();
		if (data.best.isEmpty())
		{
			best.add(Ui.small("No profitable items in this range.", Ui.MUTED));
		}
		int rank = 1;
		for (WebsiteViews.BestItem b : data.best)
		{
			JLabel name = Ui.small((rank++) + ". " + b.itemName, Ui.TEXT);
			JLabel profit = Ui.small(PlanText.signedGp(b.profit), colorOf(b.profit));
			name.setToolTipText(b.flips + " flips · " + PlanText.exactGp(b.quantity) + " items");
			best.add(Ui.fitHeight(Ui.line(name, profit)));
		}
		body.add(best);

		body.add(Ui.header("Flips (" + PlanText.exactGp(data.total) + ")"));
		if (data.flips.isEmpty())
		{
			JPanel card = Ui.card();
			card.add(Ui.small("No finished flips in this range.", Ui.MUTED));
			body.add(card);
		}
		long now = System.currentTimeMillis();
		for (WebsiteViews.TrackerFlip f : data.flips)
		{
			body.add(flipRow(f, now));
			body.add(Ui.gap(2));
		}
		JPanel pager = pager(data.page, data.pages, p ->
		{
			page = p;
			load();
		});
		if (pager != null)
		{
			body.add(pager);
		}
		finish();
	}

	private JPanel flipRow(WebsiteViews.TrackerFlip f, long now)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(Ui.CARD);
		row.setBorder(new EmptyBorder(3, 4, 3, 4));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel icon = new JLabel();
		itemManager.getImage(f.itemId).addTo(icon);
		row.add(icon, BorderLayout.WEST);
		JPanel text = Ui.column(Ui.CARD);
		text.setOpaque(false);
		text.add(Ui.fitHeight(Ui.line(Ui.small(f.itemName, Ui.TEXT), Ui.small(PlanText.signedGp(f.profit), colorOf(f.profit)))));
		text.add(Ui.small(PlanText.exactGp(f.quantity) + " x " + PlanText.compactGp(f.buyPrice) + " -> " + PlanText.compactGp(f.sellPrice)
			+ " · " + pct(f.roi), Ui.MUTED));
		text.add(Ui.small("took " + duration(f.durationMs) + " · " + agoIso(f.soldAt, now), Ui.MUTED));
		row.add(text, BorderLayout.CENTER);
		row.setToolTipText("Bought at " + PlanText.exactGp(f.buyPrice) + ", sold at " + PlanText.exactGp(f.sellPrice)
			+ ", tax " + PlanText.exactGp(f.tax));
		return Ui.fitHeight(row);
	}

	private Color colorOf(double profit)
	{
		return profit > 0 ? config.profitColor() : profit < 0 ? config.lossColor() : Ui.MUTED;
	}

	static String pct(Double f)
	{
		return f == null ? "-" : String.format(java.util.Locale.US, "%.1f%%", f * 100);
	}

	// 45m / 3.5h / 2.1d.
	static String duration(long ms)
	{
		if (ms < 0)
		{
			return "-";
		}
		double m = ms / 60_000.0;
		if (m < 60)
		{
			return Math.max(1, Math.round(m)) + "m";
		}
		double h = m / 60;
		return h < 48 ? (Math.round(h * 10) / 10.0) + "h" : (Math.round(h / 24 * 10) / 10.0) + "d";
	}

	// just now / 5m ago / 3h ago / 2d ago.
	static String timeAgo(long ts, long now)
	{
		long d = now - ts;
		if (d < 60_000)
		{
			return "just now";
		}
		if (d < 3_600_000)
		{
			return Math.round(d / 60_000.0) + "m ago";
		}
		if (d < 86_400_000)
		{
			return Math.round(d / 3_600_000.0) + "h ago";
		}
		return Math.round(d / 86_400_000.0) + "d ago";
	}

	private static String agoIso(String iso, long now)
	{
		try
		{
			return timeAgo(Instant.parse(iso).toEpochMilli(), now);
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	// "‹ Prev · Page 2 of 5 · Next ›", or null for a single page.
	static JPanel pager(int page, int pages, java.util.function.IntConsumer go)
	{
		if (pages <= 1)
		{
			return null;
		}
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setBorder(new EmptyBorder(4, 0, 0, 0));
		JButton prev = Ui.button("< Prev");
		prev.setEnabled(page > 1);
		prev.addActionListener(e -> go.accept(page - 1));
		JButton next = Ui.button("Next >");
		next.setEnabled(page < pages);
		next.addActionListener(e -> go.accept(page + 1));
		JLabel label = Ui.small("Page " + page + " of " + pages, Ui.MUTED);
		label.setHorizontalAlignment(JLabel.CENTER);
		p.add(prev, BorderLayout.WEST);
		p.add(label, BorderLayout.CENTER);
		p.add(next, BorderLayout.EAST);
		return Ui.fitHeight(p);
	}

	private void finish()
	{
		body.revalidate();
		body.repaint();
	}
}
