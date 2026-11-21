package com.flippr.osrs;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

// Reads and logs live GE offer state, tracks completed buy-then-sell cycles
// as structured flip records via FlipTracker, tracks liquid GP + inventory
// contents via InventoryTracker (event-driven and heartbeat), runs the
// read-only decision engine via DecisionEngine, highlights its current
// suggestion on the real GE interface via SuggestionOverlay - highlighting
// only, never clicking -
// and mirrors that same suggestion state as readable text in the sidebar via
// FlipprPanel, visible whether or not the GE is even open.
@Slf4j
@PluginDescriptor(
	name = "Flippr",
	description = "Grand Exchange flipping tool with optional Flippr account sync",
	tags = {"flipping", "grand exchange", "ge", "merching", "profit"}
)
public class FlipprPlugin extends Plugin
{
	// Panel refresh doesn't need to run every 0.6s game tick - this throttles
	// it to a still-responsive cadence without needless Swing churn.
	private static final long PANEL_UPDATE_INTERVAL_MS = 1000;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private FlipprConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FlipTracker flipTracker;

	@Inject
	private InventoryTracker inventoryTracker;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SuggestionOverlay suggestionOverlay;

	@Inject
	private SlotActivityTracker slotActivityTracker;

	@Inject
	private SlotTimerOverlay slotTimerOverlay;

	@Inject
	private OfferSetupOverlay offerSetupOverlay;

	@Inject
	private GeGuideOverlay geGuideOverlay;

	@Inject
	private GeGuideNpcOverlay geGuideNpcOverlay;

	@Inject
	private ChatboxFillLine chatboxFillLine;

	@Inject
	private SellOnlyControl sellOnlyControl;

	@Inject
	private CancelledOfferWatcher cancelledOfferWatcher;

	@Inject
	private PortfolioTooltipOverlay portfolioTooltipOverlay;

	@Inject
	private SlotClickSwapper slotClickSwapper;

	@Inject
	private PortfolioContextMenu portfolioContextMenu;

	@Inject
	private ChatNotifier chatNotifier;

	@Inject
	private DumpDetector dumpDetector;

	@Inject
	private NewItemDetector newItemDetector;

	@Inject
	private QuickFillHandler quickFillHandler;

	@Inject
	private SearchSuggestionInjector searchSuggestionInjector;

	@Inject
	private GuidanceGate guidanceGate;

	@Inject
	private MultiAccountAggregator multiAccountAggregator;

	@Inject
	private WebsiteConnection websiteConnection;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private OfferSync offerSync;

	@Inject
	private TradeUploader tradeUploader;

	@Inject
	private ListingReporter listingReporter;

	@Inject
	private PlanNotifier planNotifier;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private FlipprPanel flipprPanel;

	// The real Flippr brand mark (src/main/resources/com/flippr/osrs/icon.png,
	// sourced from the website's public/logo.png) - shared by the sidebar tab
	// button here and the panel title in FlipprPanel, not a runtime-drawn
	// placeholder.
	static final BufferedImage ICON = ImageUtil.loadImageResource(FlipprPlugin.class, "icon.png");

	private NavigationButton navigationButton;
	private long lastPanelUpdateTime;
	// True between a login and the next login screen/world hop, so the
	// full 8-slot snapshot is sent once per login, not after every
	// loading screen.
	private boolean loggedIn;

	@Override
	protected void startUp() throws Exception
	{
		// Before anything reads its files: bring over data from the old
		// ~/.flippr-osrs folder (copies only, never overwrites).
		FlipprFiles.migrateLegacyFiles();

		boolean added = overlayManager.add(suggestionOverlay);
		log.info("SuggestionOverlay added to OverlayManager: {}", added);
		overlayManager.add(slotTimerOverlay);
		overlayManager.add(offerSetupOverlay);
		overlayManager.add(geGuideOverlay);
		overlayManager.add(geGuideNpcOverlay);
		overlayManager.add(portfolioTooltipOverlay);

		// Safe here (unlike inside FlipprPanel's own constructor): Guice has
		// fully finished constructing and field-injecting flipprPanel by the
		// time startUp() runs, since flipprPanel is itself an @Inject field
		// on this plugin instance.
		flipprPanel.init();

		navigationButton = NavigationButton.builder()
			.tooltip("Flippr")
			.icon(ICON)
			.priority(5)
			.panel(flipprPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		quickFillHandler.register();
		tradeUploader.init();
		websiteConnection.refreshStatusAsync();
		// Turned on while already logged in: no LOGGED_IN event will come,
		// so send the full slot snapshot now (the character for website
		// calls is picked up on the next tick - syncCharacter()).
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
			offerSync.onLoggedIn();
		}

		log.info("Flippr started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		// A partly sold, cancelled sell still waiting to be re-listed is
		// recorded now rather than lost with the session.
		flipTracker.closeAllSellEpisodes();
		loggedIn = false;
		websiteConnection.setCharacter(null);
		overlayManager.remove(suggestionOverlay);
		overlayManager.remove(slotTimerOverlay);
		overlayManager.remove(offerSetupOverlay);
		overlayManager.remove(geGuideOverlay);
		overlayManager.remove(geGuideNpcOverlay);
		overlayManager.remove(portfolioTooltipOverlay);
		clientToolbar.removeNavigation(navigationButton);
		quickFillHandler.unregister();
		log.info("Flippr stopped");
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();
		GrandExchangeOfferState state = offer.getState();

		decisionEngine.onOfferChanged(slot, offer);
		slotActivityTracker.onOfferChanged(slot, offer);
		offerSync.onOfferChanged(slot, offer);

		if (state == GrandExchangeOfferState.EMPTY)
		{
			log.debug("Slot {}: empty", slot);
			flipTracker.onSlotEmptied(slot);
			serverSuggestions.requestRefresh();
			return;
		}

		if (config.verboseLogging())
		{
			String itemName = itemManager.getItemComposition(offer.getItemId()).getName();
			log.info(
				"Slot {}: {} {} price={} filled={}/{} spent={}",
				slot,
				state,
				itemName,
				offer.getPrice(),
				offer.getQuantitySold(),
				offer.getTotalQuantity(),
				offer.getSpent()
			);
		}

		flipTracker.process(slot, offer);
		updatePanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN && !loggedIn)
		{
			loggedIn = true;
			offerSync.onLoggedIn();
			serverSuggestions.requestRefresh();
			syncCharacter();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			if (loggedIn)
			{
				flipTracker.closeAllSellEpisodes();
			}
			loggedIn = false;
			websiteConnection.setCharacter(null);
		}
		else if (state == GameState.HOPPING)
		{
			// Same character: no close-out, but resend all slots after the hop.
			loggedIn = false;
		}
	}

