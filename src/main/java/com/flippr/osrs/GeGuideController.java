package com.flippr.osrs;

import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

// Feeds GeGuide from the live client and turns its steps into things to
// draw: reads which GE screen is open (and the offer being set up), picks
// the suggestion to guide (chooseTarget), and finds the widget or NPC for
// each step. Called from the overlays' render(), i.e. on the client thread;
// the result is cached per client cycle so both overlays share one read.
@Slf4j
@Singleton
public class GeGuideController
{
	static final String CLERK_NAME = "Grand Exchange Clerk";
	static final String BANKER_NAME = "Banker";
	// "Near the GE": a clerk this close (tiles).
	static final int NEARBY_TILES = 15;
	// GE setup screen children with no gameval names (built by a shared
	// interface script): the price "..." button, the quantity "..." button and
	// the Confirm button. Child indices from the live widget tree;
	// SETUP_CONFIRM is the fallback for Confirm.
	static final int SETUP_ITEM_SEARCH_CHILD = 0;
	static final int SETUP_QUANTITY_ALL_CHILD = 50;
	static final int SETUP_QUANTITY_CHILD = 51;
	static final int SETUP_PRICE_CHILD = 54;
	static final int SETUP_CONFIRM_CHILD = 58;

	@Inject
	private Client client;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private InventoryTracker inventoryTracker;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FlipprConfig config;

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private GuidanceGate guidanceGate;

	private final ChangeLog targetLog = new ChangeLog();

	private int cachedCycle = -1;
	private List<GeGuide.Step> cachedSteps = Collections.emptyList();
	private GeGuide.Target cachedTarget;
	private String cachedTargetName;

	boolean enabled()
	{
		return config.highlightSuggestions() && config.geGuideHighlights() && !guidanceGate.blocked();
	}

	// The steps to draw now (empty when off, logged out or nothing to do).
	List<GeGuide.Step> steps()
	{
		if (!enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			targetLog.reset();
			cachedSteps = Collections.emptyList();
			cachedCycle = -1;
			return cachedSteps;
		}
		int cycle = client.getGameCycle();
		if (cycle == cachedCycle)
		{
			return cachedSteps;
		}
		cachedCycle = cycle;
		GeGuide.Screen screen = readScreen();
		boolean sellOnly = serverSuggestions.isSellOnly();
		DecisionEngine.Suggestion suggestion = chooseTarget(decisionEngine.getCurrentSuggestions(), screen, sellOnly);
		cachedTarget = GeGuide.Target.of(suggestion);
		cachedTargetName = suggestion == null ? null : suggestion.itemName;
		if (cachedTarget != null && cachedTarget.sell && cachedTarget.itemId > 0)
		{
			screen.heldCount = inventoryTracker.getHeldCount(cachedTarget.itemId);
			screen.targetInInventory = screen.heldCount > 0;
		}
		cachedSteps = GeGuide.plan(true, sellOnly, cachedTarget, screen);
		logTarget(suggestion, screen);
		return cachedSteps;
	}

	// One line per change of target or steps (field debugging).
	private void logTarget(DecisionEngine.Suggestion suggestion, GeGuide.Screen screen)
	{
		String target = suggestion == null ? "none" : suggestion.type + " slot " + suggestion.slot + " "
			+ (suggestion.itemName == null ? "(no item)" : suggestion.itemName + " (" + suggestion.itemId + ")")
			+ (suggestion.sellSide ? " sell" : " buy") + " price=" + suggestion.suggestedPrice + " qty=" + suggestion.suggestedQuantity;
		String where = !screen.geOpen ? "GE closed" : screen.setupOpen ? "setup slot " + screen.openSlot
			: screen.detailsOpen ? "details slot " + screen.openSlot : "GE home";
		if (targetLog.changed(target + "|" + where + "|" + cachedSteps))
		{
			log.info("GE guide: target {} on {} -> steps {}", target, where, cachedSteps);
		}
	}

	// The suggestion to guide: on the setup screen, one for the item (and
	// side) being set up, or - before an item is chosen - a buy for that slot
	// or any buy with an item; elsewhere the one the sidebar / chat lead
	// with (SuggestionOverlay.pickOneToHighlight).
	static DecisionEngine.Suggestion chooseTarget(DecisionEngine.Suggestion[] all, GeGuide.Screen s)
	{
		return chooseTarget(all, s, false);
	}

