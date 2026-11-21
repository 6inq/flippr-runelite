package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;

// The History tab's website part (GET /api/v1/osrs/history): the
// character's trade log, newest first - GE fills, finished flips, price
// changes, positions stopped / done - filtered by side, item and dates,
// paged, as in the RS3 Alt1 app. EDT only.
public class HistoryView extends JPanel
{
	static final long REFRESH_MS = 60_000;
	private static final String[] SIDES = {"All", "Buy", "Sell"};

	@Inject
	private WebsiteViews views;

	@Inject
	private FlipprConfig config;

	private final JComboBox<String> sideBox = new JComboBox<>(SIDES);
	private final JTextField itemField = new JTextField();
	private final JTextField fromField = new JTextField();
	private final JTextField toField = new JTextField();
	private final JPanel results = Ui.column(Ui.BOX);
	private WebsiteViews.HistoryFilter filter = new WebsiteViews.HistoryFilter("all", "", "", "");
	private int page = 1;
	private WebsiteViews.History data;
	private String error;
	private boolean loading;
	private long fetchedAt;

	public HistoryView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel filters = Ui.card();
		for (Component c : new Component[]{sideBox, itemField, fromField, toField})
		{
			c.setFont(FontManager.getRunescapeSmallFont());
		}
		itemField.setToolTipText("Item name contains...");
		fromField.setToolTipText("From date, YYYY-MM-DD (empty: any)");
		toField.setToolTipText("To date, YYYY-MM-DD (empty: any)");
		filters.add(Ui.fitHeight(Ui.line(Ui.small("Side", Ui.MUTED), sideBox)));
		filters.add(Ui.gap(2));
		filters.add(Ui.small("Item", Ui.MUTED));
		filters.add(Ui.fitHeight(itemField));
		JPanel dates = new JPanel(new GridLayout(2, 2, 3, 1));
		dates.setOpaque(false);
		dates.setAlignmentX(Component.LEFT_ALIGNMENT);
		dates.add(Ui.small("From (YYYY-MM-DD)", Ui.MUTED));
		dates.add(Ui.small("To", Ui.MUTED));
		dates.add(fromField);
		dates.add(toField);
		filters.add(Ui.gap(2));
		filters.add(Ui.fitHeight(dates));
		filters.add(Ui.gap(4));
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton apply = Ui.button("Apply");
		apply.addActionListener(e -> applyFilters());
		JButton clear = Ui.button("Clear");
		clear.addActionListener(e ->
		{
			sideBox.setSelectedIndex(0);
			itemField.setText("");
			fromField.setText("");
			toField.setText("");
			applyFilters();
		});
		buttons.add(apply);
		buttons.add(clear);
		filters.add(Ui.fitHeight(buttons));
		itemField.addActionListener(e -> applyFilters());
		fromField.addActionListener(e -> applyFilters());
		toField.addActionListener(e -> applyFilters());

		JPanel top = Ui.column(Ui.BOX);
		top.setOpaque(false);
		top.add(filters);
		top.add(Ui.gap(6));
		add(top, BorderLayout.NORTH);
		results.setOpaque(false);
		add(results, BorderLayout.CENTER);
	}

	private void applyFilters()
	{
		String side = ((String) sideBox.getSelectedItem());
		filter = new WebsiteViews.HistoryFilter(side == null ? "all" : side.toLowerCase(), itemField.getText(),
			fromField.getText(), toField.getText());
		page = 1;
		load();
	}

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
		WebsiteViews.HistoryFilter f = filter;
		int pg = page;
		views.fetchHistory(f, pg, result -> SwingUtilities.invokeLater(() ->
		{
			loading = false;
			if (f != filter || pg != page)
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
		results.removeAll();
		if (data == null)
		{
			JPanel card = Ui.card();
			card.add(Ui.wrapped(error != null ? error : "Loading your history...", Ui.MUTED));
			results.add(card);
			finish();
			return;
		}
		if (error != null)
		{
			results.add(Ui.wrapped(error, config.lossColor()));
			results.add(Ui.gap(4));
		}
		if (data.events.isEmpty())
		{
			JPanel card = Ui.card();
			card.add(Ui.small(data.total == 0 && isUnfiltered() ? "No history yet." : "Nothing matches these filters.", Ui.MUTED));
			results.add(card);
		}
		long now = System.currentTimeMillis();
		SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		for (WebsiteViews.HistoryEvent e : data.events)
		{
			results.add(eventRow(e, now, full));
			results.add(Ui.gap(2));
		}
		results.add(Ui.small(PlanText.exactGp(data.total) + " events", Ui.MUTED));
		JPanel pager = TrackerView.pager(data.page, data.pages, p ->
		{
			page = p;
			load();
		});
		if (pager != null)
		{
			results.add(pager);
		}
		finish();
	}

	private boolean isUnfiltered()
	{
		return "all".equals(filter.side) && filter.q.trim().isEmpty() && filter.from.trim().isEmpty() && filter.to.trim().isEmpty();
	}

	// The event's "what" column.
	static String whatText(WebsiteViews.HistoryEvent e)
	{
		if ("price".equals(e.kind))
		{
			return (e.previousPrice == null ? "-" : PlanText.exactGp(e.previousPrice)) + " -> " + (e.price == null ? "-" : PlanText.exactGp(e.price));
		}
		if ("stopped".equals(e.kind) || "done".equals(e.kind))
		{
			return e.qty != null && e.qty > 0 ? PlanText.exactGp(e.qty) + " sold" : "";
		}
		String qty = e.qty == null ? "-" : PlanText.exactGp(e.qty);
		String price = e.price == null ? "?" : PlanText.exactGp(e.price);
		return qty + " x " + price + (e.gp != null ? " = " + PlanText.compactGp(e.gp) : "");
	}

	static String kindLabel(String kind)
	{
		if (kind == null)
		{
			return "";
		}
		switch (kind)
		{
			case "fill":
				return "Fill";
			case "flip":
				return "Flip";
			case "import":
				return "Imported";
			case "price":
				return "Price change";
			case "stopped":
				return "Stopped";
			case "done":
				return "Done";
			default:
				return kind;
		}
	}

	private JPanel eventRow(WebsiteViews.HistoryEvent e, long now, SimpleDateFormat full)
	{
		JPanel row = Ui.column(Ui.CARD);
		row.setBorder(new EmptyBorder(3, 5, 3, 5));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		String side = e.side == null ? "" : " " + ("buy".equals(e.side) ? "Buy" : "Sell");
		JLabel when = Ui.small(TrackerView.timeAgo(e.ts, now), Ui.MUTED);
		when.setToolTipText(full.format(new Date(e.ts)));
		Color sideColor = "buy".equals(e.side) ? new Color(100, 200, 255) : "sell".equals(e.side) ? Ui.ACCENT : Ui.MUTED;
		row.add(Ui.fitHeight(Ui.line(Ui.small(kindLabel(e.kind) + side, sideColor), when)));
		row.add(Ui.small(e.itemName, Ui.TEXT));
		JLabel profit = e.profit == null ? null : Ui.small(PlanText.signedGp(e.profit),
			e.profit > 0 ? config.profitColor() : e.profit < 0 ? config.lossColor() : Ui.MUTED);
		row.add(Ui.fitHeight(Ui.line(Ui.small(whatText(e), Ui.MUTED), profit)));
		row.setToolTipText(full.format(new Date(e.ts)));
		return Ui.fitHeight(row);
	}

	private void finish()
	{
		results.revalidate();
		results.repaint();
	}
}
