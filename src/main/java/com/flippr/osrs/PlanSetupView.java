package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import javax.inject.Inject;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.FontManager;

// The Plan tab's "Plan setup" card - the website's "Set up your plan" form
// (PlanSetup holds the rules): capital with an "= N gp" preview, GE slots
// (the ones in use can't be dropped), style, fill window, max per slot,
// account (F2P / members / both), keep vs reinvest; Build / Rebuild
// (asks first) -> POST /api/v1/osrs/plan/build, then the plan is read again.
// Expanded while there's no plan, behind "Change plan" otherwise. The
// plan's "Auto-update prices" / "Auto-fill free slots" switches sit on the
// card whenever there is a plan and apply at once (PATCH /plan/settings).
// Built once and updated in place, so a refresh never interrupts typing.
// EDT only.
public class PlanSetupView extends JPanel
{
	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private WebsiteConnection website;

	@Inject
	private FlipprConfig config;

	@Inject
	private SellOnlyControl sellOnlyControl;

	private final JLabel summary = Ui.small("", Ui.MUTED);
	private final JButton toggle = Ui.button("Change plan");
	private final JLabel note = Ui.wrapped("", Ui.MUTED);
	private final JPanel body = Ui.column(Ui.CARD);
	private final JPanel switches = Ui.column(Ui.CARD);
	private final JCheckBox autoPrices = new JCheckBox("Auto-update prices");
	private final JCheckBox autoFill = new JCheckBox("Auto-fill free slots");
	private final JCheckBox sellOnly = new JCheckBox("Sell-only (no new buys)");
	private boolean sellOnlyBusy;
	private final JLabel switchError = Ui.wrapped("", Ui.MUTED);
	private final JTextField capital = new JTextField();
	private final JLabel capitalHint = Ui.small("", Ui.MUTED);
	private final JLabel slotsHint = Ui.wrapped("", Ui.MUTED);
	private final JLabel styleHint = Ui.small("", Ui.MUTED);
	private final JLabel reinvestHint = Ui.wrapped("", Ui.MUTED);
	private final JButton build = Ui.button("Build plan");
	private final JLabel error = Ui.wrapped("", Ui.MUTED);
	private final List<JToggleButton> slotButtons = new ArrayList<>();
	private final List<JToggleButton> styleButtons = new ArrayList<>();
	private final List<JToggleButton> fillButtons = new ArrayList<>();
	private final List<JToggleButton> maxButtons = new ArrayList<>();
	private final List<JToggleButton> accountButtons = new ArrayList<>();
	private final List<JToggleButton> reinvestButtons = new ArrayList<>();

	private PlanSetup.Form form = new PlanSetup.Form();
	// The plan the form was filled from; edits keep the form.
	private String source;
	private boolean dirty;
	// null: follow the plan (open without one); else the player's pick.
	private Boolean expanded;
	private boolean busy;
	private String switchBusy;
	// A switch the player just flipped shows their choice until the plan
	// read back agrees (or OPTIMISTIC_MS passes): field -> value.
	private final java.util.Map<String, Boolean> optimistic = new java.util.HashMap<>();
	private long optimisticUntil;
	static final long OPTIMISTIC_MS = 20_000;
	private boolean syncingControls;