	// The chatbox switched prompts: the GE item search (preset the suggested
	// item at once) or a price / quantity box (show the Flippr line). Later
	// in the same client tick, once the chatbox has built its widgets.
	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() != VarClientID.MESLAYERMODE)
		{
			return;
		}
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		if (mode == GeChatInput.MODE_GE_SEARCH)
		{
			clientThread.invokeLater(searchSuggestionInjector::refresh);
		}
		else
		{
			searchSuggestionInjector.onSearchClosed();
		}
		if (GeChatInput.isNumericMode(mode))
		{
			clientThread.invokeLater(chatboxFillLine::refresh);
		}
	}

	// While the GE item search is open, put the Flippr row back as soon as
	// the game rebuilds the result list (typing, opening) - cheap and a no-op
	// when it's already there.
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) == GeChatInput.MODE_GE_SEARCH)
		{
			searchSuggestionInjector.refresh();
		}
	}

	// RuneLite's "Sell-only mode" setting changed: send it to the plan.
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("flippr".equals(event.getGroup()) && SellOnlyControl.CONFIG_KEY.equals(event.getKey()))
		{
			sellOnlyControl.onConfigChanged();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		slotClickSwapper.onMenuEntryAdded(event);
		portfolioContextMenu.onMenuEntryAdded(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			inventoryTracker.onInventoryChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (loggedIn)
		{
			syncCharacter();
		}
		inventoryTracker.onGameTick();
		flipTracker.onGameTick();
		serverSuggestions.onGameTick();
		offerSync.onGameTick();
		listingReporter.onGameTick();
		planNotifier.onGameTick();
		tradeUploader.onGameTick();
		decisionEngine.onGameTick();
		chatNotifier.onTick();
		dumpDetector.onGameTick();
		newItemDetector.onGameTick();
		guidanceGate.onGameTick();
		chatboxFillLine.refresh();
		sellOnlyControl.onGameTick();
		cancelledOfferWatcher.onGameTick();
		multiAccountAggregator.onGameTick();

		long now = System.currentTimeMillis();
		if (now - lastPanelUpdateTime >= PANEL_UPDATE_INTERVAL_MS)
		{
			lastPanelUpdateTime = now;
			updatePanel();
		}
	}

	// Account keys name the character on every website call
	// (X-Flippr-Character). The local player's name often isn't set yet on
	// the LOGGED_IN event, and no event comes when the plugin is enabled
	// mid-session, so it's picked up on the first tick it is known. A new
	// character resends the slot snapshot and refreshes status/suggestions.
	private void syncCharacter()
	{
		Player player = client.getLocalPlayer();
		String name = player == null ? null : player.getName();
		if (name == null || !websiteConnection.setCharacter(name))
		{
			return;
		}
		offerSync.onLoggedIn();
		serverSuggestions.requestRefresh();
		websiteConnection.refreshStatusAsync();
	}

	private void updatePanel()
	{
		// Read here, on the client thread: the Prices tab follows the item
		// of the GE offer screen that's open.
		flipprPanel.setGeScreenItem(client.getGameState() == GameState.LOGGED_IN
			? client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH) : 0);
		flipprPanel.update(
			flipTracker.getSessionFlipCount(),
			flipTracker.getSessionProfit(),
			flipTracker.getSessionDurationMs(),
			decisionEngine.getCurrentSuggestions(),
			flipTracker.getRecentFlips(),
			flipTracker.getOpenPositions()
		);
	}

	@Provides
	FlipprConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipprConfig.class);
	}
}
