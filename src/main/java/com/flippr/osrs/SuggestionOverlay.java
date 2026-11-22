package com.flippr.osrs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

// Draws a colored outline around the ONE real GE widget that most needs
// attention right now - DecisionEngine tracks every slot's suggestion
// independently (see its class comment - that's what fixed MODIFY being
// silently masked), but lighting up every due slot on screen at once is
// overwhelming and defeats the point of a single "do this next" pointer, so
// this picks exactly one to actually highlight. Highlighting, not
// automation: this never clicks anything, it only draws a rectangle telling
// the player where to click themselves.
//
// setPosition(DYNAMIC) matters and is not optional: RuneLite's
// OverlayRenderer translates the graphics context by the overlay's snap-
// corner position before render() runs (confirmed in its own source,
// OverlayRenderer.safeRender - graphics.translate(point.x, point.y)), and
// the default position (TOP_LEFT) stacks overlays at a screen corner. Without
// DYNAMIC, every highlight drawn at a real widget's on-screen bounds was
// actually being drawn relative to that corner offset instead - explains
// why nothing was visible in the first real test of this feature, even
// though the widget lookup and rendering logic itself was correct.
//
// Widget IDs verified against RuneLite's live gameval InterfaceID.GeOffers
// (built from the real, current game data, not a guessed/stale
// reference): INDEX_0..INDEX_7 are the 8 slot boxes on the main GE overview
// screen, COLLECTALL is the single bulk "collect" button. Modifying
// highlights the whole slot box; collecting is a single bulk button, not
// per-slot.
//
// Known gap: ABANDON highlights the whole slot box, not the specific per-
// slot cancel ("X") button - that button is a dynamically-created child of
// the slot widget with no named constant in the gameval component list,
// and confirming its exact position would need inspecting a live client's
// actual widget tree rather than guessing an index. Whole-slot-box
// highlighting still points the player at the right physical area on
// screen even without that precision - and matches the real reference
// behavior confirmed via a live walkthrough (a full-red slot fill, not a
// button-level outline, is exactly what a real ABANDON highlight looks like
// in practice).
//
// START_NEW_FLIP highlights the buy-icon sub-widget specifically (the green
// down-arrow, always child 0 of an EMPTY slot's box), not the whole box -
// per a live walkthrough showing exactly that button boxed off, not the
// full slot.
@Slf4j
public class SuggestionOverlay extends Overlay
{
	private static final Color DUMP_BUY_COLOR = new Color(255, 0, 220);
	private static final Color COLLECT_COLOR = new Color(0, 255, 0);
	private static final Color ABANDON_COLOR = new Color(255, 0, 0);
	private static final Color HOLD_COLOR = new Color(0, 255, 190);
	private static final Color MODIFY_COLOR = new Color(255, 200, 0);
	private static final Color SELL_COLOR = new Color(255, 140, 60);
	private static final Color START_NEW_FLIP_COLOR = new Color(0, 200, 255);

	// Which type gets shown when more than one slot has something due at
	// once. DUMP_BUY leads - catching a live dump is the highest-priority
	// action - ahead of even COLLECT, since a dump's window is measured in
	// seconds and a collect can wait a few more.
	// The rest: finish a near-complete offer, then cut what's dead, then
	// nudge what's drifting, then sell, then start fresh on an empty slot.
	// HOLD ("leave this offer alone") is last: it has nothing to click, so
	// it only shows when nothing else is due - never in place of a step.
	private static final SuggestionType[] DISPLAY_PRIORITY = {
		SuggestionType.DUMP_BUY,
		SuggestionType.COLLECT,
		SuggestionType.ABANDON,
		SuggestionType.MODIFY,
		SuggestionType.SELL,
		SuggestionType.START_NEW_FLIP,
		SuggestionType.HOLD,
	};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private FlipprConfig config;

	// Dedup key for diagnostic logging below - only logs when the resolved
	// widget lookup outcome actually changes, not every single render() call
	// (which runs every frame).
	private String lastDiagnosticKey;
	// Unconditional (not gated by verboseLogging) - fires exactly once, the
	// first time render() is ever actually invoked by RuneLite's
	// OverlayRenderer. Two live tests showed zero visible highlights and
	// zero diagnostic log lines even with verbose logging on; this rules out
	// "render() is never called at all" (registration/layer issue) as
	// distinct from "render() runs but the widget lookup fails" (ID/hidden
	// issue) without depending on any other setting being correctly on.
	private volatile boolean firstRenderLogged;

