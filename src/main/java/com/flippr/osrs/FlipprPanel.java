package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Image;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

// Sidebar - quick controls for the settings that matter moment-to-moment
// (pause the engine, toggle the overlay/logging, adjust the placeholder
// thresholds) without leaving the game to open RuneLite's own settings
// panel, plus a live view of current per-slot suggestions and recent
// completed flips. The FULL settings list (identical fields, same source of
// truth) still lives in FlipprConfig/RuneLite's own config panel - this is
// a faster path to the ones worth touching mid-session, not a replacement.
@Slf4j
public class FlipprPanel extends PluginPanel
{
	// PluginPanel's own base background is DARK_GRAY_COLOR - every section
	// here previously used that SAME color, so nothing visually separated
	// from the page background at all (the flat, dated look). CARD_COLOR is
	// deliberately one step darker so sections actually read as distinct
	// cards, matching the two-tone convention RuneLite's own plugins use.
	private static final Color CARD_COLOR = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color ACCENT_COLOR = ColorScheme.BRAND_ORANGE;
	// 24 buckets at the chart's 1h timestep - a real 24h window, not
	// arbitrary padding.
	private static final int CHART_POINTS = 24;

	// Real icon assets (Lucide, ISC license), not runtime-drawn placeholders:
	// zap/eye/scroll-text for engine/highlight/verbose, and a hand-drawn
	// stopwatch glyph for timer in the same visual style.
	// Pre-scaled to 14x14 for the compact pill toggles.
	private static final ImageIcon ENGINE_ICON = scaledIcon("icon_engine.png");
	private static final ImageIcon HIGHLIGHT_ICON = scaledIcon("icon_highlight.png");
	private static final ImageIcon VERBOSE_ICON = scaledIcon("icon_verbose.png");
	private static final ImageIcon TIMER_ICON = scaledIcon("icon_timer.png");

	private static ImageIcon scaledIcon(String resourceName)
	{
		return new ImageIcon(ImageUtil.loadImageResource(FlipprPanel.class, resourceName)
			.getScaledInstance(14, 14, Image.SCALE_SMOOTH));
	}

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FlipprConfig config;

	@Inject
	private WebsiteConnection websiteConnection;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private MultiAccountAggregator multiAccountAggregator;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private Client client;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private PinnedChartItem pinnedChartItem;

	@Inject
	private SessionStatsAggregator sessionStatsAggregator;

	@Inject
	private PortfolioExporter portfolioExporter;

	@Inject
	private OfferSync offerSync;

	@Inject
	private TradeUploader tradeUploader;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private PlanSetupView planSetupView;

	@Inject
	private SellOnlyControl sellOnlyControl;

	@Inject
	private GuidanceGate guidanceGate;

	@Inject
	private PlanBoardView planBoardView;

	@Inject
	private TrackerView trackerView;

	@Inject
	private HistoryView historyView;

	@Inject
	private PricesView pricesView;

