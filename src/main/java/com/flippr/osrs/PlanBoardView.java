package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

// The Plan tab's board: the website's summary strip and one card per open
// position (as on the website and the RS3 Alt1 app), in GE slot order,
// plus free slots. "Cards" is the
// detailed card, "Compact" one line per position (details in the tooltip,
// actions on right-click). Rebuilt only when what it shows changes. EDT
// only.
public class PlanBoardView extends JPanel
{
	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private ListingReporter listingReporter;

	@Inject
	private CancelledOfferWatcher cancelledOffers;

	@Inject
	private WebsiteConnection website;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FlipprConfig config;

	@Inject
	private ConfigManager configManager;

	private final JComboBox<PlanLayout> layoutBox = new JComboBox<>(PlanLayout.values());
	private final JPanel body = Ui.column(Ui.BOX);
	private String builtFor;
	// Positions with a stop request on the way (button shows "Working...").
	private final java.util.Set<String> stopping = new java.util.HashSet<>();

	public PlanBoardView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		layoutBox.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		layoutBox.setToolTipText("Plan layout: detailed cards, or one line per position");
		layoutBox.setPreferredSize(new Dimension(80, 20));
		layoutBox.addActionListener(e ->
		{
			PlanLayout picked = (PlanLayout) layoutBox.getSelectedItem();
			if (picked != null && config != null && picked != config.planLayout())
			{
				configManager.setConfiguration("flippr", "planLayout", picked);
			}
		});
		body.setOpaque(false);
		add(body, BorderLayout.CENTER);
	}

	// Called every panel refresh (about once a second).
	void refresh()
	{
		PlanLayout layout = config.planLayout();
		if (layoutBox.getSelectedItem() != layout)
		{
			layoutBox.setSelectedItem(layout);
		}
		ServerSuggestions.Data data = serverSuggestions.getLastData();
		long now = System.currentTimeMillis();
		String key = signature(data, layout, now);
		if (key.equals(builtFor))
		{
			return;
		}
		builtFor = key;
		rebuild(data, layout, now);
	}

	// Everything the board shows: the fetched data, the layout, the GE slots
	// and the minute (for "updated 4m ago").
	private String signature(ServerSuggestions.Data data, PlanLayout layout, long now)
	{
		StringBuilder sb = new StringBuilder().append(layout).append('|').append(now / 60_000).append('|')
			.append(website.isSyncEnabled()).append('|').append(stopping).append('|').append(serverSuggestions.isSellOnly())
			.append('|').append(cancelledOffers.promptIds());
		if (data != null)
		{
			sb.append(data.fetchedAt).append('|').append(data.hasPlan());
			if (data.hasPlan())
			{
				for (ServerSuggestions.PlanPosition p : data.planPositions)
				{
					sb.append(p.id).append(listingReporter.geSlotsFor(p.id));
				}
			}
		}
		return sb.toString();
	}

	private void rebuild(ServerSuggestions.Data data, PlanLayout layout, long now)
	{
		body.removeAll();
		if (data == null || !data.hasPlan())
		{
			JPanel card = Ui.card();
			if (!website.isSyncEnabled())
			{
				card.add(Ui.wrapped("Pair this plugin with Flippr (Account, below) to see your Plan here - "
					+ "positions, exact prices, alerts and progress, updated live from your GE slots.", Ui.MUTED));
			}
			else if (data == null)
			{
				card.add(Ui.wrapped("Loading your Plan...", Ui.MUTED));
			}
			else
			{
				card.add(Ui.wrapped("No active Plan for this character yet. Set one up in \"Plan setup\" above - it "
					+ "shows up here and the GE highlights what to buy next.", Ui.MUTED));
			}
			body.add(card);
			finish();
			return;
		}

		List<ServerSuggestions.PlanPosition> positions = ordered(data.planPositions);
		body.add(summary(data.planInfo, positions));
		body.add(Ui.gap(4));
		JLabel count = Ui.small(positions.size() + (positions.size() == 1 ? " position" : " positions"), Ui.MUTED);
		body.add(Ui.line(count, layoutBox));
		body.add(Ui.gap(4));
		if (positions.isEmpty() && data.planFreeSlots.isEmpty())
		{
			JPanel card = Ui.card();
			card.add(Ui.wrapped("Nothing open on the Plan right now.", Ui.MUTED));
			body.add(card);
		}
		for (ServerSuggestions.PlanPosition p : positions)
		{
			body.add(layout == PlanLayout.COMPACT ? compactRow(p, now) : card(p, data.planInfo, now));
			body.add(Ui.gap(layout == PlanLayout.COMPACT ? 2 : 6));
		}
		for (ServerSuggestions.FreeSlot f : data.planFreeSlots)
		{
			JPanel card = Ui.card();
			card.add(Ui.bold("Slot " + f.slot + " free", Ui.TEXT));
			card.add(Ui.wrapped(f.reason != null && !f.reason.isEmpty() ? f.reason
				: "Flippr fills it with the next flip when one qualifies.", Ui.MUTED));
			body.add(card);
			body.add(Ui.gap(4));
		}
		finish();
	}

	private void finish()
	{
		body.revalidate();
		body.repaint();
	}

	// GE layout order: positions in a GE slot by that slot, then the rest
	// by Plan slot (the RS3 app's orderPositions).
	private List<ServerSuggestions.PlanPosition> ordered(List<ServerSuggestions.PlanPosition> positions)
	{
		List<ServerSuggestions.PlanPosition> list = new ArrayList<>(positions);
		list.sort(Comparator.comparingInt(p ->
		{
			List<Integer> ge = listingReporter.geSlotsFor(p.id);
			return ge.isEmpty() ? 100 + (p.slot != null ? p.slot : 99) : ge.get(0);
		}));
		return list;
	}

	private JPanel summary(ServerSuggestions.PlanInfo plan, List<ServerSuggestions.PlanPosition> positions)
	{
		PlanText.Summary s = PlanText.summarize(plan, positions);
		Color profit = s.expectedProfit < 0 ? config.lossColor() : config.profitColor();
		String fill = s.avgFillHours == null ? "-" : PlanText.hours(s.avgFillHours) + " / " + PlanText.hours(s.maxFillHours);
		return Ui.grid(2,
			Ui.stat("Deployed", PlanText.compactGp(s.deployed) + " gp", Ui.TEXT, Math.round(s.deployedShare * 100) + "% of capital"),
			Ui.stat("Slots filled", s.slotsFilled + " / " + s.slotsTotal, Ui.TEXT, null),
			Ui.stat("Expected profit", PlanText.signedGp(s.expectedProfit), profit,
				s.realisedProfit != 0 ? PlanText.signedGp(s.realisedProfit) + " realised" : "After GE tax"),
			Ui.stat("Est. fill", fill, Ui.TEXT, "avg / max"));
	}

	private String geText(ServerSuggestions.PlanPosition p)
	{
		List<Integer> ge = listingReporter.geSlotsFor(p.id);
		if (ge.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("GE ");
		for (int i = 0; i < ge.size(); i++)
		{
			sb.append(i == 0 ? "" : ", ").append(ge.get(i));
		}
		return sb.toString();
	}

	private JLabel icon(int itemId, int size)
	{
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(size, size));
		icon.setMinimumSize(new Dimension(size, size));
		itemManager.getImage(itemId).addTo(icon);
		return icon;
	}

	private JPanel card(ServerSuggestions.PlanPosition p, ServerSuggestions.PlanInfo plan, long now)
	{
		JPanel card = Ui.card();
		String alert = PlanText.alertText(p.alertKind, p.suggestedPrice);
		Color edge = alert != null ? Ui.WARN : Ui.statusColor(p.status);
		card.setBorder(new CompoundBorder(new MatteBorder(0, 3, 0, 0, edge), new EmptyBorder(6, 6, 6, 6)));

		// Head: icon, name, "N units · alloc gp · Slot n", status + GE slot.
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.add(icon(p.itemId, 32), BorderLayout.WEST);
		JPanel title = Ui.column(Ui.CARD);
		title.setOpaque(false);
		JLabel name = Ui.bold(p.name, Ui.TEXT);
		name.setToolTipText(p.name);
		title.add(name);
		String details = PlanText.exactGp(p.targetQty) + " units · " + PlanText.compactGp(PlanText.allocation(p, plan)) + " gp"
			+ (p.slot != null ? " · Slot " + p.slot : "");
		title.add(Ui.small(details, Ui.MUTED));
		head.add(title, BorderLayout.CENTER);
		card.add(Ui.fitHeight(head));

		JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		tags.setOpaque(false);
		tags.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (paused(p))
		{
			name.setForeground(Ui.MUTED);
			JLabel pausedPill = Ui.pill(PlanText.PAUSED_LABEL, Ui.MUTED);
			pausedPill.setToolTipText("Sell-only mode: Flippr won't ask for this buy until you turn it off");
			tags.add(pausedPill);
		}
		else
		{
			tags.add(Ui.pill(PlanText.statusLabel(p.status), Ui.statusColor(p.status)));
		}
		String ge = geText(p);
		JLabel geLabel = Ui.pill(ge != null ? ge : "Not in GE", ge != null ? Ui.TEXT : Ui.MUTED);
		geLabel.setToolTipText(ge != null ? "The in-game GE slot this item is in" : "Not in any GE slot right now");
		tags.add(geLabel);
		if (p.members)
		{
			tags.add(Ui.pill("P2P", Ui.MUTED));
		}
		card.add(Ui.gap(4));
		card.add(Ui.fitHeight(tags));
		card.add(Ui.gap(4));

		// Buy at / Sell at, exact.
		card.add(Ui.grid(2,
			Ui.stat("Buy at", PlanText.exactGp(p.buyPrice) + " gp", Ui.TEXT, null),
			Ui.stat("Sell at", PlanText.exactGp(p.sellPrice) + " gp", Ui.TEXT, null)));

		String updated = PlanText.priceUpdatedLine(p, now);
		if (updated != null)
		{
			card.add(Ui.gap(3));
			card.add(Ui.wrapped(updated, Ui.MUTED));
		}
		if (p.sellBelowBreakEven)
		{
			card.add(Ui.gap(3));
			JLabel chip = Ui.wrapped("Market below break-even - sell held at break-even until it recovers", Ui.WARN);
			chip.setToolTipText("The market is below break-even after GE tax. The sell price is held at break-even until it recovers.");
			card.add(chip);
		}
		if (alert != null)
		{
			card.add(Ui.gap(3));
			JLabel a = Ui.wrapped(alert, Ui.WARN);
			a.setFont(net.runelite.client.ui.FontManager.getRunescapeBoldFont());
			card.add(a);
		}

		card.add(Ui.gap(4));
		long net = PlanText.netPerUnit(p);
		Color netColor = net < 0 ? config.lossColor() : config.profitColor();
		Color expColor = p.expectedProfit < 0 ? config.lossColor() : config.profitColor();
		card.add(Ui.grid(2,
			Ui.stat("Net / unit", PlanText.signedGp(net), netColor, null),
			Ui.stat("Allocation", PlanText.compactGp(PlanText.allocation(p, plan)) + " gp", Ui.TEXT, null),
			Ui.stat("Exp. profit", PlanText.signedGp(p.expectedProfit), expColor, null),
			Ui.stat("Est. fill", PlanText.hours(p.estFillHours), Ui.TEXT, null)));

		card.add(Ui.gap(5));
		PlanText.Progress pr = PlanText.progress(p);
		card.add(Ui.progressBar(pr.value, Ui.statusColor(p.status)));
		card.add(Ui.gap(2));
		card.add(Ui.small(PlanText.exactGp(pr.done) + " / " + PlanText.exactGp(pr.of) + " " + pr.label, Ui.MUTED));
		if (p.realisedProfit != 0)
		{
			card.add(Ui.small("Realised " + PlanText.signedGp(p.realisedProfit),
				p.realisedProfit > 0 ? config.profitColor() : config.lossColor()));
		}

		card.add(Ui.gap(5));
		CancelledBuys.Finding cancelled = cancelledOffers.promptFor(p.id);
		if (cancelled != null)
		{
			// The buy offer is gone from the GE: one click fixes the plan.
			JLabel gone = Ui.wrapped(cancelled.text(), Ui.WARN);
			gone.setFont(FontManager.getRunescapeBoldFont());
			card.add(gone);
			JButton fix = Ui.button(stopping.contains(p.id) ? "Working..." : cancelled.action());
			fix.setEnabled(!stopping.contains(p.id));
			fix.setToolTipText(cancelled.fix == CancelledBuys.Fix.DROP
				? "Nothing was bought: drop it from the plan and recycle its gp"
				: "Sell the " + PlanText.exactGp(cancelled.bought) + " bought and recycle the slot");
			fix.addActionListener(e -> fixCancelled(p));
			card.add(Ui.fitHeight(Ui.line(fix, null)));
			card.add(Ui.gap(3));
		}
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		String stop = cancelled != null ? null : PlanText.stopLabel(p);
		if (stop != null)
		{
			JButton stopButton = Ui.button(stopping.contains(p.id) ? "Working..." : stop);
			stopButton.setEnabled(!stopping.contains(p.id));
			stopButton.setToolTipText(p.boughtQty == 0 ? "Drop & recycle the gp"
				: "Stop buying - sell " + PlanText.exactGp(p.boughtQty) + " & recycle");
			stopButton.addActionListener(e -> confirmStop(p));
			actions.add(stopButton);
			actions.add(hspace(4));
		}
		JButton web = linkButton("Open on website", "/osrs/plan");
		web.setToolTipText("Mark sold / Adjust counts are on the website's Plan page");
		actions.add(web);
		card.add(Ui.fitHeight(actions));
		return card;
	}

	// One line: icon, name + progress, status (+ GE slot). Details in the
	// tooltip; Stop / Open on website on right-click.
	private boolean paused(ServerSuggestions.PlanPosition p)
	{
		return PlanText.isPaused(p, serverSuggestions.isSellOnly(), !listingReporter.geSlotsFor(p.id).isEmpty());
	}

	private JPanel compactRow(ServerSuggestions.PlanPosition p, long now)
	{
		String alert = PlanText.alertText(p.alertKind, p.suggestedPrice);
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(Ui.CARD);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new MatteBorder(0, 3, 0, 0, alert != null ? Ui.WARN : Ui.statusColor(p.status)),
			new EmptyBorder(2, 4, 2, 4)));
		row.add(icon(p.itemId, 20), BorderLayout.WEST);
		PlanText.Progress pr = PlanText.progress(p);
		JLabel text = Ui.small("<html><b>" + Ui.esc(p.name) + "</b> " + PlanText.compactGp(pr.done) + "/" + PlanText.compactGp(pr.of)
			+ "</html>", Ui.TEXT);
		row.add(text, BorderLayout.CENTER);
		String ge = geText(p);
		boolean paused = paused(p);
		if (paused)
		{
			text.setForeground(Ui.MUTED);
		}
		row.add(Ui.small(paused ? PlanText.PAUSED_LABEL : PlanText.statusLabel(p.status) + (ge != null ? " · " + ge : ""),
			paused ? Ui.MUTED : Ui.statusColor(p.status)), BorderLayout.EAST);

		StringBuilder tip = new StringBuilder("<html><b>").append(Ui.esc(p.name)).append("</b><br>")
			.append("Buy at ").append(PlanText.exactGp(p.buyPrice)).append(" · Sell at ").append(PlanText.exactGp(p.sellPrice)).append("<br>")
			.append(PlanText.exactGp(pr.done)).append(" / ").append(PlanText.exactGp(pr.of)).append(' ').append(pr.label)
			.append(" · Exp. ").append(PlanText.signedGp(p.expectedProfit));
		if (alert != null)
		{
			tip.append("<br>").append(Ui.esc(alert));
		}
		String updated = PlanText.priceUpdatedLine(p, now);
		if (updated != null)
		{
			tip.append("<br>").append(Ui.esc(updated));
		}
		row.setToolTipText(tip.append("<br><i>Right-click for actions</i></html>").toString());

		JPopupMenu menu = new JPopupMenu();
		CancelledBuys.Finding cancelled = cancelledOffers.promptFor(p.id);
		if (cancelled != null && !stopping.contains(p.id))
		{
			JMenuItem fix = new JMenuItem(cancelled.text() + ": " + cancelled.action());
			fix.addActionListener(e -> fixCancelled(p));
			menu.add(fix);
			row.setBorder(new CompoundBorder(new MatteBorder(0, 3, 0, 0, Ui.WARN), new EmptyBorder(2, 4, 2, 4)));
		}
		String stop = cancelled != null ? null : PlanText.stopLabel(p);
		if (stop != null && !stopping.contains(p.id))
		{
			JMenuItem item = new JMenuItem(stop);
			item.addActionListener(e -> confirmStop(p));
			menu.add(item);
		}
		JMenuItem open = new JMenuItem("Open on website");
		open.addActionListener(e -> openSite("/osrs/plan"));
		menu.add(open);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					menu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});
		return Ui.fitHeight(row);
	}

	private JButton linkButton(String text, String path)
	{
		JButton b = Ui.button(text);
		b.addActionListener(e -> openSite(path));
		return b;
	}

	private void openSite(String path)
	{
		String base = website.baseUrl();
		if (!base.isEmpty())
		{
			LinkBrowser.browse(base + path);
		}
	}

	// The cancelled-buy prompt's button: one click, no dialog.
	private void fixCancelled(ServerSuggestions.PlanPosition p)
	{
		stopping.add(p.id);
		builtFor = null;
		refresh();
		cancelledOffers.fix(p.id, result -> SwingUtilities.invokeLater(() ->
		{
			stopping.remove(p.id);
			builtFor = null;
			refresh();
			if (!result.ok)
			{
				JOptionPane.showMessageDialog(this, result.error, "Flippr", JOptionPane.WARNING_MESSAGE);
			}
		}));
	}

	private void confirmStop(ServerSuggestions.PlanPosition p)
	{
		String label = PlanText.stopLabel(p);
		if (label == null)
		{
			return;
		}
		int answer = JOptionPane.showConfirmDialog(this, PlanText.stopQuestion(p), "Flippr - " + label,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (answer != JOptionPane.OK_OPTION)
		{
			return;
		}
		stopping.add(p.id);
		builtFor = null;
		refresh();
		serverSuggestions.stopPosition(p.id, result -> SwingUtilities.invokeLater(() ->
		{
			stopping.remove(p.id);
			builtFor = null;
			refresh();
			if (!result.ok)
			{
				JOptionPane.showMessageDialog(this, result.error, "Flippr", JOptionPane.WARNING_MESSAGE);
			}
		}));
	}

	// Fixed-width horizontal spacer for FlowLayout rows.
	private static Component hspace(int w)
	{
		JPanel s = new JPanel();
		s.setOpaque(false);
		s.setPreferredSize(new Dimension(w, 1));
		return s;
	}
}