	@Inject
	public SuggestionOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!firstRenderLogged)
		{
			firstRenderLogged = true;
			log.info("SuggestionOverlay.render() invoked for the first time - overlay rendering pipeline is live");
		}

		// The step-by-step GE guidance (GeGuideOverlay) replaces this one
		// while it's on; this stays for players who turn it off.
		if (!config.highlightSuggestions() || config.geGuideHighlights())
		{
			return null;
		}

		DecisionEngine.Suggestion suggestion = pickOneToHighlight(decisionEngine.getCurrentSuggestions());
		if (suggestion != null)
		{
			renderSuggestion(graphics, suggestion);
		}
		return null;
	}

	// Package-visible, not private: ChatNotifier reuses this exact ordering so
	// the chat line and the on-screen highlight can never disagree about
	// which one suggestion matters right now.
	static DecisionEngine.Suggestion pickOneToHighlight(DecisionEngine.Suggestion[] suggestions)
	{
		for (SuggestionType type : DISPLAY_PRIORITY)
		{
			for (DecisionEngine.Suggestion suggestion : suggestions)
			{
				if (suggestion != null && suggestion.type == type)
				{
					return suggestion;
				}
			}
		}
		return null;
	}

	private void renderSuggestion(Graphics2D graphics, DecisionEngine.Suggestion suggestion)
	{
		Widget widget;
		String widgetLabel;
		if (suggestion.type == SuggestionType.COLLECT)
		{
			widget = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
			widgetLabel = "COLLECTALL";
		}
		else if (suggestion.type == SuggestionType.SELL)
		{
			// Offering an item is done by clicking it in the inventory, so
			// the item itself is the sell flow's first step.
			widget = findInventoryItem(suggestion.itemId);
			widgetLabel = "inventoryItem(" + suggestion.itemId + ")";
		}
		else if (suggestion.type == SuggestionType.START_NEW_FLIP || suggestion.type == SuggestionType.DUMP_BUY)
		{
			// The buy icon specifically, not the whole box - see class
			// comment. DUMP_BUY only ever targets an already-empty slot too
			// (see DecisionEngine), so the same buy-icon widget applies.
			widget = GeWidgets.getBuyIcon(client, suggestion.slot);
			widgetLabel = "buyIcon(slot " + suggestion.slot + ")";
		}
		else
		{
			widget = client.getWidget(GeWidgets.SLOT_COMPONENT_IDS[suggestion.slot]);
			widgetLabel = "slotBox(slot " + suggestion.slot + ")";
		}

		logDiagnostic(suggestion, widgetLabel, widget);

		if (widget == null || widget.isHidden())
		{
			// GE isn't open, or this slot's widget isn't visible right now -
			// nothing to draw. Highlighting is only meaningful while the
			// player is actually looking at the GE interface.
			return;
		}

		if (suggestion.type == SuggestionType.DUMP_BUY)
		{
			// All dump-alert UI flashes: a fixed-cadence alpha pulse, distinct from every
			// other suggestion type's static highlight, so a live dump is
			// impossible to miss even in peripheral vision - the whole point
			// of this being the single highest-priority thing this plugin
			// ever shows.
			boolean flashOn = (System.currentTimeMillis() / 300) % 2 == 0;
			int alpha = flashOn ? 220 : 80;
			OverlayUtil.renderPolygon(
				graphics, widget.getBounds(), DUMP_BUY_COLOR,
				new Color(DUMP_BUY_COLOR.getRed(), DUMP_BUY_COLOR.getGreen(), DUMP_BUY_COLOR.getBlue(), alpha),
				new BasicStroke(3)
			);
		}
		else if (suggestion.type == SuggestionType.ABANDON)
		{
			// A solid-ish fill, not just an outline - matches the real
			// reference behavior confirmed via a live walkthrough (a full red
			// slot, not a thin border).
			OverlayUtil.renderPolygon(
				graphics, widget.getBounds(), ABANDON_COLOR,
				new Color(ABANDON_COLOR.getRed(), ABANDON_COLOR.getGreen(), ABANDON_COLOR.getBlue(), 180),
				new BasicStroke(2)
			);
		}
		else
		{
			OverlayUtil.renderPolygon(graphics, widget.getBounds(), colorFor(suggestion.type));
		}
	}

	// Real widget-lookup ground truth for diagnosing why nothing renders,
	// without guessing further at whether a resolved widget is actually the
	// right one or a live widget-tree assumption that turned out wrong.
	// Unconditional (not gated behind verboseLogging) since two prior live
	// tests produced zero diagnostic output despite verbose logging being on
	// - deduped by outcome so it still can't spam.
	// The inventory slot holding this item (or its noted form). While the
	// GE is open the inventory is the GE side panel
	// (InterfaceID.GeOffersSide.ITEMS, group 467), otherwise the normal
	// inventory (InterfaceID.Inventory.ITEMS, group 149) - both verified with
	// javap against runelite-api 1.13.7. Their item children are matched by
	// getItemId(), not by a guessed child index.
	private Widget findInventoryItem(int itemId)
	{
		int notedId = itemManager.getItemComposition(itemId).getLinkedNoteId();
		for (int containerId : new int[]{InterfaceID.GeOffersSide.ITEMS, InterfaceID.Inventory.ITEMS})
		{
			Widget container = client.getWidget(containerId);
			if (container == null || container.isHidden())
			{
				continue;
			}
			Widget[] children = container.getDynamicChildren();
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				if (child != null && !child.isHidden() && (child.getItemId() == itemId || (notedId > 0 && child.getItemId() == notedId)))
				{
					return child;
				}
			}
		}
		return null;
	}

	private void logDiagnostic(DecisionEngine.Suggestion suggestion, String widgetLabel, Widget widget)
	{
		String key = suggestion.key + ":" + (widget == null ? "null" : widget.isHidden() ? "hidden" : "visible");
		if (key.equals(lastDiagnosticKey))
		{
			return;
		}
		lastDiagnosticKey = key;

		if (widget == null)
		{
			log.info("Overlay: suggestion {} -> {} -> getWidget returned null (GE interface likely not open, "
				+ "or this widget lookup is wrong)", suggestion.key, widgetLabel);
		}
		else
		{
			Rectangle bounds = widget.getBounds();
			log.info(
				"Overlay: suggestion {} -> {} -> widget found, hidden={}, bounds={}",
				suggestion.key, widgetLabel, widget.isHidden(), bounds
			);
		}
	}

	private static Color colorFor(SuggestionType type)
	{
		switch (type)
		{
			case DUMP_BUY:
				return DUMP_BUY_COLOR;
			case COLLECT:
				return COLLECT_COLOR;
			case ABANDON:
				return ABANDON_COLOR;
			case HOLD:
				return HOLD_COLOR;
			case MODIFY:
				return MODIFY_COLOR;
			case SELL:
				return SELL_COLOR;
			case START_NEW_FLIP:
			default:
				return START_NEW_FLIP_COLOR;
		}
	}
}
