package com.flippr.osrs;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.Widget;

// Makes the highlighted slot's default LEFT-CLICK resolve to "Modify offer"
// or "Abort offer" instead of OSRS's normal default ("View offer") - a
// standard RuneLite menu technique (MenuEntryAdded +
// MenuEntry.setDeprioritized), not automation: no click is ever synthesized here, the player still clicks
// themselves - this only changes which real, already-existing menu option
// that click resolves to. Every non-matching entry is deprioritized
// rather than the menu array being reordered by hand.
//
// Deliberately reuses SuggestionOverlay.pickOneToHighlight() - the SAME one
// suggestion that gets the on-screen highlight is the only one whose click
// gets swapped, so what's highlighted and what a click actually does can
// never disagree. Gated by its own config flag (swapSlotLeftClick),
// independent of highlightSuggestions, since this changes real click
// behavior on a real account - a player who wants the visual highlight
// without changing what their click does can turn this off separately.
@Slf4j
@Singleton
public class SlotClickSwapper
{
	@Inject
	private Client client;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private FlipprConfig config;

	// Mis-click prevention: a suggestion has to stay the SAME (by key) for
	// at least misclickPreventionMs before the swap below actually engages
	// on it. Without this, a suggestion flipping to a different slot/type
	// the instant before the player's already-in-flight click resolves
	// could redirect that click to a different real action than what they
	// saw a moment ago - a real race, not a theoretical one, since
	// DecisionEngine re-evaluates every game tick (~0.6s) independent of
	// player input timing.
	private String stableSuggestionKey;
	private long stableSince;

	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Already effectively a no-op during Watch Mode (suggestions are
		// always empty, so the null/type check below would bail out
		// anyway), but checked explicitly and first so this never even
		// evaluates - clearest possible guarantee this touches zero real
		// click behavior while Watch Mode's on.
		if (config.watchModeEnabled() || config.watchModeDumpEnabled() || !config.swapSlotLeftClick())
		{
			return;
		}

		DecisionEngine.Suggestion suggestion = SuggestionOverlay.pickOneToHighlight(decisionEngine.getCurrentSuggestions());
		if (suggestion == null || (suggestion.type != SuggestionType.MODIFY && suggestion.type != SuggestionType.ABANDON))
		{
			stableSuggestionKey = null;
			return;
		}

		long now = System.currentTimeMillis();
		if (!suggestion.key.equals(stableSuggestionKey))
		{
			stableSuggestionKey = suggestion.key;
			stableSince = now;
		}
		if (now - stableSince < config.misclickPreventionMs())
		{
			// Too new - let the player's click resolve to whatever OSRS's
			// normal default already is this one time, rather than swap on
			// a suggestion that just appeared.
			return;
		}

		Widget slotWidget = client.getWidget(GeWidgets.SLOT_COMPONENT_IDS[suggestion.slot]);
		if (slotWidget == null)
		{
			return;
		}

		MenuEntry entry = event.getMenuEntry();
		Widget entryWidget = entry.getWidget();
		if (entryWidget == null || entryWidget.getId() != slotWidget.getId())
		{
			// Not a menu entry for the one slot we're actively suggesting
			// against - leave every other slot's menu alone.
			return;
		}

		String targetOption = suggestion.type == SuggestionType.ABANDON ? "Abort offer" : "Modify offer";
		if (!targetOption.equals(entry.getOption()))
		{
			entry.setDeprioritized(true);
		}
	}
}