	// Sell-only: buy suggestions are left out before choosing.
	static DecisionEngine.Suggestion chooseTarget(DecisionEngine.Suggestion[] all, GeGuide.Screen s, boolean sellOnly)
	{
		if (all == null)
		{
			return null;
		}
		if (sellOnly)
		{
			DecisionEngine.Suggestion[] sells = new DecisionEngine.Suggestion[all.length];
			for (int i = 0; i < all.length; i++)
			{
				sells[i] = all[i] != null && GeGuide.isBuySide(GeGuide.Target.of(all[i])) ? null : all[i];
			}
			all = sells;
		}
		DecisionEngine.Suggestion lead = SuggestionOverlay.pickOneToHighlight(all);
		if (s == null || !s.setupOpen)
		{
			return lead;
		}
		if (s.setupItemId > 0)
		{
			if (matchesSetup(lead, s))
			{
				return lead;
			}
			for (DecisionEngine.Suggestion x : all)
			{
				if (matchesSetup(x, s))
				{
					return x;
				}
			}
			return lead;
		}
		if (!s.setupSell)
		{
			DecisionEngine.Suggestion anyBuy = null;
			for (DecisionEngine.Suggestion x : all)
			{
				if (x != null && x.itemId > 0 && GeGuide.isBuy(GeGuide.Target.of(x)))
				{
					if (x.slot == s.openSlot)
					{
						return x;
					}
					if (anyBuy == null || x.type == SuggestionType.DUMP_BUY)
					{
						anyBuy = x;
					}
				}
			}
			if (anyBuy != null)
			{
				return anyBuy;
			}
		}
		return lead;
	}

	private static boolean matchesSetup(DecisionEngine.Suggestion x, GeGuide.Screen s)
	{
		return x != null && x.itemId == s.setupItemId && x.sellSide == s.setupSell
			&& (x.suggestedPrice != null || x.suggestedQuantity != null);
	}

