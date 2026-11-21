package com.flippr.osrs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// GE guidance: which on-screen element the player should use next for the
// current Flippr suggestion, step by step - the slot's buy button (or the
// item in the inventory, for a sell), the item in the GE search (or the item
// box that opens it), the price and quantity buttons until they match the
// suggestion, then Confirm; Back when the wrong side (or the wrong item for
// a sell) is set up; Collect; and, away from the GE,
// the clerk (or a banker / the bank's close button when gp is needed or the
// bank is in the way).
//
// Pure: a small description of what is on screen (Screen) and of the
// suggestion (Target) in, a list of Steps out - GeGuideController reads the
// real client state into that description and resolves each Step to a
// widget or NPC to
// draw. Nothing here (or there) clicks, types or submits anything: the
// player does every action; this only says where.
final class GeGuide
{
	// What a step points at.
	enum Element
	{
		// The empty slot's "Buy" button on the GE home screen.
		SLOT_BUY_BUTTON,
		// A whole slot box (modify / abort an offer).
		SLOT_BOX,
		// The item in the GE side inventory (clicking it starts a sell offer).
		INVENTORY_ITEM,
		// The item among the GE item search results.
		SEARCH_RESULT,
		// The setup screen's item box, which (re)opens the item search.
		ITEM_SEARCH_BUTTON,
		PRICE_BUTTON,
		QUANTITY_BUTTON,
		// The quantity "All" button (a sell of everything held).
		QUANTITY_ALL_BUTTON,
		CONFIRM,
		BACK,
		// "Modify offer" on an offer's details screen.
		MODIFY_BUTTON,
		// "Collect" on the GE home screen.
		COLLECT,
		BANK_CLOSE,
		CLERK,
		BANKER
	}

	// Blue: the next action. Amber: needs attention / a price change. Red:
	// cancel / abort.
	enum Tone
	{
		NEXT,
		ATTENTION,
		ABORT
	}

	static final class Step
	{
		final Element element;
		final Tone tone;
		// GE slot 0-7 for SLOT_* elements, else -1.
		final int slot;
		// A live dump: flashes faster.
		final boolean urgent;

