package com.flippr.osrs;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("flippr")
public interface FlipprConfig extends Config
{
	String DEFAULT_WEBSITE_URL = "https://flippr.exchange";

	@ConfigSection(
		name = "Website",
		description = "Optional: connect this plugin to your Flippr account for suggestions, live Plan sync and Tracker uploads",
		position = 100
	)
	String websiteSection = "website";

	@ConfigSection(
		name = "GE guidance",
		description = "Step-by-step highlights, the price / quantity line and the GE search preset for the current "
			+ "Flippr suggestion. They only point and pre-fill - you do every click and press Enter yourself.",
		position = 90
	)
	String geGuidanceSection = "geGuidance";

	@ConfigSection(
		name = "Notifications",
		description = "RuneLite notifications for your Flippr Plan (uses RuneLite's own notification settings for "
			+ "tray popups, sound and flashing). Needs the plugin paired with Flippr.",
		position = 110
	)
	String notificationsSection = "notifications";

	@ConfigSection(
		name = "Sidebar",
		description = "How the Flippr sidebar shows your Plan",
		position = 120
	)
	String sidebarSection = "sidebar";

	@ConfigItem(
		keyName = "engineEnabled",
		name = "Engine enabled",
		description = "Master pause: when off, Flippr stops making suggestions and highlighting. Your flips and inventory "
			+ "are still recorded."
	)
	default boolean engineEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPortfolioTooltip",
		name = "Portfolio badge + hover tooltip",
		description = "Two things, one switch: a small marker on every inventory item this plugin tracked buying, "
			+ "and hovering one shows a live-priced readout - inventory count, time held, avg buy price, total "
			+ "value, unrealized profit/ROI. Purely informational (never changes anything), and stays on during "
			+ "Watch Mode."
	)
	default boolean showPortfolioTooltip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPortfolioContextMenu",
		name = "Portfolio right-click options",
		description = "Adds 'Remove-All from portfolio' / 'Remove-X from portfolio' (manually correct a tracked "
			+ "position - this plugin can only ever see real GE offers, not every way an item might leave/enter "
			+ "your inventory) and 'Flippr chart' (pin the sidebar's price chart to this item) to the right-click "
			+ "menu of any inventory item this plugin tracked buying. Remove-All/Remove-X change only this "
			+ "plugin's own tracked-position data, not anything in the actual game."
	)
	default boolean showPortfolioContextMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "watchModeEnabled",
		name = "Watch mode (log flips only)",
		description = "Silences everything that talks to you or acts for you - suggestions, highlights, chat lines, "
			+ "dump/new-item/inventory alerts, auto-fill and the GE search item. Your flips and inventory are still "
			+ "recorded as normal. Overrides 'Engine enabled' while on."
	)
	default boolean watchModeEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "watchModeDumpEnabled",
		name = "Watch mode (dump)",
		description = "Same silence as Watch mode above, but every flip and session recorded while it's on is also marked "
			+ "as part of a dump-hunting session, so dump flips can be told apart from regular ones. Use this while "
			+ "you're hunting dumps yourself; use plain Watch mode for everything else."
	)
	default boolean watchModeDumpEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "verboseLogging",
		name = "Verbose offer logging",
		description = "Log every GE offer state change to the client log, not just flip/decision/inventory outcomes"
	)
	default boolean verboseLogging()
	{
		return false;
	}

	@ConfigItem(
		keyName = "highlightSuggestions",
		name = "Highlight suggestions on-screen",
		description = "Draw a colored outline around the GE widget the decision engine wants attention on next"
	)
	default boolean highlightSuggestions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSlotTimers",
		name = "Show slot activity timers",
		description = "Draw a small readout on each active GE slot showing time since it last actually made buy/sell "
			+ "progress - independent of the decision engine, works even while it's paused"
	)
	default boolean showSlotTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "swapSlotLeftClick",
		name = "Swap left-click on highlighted slot",
		description = "For the one slot currently highlighted MODIFY or ABANDON, make left-click do that (Modify "
			+ "offer / Abort offer) instead of OSRS's normal default (View offer). Never clicks anything itself - "
			+ "only changes which real menu option your own click resolves to. Independent of the highlight "
			+ "toggle above, since this changes real click behavior on your account."
	)
	default boolean swapSlotLeftClick()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geGuideHighlights",
		name = "Step-by-step highlights",
		description = "Outline what to use next for the current Flippr suggestion: the slot's Buy button or the "
			+ "item to sell, the item in the GE search, the price and quantity buttons until they match, then "
			+ "Confirm; Back when the wrong item is set up; Collect; the GE clerk (or a banker) when you're near "
			+ "the GE. Only drawn while the GE, the bank or the clerk is on screen. Blue = next, amber = needs a "
			+ "price change, red = abort. Highlights only - you do every click. Needs \"Highlight suggestions "
			+ "on-screen\" on.",
		section = geGuidanceSection,
		position = 0
	)
	default boolean geGuideHighlights()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geGuideNpcs",
		name = "Highlight GE clerk / banker",
		description = "Part of the step-by-step highlights: outline the nearest Grand Exchange Clerk when there's "
			+ "something to do at the GE, or a Banker when a buy needs more gp than you carry",
		section = geGuidanceSection,
		position = 1
	)
	default boolean geGuideNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geGuideFlash",
		name = "Flash highlights",
		description = "Pulse the highlights (faster for a live dump); off draws a steady outline",
		section = geGuidanceSection,
		position = 2
	)
	default boolean geGuideFlash()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geGuideNextColor",
		name = "Next action colour",
		description = "Highlight colour for the next thing to click",
		section = geGuidanceSection,
		position = 3
	)
	default Color geGuideNextColor()
	{
		return new Color(30, 150, 255);
	}

	@ConfigItem(
		keyName = "geGuideAttentionColor",
		name = "Attention colour",
		description = "Highlight colour for an offer whose price should change",
		section = geGuidanceSection,
		position = 4
	)
	default Color geGuideAttentionColor()
	{
		return new Color(255, 170, 0);
	}

	@ConfigItem(
		keyName = "geGuideAbortColor",
		name = "Abort colour",
		description = "Highlight colour for an offer to cancel",
		section = geGuidanceSection,
		position = 5
	)
	default Color geGuideAbortColor()
	{
		return new Color(230, 40, 40);
	}

	@ConfigItem(
		keyName = "geFillLine",
		name = "Price / quantity line",
		description = "When the GE price or quantity box is open for the suggested item, show the suggested "
			+ "value as a \"Flippr price: 1,234\" / \"Flippr quantity: 500\" line in the chatbox, for you to type in.",
		section = geGuidanceSection,
		position = 6
	)
	default boolean geFillLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geFillKeybind",
		name = "Fill keybind",
		description = "Press this key while the GE price or quantity box is open for Flippr's suggested item to "
			+ "put Flippr's value in the box. You still press Enter yourself. Off until you pick a key. "
			+ "Modifier keys (Shift, Ctrl, Alt) and Enter are not supported.",
		section = geGuidanceSection,
		position = 7
	)
	default Keybind geFillKeybind()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "showSuggestedItemInSearch",
		name = "Suggested item in GE search",
		description = "When you open the GE item search for the suggested buy, show the item as the first, "
			+ "selectable result (where the game shows your previous search) so it's one click. You still "
			+ "click it or press Enter yourself. Steps aside if an item is already shown there.",
		section = geGuidanceSection,
		position = 9
	)
	default boolean showSuggestedItemInSearch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "misclickPreventionMs",
		name = "Mis-click prevention (ms)",
		description = "A newly-changed suggestion has to stay the same for this long before the left-click swap "
			+ "above actually activates on it - stops a click already in flight from getting redirected to a "
			+ "different action than what you saw a moment ago just because the suggestion flipped mid-click. Set "
			+ "to 0 to disable and swap instantly."
	)
	default int misclickPreventionMs()
	{
		return 600;
	}

	@ConfigItem(
		keyName = "profitColor",
		name = "Profit color",
		description = "Text/border color used for positive profit figures throughout the sidebar"
	)
	default Color profitColor()
	{
		return new Color(110, 230, 30);
	}

	@ConfigItem(
		keyName = "lossColor",
		name = "Loss color",
		description = "Text/border color used for negative profit (loss) figures throughout the sidebar"
	)
	default Color lossColor()
	{
		return new Color(250, 100, 75);
	}

	@ConfigItem(
		keyName = "riskLevel",
		name = "Risk level",
		description = "How much slack the engine gives before flagging something as due - Low is patient with "
			+ "fewer false alarms, High is fast and twitchier. Scales the stale-buy/price-move/modify-fallback/"
			+ "dump thresholds below rather than replacing them - your own tuning of those numbers is kept, this "
			+ "just multiplies it. Medium (default) changes nothing."
	)
	default RiskLevel riskLevel()
	{
		return RiskLevel.MEDIUM;
	}

	@ConfigItem(
		keyName = "staleFillMinutes",
		name = "Stale buy threshold (minutes)",
		description = "How long a buy can sit at 0% filled before it's flagged as stale"
	)
	default int staleFillMinutes()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "priceMoveThresholdPercent",
		name = "Price-move nudge threshold (%)",
		description = "How far the live instant price has to move from where it was when an offer opened before "
			+ "it's flagged as due for a price nudge"
	)
	default int priceMoveThresholdPercent()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "sellPriceStepMinutes",
		name = "Sell price-step interval (minutes)",
		description = "A sell sitting at 0% filled gets a nudge to lower its price this often, before the full abandon "
			+ "threshold above. Keep this shorter than the stale-buy threshold so a sell gets at least one step "
			+ "before it's abandoned."
	)
	default int sellPriceStepMinutes()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "modifyFallbackMinutes",
		name = "Modify fallback threshold (minutes)",
		description = "Elapsed-time fallback for the price-nudge check, used only for items the live price feed "
			+ "hasn't observed yet"
	)
	default int modifyFallbackMinutes()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "dumpAlertTier",
		name = "Dump alerts",
		description = "Flag items whose instant-sell price (OSRS Wiki live prices) has dropped fast and whose possible "
			+ "profit clears this tier - buying the item's GE limit at the crashed price and reselling at today's "
			+ "instant-buy price, after GE tax. This is the size of the opportunity, not what your own GP allows "
			+ "(that's Min. predicted profit below). Off turns dump detection off. Based on public wiki prices, so "
			+ "slower and coarser than dedicated real-time dump alerts."
	)
	default DumpAlertTier dumpAlertTier()
	{
		return DumpAlertTier.K100;
	}

	@ConfigItem(
		keyName = "dumpThresholdPercent",
		name = "Dump threshold (%)",
		description = "How far an item's instant-sell price has to fall within the dump window before it's flagged"
	)
	default int dumpThresholdPercent()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "dumpWindowMinutes",
		name = "Dump window (minutes)",
		description = "How far back to compare the current instant-sell price against when checking for a dump"
	)
	default int dumpWindowMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "minPredictedProfit",
		name = "Min. predicted profit",
		description = "A dump buy is only suggested if its estimated profit clears this - worked out from current prices "
			+ "(instant-buy price minus the crashed price, after GE tax, times the suggested quantity your GP "
			+ "allows), never a price forecast. A finer filter on top of Dump alerts above. Auto adds no extra "
			+ "filter."
	)
	default MinPredictedProfit minPredictedProfit()
	{
		return MinPredictedProfit.AUTO;
	}

	@ConfigItem(
		keyName = "reservedDumpSlots",
		name = "Reserved slots",
		description = "How many of the 8 GE slots to keep free for catching dumps. Dump buys prefer an empty reserved slot, "
			+ "and new flips aren't suggested in reserved slots. Auto = no slots reserved."
	)
	default ReservedDumpSlots reservedDumpSlots()
	{
		return ReservedDumpSlots.AUTO;
	}

	@ConfigItem(
		keyName = "inventoryBudgetAlertEnabled",
		name = "Inventory space alert",
		description = "Warn when free inventory space drops to or below the threshold below, since a full inventory stops "
			+ "you collecting your next flip even with a GE slot free"
	)
	default boolean inventoryBudgetAlertEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inventoryFreeSlotThreshold",
		name = "Inventory free-slot threshold",
		description = "Alert once free inventory slots drop to or below this number"
	)
	default int inventoryFreeSlotThreshold()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "dumpBuyAbandonSeconds",
		name = "Dump-buy abandon (seconds)",
		description = "An unfilled dump-buy is cut after this many seconds, but only once the price has recovered "
			+ "above your offer (the dump is over). While the price is still at or below your offer it keeps "
			+ "waiting on the normal stale-buy time instead."
	)
	default int dumpBuyAbandonSeconds()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "dumpBuyCapitalFraction",
		name = "Dump-buy capital fraction",
		description = "What fraction (0-1) of your available GP (coins minus the reserve floor below) a single dump buy may "
			+ "use, on top of the item's GE buy limit."
	)
	default double dumpBuyCapitalFraction()
	{
		return 0.5;
	}

	@ConfigItem(
		keyName = "reserveFloorGp",
		name = "Reserve floor (gp)",
		description = "This much GP is never committed to anything, however good an opportunity looks - only GP above it "
			+ "counts toward dump-buy sizing. 0 (default) means no floor."
	)
	default int reserveFloorGp()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "buyAndHoldEnabled",
		name = "Buy-and-hold pattern detection",
		description = "When a sell is due to be abandoned, check whether the price is unusually low for this day and time "
			+ "of the week compared with about the last 13 weeks of OSRS Wiki prices. If so, suggest holding "
			+ "instead of selling into the dip. Looks back only, not a forecast, and only affects sells."
	)
	default boolean buyAndHoldEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "buyAndHoldThresholdPercent",
		name = "Buy-and-hold threshold (%)",
		description = "How far below this exact day-of-week/time-of-day slot's historical median price the current "
			+ "price has to sit before HOLD overrides an otherwise-due ABANDON."
	)
	default int buyAndHoldThresholdPercent()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "dumpNotificationEnabled",
		name = "Dump tray/sound notification",
		description = "Fire RuneLite's own tray popup + notification sound on a dump alert, in addition to the chat "
			+ "line - respects your RuneLite-wide Notifications settings for whether sound/tray actually fire"
	)
	default boolean dumpNotificationEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dumpWebhookUrl",
		name = "Dump webhook URL",
		description = "Optional Discord webhook URL - a dump alert also gets posted here, useful if you want a "
			+ "ping while alt-tabbed away entirely. Leave blank to disable."
	)
	default String dumpWebhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "newItemAlertsEnabled",
		name = "New-item alerts",
		description = "OSRS-side equivalent of Flippr website's new-item volatility flipping detection - flags "
			+ "any item newly added to the OSRS Wiki's price catalog, since a new item has no price history and "
			+ "can swing wildly before the market settles"
	)
	default boolean newItemAlertsEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "staleSellFlushMinutes",
		name = "Close cancelled sells after (min)",
		description = "A partly sold offer that was cancelled and collected is recorded as a finished flip (for "
			+ "the units that sold) if the item isn't listed for sale again within this many minutes. Unsold "
			+ "units stay in the portfolio."
	)
	default int staleSellFlushMinutes()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "websiteBaseUrl",
		name = "Flippr website URL",
		description = "The Flippr website this plugin pairs with - https://flippr.exchange. Nothing is sent until you "
			+ "pair with a code from your Flippr Account page. Empty turns every website feature off.",
		section = websiteSection,
		position = 0
	)
	default String websiteBaseUrl()
	{
		return DEFAULT_WEBSITE_URL;
	}

	@ConfigItem(
		keyName = "syncWithWebsite",
		name = "Sync with website",
		description = "Only used once paired. Sends this character's own GE slot states (item, price, quantity "
			+ "filled) and completed flips to your Flippr account, and fetches flip suggestions. Nothing about "
			+ "other players is ever sent.",
		section = websiteSection,
		position = 1
	)
	default boolean syncWithWebsite()
	{
		return true;
	}

	// Set by WebsiteConnection after a successful pairing, not typed in
	// directly - secret = true masks it in the config UI the same way a
	// password field would be, since it's a real bearer credential.
	@ConfigItem(
		keyName = "pluginApiKey",
		name = "API key",
		description = "Set automatically after pairing - do not share this value",
		section = websiteSection,
		position = 2,
		secret = true
	)
	default String pluginApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "notificationsEnabled",
		name = "Plan notifications",
		description = "Master switch: off silences every Plan notification below",
		section = notificationsSection,
		position = 0
	)
	default boolean notificationsEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyPriceAlerts",
		name = "Price alerts",
		description = "The market moved past a listed offer: the exact price to set, e.g. \"Set buy to 1,234,567 - "
			+ "then update the offer in the GE\"",
		section = notificationsSection,
		position = 1
	)
	default boolean notifyPriceAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOfferCompleted",
		name = "Offer completed",
		description = "A Plan buy or sell finished (with the sell price to list, or the profit)",
		section = notificationsSection,
		position = 2
	)
	default boolean notifyOfferCompleted()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyPartialFills",
		name = "Partial fills",
		description = "Some of a Plan offer filled - at most once per position every 10 minutes",
		section = notificationsSection,
		position = 3
	)
	default boolean notifyPartialFills()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyAutoPrice",
		name = "Price auto-updated",
		description = "Flippr changed the buy or sell price of an offer you haven't listed yet, or the market is "
			+ "below break-even",
		section = notificationsSection,
		position = 4
	)
	default boolean notifyAutoPrice()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyNewFlip",
		name = "New flip in a free slot",
		description = "Flippr started a new flip in a free Plan slot (\"Slot 3: new flip - buy 100 x Item at 1,234\"), "
			+ "or a slot is free with nothing to fill it yet",
		section = notificationsSection,
		position = 5
	)
	default boolean notifyNewFlip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "planLayout",
		name = "Plan layout",
		description = "Cards: each Plan position in detail, like the website. Compact: one line per position.",
		section = sidebarSection,
		position = 0
	)
	default PlanLayout planLayout()
	{
		return PlanLayout.CARDS;
	}

	@ConfigItem(
		keyName = "sellOnlyMode",
		name = "Sell-only mode",
		description = "Sell what you hold, start no new buys: Pending plan buys are paused, free slots aren't "
			+ "filled, and the GE guidance, fill line and notifications only cover sells. Buy offers already in "
			+ "the GE keep filling. With a Plan this is the Plan's own setting (the same switch as on the website); "
			+ "the Plan tab has it too.",
		section = sidebarSection,
		position = 1
	)
	default boolean sellOnlyMode()
	{
		return false;
	}

	// The sidebar tab last used (plan / tracker / history / prices).
	@ConfigItem(
		keyName = "sidebarTab",
		name = "",
		description = "",
		hidden = true
	)
	default String sidebarTab()
	{
		return "plan";
	}
}