	public PlanSetupView()
	{
		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel card = Ui.card();

		JLabel title = Ui.bold("Plan setup", Ui.TEXT);
		JPanel titleCol = Ui.column(Ui.CARD);
		titleCol.setOpaque(false);
		titleCol.add(title);
		titleCol.add(summary);
		card.add(Ui.fitHeight(Ui.line(titleCol, toggle)));
		toggle.addActionListener(e -> setExpanded(!isExpanded()));
		card.add(note);

		switches.setOpaque(false);
		for (JCheckBox box : new JCheckBox[]{autoPrices, autoFill, sellOnly})
		{
			box.setOpaque(false);
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setAlignmentX(Component.LEFT_ALIGNMENT);
			switches.add(box);
		}
		autoPrices.setToolTipText("Sell and buy prices of offers you haven't listed follow the market");
		autoFill.setToolTipText("A slot whose trade finished gets its next flip automatically");
		autoPrices.addActionListener(e -> flipSwitch("autoSellPrice", autoPrices));
		autoFill.addActionListener(e -> flipSwitch("autoFill", autoFill));
		sellOnly.setToolTipText("Sell what you hold, start no new buys: Pending buys pause, free slots stay empty");
		sellOnly.addActionListener(e ->
		{
			if (syncingControls || sellOnlyBusy)
			{
				return;
			}
			sellOnlyBusy = true;
			switchError.setText("");
			sellOnlyControl.set(sellOnly.isSelected(), result -> SwingUtilities.invokeLater(() ->
			{
				sellOnlyBusy = false;
				if (!result.ok)
				{
					showError(switchError, result.error);
				}
				refresh();
			}));
			refresh();
		});
		switches.add(switchError);
		card.add(Ui.gap(4));
		card.add(switches);

		body.setOpaque(false);
		body.add(Ui.gap(4));
		body.add(Ui.small("Capital for new items", Ui.MUTED));
		capital.setFont(FontManager.getRunescapeSmallFont());
		capital.setToolTipText("e.g. 1.5m, 250k, 15000000");
		body.add(Ui.fitHeight(capital));
		body.add(capitalHint);
		capital.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onCapital();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onCapital();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onCapital();
			}
		});
		capital.addActionListener(e -> submit());

		String[] slotLabels = new String[PlanSetup.MAX_SLOTS];
		for (int i = 0; i < slotLabels.length; i++)
		{
			slotLabels[i] = String.valueOf(i + 1);
		}
		group("GE slots", slotLabels, slotButtons, i -> pick(f -> f.slots = i + 1));
		body.add(slotsHint);
		group("Trading style", PlanSetup.STYLE_LABELS, styleButtons, i -> pick(f -> f.style = PlanSetup.STYLES[i]));
		body.add(styleHint);
		group("Fill window", PlanSetup.FILL_WINDOW_LABELS, fillButtons, i -> pick(f -> f.fillWindowHours = PlanSetup.FILL_WINDOWS[i]));
		body.add(Ui.wrapped(PlanSetup.FILL_WINDOW_HINT, Ui.MUTED));
		group("Max per slot", new String[]{"Off", "25%", "33%", "50%"}, maxButtons, i -> pick(f -> f.maxPerSlotPct = PlanSetup.MAX_PER_SLOT.get(i)));
		group("Account", PlanSetup.ACCOUNT_LABELS, accountButtons, i -> pick(f -> f.account = PlanSetup.ACCOUNTS[i]));
		group("When a flip completes", new String[]{"Keep to bank", "Reinvest"}, reinvestButtons, i -> pick(f -> f.reinvest = i == 1));
		body.add(reinvestHint);
		body.add(Ui.gap(6));
		build.addActionListener(e -> submit());
		body.add(Ui.fitHeight(Ui.line(build, null)));
		body.add(error);
		card.add(body);
		add(card, BorderLayout.CENTER);
	}

	private void group(String label, String[] labels, List<JToggleButton> into, java.util.function.IntConsumer onPick)
	{
		body.add(Ui.gap(4));
		body.add(Ui.small(label, Ui.MUTED));
		JPanel row = new JPanel(new GridLayout(1, labels.length, 2, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		ButtonGroup bg = new ButtonGroup();
		for (int i = 0; i < labels.length; i++)
		{
			int index = i;
			JToggleButton b = new JToggleButton(labels[i]);
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setMargin(new Insets(2, 0, 2, 0));
			b.setFocusPainted(false);
			b.addActionListener(e ->
			{
				if (!syncingControls)
				{
					onPick.accept(index);
				}
			});
			bg.add(b);
			row.add(b);
			into.add(b);
		}
		body.add(Ui.fitHeight(row));
	}

	private void pick(Consumer<PlanSetup.Form> change)
	{
		change.accept(form);
		dirty = true;
		error.setText("");
		syncControls();
	}

	private void onCapital()
	{
		if (syncingControls)
		{
			return;
		}
		form.capital = capital.getText();
		dirty = true;
		syncControls();
	}

	private ServerSuggestions.Data data()
	{
		return serverSuggestions.getLastData();
	}

	private ServerSuggestions.PlanInfo plan()
	{
		ServerSuggestions.Data d = data();
		return d != null && d.hasPlan() ? d.planInfo : null;
	}

	private int slotsInUse()
	{
		ServerSuggestions.Data d = data();
		return d != null && d.hasPlan() ? PlanSetup.countSlotsInUse(d.planPositions) : 0;
	}

	private boolean isExpanded()
	{
		return expanded == null ? plan() == null : expanded;
	}

	private void setExpanded(boolean open)
	{
		expanded = open;
		if (open)
		{
			// Opened again: start from the plan as it is now.
			dirty = false;
			source = null;
			error.setText("");
		}
		refresh();
	}

	// Called about once a second while the Plan tab shows.
	void refresh()
	{
		ServerSuggestions.PlanInfo plan = plan();
		boolean blocked = !website.isSyncEnabled() || data() == null;
		note.setVisible(blocked);
		if (blocked)
		{
			note.setText(htmlWrap(!website.isSyncEnabled()
				? "Pair this plugin with Flippr (Account, below) to set up your Plan here."
				: "Loading your Plan..."));
		}
		summary.setText(plan != null && !blocked ? PlanText.compactGp(plan.capital) + " gp · " + plan.slots + " slots" : "");
		toggle.setVisible(!blocked && plan != null);
		boolean open = !blocked && isExpanded();
		toggle.setText(open ? "Close" : "Change plan");
		body.setVisible(open);

		switches.setVisible(!blocked && plan != null);
		if (plan != null)
		{
			syncingControls = true;
			autoPrices.setSelected(shown("autoSellPrice", PlanSetup.autoSellPrice(plan)));
			autoFill.setSelected(shown("autoFill", PlanSetup.autoFill(plan)));
			sellOnly.setSelected(sellOnlyControl.isOn());
			syncingControls = false;
			autoPrices.setEnabled(switchBusy == null);
			autoFill.setEnabled(switchBusy == null);
			sellOnly.setEnabled(!sellOnlyBusy);
		}

		if (open)
		{
			String src = (plan == null ? "none" : plan.id + "/" + plan.capital + "/" + plan.slots + "/" + plan.createdAt);
			if (!dirty && !Objects.equals(src, source))
			{
				form = PlanSetup.initialForm(plan);
				source = src;
				syncingControls = true;
				capital.setText(form.capital);
				syncingControls = false;
			}
			syncControls();
		}
		revalidate();
	}

	// The plan's value, or the player's just-flipped one until the plan agrees.
	private boolean shown(String field, boolean planValue)
	{
		Boolean mine = optimistic.get(field);
		if (mine == null || mine == planValue || System.currentTimeMillis() > optimisticUntil)
		{
			optimistic.remove(field);
			return planValue;
		}
		return mine;
	}

	// Buttons, hints and the Build button from the form.
	private void syncControls()
	{
		syncingControls = true;
		int inUse = slotsInUse();
		int floor = PlanSetup.minSlots(inUse);
		int slots = Math.max(form.slots, floor);
		select(slotButtons, slots - 1, i -> i + 1 >= floor);
		select(styleButtons, "highTicket".equals(form.style) ? 1 : 0, i -> true);
		int fill = 2;
		for (int i = 0; i < PlanSetup.FILL_WINDOWS.length; i++)
		{
			if (PlanSetup.FILL_WINDOWS[i] == form.fillWindowHours)
			{
				fill = i;
			}
		}
		select(fillButtons, fill, i -> true);
		select(maxButtons, Math.max(0, PlanSetup.MAX_PER_SLOT.indexOf(form.maxPerSlotPct)), i -> true);
		select(accountButtons, java.util.Arrays.asList(PlanSetup.ACCOUNTS).indexOf(form.account), i -> true);
		select(reinvestButtons, form.reinvest ? 1 : 0, i -> true);
		syncingControls = false;

		PlanSetup.Hint hint = PlanSetup.capitalHint(form.capital);
		capitalHint.setText(hint.text);
		capitalHint.setForeground(hint.invalid ? config.lossColor() : Ui.MUTED);
		slotsHint.setText(htmlWrap(inUse > 0 ? inUse + " already in use by positions in progress; they keep their slots." : ""));
		slotsHint.setVisible(inUse > 0);
		styleHint.setText(PlanSetup.STYLE_HINTS["highTicket".equals(form.style) ? 1 : 0]);
		reinvestHint.setText(htmlWrap(form.reinvest ? "Each slot's profit rolls into its next flip."
			: "Next flips use your original capital."));
		boolean hasPlan = plan() != null;
		build.setText(busy ? (hasPlan ? "Rebuilding..." : "Building...") : hasPlan ? "Rebuild plan" : "Build plan");
		build.setEnabled(!busy && hint.gp != null);
	}

	private static void select(List<JToggleButton> buttons, int index, IntPredicate enabled)
	{
		for (int i = 0; i < buttons.size(); i++)
		{
			JToggleButton b = buttons.get(i);
			b.setEnabled(enabled.test(i));
			if (i == index && !b.isSelected())
			{
				b.setSelected(true);
			}
		}
	}

	private static String htmlWrap(String text)
	{
		return "<html><body style='width:" + Ui.TEXT_WIDTH + "px'>" + Ui.esc(text) + "</body></html>";
	}

	private void showError(JLabel label, String text)
	{
		label.setForeground(config.lossColor());
		label.setText(text == null ? "" : htmlWrap(text));
	}

	private void submit()
	{
		if (busy)
		{
			return;
		}
		boolean hasPlan = plan() != null;
		PlanSetup.Request request = PlanSetup.buildRequest(form, slotsInUse());
		if (request.error != null)
		{
			showError(error, request.error);
			return;
		}
		if (hasPlan && JOptionPane.showConfirmDialog(this, PlanSetup.REBUILD_QUESTION, "Flippr - Rebuild plan",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}
		busy = true;
		error.setText("");
		syncControls();
		serverSuggestions.buildPlan(request.body, result -> SwingUtilities.invokeLater(() ->
		{
			busy = false;
			if (result.ok)
			{
				// The new plan is on the board: fold the form away.
				expanded = null;
				dirty = false;
				source = null;
				error.setText("");
			}
			else
			{
				showError(error, result.error);
			}
			refresh();
		}));
	}

	private void flipSwitch(String field, JCheckBox box)
	{
		if (syncingControls || switchBusy != null)
		{
			return;
		}
		boolean on = box.isSelected();
		optimistic.put(field, on);
		optimisticUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
		switchBusy = field;
		switchError.setText("");
		refresh();
		serverSuggestions.setPlanSetting(field, on, result -> SwingUtilities.invokeLater(() ->
		{
			switchBusy = null;
			if (!result.ok)
			{
				optimistic.remove(field);
				showError(switchError, result.error);
			}
			refresh();
		}));
	}
}