	// The suggested buy for the GE search row, worked out on its own (not
	// from the highlight pass, which is skipped when highlights are off).
	// Null when there's no buy with an item, or sell-only mode is on.
	DecisionEngine.Suggestion searchPresetSuggestion()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		return searchTarget(decisionEngine.getCurrentSuggestions(), client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1,
			serverSuggestions.isSellOnly());
	}

	// The GE item search only opens for a buy with no item chosen yet, so
	// its target is the buy chooseTarget picks for that screen (the open
	// slot's, else a dump, else any) - whatever the setup screen widgets
	// say at that moment.
	static DecisionEngine.Suggestion searchTarget(DecisionEngine.Suggestion[] all, int openSlot, boolean sellOnly)
	{
		GeGuide.Screen buySetup = new GeGuide.Screen();
		buySetup.geOpen = true;
		buySetup.setupOpen = true;
		buySetup.setupItemId = -1;
		buySetup.openSlot = openSlot;
		return presetFor(chooseTarget(all, buySetup, sellOnly), sellOnly);
	}

	static DecisionEngine.Suggestion presetFor(DecisionEngine.Suggestion s, boolean sellOnly)
	{
		GeGuide.Target t = GeGuide.Target.of(s);
		return t != null && !sellOnly && GeGuide.isBuy(t) && t.itemId > 0 && s.itemName != null ? s : null;
	}

	GeGuide.Screen readScreen()
	{
		GeGuide.Screen s = new GeGuide.Screen();
		Widget slot0 = client.getWidget(InterfaceID.GeOffers.INDEX_0);
		// Loaded = open (the slot boxes are hidden behind the setup / details
		// screens, but still there).
		s.geOpen = slot0 != null;
		if (s.geOpen)
		{
			s.openSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
			// Telling them apart: a slot is open, and the
			// setup screen is the one with its Confirm button showing; an
			// open slot without it is the offer's details screen.
			Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
			Widget confirm = setup == null ? null : setup.getChild(SETUP_CONFIRM_CHILD);
			s.setupOpen = s.openSlot >= 0 && (confirm != null ? !confirm.isHidden() : setup != null && !setup.isHidden());
			Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
			s.detailsOpen = !s.setupOpen && s.openSlot >= 0 && (details == null || !details.isHidden());
			if (s.setupOpen)
			{
				s.setupSell = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
				s.setupItemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
				s.setupPrice = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);
				s.setupQuantity = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
				Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
				s.searchOpen = results != null && !results.isHidden();
			}
		}
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		s.bankOpen = bank != null && !bank.isHidden();
		if (!s.geOpen && !s.bankOpen && config.geGuideNpcs())
		{
			s.clerkNearby = closestNpc(CLERK_NAME) != null;
			s.bankerNearby = s.clerkNearby && closestNpc(BANKER_NAME) != null;
		}
		s.coins = inventoryTracker.getCoins();
		return s;
	}

	NPC closestNpc(String name)
	{
		Player me = client.getLocalPlayer();
		WorldPoint here = me == null ? null : me.getWorldLocation();
		if (here == null)
		{
			return null;
		}
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || !name.equals(npc.getName()) || npc.getWorldLocation() == null)
			{
				continue;
			}
			int d = npc.getWorldLocation().distanceTo(here);
			if (d <= NEARBY_TILES && d < bestDistance)
			{
				best = npc;
				bestDistance = d;
			}
		}
		return best;
	}

	// The widget a step points at, or null (not on screen).
	Widget widgetFor(GeGuide.Step step)
	{
		switch (step.element)
		{
			case SLOT_BUY_BUTTON:
				return step.slot >= 0 && step.slot < 8 ? GeWidgets.getBuyIcon(client, step.slot) : null;
			case SLOT_BOX:
				return step.slot >= 0 && step.slot < 8 ? client.getWidget(GeWidgets.SLOT_COMPONENT_IDS[step.slot]) : null;
			case INVENTORY_ITEM:
				return cachedTarget == null ? null : inventoryItem(cachedTarget.itemId);
			case SEARCH_RESULT:
				return cachedTarget == null ? null : searchResult(cachedTarget.itemId, cachedTargetName);
			case ITEM_SEARCH_BUTTON:
				return setupChild(SETUP_ITEM_SEARCH_CHILD);
			case PRICE_BUTTON:
				return setupChild(SETUP_PRICE_CHILD);
			case QUANTITY_BUTTON:
				return setupChild(SETUP_QUANTITY_CHILD);
			case QUANTITY_ALL_BUTTON:
				return setupChild(SETUP_QUANTITY_ALL_CHILD);
			case CONFIRM:
			{
				Widget confirm = setupChild(SETUP_CONFIRM_CHILD);
				return confirm != null ? confirm : client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM);
			}
			case BACK:
				return client.getWidget(InterfaceID.GeOffers.BACK);
			case MODIFY_BUTTON:
				return client.getWidget(InterfaceID.GeOffers.DETAILS_MODIFY);
			case COLLECT:
				return collectButton();
			case BANK_CLOSE:
				return closeButton(client.getWidget(InterfaceID.Bankmain.FRAME));
			default:
				return null;
		}
	}

	NPC npcFor(GeGuide.Step step)
	{
		if (!config.geGuideNpcs())
		{
			return null;
		}
		if (step.element == GeGuide.Element.CLERK)
		{
			return closestNpc(CLERK_NAME);
		}
		if (step.element == GeGuide.Element.BANKER)
		{
			return closestNpc(BANKER_NAME);
		}
		return null;
	}

	private Widget setupChild(int index)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		return setup == null || setup.isHidden() ? null : setup.getChild(index);
	}

	// The "Collect" button in the GE's top bar, else the whole bar.
	private Widget collectButton()
	{
		Widget bar = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
		if (bar == null)
		{
			return null;
		}
		Widget[] children = bar.getChildren();
		if (children != null)
		{
			for (Widget c : children)
			{
				if (c != null && !c.isHidden() && "Collect".equals(c.getText()))
				{
					return c;
				}
			}
		}
		return bar;
	}

	// The frame's child with a "Close" action.
	private static Widget closeButton(Widget frame)
	{
		if (frame == null || frame.isHidden())
		{
			return null;
		}
		Widget[] children = frame.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget c : children)
		{
			String[] actions = c == null ? null : c.getActions();
			if (actions == null || c.isHidden())
			{
				continue;
			}
			for (String a : actions)
			{
				if ("Close".equals(a))
				{
					return c;
				}
			}
		}
		return null;
	}

	// The item (or its note) in the GE side inventory.
	private Widget inventoryItem(int itemId)
	{
		Widget container = client.getWidget(InterfaceID.GeOffersSide.ITEMS);
		if (container == null || container.isHidden() || container.getDynamicChildren() == null)
		{
			return null;
		}
		int noted = itemManager.getItemComposition(itemId).getLinkedNoteId();
		for (Widget c : container.getDynamicChildren())
		{
			if (c != null && !c.isHidden() && c.getItemQuantity() > 0 && (c.getItemId() == itemId || (noted > 0 && c.getItemId() == noted)))
			{
				return c;
			}
		}
		return null;
	}

	// The item's row in the GE search results: by name (the rows' names are
	// the item names), else its icon.
	private Widget searchResult(int itemId, String name)
	{
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (results == null || results.isHidden())
		{
			return null;
		}
		Widget[] children = results.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		if (name != null)
		{
			for (Widget c : children)
			{
				if (c != null && !c.isHidden() && c.getName() != null && name.equalsIgnoreCase(Text.removeTags(c.getName())))
				{
					return c;
				}
			}
		}
		for (Widget c : children)
		{
			if (c != null && !c.isHidden() && c.getItemId() == itemId)
			{
				return c;
			}
		}
		return null;
	}
}
