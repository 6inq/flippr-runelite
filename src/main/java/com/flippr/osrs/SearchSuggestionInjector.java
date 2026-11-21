package com.flippr.osrs;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

// The "Flippr item: X" row at the top of the GE item search while the
// search box is empty: the suggested buy (GeGuideController's target - a
// Plan position, a website flip or a dump), selectable by clicking it or
// pressing Enter, the same way as any search result. Four children of the
// search results (a rectangle with op and key listeners on clientscript 754
// - the game's own "select this item" script - the label, the item name and
// its icon), either created at indices 0-3 or, when the game shows the
// previous search there ("Show last searched" on and a previous search set),
// that row pointed at the item in place. Only rows carrying Flippr's own name
// marker are ever updated or cleared; any other row (one this plugin didn't
// make and that isn't the game's previous search) is left alone, whatever it
// says. The player still clicks or presses Enter; nothing is selected for
// them. decide() is the pure rule.
@Slf4j
@Singleton
public class SearchSuggestionInjector
{
	// What to do with the first search-result row this tick.
	enum Action
	{
		NONE,
		CREATE,
		UPDATE_OWN,
		OVERWRITE_NATIVE,
		CLEAR_OWN
	}

	// The native clientscript real GE search result rows use to select an
	// item, and the "any key" sentinel for the key-listener variant.
	private static final int SELECT_ITEM_SCRIPT_ID = 754;
	private static final int SELECT_ITEM_KEY_PARAM = -2147483640;
	// Flippr's own marker on its row's name: the only thing that makes a row
	// "ours". Deliberately not the game's "<col=ff9040>" item-name colour, so
	// a row this plugin didn't make is never mistaken for its own and
	// overwritten or blanked.
	private static final String NAME_MARKER_PREFIX = "<col=40a0ff>";

	@Inject
	private Client client;

	@Inject
	private GeGuideController guide;

	@Inject
	private FlipprConfig config;

	@Inject
	private GuidanceGate guidanceGate;

	private final ChangeLog changeLog = new ChangeLog();

	// What decide() chose, and why (for the log).
	static final class Decision
	{
		final Action action;
		final String reason;

		Decision(Action action, String reason)
		{
			this.action = action;
			this.reason = reason;
		}
	}