	// Segmented pill toggles, not checkboxes - the moment-to-moment settings
	// worth a glance read better as a compact toolbar than a checklist.
	private final PillToggleButton engineToggle = new PillToggleButton("Engine");
	private final PillToggleButton highlightToggle = new PillToggleButton("Highlight");
	private final PillToggleButton verboseToggle = new PillToggleButton("Verbose");
	private final PillToggleButton timersToggle = new PillToggleButton("Timers");
	// Own row, not in the 2x2 grid with the others - these are MODE
	// switches (silence suggestions/highlighting/every alert path at once),
	// not an independent display toggle like the four above, so they get
	// their own visual weight rather than being lumped in with them.
	// Side by side with each other (see buildQuickControls) since they're
	// mutually-exclusive-in-spirit variants of the same underlying "just
	// watch" state, not two unrelated settings.
	private final PillToggleButton watchModeToggle = new PillToggleButton("Watch Mode");
	private final PillToggleButton watchModeDumpToggle = new PillToggleButton("Watch Mode (Dump)");
	private final JSpinner staleFillSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));
	private final JSpinner priceMoveSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 20, 1));
	private final JSpinner modifyFallbackSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 120, 1));
	// Real dropdowns (JComboBox), not spinners - both settings are a short
	// fixed list of options.
	private final JComboBox<MinPredictedProfit> minPredictedProfitDropdown = new JComboBox<>(MinPredictedProfit.values());
	private final JComboBox<ReservedDumpSlots> reservedDumpSlotsDropdown = new JComboBox<>(ReservedDumpSlots.values());

	// Dropdown option sets for the session/account pickers - pure view
	// state, not @ConfigItem-backed settings, since these only change what
	// the stats block below is currently showing, not any real engine
	// behavior. SESSION + the current account reproduces this plugin's
	// existing live behavior exactly (FlipTracker's own in-memory
	// counters) - every other combination triggers a real file-based
	// aggregation over flips.jsonl via SessionStatsAggregator, cached in
	// historicalStats and only recomputed when a selection actually
	// changes (not every panel tick - that'd be needless disk I/O every
	// second).
	private final JComboBox<SessionTimeRange> sessionTimeRangeDropdown = new JComboBox<>(SessionTimeRange.values());
	private final JComboBox<String> sessionAccountDropdown = new JComboBox<>();
	private SessionStatsAggregator.Stats historicalStats;
	private long historicalRangeStartMs;
	private LocalDate customStartDate;
	// How often a non-live (historical) view re-queries flips.jsonl - see
	// refreshHistoricalStats()'s own comment for why this can't be
	// selection-change-only. Throttled well below updateOnEdt()'s own
	// ~1s cadence so this stays real disk I/O on a human timescale, not a
	// per-tick cost.
	private static final long HISTORICAL_REFRESH_INTERVAL_MS = 30_000L;
	private long lastHistoricalRefreshMs;

	private final JLabel reserveFloorLabel = new JLabel();
	private final JLabel profitValueLabel = new JLabel();
	private final JLabel flipsValueLabel = new JLabel();
	private final JLabel hourlyValueLabel = new JLabel();
	private final JLabel timeValueLabel = new JLabel();
	private final JPanel accountsContainer = new JPanel();
	private final JPanel portfolioContainer = new JPanel();
	private final JPanel suggestionsContainer = new JPanel();
	private final JPanel recentFlipsContainer = new JPanel();
	private final JPanel websiteContainer = new JPanel();
	private volatile boolean websiteRequestInFlight;
	// Which layout the Website section was last built for (see
	// websiteStateKey()); the status lines below update in place each tick.
	private String builtWebsiteState;
	private final JLabel liveSyncLabel = new JLabel();
	private final JLabel uploadsLabel = new JLabel();
	private final JLabel suggestionsStatusLabel = new JLabel();

	@Inject
	public FlipprPanel()
	{
		super();
		setLayout(new BorderLayout());

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Flippr", new ImageIcon(FlipprPlugin.ICON.getScaledInstance(28, 28, Image.SCALE_SMOOTH)), JLabel.LEFT);
		title.setIconTextGap(8);
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		title.setForeground(Color.WHITE);
		title.setBorder(new EmptyBorder(0, 0, 2, 0));
		title.setAlignmentX(LEFT_ALIGNMENT);
		top.add(title);
		headerStatusLabel.setFont(FontManager.getRunescapeSmallFont());
		headerStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headerStatusLabel.setBorder(new EmptyBorder(0, 0, 6, 0));
		headerStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
		top.add(headerStatusLabel);

		// Plan | Tracker | History | Prices, like the RS3 app and the site.
		JPanel tabBar = new JPanel(new GridLayout(1, TABS.length, 2, 0));
		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabBar.setAlignmentX(LEFT_ALIGNMENT);
		ButtonGroup group = new ButtonGroup();
		for (int i = 0; i < TABS.length; i++)
		{
			String id = TABS[i];
			JToggleButton tab = new JToggleButton(TAB_LABELS[i]);
			tab.setFont(FontManager.getRunescapeSmallFont());
			tab.setFocusPainted(false);
			tab.setMargin(new java.awt.Insets(3, 0, 3, 0));
			tab.addActionListener(e -> showTab(id, true));
			group.add(tab);
			tabBar.add(tab);
			tabButtons[i] = tab;
		}
		top.add(tabBar);
		top.add(Box.createVerticalStrut(6));
		add(top, BorderLayout.NORTH);

		tabContent.setLayout(new BorderLayout());
		tabContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(tabContent, BorderLayout.CENTER);

		// PluginPanel's own scroll pane scrolls 1px per unit by default.
		SidebarScroll.apply(getScrollPane());
	}

	private static final String[] TABS = {"plan", "tracker", "history", "prices"};
	private static final String[] TAB_LABELS = {"Plan", "Tracker", "History", "Prices"};
	private final JToggleButton[] tabButtons = new JToggleButton[TABS.length];
	private final JPanel tabContent = new JPanel();
	private final JLabel headerStatusLabel = new JLabel();
	private final JLabel premiumNoticeLabel = new JLabel();
	// Plan tab banner: what is switching the GE guidance off (GuidanceGate).
	private final JPanel guidanceBanner = new JPanel(new BorderLayout(0, 4));
	private final JLabel guidanceBannerLabel = new JLabel();
	// Plan tab header: sell-only mode (SellOnlyControl).
	private final JCheckBox sellOnlyHeader = new JCheckBox("Sell-only mode (no new buys)");
	private boolean sellOnlyHeaderSyncing;
	private boolean sellOnlyHeaderBusy;
	private JPanel[] tabPanels;
	private String currentTab;
	// The item of the GE offer screen that's open (0 none) - read on the
	// client thread by FlipprPlugin.
	private volatile int geScreenItemId;

	// Each tab's content: the website's view first, then this plugin's own
	// local sections that belong with it.
	private void buildTabs()
	{
		JPanel plan = tabColumn();
		premiumNoticeLabel.setFont(FontManager.getRunescapeSmallFont());
		premiumNoticeLabel.setAlignmentX(LEFT_ALIGNMENT);
		premiumNoticeLabel.setBorder(new EmptyBorder(0, 2, 6, 2));
		sellOnlyHeader.setFont(FontManager.getRunescapeSmallFont());
		sellOnlyHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sellOnlyHeader.setAlignmentX(LEFT_ALIGNMENT);
		sellOnlyHeader.setToolTipText("Sell what you hold, start no new buys: Pending buys pause, free slots stay empty, "
			+ "and the GE guidance and notifications only cover sells. Buy offers already up keep filling.");
		sellOnlyHeader.addActionListener(e ->
		{
			if (sellOnlyHeaderSyncing || sellOnlyHeaderBusy)
			{
				return;
			}
			sellOnlyHeaderBusy = true;
			sellOnlyControl.set(sellOnlyHeader.isSelected(), result -> SwingUtilities.invokeLater(() ->
			{
				sellOnlyHeaderBusy = false;
				if (!result.ok)
				{
					JOptionPane.showMessageDialog(this, result.error, "Flippr", JOptionPane.WARNING_MESSAGE);
				}
				refreshSellOnlyHeader();
			}));
			refreshSellOnlyHeader();
		});
		plan.add(buildGuidanceBanner());
		plan.add(sellOnlyHeader);
		plan.add(premiumNoticeLabel);
		plan.add(planSetupView);
		plan.add(Box.createVerticalStrut(6));
		plan.add(planBoardView);
		plan.add(sectionHeader("GE suggestions"));
		plan.add(card(suggestionsContainer));
		plan.add(sectionHeader("Holding"));
		plan.add(card(portfolioContainer));
		plan.add(sectionHeader("Quick settings"));
		JPanel quick = buildQuickControls();
		quick.setAlignmentX(LEFT_ALIGNMENT);
		plan.add(quick);
		plan.add(sectionHeader("Account"));
		plan.add(card(websiteContainer));

		JPanel tracker = tabColumn();
		tracker.add(trackerView);
		tracker.add(sectionHeader("This computer"));
		JPanel stats = buildStatsBlock();
		stats.setAlignmentX(LEFT_ALIGNMENT);
		tracker.add(stats);
		tracker.add(sectionHeader("All accounts (today)"));
		tracker.add(card(accountsContainer));

		JPanel history = tabColumn();
		history.add(historyView);
		history.add(sectionHeader("Recent flips (this session)"));
		history.add(card(recentFlipsContainer));

		JPanel prices = tabColumn();
		prices.add(pricesView);

		tabPanels = new JPanel[]{plan, tracker, history, prices};
	}

	// "GE guidance is off: Watch mode is on" + "Turn guidance on"; hidden
	// while nothing blocks the guidance (refreshGuidanceBanner).
	private JPanel buildGuidanceBanner()
	{
		guidanceBanner.setBackground(new Color(110, 30, 30));
		guidanceBanner.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, new Color(230, 80, 80)), new EmptyBorder(6, 6, 6, 6)));
		guidanceBanner.setAlignmentX(LEFT_ALIGNMENT);
		guidanceBannerLabel.setFont(FontManager.getRunescapeBoldFont());
		guidanceBannerLabel.setForeground(Color.WHITE);
		JButton turnOn = new JButton("Turn guidance on");
		turnOn.setToolTipText("Turns Watch mode and Watch mode (Dump) off and the suggestion engine on");
		turnOn.addActionListener(e ->
		{
			guidanceGate.turnGuidanceOn();
			refreshGuidanceBanner();
		});
		guidanceBanner.add(guidanceBannerLabel, BorderLayout.CENTER);
		guidanceBanner.add(turnOn, BorderLayout.SOUTH);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setBorder(new EmptyBorder(0, 0, 6, 0));
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.add(guidanceBanner, BorderLayout.CENTER);
		refreshGuidanceBanner();
		return wrap;
	}

	private void refreshGuidanceBanner()
	{
		String text = GuidanceGate.bannerText(guidanceGate.current());
		if (text != null)
		{
			guidanceBannerLabel.setText("<html>" + text + "</html>");
		}
		// The wrapper (with its bottom gap) shows and hides with it.
		if (guidanceBanner.getParent() != null)
		{
			guidanceBanner.getParent().setVisible(text != null);
		}
	}

	private static JPanel tabColumn()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	private static JPanel card(JPanel container)
	{
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(CARD_COLOR);
		container.setBorder(new EmptyBorder(4, 4, 4, 4));
		container.setAlignmentX(LEFT_ALIGNMENT);
		return container;
	}

	// Switches the visible tab (remembered in the config when the player
	// picked it).
	private void showTab(String id, boolean remember)
	{
		int index = 0;
		for (int i = 0; i < TABS.length; i++)
		{
			if (TABS[i].equals(id))
			{
				index = i;
			}
		}
		String tab = TABS[index];
		tabButtons[index].setSelected(true);
		if (remember && !tab.equals(config.sidebarTab()))
		{
			configManager.setConfiguration("flippr", "sidebarTab", tab);
		}
		if (tab.equals(currentTab))
		{
			return;
		}
		currentTab = tab;
		tabContent.removeAll();
		tabContent.add(tabPanels[index], BorderLayout.NORTH);
		tabContent.revalidate();
		tabContent.repaint();
		// A new tab starts at its top.
		if (getScrollPane() != null)
		{
			getScrollPane().getVerticalScrollBar().setValue(0);
		}
		if ("tracker".equals(tab))
		{
			trackerView.load();
		}
		else if ("history".equals(tab))
		{
			historyView.load();
		}
		refreshCurrentTab();
	}

	// The open tab's website / price views (about once a second).
	private void refreshCurrentTab()
	{
		if (currentTab == null)
		{
			return;
		}
		switch (currentTab)
		{
			case "plan":
				refreshSellOnlyHeader();
				planSetupView.refresh();
				planBoardView.refresh();
				break;
			case "tracker":
				trackerView.refresh();
				break;
			case "history":
				historyView.refresh();
				break;
			case "prices":
				pricesView.refresh(geScreenItemId, pinnedChartItem.get());
				break;
			default:
				break;
		}
	}

	private void refreshSellOnlyHeader()
	{
		boolean on = sellOnlyControl.isOn();
		sellOnlyHeaderSyncing = true;
		sellOnlyHeader.setSelected(on);
		sellOnlyHeaderSyncing = false;
		sellOnlyHeader.setEnabled(!sellOnlyHeaderBusy);
		sellOnlyHeader.setForeground(on ? Ui.WARN : ColorScheme.LIGHT_GRAY_COLOR);
	}

	// Set from the client thread every panel update.
	public void setGeScreenItem(int itemId)
	{
		geScreenItemId = itemId;
	}

	// Deliberately NOT called from the constructor above: Guice populates
	// @Inject fields (config, itemManager, websiteConnection, ...) AFTER the
	// constructor body finishes running, not before - referencing them from
	// inside the constructor is a real, guaranteed-null NPE trap, not just a
	// theoretical one. This is called from FlipprPlugin.startUp() instead,
	// which only ever runs once Guice has fully finished constructing and
	// injecting this panel, so every field is safely non-null by then.
	public void init()
	{
		buildTabs();
		showTab(config.sidebarTab(), false);
		refreshAccountDropdownOptions();
		updateOnEdt(0, 0, 0, new DecisionEngine.Suggestion[0], List.of(), List.of());
		refreshWebsiteSection();
	}

	// Real account names that have ever logged a flip on this machine
	// (SessionStatsAggregator.getKnownAccounts(), reads flips.jsonl) plus
	// "All accounts" - only called on init() and after a filter change
	// triggers a recompute, not every panel tick, since it's real file I/O.
	private void refreshAccountDropdownOptions()
	{
		String previouslySelected = (String) sessionAccountDropdown.getSelectedItem();
		sessionAccountDropdown.removeAllItems();
		for (String account : sessionStatsAggregator.getKnownAccounts())
		{
			sessionAccountDropdown.addItem(account);
		}
		sessionAccountDropdown.addItem("All accounts");

		if (previouslySelected != null)
		{
			sessionAccountDropdown.setSelectedItem(previouslySelected);
		}
	}

	// SESSION time-range + the current account is this plugin's existing
	// live behavior (FlipTracker's own in-memory counters), unchanged -
	// every other combination needs a real file-based aggregation instead.
	private boolean isViewingLiveSession()
	{
		SessionTimeRange range = (SessionTimeRange) sessionTimeRangeDropdown.getSelectedItem();
		String account = (String) sessionAccountDropdown.getSelectedItem();
		String currentRsn = sessionStatsAggregator.getCurrentRsn();
		return range == SessionTimeRange.SESSION && (account == null || account.equals(currentRsn));
	}

	private long computeSinceEpochMs(SessionTimeRange range)
	{
		return range.sinceEpochMs(System.currentTimeMillis(), customStartDate);
	}

	// Recomputes historicalStats when the time-range or account dropdown
	// actually changes - NOT on every panel tick, since this is real disk
	// I/O over flips.jsonl. Live-view selections just clear the cache
	// (updateOnEdt already knows to use the real-time parameters instead
	// whenever isViewingLiveSession() is true).
	private void onSessionFilterChanged()
	{
		if (sessionTimeRangeDropdown.getSelectedItem() == SessionTimeRange.PICK_START_DATE && customStartDate == null)
		{
			String input = JOptionPane.showInputDialog(this, "Start date (yyyy-MM-dd):", "");
			if (input == null || input.isBlank())
			{
				sessionTimeRangeDropdown.setSelectedItem(SessionTimeRange.SESSION);
				return;
			}
			try
			{
				customStartDate = LocalDate.parse(input.trim());
			}
			catch (DateTimeParseException e)
			{
				JOptionPane.showMessageDialog(this, "Couldn't parse that date - use yyyy-MM-dd.", "Flippr", JOptionPane.WARNING_MESSAGE);
				sessionTimeRangeDropdown.setSelectedItem(SessionTimeRange.SESSION);
				return;
			}
		}

		if (isViewingLiveSession())
		{
			historicalStats = null;
			return;
		}

		refreshHistoricalStats();
	}

	// The actual query, factored out of onSessionFilterChanged() so it can
	// also be re-run periodically (see HISTORICAL_REFRESH_INTERVAL_MS in
	// updateOnEdt()) rather than only once at selection time. That
	// one-shot-only version was a real, confirmed bug: computeSinceEpochMs()
	// is relative to "now" at the moment it's called, so a "Past Hour"
	// query computed once and never rerun quietly goes stale the instant
	// real time moves on - new flips completed after that moment never get
	// counted, the window's lower bound stops sliding forward with "now"
	// (so "Session time" for a fixed-window range grew unbounded past the
	// window's own label instead of tracking it), and a range selected
	// during a brief lull (zero qualifying flips at that exact instant)
	// would show 0gp/0 flips forever afterward even while real, profitable
	// flips kept completing right under it.
	private void refreshHistoricalStats()
	{
		SessionTimeRange range = (SessionTimeRange) sessionTimeRangeDropdown.getSelectedItem();
		String account = (String) sessionAccountDropdown.getSelectedItem();
		String rsnFilter = "All accounts".equals(account) ? null : account;

		long sinceEpochMs = computeSinceEpochMs(range);
		historicalStats = sessionStatsAggregator.aggregate(sinceEpochMs, rsnFilter);
		historicalRangeStartMs = sinceEpochMs > 0 ? sinceEpochMs : historicalStats.getEarliestTimestamp();
	}

	// Export/import scoped by character, per the actual request - operates
	// on whichever account is currently selected in sessionAccountDropdown
	// (falling back to the logged-in character if "All accounts" is
	// selected, since a single export file is always one character's data,
	// never a merged blob - see PortfolioExporter's own header comment for
	// why the portfolio half specifically can only ever come from whoever's
	// actually logged in right now).
	private JPanel buildExportImportRow()
	{
		JButton exportButton = new JButton("Export");
		exportButton.setFont(FontManager.getRunescapeSmallFont());
		exportButton.addActionListener(e -> exportSelectedAccount());

		JButton importButton = new JButton("Import");
		importButton.setFont(FontManager.getRunescapeSmallFont());
		importButton.addActionListener(e -> importIntoSelectedAccount());

		JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 0, 0, 0));
		row.add(exportButton);
		row.add(importButton);
		return row;
	}

	private String resolveExportRsn()
	{
		String selected = (String) sessionAccountDropdown.getSelectedItem();
		if (selected == null || "All accounts".equals(selected))
		{
			return sessionStatsAggregator.getCurrentRsn();
		}
		return selected;
	}

	private void exportSelectedAccount()
	{
		String rsn = resolveExportRsn();
		if (rsn == null)
		{
			JOptionPane.showMessageDialog(this, "No character to export yet - log in first.", "Flippr", JOptionPane.WARNING_MESSAGE);
			return;
		}

		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new java.io.File(rsn + "-flippr-export.json"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			portfolioExporter.export(rsn, chooser.getSelectedFile().toPath());
			JOptionPane.showMessageDialog(this, "Exported " + rsn + "'s portfolio, trades, and sessions.", "Flippr", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (java.io.IOException ex)
		{
			log.warn("Export failed", ex);
			JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Flippr", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void importIntoSelectedAccount()
	{
		JFileChooser chooser = new JFileChooser();
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		try
		{
			PortfolioExporter.ImportResult result = portfolioExporter.importFrom(chooser.getSelectedFile().toPath());
			refreshAccountDropdownOptions();
			String message = String.format(
				"Imported %s: %d new flip(s), %d new session(s), %d position(s) restored.",
				result.rsn == null ? "unknown character" : result.rsn,
				result.flipsAdded, result.sessionsAdded, result.positionsRestored
			);
			JOptionPane.showMessageDialog(this, message, "Flippr", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (java.io.IOException ex)
		{
			log.warn("Import failed", ex);
			JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(), "Flippr", JOptionPane.ERROR_MESSAGE);
		}
	}

	private JPanel buildQuickControls()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(CARD_COLOR);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));

		// 2x2, not a single row of 4 - "Highlight"/"Verbose"/"Timers" don't fit
		// four-across in the sidebar's real width without truncating.
		JPanel toggleRow = new JPanel(new GridLayout(2, 2, 4, 4));
		toggleRow.setBackground(CARD_COLOR);
		stylePillToggle(engineToggle, ENGINE_ICON, "Master pause for the decision engine");
		stylePillToggle(highlightToggle, HIGHLIGHT_ICON, "Highlight the current suggestion on the Grand Exchange interface");
		stylePillToggle(verboseToggle, VERBOSE_ICON, "Log every Grand Exchange offer event to the client log");
		stylePillToggle(timersToggle, TIMER_ICON, "Show a live timer on each active GE slot since it last made buy/sell progress");
		toggleRow.add(engineToggle);
		toggleRow.add(highlightToggle);
		toggleRow.add(verboseToggle);
		toggleRow.add(timersToggle);
		panel.add(toggleRow);

		stylePillToggle(watchModeToggle, null,
			"Silence everything - no suggestions, no highlighting, no chat lines, no dump/new-item/inventory "
				+ "alerts. Your flips and inventory are still recorded as normal.");
		stylePillToggle(watchModeDumpToggle, null,
			"Same silence as Watch Mode. Use this instead while you're hunting dumps (not regular flipping) - "
				+ "every flip and session recorded while it's on is marked as dump-hunting, so the two can be "
				+ "told apart.");
		// Side by side, not stacked - these are two flavors of the same
		// underlying "just watch" state, not unrelated settings.
		JPanel watchModeRow = new JPanel(new GridLayout(1, 2, 4, 0));
		watchModeRow.setBackground(CARD_COLOR);
		watchModeRow.setBorder(new EmptyBorder(4, 0, 0, 0));
		watchModeRow.add(watchModeToggle);
		watchModeRow.add(watchModeDumpToggle);
		panel.add(watchModeRow);

		engineToggle.addActionListener(e -> setConfig("engineEnabled", engineToggle.isSelected()));
		highlightToggle.addActionListener(e -> setConfig("highlightSuggestions", highlightToggle.isSelected()));
		verboseToggle.addActionListener(e -> setConfig("verboseLogging", verboseToggle.isSelected()));
		timersToggle.addActionListener(e -> setConfig("showSlotTimers", timersToggle.isSelected()));
		watchModeToggle.addActionListener(e -> setConfig("watchModeEnabled", watchModeToggle.isSelected()));
		watchModeDumpToggle.addActionListener(e -> setConfig("watchModeDumpEnabled", watchModeDumpToggle.isSelected()));

		staleFillSpinner.addChangeListener(e -> setConfig("staleFillMinutes", staleFillSpinner.getValue()));
		priceMoveSpinner.addChangeListener(e -> setConfig("priceMoveThresholdPercent", priceMoveSpinner.getValue()));
		modifyFallbackSpinner.addChangeListener(e -> setConfig("modifyFallbackMinutes", modifyFallbackSpinner.getValue()));
		// Enum ConfigItems round-trip through RuneLite's ConfigManager by
		// constant NAME (Enum.valueOf), not by our own toString() display
		// label - .name() explicitly here, not the generic setConfig()
		// helper's String.valueOf(), which would pass the wrong string
		// ("Auto" instead of "AUTO") and silently fail to persist.
		minPredictedProfitDropdown.addActionListener(e ->
			setConfig("minPredictedProfit", ((MinPredictedProfit) minPredictedProfitDropdown.getSelectedItem()).name()));
		reservedDumpSlotsDropdown.addActionListener(e ->
			setConfig("reservedDumpSlots", ((ReservedDumpSlots) reservedDumpSlotsDropdown.getSelectedItem()).name()));

		JPanel settingsRows = new JPanel(new GridLayout(0, 1, 0, 4));
		settingsRows.setBackground(CARD_COLOR);
		settingsRows.setBorder(new EmptyBorder(8, 0, 0, 0));
		settingsRows.add(labeledSpinner("Stale buy (min)", staleFillSpinner));
		settingsRows.add(labeledSpinner("Price-move nudge (%)", priceMoveSpinner));
		settingsRows.add(labeledSpinner("Modify fallback (min)", modifyFallbackSpinner));
		settingsRows.add(labeledDropdown("Min. predicted profit", minPredictedProfitDropdown));
		settingsRows.add(labeledDropdown("Reserved slots", reservedDumpSlotsDropdown));
		panel.add(settingsRows);

		return panel;
	}

	// A compact segmented-toggle look (accent fill when on, muted outline when
	// off) - one ChangeListener recolors it on every selection change,
	// whether that change came from a real click or updateOnEdt()'s
	// setSelected() syncing from config, so the two paths can't drift out of
	// visual sync.
	private void stylePillToggle(PillToggleButton toggle, ImageIcon icon, String tooltip)
	{
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setIcon(icon);
		toggle.setIconTextGap(5);
		toggle.setToolTipText(tooltip);
		toggle.setBackground(ColorScheme.DARK_GRAY_COLOR);
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		toggle.addChangeListener(e ->
		{
			toggle.setBackground(toggle.isSelected() ? ACCENT_COLOR : ColorScheme.DARK_GRAY_COLOR);
			toggle.setForeground(toggle.isSelected() ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	// RuneLite's look-and-feel skins JToggleButton/JButton itself and ignores
	// a plain setBackground() call for the actual painted fill - confirmed
	// live: engineToggle stayed visibly flat gray even while genuinely
	// selected (suggestions were populated, which only happens when
	// engineEnabled() is true). setContentAreaFilled(false) stops the LAF's
	// own ButtonUI from painting over us, and paintComponent fills the real
	// background color itself before the text is drawn, so selection state
	// is guaranteed to be visible regardless of LAF quirks.
	private static class PillToggleButton extends JToggleButton
	{
		PillToggleButton(String text)
		{
			super(text);
			setContentAreaFilled(false);
			setFocusPainted(false);
			setBorderPainted(false);
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
			super.paintComponent(g);
		}
	}

	private JPanel labeledSpinner(String labelText, JSpinner spinner)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(CARD_COLOR);

		JLabel label = new JLabel(labelText);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		row.add(label, BorderLayout.CENTER);
		row.add(spinner, BorderLayout.EAST);
		return row;
	}

	private JPanel labeledDropdown(String labelText, JComboBox<?> dropdown)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(CARD_COLOR);

		JLabel label = new JLabel(labelText);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		dropdown.setBackground(ColorScheme.DARK_GRAY_COLOR);
		dropdown.setForeground(Color.WHITE);
		dropdown.setFont(FontManager.getRunescapeSmallFont());
		dropdown.setFocusable(false);

		row.add(label, BorderLayout.CENTER);
		row.add(dropdown, BorderLayout.EAST);
		return row;
	}

	// Deliberately NOT a CARD_COLOR box, unlike every other section - this
	// headline reads better breathing against the page background, with
	// hierarchy carried by size/color/spacing alone (large colored profit
	// figure, small dim stat rows underneath) rather than a container edge.
	private JPanel buildStatsBlock()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 2, 4, 2));

		sessionTimeRangeDropdown.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sessionTimeRangeDropdown.setForeground(Color.WHITE);
		sessionTimeRangeDropdown.setFont(FontManager.getRunescapeSmallFont());
		sessionTimeRangeDropdown.setFocusable(false);
		sessionTimeRangeDropdown.addActionListener(e -> onSessionFilterChanged());

		JButton resetButton = new JButton("Reset");
		resetButton.setFont(FontManager.getRunescapeSmallFont());
		resetButton.setForeground(ACCENT_COLOR);
		resetButton.setContentAreaFilled(false);
		resetButton.setBorderPainted(false);
		resetButton.setFocusPainted(false);
		resetButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
		resetButton.setToolTipText("Save this session's totals and start a fresh one");
		// flipTracker's own totals zero out immediately; the panel's next
		// regular update (within 1s, driven by FlipprPlugin's game-tick
		// loop) picks up the reset values naturally, same as any other
		// state change - no separate manual refresh needed here. Also
		// snaps the view back to Session, so resetting while looking at a
		// different range doesn't leave the player staring at numbers that
		// barely moved and wondering if Reset even worked.
		resetButton.addActionListener(e ->
		{
			flipTracker.resetSession();
			sessionTimeRangeDropdown.setSelectedItem(SessionTimeRange.SESSION);
		});

		JPanel headlineRow = new JPanel(new BorderLayout());
		headlineRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headlineRow.add(sessionTimeRangeDropdown, BorderLayout.CENTER);
		headlineRow.add(resetButton, BorderLayout.EAST);
		panel.add(headlineRow);

		sessionAccountDropdown.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sessionAccountDropdown.setForeground(Color.WHITE);
		sessionAccountDropdown.setFont(FontManager.getRunescapeSmallFont());
		sessionAccountDropdown.setFocusable(false);
		sessionAccountDropdown.addActionListener(e -> onSessionFilterChanged());
		JPanel accountRow = new JPanel(new BorderLayout());
		accountRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		accountRow.setBorder(new EmptyBorder(2, 0, 0, 0));
		accountRow.add(sessionAccountDropdown, BorderLayout.CENTER);
		panel.add(accountRow);

		panel.add(buildExportImportRow());

		profitValueLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(20f));
		panel.add(profitValueLabel);

		JPanel statRows = new JPanel(new GridLayout(0, 1, 0, 2));
		statRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		statRows.setBorder(new EmptyBorder(6, 0, 0, 0));
		statRows.add(statRow("Flips made", flipsValueLabel));
		statRows.add(statRow("Hourly profit", hourlyValueLabel));
		statRows.add(statRow("Session time", timeValueLabel));
		statRows.add(statRow("Reserve floor", reserveFloorLabel));
		panel.add(statRows);

		return panel;
	}

	private JPanel statRow(String labelText, JLabel valueLabel)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel label = new JLabel(labelText);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.WHITE);

		row.add(label, BorderLayout.WEST);
		row.add(valueLabel, BorderLayout.EAST);
		return row;
	}

	private void setConfig(String key, Object value)
	{
		configManager.setConfiguration("flippr", key, String.valueOf(value));
	}

	private static JLabel sectionHeader(String text)
	{
		JLabel header = new JLabel(text.toUpperCase());
		header.setForeground(ACCENT_COLOR);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setBorder(new EmptyBorder(12, 2, 4, 0));
		return header;
	}

	// Safe to call from any thread - marshals onto the Swing EDT itself, so
	// FlipprPlugin's game-thread event handlers can call this directly.
	public void update(int sessionFlipCount, long sessionProfit, long sessionDurationMs,
		DecisionEngine.Suggestion[] suggestions, List<CompletedFlip> recentFlips, List<OpenPositionEntry> openPositions)
	{
		SwingUtilities.invokeLater(() ->
			updateOnEdt(sessionFlipCount, sessionProfit, sessionDurationMs, suggestions, recentFlips, openPositions));
	}

	private void updateOnEdt(int sessionFlipCount, long sessionProfit, long sessionDurationMs,
		DecisionEngine.Suggestion[] suggestions, List<CompletedFlip> recentFlips, List<OpenPositionEntry> openPositions)
	{
		// Reflects the true current config even if it changed elsewhere
		// (e.g. RuneLite's own settings panel) - setSelected/setValue are
		// no-ops when the value is already correct, so this can't fight the
		// user mid-edit or loop back into the listeners above.
		updateWebsiteStatus();
		refreshGuidanceBanner();
		engineToggle.setSelected(config.engineEnabled());
		highlightToggle.setSelected(config.highlightSuggestions());
		verboseToggle.setSelected(config.verboseLogging());
		timersToggle.setSelected(config.showSlotTimers());
		watchModeToggle.setSelected(config.watchModeEnabled());
		watchModeDumpToggle.setSelected(config.watchModeDumpEnabled());
		staleFillSpinner.setValue(config.staleFillMinutes());
		priceMoveSpinner.setValue(config.priceMoveThresholdPercent());
		modifyFallbackSpinner.setValue(config.modifyFallbackMinutes());
		minPredictedProfitDropdown.setSelectedItem(config.minPredictedProfit());
		reservedDumpSlotsDropdown.setSelectedItem(config.reservedDumpSlots());

		// isViewingLiveSession(): SESSION time-range + the current account,
		// this plugin's existing live behavior, unchanged - anything else
		// renders from historicalStats instead (real file-based
		// aggregation over flips.jsonl). Re-run periodically, throttled to
		// HISTORICAL_REFRESH_INTERVAL_MS rather than every tick (real disk
		// I/O) - see refreshHistoricalStats()'s own comment for why a
		// selection-change-only refresh was a real, confirmed bug: a fixed
		// window like "Past Hour" has to keep sliding forward with real
		// time to mean what its label says, and new flips completed after
		// the dropdown was last touched need to actually show up.
		if (!isViewingLiveSession()
			&& System.currentTimeMillis() - lastHistoricalRefreshMs >= HISTORICAL_REFRESH_INTERVAL_MS)
		{
			refreshHistoricalStats();
			lastHistoricalRefreshMs = System.currentTimeMillis();
		}

		int displayFlipCount;
		long displayProfit;
		long displayDurationMs;
		if (isViewingLiveSession())
		{
			displayFlipCount = sessionFlipCount;
			displayProfit = sessionProfit;
			displayDurationMs = sessionDurationMs;
		}
		else if (historicalStats != null)
		{
			displayFlipCount = historicalStats.getFlipCount();
			displayProfit = historicalStats.getProfit();
			displayDurationMs = historicalRangeStartMs > 0 ? System.currentTimeMillis() - historicalRangeStartMs : 0;
		}
		else
		{
			// Selection just changed and hasn't been computed yet (or
			// there's genuinely no data for it) - show zero rather than
			// stale live numbers from a different view.
			displayFlipCount = 0;
			displayProfit = 0;
			displayDurationMs = 0;
		}

		profitValueLabel.setForeground(displayProfit >= 0 ? config.profitColor() : config.lossColor());
		profitValueLabel.setText(
			(displayProfit >= 0 ? "+" : "-") + QuantityFormatter.formatNumber(Math.abs(displayProfit)) + " gp"
		);
		flipsValueLabel.setText(String.valueOf(displayFlipCount));

		// Guarded against the first seconds of a session (or a client just
		// started): with hoursElapsed near zero, profit/hoursElapsed would
		// otherwise blow up towards +-Infinity/NaN and render garbage.
		double hoursElapsed = displayDurationMs / 3_600_000.0;
		long hourlyProfit = hoursElapsed >= (1.0 / 60) ? Math.round(displayProfit / hoursElapsed) : 0;
		hourlyValueLabel.setForeground(hourlyProfit >= 0 ? config.profitColor() : config.lossColor());
		hourlyValueLabel.setText(
			(hourlyProfit >= 0 ? "+" : "-") + QuantityFormatter.formatNumber(Math.abs(hourlyProfit)) + " gp/hr"
		);
		timeValueLabel.setText(formatDuration(displayDurationMs));

		// The reserve floor - real visibility into
		// what's actually protected, not just a config value the player set
		// once and can no longer see the effect of.
		int reserveFloorGp = config.reserveFloorGp();
		reserveFloorLabel.setForeground(reserveFloorGp > 0 ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
		reserveFloorLabel.setText(reserveFloorGp > 0
			? QuantityFormatter.formatNumber(reserveFloorGp) + " gp protected"
			: "not set");

		updateHeaderStatus();
		refreshCurrentTab();

		portfolioContainer.removeAll();
		if (openPositions.isEmpty())
		{
			portfolioContainer.add(dimLabel("No open positions"));
		}
		else
		{
			for (OpenPositionEntry position : openPositions)
			{
				portfolioContainer.add(buildPortfolioRow(position));
			}
		}
		portfolioContainer.revalidate();
		portfolioContainer.repaint();

		accountsContainer.removeAll();
		List<MultiAccountAggregator.AccountSummary> accountSummaries = multiAccountAggregator.getAccountSummaries();
		if (accountSummaries.isEmpty())
		{
			accountsContainer.add(dimLabel("No flips recorded today yet"));
		}
		else
		{
			String currentRsn = multiAccountAggregator.getCurrentRsn();
			for (MultiAccountAggregator.AccountSummary account : accountSummaries)
			{
				accountsContainer.add(buildAccountRow(account, account.getRsn().equals(currentRsn)));
			}
		}
		accountsContainer.revalidate();
		accountsContainer.repaint();

		suggestionsContainer.removeAll();
		boolean anySuggestion = false;
		for (DecisionEngine.Suggestion suggestion : suggestions)
		{
			if (suggestion == null)
			{
				continue;
			}
			anySuggestion = true;
			suggestionsContainer.add(buildSuggestionRow(suggestion));
		}
		if (!anySuggestion)
		{
			String idleText = config.watchModeEnabled()
				? "Watch Mode - logging flips only"
				: config.watchModeDumpEnabled()
					? "Watch Mode (Dump) - logging dump-mode flips only"
					: config.engineEnabled() ? "Nothing due right now" : "Engine paused";
			suggestionsContainer.add(dimLabel(idleText));
		}
		suggestionsContainer.revalidate();
		suggestionsContainer.repaint();

		recentFlipsContainer.removeAll();
		if (recentFlips.isEmpty())
		{
			recentFlipsContainer.add(dimLabel("No flips completed yet"));
		}
		else
		{
			for (CompletedFlip flip : recentFlips)
			{
				recentFlipsContainer.add(buildFlipRow(flip));
			}
		}
		recentFlipsContainer.revalidate();
		recentFlipsContainer.repaint();
	}

	// Rebuilds the Website section when its layout changes (not configured /
	// not paired / key rejected / paired, plus the status it shows), same
	// pattern as suggestions/recent flips. Called every refresh by
	// updateWebsiteStatus(), which only rebuilds when the state key changed.
	private void refreshWebsiteSection()
	{
		websiteContainer.removeAll();
		builtWebsiteState = websiteStateKey();

		if (!websiteConnection.isConfigured())
		{
			websiteContainer.add(wrappedLabel("Website features are off (optional). To use them: set "
				+ "\"Flippr website URL\" in this plugin's settings, sign in on the site, then enter the pairing "
				+ "code from your Account page here."));
		}
		else if (!websiteConnection.isPaired() || websiteConnection.isAuthRejected())
		{
			JTextField codeField = new JTextField();
			codeField.setFont(FontManager.getRunescapeSmallFont());

			JButton connectButton = new JButton("Connect");
			connectButton.addActionListener(e -> runWebsiteAction(connectButton, () -> websiteConnection.pair(codeField.getText())));

			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(CARD_COLOR);
			row.add(codeField, BorderLayout.CENTER);
			row.add(connectButton, BorderLayout.EAST);

			if (websiteConnection.isAuthRejected())
			{
				JLabel rejected = wrappedLabel("This plugin key is no longer valid - pair again with a new code.");
				rejected.setForeground(config.lossColor());
				websiteContainer.add(rejected);
			}
			else
			{
				websiteContainer.add(wrappedLabel("Not connected - enter the pairing code from your Account page "
					+ "on " + websiteConnection.baseUrl()));
			}
			websiteContainer.add(row);
		}
		else
		{
			WebsiteConnection.Status status = websiteConnection.getLastStatus();
			JButton verifyButton = new JButton("Verify Premium");
			verifyButton.addActionListener(e -> runWebsiteAction(verifyButton, websiteConnection::verifyPremium));

			String character = websiteConnection.getCharacter();
			String characterError = websiteConnection.getCharacterError();
			if (character == null)
			{
				websiteContainer.add(dimLabel("Connected - log in to a character to sync"));
			}
			else if (status == null && characterError == null)
			{
				websiteContainer.add(dimLabel("Connected - checking " + character + "..."));
			}
			else
			{
				// A legacy one-character key always acts for its own
				// character; an account key for whoever is logged in.
				String linkedCharacter = status != null && status.characterName != null ? status.characterName : character;
				String email = status != null && status.email != null ? status.email : "your Flippr account";
				websiteContainer.add(wrappedLabel("Linked to " + email + " - playing " + linkedCharacter));
				if (status != null && "character".equals(status.scope) && !linkedCharacter.equalsIgnoreCase(character))
				{
					websiteContainer.add(wrappedLabel("This key only covers " + linkedCharacter + ". Pair again with a code "
						+ "from your Account page to use every character."));
				}
			}

			if (characterError != null)
			{
				JLabel conflict = wrappedLabel(characterError);
				conflict.setForeground(config.lossColor());
				websiteContainer.add(conflict);
			}
			else if (status != null && character != null)
			{
				JLabel discordLabel = dimLabel("Discord: " + (status.discordLinked ? status.discordUsername : "not linked"));
				discordLabel.setForeground(status.discordLinked ? config.profitColor() : ColorScheme.LIGHT_GRAY_COLOR);
				websiteContainer.add(discordLabel);

				boolean seat = status.seat != null && status.seat.assigned;
				JLabel premiumLabel = new JLabel(status.premium
					? "Premium: yes" + (seat ? " (seat assigned)" : status.allCharactersIncluded ? " (all characters)" : "")
					: "Premium: no - free suggestions are F2P items");
				premiumLabel.setForeground(status.premium ? config.profitColor() : ColorScheme.LIGHT_GRAY_COLOR);
				premiumLabel.setFont(FontManager.getRunescapeSmallFont());
				websiteContainer.add(premiumLabel);
				// The website's in-game name check (PremiumVerification).
				PremiumVerification.Line verification = PremiumVerification.statusLine(status, character);
				if (verification != null)
				{
					JLabel verificationLabel = wrappedLabel(verification.text);
					verificationLabel.setForeground(verification.tone == PremiumVerification.Tone.GOOD ? config.profitColor()
						: verification.tone == PremiumVerification.Tone.BAD ? config.lossColor() : ColorScheme.LIGHT_GRAY_COLOR);
					websiteContainer.add(verificationLabel);
				}
				if (status.premiumGames != null && !status.premiumGames.isEmpty())
				{
					websiteContainer.add(dimLabel("Premium games: " + String.join(", ", status.premiumGames)));
				}
				if (!status.premium)
				{
					websiteContainer.add(dimLabel("Upgrade at " + websiteConnection.baseUrl() + "/subscriptions"));
				}
			}

			for (JLabel label : new JLabel[]{liveSyncLabel, uploadsLabel, suggestionsStatusLabel})
			{
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				websiteContainer.add(label);
			}

			JCheckBox syncBox = new JCheckBox("Sync with website", config.syncWithWebsite());
			syncBox.setBackground(CARD_COLOR);
			syncBox.setFont(FontManager.getRunescapeSmallFont());
			syncBox.addActionListener(e -> setConfig("syncWithWebsite", syncBox.isSelected()));
			websiteContainer.add(syncBox);
			websiteContainer.add(wrappedLabel("When on, this sends only your own data: this character's GE slots "
				+ "(item, price, quantity filled) and completed flips, and fetches flip suggestions."));

			JButton uploadHistoryButton = new JButton("Upload history");
			uploadHistoryButton.setToolTipText("Queue every past flip of this character for the "
				+ "website Tracker (already-uploaded flips are skipped by the server)");
			uploadHistoryButton.setEnabled(config.syncWithWebsite());
			uploadHistoryButton.addActionListener(e -> uploadHistory(uploadHistoryButton));

			JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
			buttons.setBackground(CARD_COLOR);
			buttons.add(verifyButton);
			buttons.add(uploadHistoryButton);
			websiteContainer.add(buttons);
		}

		updateWebsiteStatusLabels();
		websiteContainer.revalidate();
		websiteContainer.repaint();
	}

	// Everything that changes the Website section's layout.
	private String websiteStateKey()
	{
		WebsiteConnection.Status status = websiteConnection.getLastStatus();
		return websiteConnection.isConfigured() + ":" + websiteConnection.isPaired() + ":"
			+ websiteConnection.isAuthRejected() + ":" + config.syncWithWebsite() + ":"
			+ websiteConnection.getCharacter() + ":" + websiteConnection.getCharacterError() + ":"
			+ (status == null ? "-" : status.characterName + "/" + status.premium + "/" + status.discordUsername + "/" + status.premiumState);
	}

	private void updateWebsiteStatus()
	{
		if (websiteRequestInFlight)
		{
			return;
		}
		if (!websiteStateKey().equals(builtWebsiteState))
		{
			refreshWebsiteSection();
		}
		else
		{
			updateWebsiteStatusLabels();
		}
	}

	// The line under the title, and the Plan tab's Premium note: P2P items
	// need Premium for this character, but nothing else is blocked - F2P
	// flips, the Plan, Tracker, History and Prices keep working.
	private void updateHeaderStatus()
	{
		String header;
		if (!websiteConnection.isConfigured())
		{
			header = "Website off - local features only";
		}
		else if (!websiteConnection.isPaired() || websiteConnection.isAuthRejected())
		{
			header = "Not paired - see Plan > Account";
		}
		else if (websiteConnection.getCharacter() == null)
		{
			header = "Paired - log in to sync";
		}
		else if (websiteConnection.getCharacterError() != null)
		{
			header = "Can't sync this character - see Account";
		}
		else if (websiteConnection.isInMaintenance())
		{
			header = "Flippr maintenance - retrying";
		}
		else if (!config.syncWithWebsite())
		{
			header = "Sync off - " + websiteConnection.getCharacter();
		}
		else
		{
			header = "Synced - " + websiteConnection.getCharacter();
		}
		headerStatusLabel.setText(header);

		String premiumBlocked = websiteConnection.getPremiumBlockedMessage();
		String notice = null;
		if (serverSuggestions.isGated())
		{
			notice = premiumBlocked != null
				? "P2P items are paused: " + premiumBlocked + " F2P flips keep working."
				: "Free plan: Flippr suggests F2P items for this character. P2P items need Premium ("
					+ websiteConnection.baseUrl() + "/subscriptions) - everything else works.";
		}
		premiumNoticeLabel.setVisible(notice != null);
		premiumNoticeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		premiumNoticeLabel.setText(notice == null ? "" : "<html><body style='width:180px'>" + escapeHtml(notice) + "</body></html>");
	}

	// "Live sync: on - last sent 3s ago", "Uploads queued: 0", and the
	// maintenance/offline/Premium notes - refreshed every panel tick.
	private void updateWebsiteStatusLabels()
	{
		long now = System.currentTimeMillis();
		if (!config.syncWithWebsite())
		{
			liveSyncLabel.setText("Live sync: off");
			uploadsLabel.setText("Uploads queued: " + Math.max(0, tradeUploader.getQueuedCount()));
			suggestionsStatusLabel.setText("");
			return;
		}
		if (websiteConnection.isInMaintenance())
		{
			liveSyncLabel.setText("Flippr is under maintenance - retrying in "
				+ formatAgo(websiteConnection.getMaintenanceRemainingMs()));
			liveSyncLabel.setForeground(config.lossColor());
		}
		else if (offerSync.getRetryRemainingMs() > 0)
		{
			liveSyncLabel.setText("Offline - retrying in " + formatAgo(offerSync.getRetryRemainingMs()));
			liveSyncLabel.setForeground(config.lossColor());
		}
		else
		{
			long lastSent = offerSync.getLastSentAt();
			liveSyncLabel.setText("Live sync: on" + (lastSent == 0 ? "" : " - last sent " + formatAgo(now - lastSent) + " ago"));
			liveSyncLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		long uploadRetry = tradeUploader.getRetryRemainingMs();
		uploadsLabel.setText("Uploads queued: " + Math.max(0, tradeUploader.getQueuedCount())
			+ (uploadRetry > 0 ? " (retrying in " + formatAgo(uploadRetry) + ")" : ""));

		String premiumBlocked = websiteConnection.getPremiumBlockedMessage();
		if (serverSuggestions.isGated() && premiumBlocked != null)
		{
			// Seat is there, the in-game name check isn't: F2P suggestions
			// keep coming, say why P2P ones don't.
			suggestionsStatusLabel.setText("<html>Free suggestions only: " + escapeHtml(premiumBlocked) + "</html>");
		}
		else if (serverSuggestions.isGated())
		{
			suggestionsStatusLabel.setText("<html>P2P suggestions need Premium for this character - "
				+ escapeHtml(websiteConnection.baseUrl()) + "/subscriptions</html>");
		}
		else if (serverSuggestions.isFailing() && serverSuggestions.getRetryRemainingMs() > 0)
		{
			suggestionsStatusLabel.setText("Suggestions offline - retrying in "
				+ formatAgo(serverSuggestions.getRetryRemainingMs()));
		}
		else
		{
			suggestionsStatusLabel.setText(serverSuggestions.getData() == null ? "Suggestions: waiting for the website"
				: serverSuggestions.getData().hasPlan() ? "Suggestions: from your plan" : "Suggestions: from the website");
		}
	}

	// 3s / 2m / 1h 5m
	static String formatAgo(long ms)
	{
		long seconds = Math.max(0, ms) / 1000;
		if (seconds < 60)
		{
			return seconds + "s";
		}
		long minutes = seconds / 60;
		if (minutes < 60)
		{
			return minutes + "m";
		}
		return (minutes / 60) + "h " + (minutes % 60) + "m";
	}

	private void uploadHistory(JButton button)
	{
		String rsn = flipTracker.getLastKnownRsn();
		if (rsn == null)
		{
			JOptionPane.showMessageDialog(this, "Log in first - history is uploaded for the logged-in character.",
				"Flippr", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		button.setEnabled(false);
		executor.execute(() ->
		{
			int queued = tradeUploader.queueHistory(rsn);
			SwingUtilities.invokeLater(() ->
			{
				button.setEnabled(true);
				JOptionPane.showMessageDialog(this, queued == 0
						? "No past flips found for " + rsn + "."
						: "Queued " + queued + " past flip(s) of " + rsn + " for upload. Already-uploaded ones are skipped.",
					"Flippr", JOptionPane.INFORMATION_MESSAGE);
			});
		});
	}

	private static JLabel wrappedLabel(String text)
	{
		JLabel label = dimLabel("<html><body style='width:170px'>" + escapeHtml(text) + "</body></html>");
		return label;
	}

	// Runs a WebsiteConnection call off the EDT (it blocks on real network
	// I/O) and marshals the result back on. Disables the triggering button
	// for the duration so a slow/stuck request can't be double-fired.
	private void runWebsiteAction(JButton button, java.util.function.Supplier<WebsiteConnection.Result<?>> action)
	{
		if (websiteRequestInFlight)
		{
			return;
		}
		websiteRequestInFlight = true;
		button.setEnabled(false);
		button.setText("Working...");

		// RuneLite's shared executor: off the EDT and off the client thread.
		executor.submit(() ->
		{
			WebsiteConnection.Result<?> result = action.get();
			SwingUtilities.invokeLater(() ->
			{
				websiteRequestInFlight = false;
				// Refresh BEFORE the (blocking, modal) error dialog - otherwise
				// the button sits frozen on "Working..." behind the dialog
				// until it's dismissed, which read as the panel being stuck.
				refreshWebsiteSection();
				if (result.isError())
				{
					JOptionPane.showMessageDialog(this, result.error, "Flippr", JOptionPane.WARNING_MESSAGE);
				}
			});
		});
	}

	private JPanel buildSuggestionRow(DecisionEngine.Suggestion suggestion)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_COLOR);
		row.setBorder(new CompoundBorder(
			new MatteBorder(0, 3, 0, 0, colorFor(suggestion.type)),
			new EmptyBorder(3, 5, 3, 0)
		));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(20, 20));
		if (suggestion.itemId > 0)
		{
			itemManager.getImage(suggestion.itemId).addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);

		String itemPart = suggestion.itemName == null ? "" : " - " + suggestion.itemName;
		if (suggestion.itemName != null && suggestion.suggestedQuantity != null && suggestion.suggestedPrice != null)
		{
			itemPart = " - " + QuantityFormatter.formatNumber(suggestion.suggestedQuantity) + " x " + suggestion.itemName
				+ " @ " + QuantityFormatter.formatNumber(suggestion.suggestedPrice);
		}
		else if (suggestion.itemName != null && suggestion.suggestedPrice != null)
		{
			itemPart = " - " + suggestion.itemName + " to " + QuantityFormatter.formatNumber(suggestion.suggestedPrice);
		}
		JLabel text = new JLabel(String.format(
			"<html>slot %d: <b>%s</b>%s</html>", suggestion.slot, actionLabel(suggestion.type), escapeHtml(itemPart)
		));
		text.setForeground(colorFor(suggestion.type));
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setToolTipText(suggestion.message);
		row.add(text, BorderLayout.CENTER);

		return row;
	}

	// Portfolio row: market value, unrealized profit/ROI, avg buy price, time
	// held - for a position this plugin itself tracked buying (not a live
	// inventory scan, so a player's own pre-owned/manually-banked stock never
	// shows up here). Market value/unrealized profit use
	// PriceFeed's live instant-sell price (what selling right now would
	// actually realize) - shown as "-" rather than a fabricated number for
	// any item PriceFeed has never observed.
	private JPanel buildPortfolioRow(OpenPositionEntry position)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_COLOR);

		// PortfolioMath.getMarketPrice: the item's own real active sell
		// offer price when one exists, else PriceFeed's mid price (not the
		// bid-only instant-sell price) - see PortfolioMath's own comment
		// for why. Deliberately GROSS, no tax subtracted: unrealized profit
		// is a clean price difference with no tax term.
		// This is a mark-to-market READOUT of stock, not a promise about
		// what an actual future sale would net after tax - that's what a
		// completed flip's own FlipLogger record already captures for real.
		Long marketPrice = PortfolioMath.getMarketPrice(decisionEngine, priceFeed, position.getItemId());
		Long unrealizedProfit = marketPrice == null
			? null
			: (marketPrice - position.getAvgBuyPrice()) * position.getQuantity();

		row.setBorder(new CompoundBorder(
			new MatteBorder(0, 3, 0, 0, unrealizedProfit == null || unrealizedProfit >= 0
				? config.profitColor() : config.lossColor()),
			new EmptyBorder(3, 5, 3, 0)
		));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(20, 20));
		itemManager.getImage(position.getItemId()).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JPanel centerColumn = new JPanel();
		centerColumn.setLayout(new BoxLayout(centerColumn, BoxLayout.Y_AXIS));
		centerColumn.setBackground(CARD_COLOR);

		JLabel nameLabel = new JLabel(String.format(
			"<html>%dx <b>%s</b></html>", position.getQuantity(), escapeHtml(position.getItemName())
		));
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		centerColumn.add(nameLabel);

		JLabel detailLabel = dimLabel(String.format(
			"avg %s gp - held %s",
			QuantityFormatter.formatNumber(position.getAvgBuyPrice()),
			formatDuration(System.currentTimeMillis() - position.getFirstBuyTime())
		));
		centerColumn.add(detailLabel);
		row.add(centerColumn, BorderLayout.CENTER);

		JLabel profitLabel;
		if (unrealizedProfit == null)
		{
			profitLabel = new JLabel("-");
			profitLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			long costBasis = position.getAvgBuyPrice() * position.getQuantity();
			double roiPercent = costBasis == 0 ? 0 : (unrealizedProfit * 100.0) / costBasis;
			profitLabel = new JLabel(String.format(
				"<html>%s%s gp<br>%s%.1f%%</html>",
				unrealizedProfit >= 0 ? "+" : "-", QuantityFormatter.formatNumber(Math.abs(unrealizedProfit)),
				unrealizedProfit >= 0 ? "+" : "-", Math.abs(roiPercent)
			));
			profitLabel.setForeground(unrealizedProfit >= 0 ? config.profitColor() : config.lossColor());
		}
		profitLabel.setFont(FontManager.getRunescapeSmallFont());
		profitLabel.setHorizontalAlignment(JLabel.RIGHT);
		row.add(profitLabel, BorderLayout.EAST);

		return row;
	}

	// "You" isn't a real second account - it's this row's plain-language
	// stand-in for "the account this exact plugin instance is running as,"
	// so a player running several clients side by side can tell their own
	// row apart from the others at a glance without reading RSNs closely.
	private JPanel buildAccountRow(MultiAccountAggregator.AccountSummary account, boolean isCurrentAccount)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_COLOR);
		row.setBorder(new CompoundBorder(
			new MatteBorder(0, 3, 0, 0, account.getProfit() >= 0 ? config.profitColor() : config.lossColor()),
			new EmptyBorder(3, 5, 3, 0)
		));

		String label = isCurrentAccount ? account.getRsn() + " (you)" : account.getRsn();
		JLabel nameLabel = new JLabel(String.format(
			"<html>%s <span style='color:#a0a0a0'>- %d flip%s</span></html>",
			escapeHtml(label), account.getFlipCount(), account.getFlipCount() == 1 ? "" : "s"
		));
		nameLabel.setForeground(isCurrentAccount ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		row.add(nameLabel, BorderLayout.CENTER);

		JLabel profitLabel = new JLabel(
			(account.getProfit() >= 0 ? "+" : "-") + QuantityFormatter.formatNumber(Math.abs(account.getProfit()))
		);
		profitLabel.setForeground(account.getProfit() >= 0 ? config.profitColor() : config.lossColor());
		profitLabel.setFont(FontManager.getRunescapeSmallFont());
		row.add(profitLabel, BorderLayout.EAST);

		return row;
	}

	private JPanel buildFlipRow(CompletedFlip flip)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_COLOR);
		row.setBorder(new CompoundBorder(
			new MatteBorder(0, 3, 0, 0, flip.profit >= 0 ? config.profitColor() : config.lossColor()),
			new EmptyBorder(3, 5, 3, 0)
		));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(20, 20));
		itemManager.getImage(flip.itemId).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JLabel text = new JLabel(String.format("<html>%dx %s</html>", flip.quantity, escapeHtml(flip.itemName)));
		text.setForeground(Color.WHITE);
		text.setFont(FontManager.getRunescapeSmallFont());
		row.add(text, BorderLayout.CENTER);

		JLabel profit = new JLabel((flip.profit >= 0 ? "+" : "-") + QuantityFormatter.formatNumber(Math.abs(flip.profit)));
		profit.setForeground(flip.profit >= 0 ? config.profitColor() : config.lossColor());
		profit.setFont(FontManager.getRunescapeSmallFont());
		row.add(profit, BorderLayout.EAST);

		return row;
	}

	private static String formatDuration(long ms)
	{
		long totalSeconds = ms / 1000;
		return String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
	}

	private static JLabel dimLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static String actionLabel(SuggestionType type)
	{
		switch (type)
		{
			case DUMP_BUY:
				return "DUMP BUY";
			case COLLECT:
				return "COLLECT";
			case ABANDON:
				return "ABANDON";
			case HOLD:
				return "HOLD";
			case MODIFY:
				return "MODIFY";
			case SELL:
				return "SELL";
			case START_NEW_FLIP:
			default:
				return "NEW FLIP";
		}
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private Color colorFor(SuggestionType type)
	{
		switch (type)
		{
			case DUMP_BUY:
				return new Color(255, 0, 220);
			case COLLECT:
				return new Color(0, 255, 0);
			case ABANDON:
				return config.lossColor();
			case HOLD:
				return new Color(0, 255, 190);
			case MODIFY:
				return new Color(255, 200, 0);
			case SELL:
				return new Color(255, 140, 60);
			case START_NEW_FLIP:
			default:
				return new Color(100, 200, 255);
		}
	}
}
