package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

// The Prices tab, as in the RS3 Alt1 app: item search with
// autocomplete over the wiki catalogue (PriceFeed's /mapping), the item's
// instant buy / sell prices (/latest), its margin after the OSRS GE tax
// (GeTax: 2%, nothing under 50gp, 5M cap), buy limit, traded volume and a
// 24h / 7d chart (/timeseries: 5m buckets for 24h, 1h for 7 days). Opening
// an item's GE offer, or "Flippr chart" on an inventory item, shows it
// here too. Wiki data only, so it works without pairing. EDT only.
public class PricesView extends JPanel
{
	private static final int SUGGESTIONS = 8;
	// 5m buckets in 24h, 1h buckets in 7 days.
	private static final int DAY_BUCKETS_5M = 288;
	private static final int WEEK_BUCKETS_1H = 168;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FlipprConfig config;

	private final JTextField search = new JTextField();
	private final JPanel suggestions = Ui.column(Ui.BOX);
	private final JPanel detail = Ui.column(Ui.BOX);
	private final PriceChartPanel chart = new PriceChartPanel();
	private final JButton dayButton = Ui.button("24h");
	private final JButton weekButton = Ui.button("7d");
	private int itemId = -1;
	private boolean week;
	private int lastGeItem = -1;
	private int lastPinned = -1;
	private String builtFor;