		Step(Element element, Tone tone, int slot, boolean urgent)
		{
			this.element = element;
			this.tone = tone;
			this.slot = slot;
			this.urgent = urgent;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Step))
			{
				return false;
			}
			Step s = (Step) o;
			return element == s.element && tone == s.tone && slot == s.slot && urgent == s.urgent;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(element, tone, slot, urgent);
		}

		@Override
		public String toString()
		{
			return element + "/" + tone + (slot >= 0 ? "@" + slot : "") + (urgent ? "!" : "");
		}
	}

	// The suggestion being guided (from DecisionEngine).
	static final class Target
	{
		final SuggestionType type;
		final int slot;
		// 0 when the suggestion has no item (a free slot, unpaired).
		final int itemId;
		final boolean sell;
		final Long price;
		final Long quantity;

		Target(SuggestionType type, int slot, int itemId, boolean sell, Long price, Long quantity)
		{
			this.type = type;
			this.slot = slot;
			this.itemId = itemId;
			this.sell = sell;
			this.price = price;
			this.quantity = quantity;
		}

		static Target of(DecisionEngine.Suggestion s)
		{
			return s == null ? null : new Target(s.type, s.slot, s.itemId, s.sellSide, s.suggestedPrice, s.suggestedQuantity);
		}

		boolean isNewOffer()
		{
			return type == SuggestionType.START_NEW_FLIP || type == SuggestionType.DUMP_BUY || type == SuggestionType.SELL;
		}
	}

	// What's on screen, as far as the guide needs it.
	static final class Screen
	{
		// GE interface open; and which part of it.
		boolean geOpen;
		// The "Set up offer" screen (new offer or modify).
		boolean setupOpen;
		// An existing offer's details screen.
		boolean detailsOpen;
		// The slot whose setup / details screen is open, 0-7, else -1.
		int openSlot = -1;
		// Setup screen fields.
		boolean setupSell;
		// <= 0: no item chosen yet.
		int setupItemId;
		long setupPrice;
		long setupQuantity;
		// The item search is open in the chatbox.
		boolean searchOpen;
		// Away from the GE.
		boolean bankOpen;
		boolean clerkNearby;
		boolean bankerNearby;
		// Coins in the inventory; -1 unknown.
		long coins = -1;
		// The target item (or its note) is in the inventory.
		boolean targetInInventory;
		// How many of the target item the inventory holds; -1 unknown.
		long heldCount = -1;
	}

	private GeGuide()
	{
	}

	// Steps for this suggestion on this screen; nothing when guidance is off.
	static List<Step> plan(boolean enabled, Target t, Screen s)
	{
		return plan(enabled, false, t, s);
	}

	// Sell-only mode: buys (and buy price changes) are never guided - only
	// collect, sells, sell price changes and aborts.
	static List<Step> plan(boolean enabled, boolean sellOnly, Target t, Screen s)
	{
		if (!enabled || t == null || s == null || (sellOnly && isBuySide(t)))
		{
			return Collections.emptyList();
		}
		return steps(t, s);
	}

	// A buy, or a price change of a buy offer.
	static boolean isBuySide(Target t)
	{
		return isBuy(t) || (t.type == SuggestionType.MODIFY && !t.sell);
	}

	static boolean isBuy(Target t)
	{
		return !t.sell && (t.type == SuggestionType.START_NEW_FLIP || t.type == SuggestionType.DUMP_BUY);
	}

	// A buy the coins in the inventory can't pay for.
	static boolean needsGp(Target t, Screen s)
	{
		return isBuy(t) && t.price != null && t.quantity != null && s.coins >= 0 && s.coins < t.price * t.quantity;
	}

	// Something to do at the GE for this suggestion.
	static boolean actionable(Target t)
	{
		switch (t.type)
		{
			case COLLECT:
			case ABANDON:
				return t.slot >= 0 || t.type == SuggestionType.COLLECT;
			case MODIFY:
				// Only with a real new price to change to.
				return t.slot >= 0 && t.price != null;
			case START_NEW_FLIP:
			case DUMP_BUY:
				// Only with an item to buy - an empty slot alone leads nowhere.
				return t.slot >= 0 && t.itemId > 0;
			case SELL:
				return t.itemId > 0;
			default:
				return false;
		}
	}

	static List<Step> steps(Target t, Screen s)
	{
		if (!actionable(t))
		{
			return Collections.emptyList();
		}
		boolean urgent = t.type == SuggestionType.DUMP_BUY;
		if (!s.geOpen)
		{
			return awayFromGe(t, s, urgent);
		}
		if (s.setupOpen)
		{
			return setupSteps(t, s, urgent);
		}
		if (s.detailsOpen)
		{
			if (t.type == SuggestionType.MODIFY && s.openSlot == t.slot)
			{
				return one(Element.MODIFY_BUTTON, Tone.ATTENTION, -1, urgent);
			}
			// Everything else starts from the home screen.
			return one(Element.BACK, Tone.NEXT, -1, urgent);
		}
		return homeSteps(t, s, urgent);
	}

	private static List<Step> awayFromGe(Target t, Screen s, boolean urgent)
	{
		boolean needGp = needsGp(t, s);
		if (s.bankOpen)
		{
			// Withdrawing gp: leave the bank alone; otherwise close it.
			return needGp ? Collections.emptyList() : one(Element.BANK_CLOSE, Tone.NEXT, -1, urgent);
		}
		if (!s.clerkNearby)
		{
			// Not at the GE: nothing on screen to point at.
			return Collections.emptyList();
		}
		if (needGp && s.bankerNearby)
		{
			return one(Element.BANKER, Tone.ATTENTION, -1, urgent);
		}
		return one(Element.CLERK, Tone.NEXT, -1, urgent);
	}

	private static List<Step> homeSteps(Target t, Screen s, boolean urgent)
	{
		switch (t.type)
		{
			case COLLECT:
				return one(Element.COLLECT, Tone.NEXT, -1, urgent);
			case ABANDON:
				return one(Element.SLOT_BOX, Tone.ABORT, t.slot, urgent);
			case MODIFY:
				return one(Element.SLOT_BOX, Tone.ATTENTION, t.slot, urgent);
			case START_NEW_FLIP:
			case DUMP_BUY:
				return one(Element.SLOT_BUY_BUTTON, Tone.NEXT, t.slot, urgent);
			case SELL:
				return s.targetInInventory ? one(Element.INVENTORY_ITEM, Tone.NEXT, -1, urgent) : Collections.emptyList();
			default:
				return Collections.emptyList();
		}
	}

	private static List<Step> setupSteps(Target t, Screen s, boolean urgent)
	{
		boolean modify = t.type == SuggestionType.MODIFY;
		if (!t.isNewOffer() && !modify)
		{
			// Collect / abort happen on the home screen.
			return one(Element.BACK, Tone.NEXT, -1, urgent);
		}
		if (t.itemId <= 0)
		{
			// A free slot with no item from Flippr: the player picks.
			return Collections.emptyList();
		}
		if (s.setupSell != t.sell)
		{
			return one(Element.BACK, Tone.NEXT, -1, urgent);
		}
		if (s.setupItemId != t.itemId && !t.sell && !modify)
		{
			// A buy with no item or the wrong one: the item in the search
			// when it's open, else the item box that opens it.
			return one(s.searchOpen ? Element.SEARCH_RESULT : Element.ITEM_SEARCH_BUTTON, Tone.NEXT, -1, urgent);
		}
		if (s.setupItemId != t.itemId)
		{
			// A sell (or a modify) of the wrong item: a sell starts from the
			// inventory, a modify from its slot.
			return s.setupItemId <= 0 ? Collections.emptyList() : one(Element.BACK, Tone.NEXT, -1, urgent);
		}
		List<Step> out = new ArrayList<>();
		boolean priceOk = t.price == null || s.setupPrice == t.price;
		boolean quantityOk = t.quantity == null || s.setupQuantity == t.quantity;
		if (!priceOk)
		{
			out.add(new Step(Element.PRICE_BUTTON, modify ? Tone.ATTENTION : Tone.NEXT, -1, urgent));
		}
		if (!quantityOk)
		{
			// Selling everything held: "All" sets it in one click.
			boolean all = t.sell && t.quantity != null && s.heldCount == t.quantity;
			out.add(new Step(all ? Element.QUANTITY_ALL_BUTTON : Element.QUANTITY_BUTTON, Tone.NEXT, -1, urgent));
		}
		if (priceOk && quantityOk)
		{
			out.add(new Step(Element.CONFIRM, Tone.NEXT, -1, urgent));
		}
		return out;
	}

	private static List<Step> one(Element e, Tone tone, int slot, boolean urgent)
	{
		return Collections.singletonList(new Step(e, tone, slot, urgent));
	}
}