	// Client thread, while the GE item search is open: when the chatbox
	// switches to it (FlipprPlugin's VarClientIntChanged, a client tick later)
	// and on each client tick after, so the row
	// comes back when the game rebuilds the list (the player clears what
	// they typed). A no-op when the row is already right.
	public void refresh()
	{
		Widget searchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (searchResults == null)
		{
			return;
		}
		String whyNot = null;
		DecisionEngine.Suggestion preset = null;
		if (guidanceGate.blocked())
		{
			// Watch mode's whole point is "touch nothing".
			whyNot = GuidanceGate.bannerText(guidanceGate.current());
		}
		else if (!config.showSuggestedItemInSearch())
		{
			whyNot = "\"Show Flippr item in GE search\" is off";
		}
		else
		{
			preset = guide.searchPresetSuggestion();
			if (preset == null)
			{
				whyNot = "no suggested buy with an item (sell-only, no plan position for a free slot, or not paired)";
			}
		}
		int itemId = preset == null ? 0 : preset.itemId;
		String itemName = preset == null ? null : preset.itemName;
		Widget row = searchResults.getChild(0);
		Widget icon = searchResults.getChild(3);
		Decision d = decide(whyNot, isSearchEmpty(), row == null ? null : row.getName(), icon == null ? -1 : icon.getItemId(),
			client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED), client.getVarbitValue(VarbitID.DISABLE_LAST_SEARCHED) == 0);
		if (changeLog.changed(d.action + ":" + itemId + ":" + d.reason))
		{
			log.info("GE search row: {} {} - {}", d.action, itemName == null ? "" : itemName + " (" + itemId + ")", d.reason);
		}
		switch (d.action)
		{
			case CREATE:
				createRow(searchResults, itemId, itemName);
				break;
			case UPDATE_OWN:
			case OVERWRITE_NATIVE:
				updateExistingRow(searchResults, itemId, itemName);
				break;
			case CLEAR_OWN:
				clearSuggestedItem(searchResults);
				break;
			default:
				break;
		}
	}

	// The search closed: the next one logs afresh.
	public void onSearchClosed()
	{
		changeLog.reset();
	}

	// The rule. want: there's a suggested buy to preset; rowName: the first
	// row's name now; nativeShown: the game's own previous search for the
	// item in rowItemId occupies that row.
	static Action decide(boolean want, boolean searchEmpty, String rowName, int rowItemId, boolean nativeShown)
	{
		return decide(want ? null : "nothing to preset", searchEmpty, rowName, rowItemId, nativeShown ? rowItemId : -1, nativeShown).action;
	}

	// whyNot: null when there's a suggested buy to preset, else why not.
	// rowItemId: the item in the first row's icon (-1 if none);
	// lastSearchedItemId (varp GE_LAST_SEARCHED, -1 when unset) and
	// showLastSearched (varbit DISABLE_LAST_SEARCHED == 0): with both, the
	// game's own previous-search row is in the first row, recognised by
	// showing that same item, and is pointed at the item in place. An empty
	// first row gets Flippr's row created there. Anything else in the first
	// row isn't Flippr's (no marker) and is left alone.
	static Decision decide(String whyNot, boolean searchEmpty, String rowName, int rowItemId, int lastSearchedItemId,
		boolean showLastSearched)
	{
		boolean own = isOwnRow(rowName);
		boolean previousSearchSet = lastSearchedItemId != -1;
		if (whyNot != null || !searchEmpty)
		{
			String why = whyNot != null ? whyNot : "the player is typing a search";
			return new Decision(own ? Action.CLEAR_OWN : Action.NONE, why);
		}
		if (own)
		{
			return new Decision(Action.UPDATE_OWN, "Flippr row already there");
		}
		if (rowName == null || rowName.isEmpty())
		{
			// Nothing in the first row (no previous search, "Show last
			// searched" off, or "Start typing..."): Flippr's own row.
			return new Decision(Action.CREATE, "empty search, first row free (previousSearch=" + previousSearchSet
				+ ", showLastSearched=" + showLastSearched + ")");
		}
		if (previousSearchSet && showLastSearched && rowItemId == lastSearchedItemId)
		{
			return new Decision(Action.OVERWRITE_NATIVE, "game's previous-search row pointed at the item");
		}
		return new Decision(Action.NONE, "first row isn't Flippr's - left alone");
	}

	static boolean isOwnRow(String rowName)
	{
		return rowName != null && rowName.startsWith(NAME_MARKER_PREFIX);
	}

	private boolean isSearchEmpty()
	{
		String text = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
		return text == null || text.isEmpty();
	}

	private Widget findExistingRow(Widget searchResults)
	{
		Widget[] children = searchResults.getChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget child : children)
		{
			if (child != null && isOwnRow(child.getName()))
			{
				return child;
			}
		}
		return null;
	}

	private void updateExistingRow(Widget searchResults, int itemId, String itemName)
	{
		Widget row = searchResults.getChild(0);
		Widget label = searchResults.getChild(1);
		Widget nameWidget = searchResults.getChild(2);
		Widget iconWidget = searchResults.getChild(3);
		if (row == null || label == null || nameWidget == null || iconWidget == null)
		{
			return;
		}

		String name = NAME_MARKER_PREFIX + itemName + "</col>";
		if (name.equals(row.getName()) && iconWidget.getItemId() == itemId)
		{
			// Already showing this item: nothing to redo (runs every client tick).
			return;
		}
		row.setHasListener(true);
		row.setOnOpListener(SELECT_ITEM_SCRIPT_ID, itemId, 84);
		row.setOnKeyListener(SELECT_ITEM_SCRIPT_ID, itemId, SELECT_ITEM_KEY_PARAM);
		row.setName(NAME_MARKER_PREFIX + itemName + "</col>");
		row.setAction(0, "Select");
		row.revalidate();

		label.setText("Flippr item:");
		// The game's own previous-search label is laid out differently.
		label.setOriginalWidth(95);
		label.setXTextAlignment(WidgetTextAlignment.LEFT);
		label.revalidate();

		nameWidget.setText(itemName);
		nameWidget.revalidate();

		iconWidget.setItemId(itemId);
		iconWidget.revalidate();
	}

	private void createRow(Widget searchResults, int itemId, String itemName)
	{
		Widget row = searchResults.createChild(0, WidgetType.RECTANGLE);
		row.setTextColor(0xFFFFFF);
		row.setOpacity(255);
		row.setName(NAME_MARKER_PREFIX + itemName + "</col>");
		row.setHasListener(true);
		row.setFilled(true);
		row.setOriginalX(114);
		row.setOriginalY(0);
		row.setOriginalWidth(256);
		row.setOriginalHeight(32);
		row.setOnOpListener(SELECT_ITEM_SCRIPT_ID, itemId, 84);
		row.setOnKeyListener(SELECT_ITEM_SCRIPT_ID, itemId, SELECT_ITEM_KEY_PARAM);
		row.setAction(0, "Select");
		row.setOnMouseOverListener((JavaScriptCallback) ev -> row.setOpacity(200));
		row.setOnMouseLeaveListener((JavaScriptCallback) ev -> row.setOpacity(255));
		row.revalidate();

		Widget label = searchResults.createChild(1, WidgetType.TEXT);
		label.setText("Flippr item:");
		label.setFontId(495);
		label.setOriginalX(114);
		label.setOriginalY(0);
		label.setOriginalWidth(95);
		label.setOriginalHeight(32);
		label.setXTextAlignment(WidgetTextAlignment.LEFT);
		label.setYTextAlignment(1);
		label.revalidate();

		Widget nameWidget = searchResults.createChild(2, WidgetType.TEXT);
		nameWidget.setText(itemName);
		nameWidget.setFontId(495);
		nameWidget.setOriginalX(254);
		nameWidget.setOriginalY(0);
		nameWidget.setOriginalWidth(116);
		nameWidget.setOriginalHeight(32);
		nameWidget.setYTextAlignment(1);
		nameWidget.revalidate();

		Widget iconWidget = searchResults.createChild(3, WidgetType.GRAPHIC);
		iconWidget.setItemId(itemId);
		iconWidget.setItemQuantity(1);
		iconWidget.setItemQuantityMode(0);
		iconWidget.setRotationX(550);
		iconWidget.setModelZoom(1031);
		iconWidget.setBorderType(1);
		iconWidget.setOriginalX(214);
		iconWidget.setOriginalY(0);
		iconWidget.setOriginalWidth(36);
		iconWidget.setOriginalHeight(32);
		iconWidget.revalidate();
	}

	private void clearSuggestedItem(Widget searchResults)
	{
		Widget row = findExistingRow(searchResults);
		if (row == null)
		{
			return;
		}

		row.setHasListener(false);
		row.setName("");
		row.setAction(0, "");
		row.revalidate();

		Widget label = searchResults.getChild(1);
		if (label != null)
		{
			label.setText("");
			label.revalidate();
		}
		Widget nameWidget = searchResults.getChild(2);
		if (nameWidget != null)
		{
			nameWidget.setText("");
			nameWidget.revalidate();
		}
		Widget iconWidget = searchResults.getChild(3);
		if (iconWidget != null)
		{
			iconWidget.setItemId(-1);
			iconWidget.revalidate();
		}
	}
}