	public PricesView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel top = Ui.column(Ui.BOX);
		top.setOpaque(false);
		search.setFont(FontManager.getRunescapeSmallFont());
		search.setToolTipText("Search an item by name (2+ letters)");
		top.add(Ui.small("Search items", Ui.MUTED));
		top.add(Ui.fitHeight(search));
		suggestions.setOpaque(false);
		top.add(suggestions);
		top.add(Ui.gap(6));
		add(top, BorderLayout.NORTH);
		detail.setOpaque(false);
		add(detail, BorderLayout.CENTER);

		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateSuggestions();
			}
		});
		search.addActionListener(e ->
		{
			List<PriceFeed.MappingEntry> hits = priceFeed.searchItems(search.getText(), 1);
			if (!hits.isEmpty())
			{
				select(hits.get(0).id);
			}
		});
		dayButton.addActionListener(e ->
		{
			week = false;
			builtFor = null;
		});
		weekButton.addActionListener(e ->
		{
			week = true;
			builtFor = null;
		});
		chart.setPreferredSize(new Dimension(1, 110));
	}

	private void updateSuggestions()
	{
		suggestions.removeAll();
		String q = search.getText();
		if (q.trim().length() >= 2)
		{
			if (!priceFeed.hasCatalog())
			{
				suggestions.add(Ui.small("Loading the item list...", Ui.MUTED));
			}
			List<PriceFeed.MappingEntry> hits = priceFeed.searchItems(q, SUGGESTIONS);
			if (hits.isEmpty() && priceFeed.hasCatalog())
			{
				suggestions.add(Ui.small("No item matches \"" + q.trim() + "\"", Ui.MUTED));
			}
			for (PriceFeed.MappingEntry hit : hits)
			{
				JButton b = Ui.button(hit.name + (hit.members ? " (P2P)" : ""));
				b.setHorizontalAlignment(JButton.LEFT);
				b.setAlignmentX(Component.LEFT_ALIGNMENT);
				b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
				b.addActionListener(e -> select(hit.id));
				suggestions.add(b);
			}
		}
		suggestions.revalidate();
		suggestions.repaint();
	}

	private void select(int id)
	{
		itemId = id;
		builtFor = null;
		search.setText("");
	}

	// Called while the tab is showing. geItemId: the item of the GE offer
	// screen that's open (0 when none); pinned: "Flippr chart" item (0 none).
	void refresh(int geItemId, int pinned)
	{
		priceFeed.maybePoll();
		priceFeed.maybeFetchMapping();
		if (geItemId > 0 && geItemId != lastGeItem)
		{
			itemId = geItemId;
		}
		lastGeItem = geItemId;
		if (pinned > 0 && pinned != lastPinned)
		{
			itemId = pinned;
		}
		lastPinned = pinned;
		if (itemId <= 0)
		{
			if (!"none".equals(builtFor))
			{
				builtFor = "none";
				detail.removeAll();
				JPanel card = Ui.card();
				card.add(Ui.wrapped("Search an item above, open an item's offer in the GE, or right-click an item "
					+ "you flipped and pick \"Flippr chart\".", Ui.MUTED));
				detail.add(card);
				detail.revalidate();
				detail.repaint();
			}
			return;
		}
		priceFeed.maybeFetchTimeseries(itemId, "5m");
		if (week)
		{
			priceFeed.maybeFetchTimeseries(itemId, "1h");
		}
		PriceFeed.PricePoint latest = priceFeed.getLatest(itemId);
		List<PriceFeed.TimeseriesPoint> day = priceFeed.getTimeseries(itemId, "5m");
		List<PriceFeed.TimeseriesPoint> hourly = week ? priceFeed.getTimeseries(itemId, "1h") : day;
		String key = itemId + "|" + week + "|" + (latest == null ? "-" : latest.high + "/" + latest.low + "/" + latest.highTime + "/" + latest.lowTime)
			+ "|" + day.size() + "|" + hourly.size() + "|" + System.currentTimeMillis() / 60_000;
		if (key.equals(builtFor))
		{
			return;
		}
		builtFor = key;
		render(latest, day, hourly);
	}

	private void render(PriceFeed.PricePoint latest, List<PriceFeed.TimeseriesPoint> day, List<PriceFeed.TimeseriesPoint> hourly)
	{
		detail.removeAll();
		PriceFeed.MappingEntry item = priceFeed.getItem(itemId);
		String name = item != null ? item.name : "Item " + itemId;

		JPanel card = Ui.card();
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		itemManager.getImage(itemId).addTo(icon);
		head.add(icon, BorderLayout.WEST);
		JPanel title = Ui.column(Ui.CARD);
		title.setOpaque(false);
		title.add(Ui.bold(name, Ui.TEXT));
		title.add(Ui.small(item != null && item.members ? "Members (P2P)" : item != null ? "Free-to-play" : "", Ui.MUTED));
		head.add(title, BorderLayout.CENTER);
		card.add(Ui.fitHeight(head));
		card.add(Ui.gap(4));

		long nowSec = System.currentTimeMillis() / 1000;
		Long high = latest != null && latest.high != null && latest.high > 0 ? latest.high : null;
		Long low = latest != null && latest.low != null && latest.low > 0 ? latest.low : null;
		Long margin = GeTax.margin(name, high, low);
		long tax = high == null ? 0 : GeTax.taxPerUnit(name, high);
		Integer limit = priceFeed.getBuyLimit(itemId);
		Long volume = PriceFeed.volume(day, DAY_BUCKETS_5M);
		Color marginColor = margin == null ? Ui.MUTED : margin > 0 ? config.profitColor() : margin < 0 ? config.lossColor() : Ui.MUTED;
		card.add(Ui.grid(2,
			Ui.stat("Instant buy", high == null ? "-" : PlanText.exactGp(high), Ui.TEXT, ago(latest == null ? null : latest.highTime, nowSec)),
			Ui.stat("Instant sell", low == null ? "-" : PlanText.exactGp(low), Ui.TEXT, ago(latest == null ? null : latest.lowTime, nowSec)),
			Ui.stat("Margin", margin == null ? "-" : PlanText.signedGp(margin), marginColor, "after " + PlanText.exactGp(tax) + " tax"),
			Ui.stat("Buy limit", limit == null ? "-" : PlanText.exactGp(limit), Ui.TEXT, "per 4h"),
			Ui.stat("Volume (24h)", volume == null ? "-" : PlanText.compactGp(volume), Ui.TEXT, "items traded"),
			Ui.stat("ROI", margin == null || low == null ? "-" : String.format(java.util.Locale.US, "%.2f%%", margin * 100.0 / low), marginColor, "per flip")));
		card.add(Ui.gap(6));

		JPanel range = new JPanel(new GridLayout(1, 2, 3, 0));
		range.setOpaque(false);
		range.setAlignmentX(Component.LEFT_ALIGNMENT);
		dayButton.setEnabled(week);
		weekButton.setEnabled(!week);
		range.add(dayButton);
		range.add(weekButton);
		card.add(Ui.fitHeight(range));
		card.add(Ui.gap(4));
		List<PriceFeed.TimeseriesPoint> points = week ? hourly : day;
		int keep = week ? WEEK_BUCKETS_1H : DAY_BUCKETS_5M;
		chart.setPoints(points.subList(Math.max(0, points.size() - keep), points.size()));
		chart.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(chart);
		card.add(Ui.small("Blue: instant buy · Yellow: instant sell", Ui.MUTED));
		detail.add(card);
		detail.revalidate();
		detail.repaint();
	}

	private static String ago(Long tsSec, long nowSec)
	{
		return tsSec == null || tsSec <= 0 ? null : TrackerView.timeAgo(tsSec * 1000, nowSec * 1000);
	}
}
