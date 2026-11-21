package com.flippr.osrs;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

// Shared GE widget lookups - SLOT_COMPONENT_IDS[i] is the whole offer box
// widget for slot i on the main GE overview screen. Verified against
// RuneLite's live gameval InterfaceID.GeOffers (built from real,
// current game data, not a guessed/stale reference - see SuggestionOverlay's
// class comment for the original confirmation). Split out from
// SuggestionOverlay (which used to keep its own private copy of this array)
// so SlotTimerOverlay doesn't need to duplicate it - two independent copies
// of the same widget-ID list only invites the two overlays quietly drifting
// apart if the real IDs ever changed.
final class GeWidgets
{
	static final int[] SLOT_COMPONENT_IDS = {
		InterfaceID.GeOffers.INDEX_0,
		InterfaceID.GeOffers.INDEX_1,
		InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3,
		InterfaceID.GeOffers.INDEX_4,
		InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6,
		InterfaceID.GeOffers.INDEX_7,
	};

	// The quantity/price "..." entry buttons on the "Set up offer" screen
	// (InterfaceID.GeOffers.SETUP) have no named gameval constants - unlike
	// SETUP_CONFIRM, which does - because they're built by a shared, reusable
	// interface script rather than declared statically per-interface, so
	// RuneLite's gameval ID mapping never assigns them individual
	// names (the exact same reason the per-slot ABANDON "X" cancel button has
	// no named constant either, per SuggestionOverlay's own class comment).
	// These child indices come from the setup screen's widget tree. If a
	// live test shows these pointing at the wrong element, that's the first
	// place to check.
	private static final int SETUP_QUANTITY_SET_CHILD = 51;
	private static final int SETUP_PRICE_SET_CHILD = 54;

	private GeWidgets()
	{
	}

	// The buy-icon sub-widget within an EMPTY slot's box (the green down-
	// arrow) - always child 0 of the slot widget. Only meaningful while the
	// slot is actually empty (a filled slot's child 0 is a different
	// element), which matches START_NEW_FLIP's own precondition exactly.
	static Widget getBuyIcon(Client client, int slot)
	{
		Widget slotWidget = client.getWidget(SLOT_COMPONENT_IDS[slot]);
		return slotWidget == null ? null : slotWidget.getChild(0);
	}

	static Widget getSetupQuantitySetButton(Client client)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		return setup == null ? null : setup.getChild(SETUP_QUANTITY_SET_CHILD);
	}

	static Widget getSetupPriceSetButton(Client client)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		return setup == null ? null : setup.getChild(SETUP_PRICE_SET_CHILD);
	}
}
