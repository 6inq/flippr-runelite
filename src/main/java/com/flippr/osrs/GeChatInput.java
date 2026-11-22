package com.flippr.osrs;

import java.text.NumberFormat;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

// The GE's chatbox price / quantity prompt on the "Set up offer" screen:
// which one is open, for which item and side, and the suggestion value that
// belongs in it. Read-only: nothing here writes to the prompt - the player
// types the value and presses Enter themselves. Client thread only; the
// static helpers are pure (unit tested).
@Singleton
public class GeChatInput
{
	// Chatbox prompt modes (VarClientID.MESLAYERMODE): a 32-bit number
	// (quantity), a 64-bit number (price - since the 2026 max-cash update) and
	// the GE item search.
	static final int MODE_INT = 7;
	static final int MODE_LONG = 30;
	static final int MODE_GE_SEARCH = 14;

	static final String TITLE_BUY_QUANTITY = "How many do you wish to buy?";
	static final String TITLE_SELL_QUANTITY = "How many do you wish to sell?";
	static final String TITLE_PRICE = "Set a price for each item:";
	// The "Buy offer" / "Sell offer" text: a child of the setup container
	// built by the GE's own script. The named SETUP_DESC component is the
	// item's description box, not this.
	static final int SETUP_OFFER_TEXT_CHILD = 20;

	enum Box
	{
		NONE,
		PRICE,
		QUANTITY
	}

	@Inject
	private Client client;

	@Inject
	private ServerSuggestions serverSuggestions;

	static boolean isNumericMode(int mode)
	{
		return mode == MODE_INT || mode == MODE_LONG;
	}

	// A GE price or quantity prompt is open: numeric input mode, the chatbox
	// title, the offer setup container and an open slot.
	static boolean inputOpen(int mode, boolean titleShown, boolean setupShown, boolean slotOpen)
	{
		return isNumericMode(mode) && titleShown && setupShown && slotOpen;
	}

	// Which prompt this is, from its title and the offer screen's
	// "Buy offer" / "Sell offer" text.
	static Box boxOf(int mode, String title, String setupDesc)
	{
		if (!isNumericMode(mode) || title == null)
		{
			return Box.NONE;
		}
		if (TITLE_BUY_QUANTITY.equals(title) || TITLE_SELL_QUANTITY.equals(title))
		{
			return Box.QUANTITY;
		}
		if (TITLE_PRICE.equals(title) && ("Buy offer".equals(setupDesc) || "Sell offer".equals(setupDesc)))
		{
			return Box.PRICE;
		}
		return Box.NONE;
	}

	// The suggestion value for this box, or null: the suggestion has to be
	// for this item and side (findSuggestion).
	static Long valueFor(DecisionEngine.Suggestion[] suggestions, Box box, int itemId, boolean sell)
	{
		return valueFor(suggestions, box, itemId, sell, false);
	}

	// Sell-only: nothing for a buy box.
	static Long valueFor(DecisionEngine.Suggestion[] suggestions, Box box, int itemId, boolean sell, boolean sellOnly)
	{
		if (box == Box.NONE || (sellOnly && !sell))
		{
			return null;
		}
		boolean quantity = box == Box.QUANTITY;
		DecisionEngine.Suggestion s = findSuggestion(suggestions, itemId, sell, quantity);
		return s == null ? null : quantity ? s.suggestedQuantity : s.suggestedPrice;
	}

	// The suggestion for this item, on the same side (buy/sell) as the open
	// box, with a value for the box (quantity or price). The one the overlay
	// highlights wins when it qualifies; otherwise the first qualifying slot.
	// Null = nothing to show.
	static DecisionEngine.Suggestion findSuggestion(DecisionEngine.Suggestion[] suggestions, int itemId, boolean sellBox,
		boolean quantityBox)
	{
		DecisionEngine.Suggestion highlighted = SuggestionOverlay.pickOneToHighlight(suggestions);
		if (matches(highlighted, itemId, sellBox, quantityBox))
		{
			return highlighted;
		}
		for (DecisionEngine.Suggestion suggestion : suggestions)
		{
			if (matches(suggestion, itemId, sellBox, quantityBox))
			{
				return suggestion;
			}
		}
		return null;
	}

	private static boolean matches(DecisionEngine.Suggestion s, int itemId, boolean sellBox, boolean quantityBox)
	{
		return s != null && itemId > 0 && s.itemId == itemId && s.sellSide == sellBox
			&& (quantityBox ? s.suggestedQuantity != null : s.suggestedPrice != null);
	}

	// "Flippr price: 1,234" / "Flippr quantity: 500".
	static String lineText(Box box, long value)
	{
		String number = NumberFormat.getIntegerInstance(Locale.US).format(value);
		return box == Box.PRICE ? "Flippr price: " + number : "Flippr quantity: " + number;
	}

	// The prompt open now: a numeric
	// chatbox input (VarClientInt INPUT_TYPE), the chatbox title, the GE
	// setup container and an open slot, then price vs quantity from the
	// title (and the offer screen's "Buy offer" / "Sell offer").
	Box openBox()
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		if (!inputOpen(mode, title != null, setup != null, client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) > 0))
		{
			return Box.NONE;
		}
		return boxOf(mode, title.getText(), offerText(setup));
	}

	private String offerText(Widget setup)
	{
		Widget text = setup.getChild(SETUP_OFFER_TEXT_CHILD);
		if (text != null && text.getText() != null && !text.getText().isEmpty())
		{
			return text.getText();
		}
		Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		return desc == null ? null : desc.getText();
	}

	int setupItemId()
	{
		return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
	}

	boolean setupIsSell()
	{
		return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
	}

	// The value Flippr suggests for the open box, or null.
	Long suggestedValue(DecisionEngine.Suggestion[] suggestions)
	{
		return valueFor(suggestions, openBox(), setupItemId(), setupIsSell(), serverSuggestions.isSellOnly());
	}
}
