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
import net.runelite.client.config.Keybind;

// The GE's chatbox price / quantity prompt on the "Set up offer" screen:
// which one is open, for which item and side, the suggestion value that
// belongs in it, and typing that value into it. Typing means what typing
// does - the input widget's text and the input VarClientStr - and nothing
// else: the player still presses Enter. Client thread only; the static
// helpers are pure (unit tested).
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
	// for this item and side (QuickFillHandler.findSuggestion).
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
		DecisionEngine.Suggestion s = QuickFillHandler.findSuggestion(suggestions, itemId, sell, quantity);
		return s == null ? null : quantity ? s.suggestedQuantity : s.suggestedPrice;
	}

	// "Flippr price: 1,234 gp" / "Flippr quantity: 500", with the key when
	// fill-on-keypress is on.
	static String lineText(Box box, long value, Keybind keybind, boolean keybindOn)
	{
		String number = NumberFormat.getIntegerInstance(Locale.US).format(value);
		String text = box == Box.PRICE ? "Flippr price: " + number + " gp" : "Flippr quantity: " + number;
		if (keybindOn && keybind != null && !Keybind.NOT_SET.equals(keybind))
		{
			text += "  (or press " + keybind + ")";
		}
		return text;
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

	// Why there is nothing to fill now, or null when there is (for the log).
	String skipReason(DecisionEngine.Suggestion[] suggestions)
	{
		Box box = openBox();
		if (box == Box.NONE)
		{
			return "no GE price/quantity prompt open (mode=" + client.getVarcIntValue(VarClientID.MESLAYERMODE) + ")";
		}
		boolean sell = setupIsSell();
		if (!sell && serverSuggestions.isSellOnly())
		{
			return "sell-only mode, buy offer";
		}
		if (valueFor(suggestions, box, setupItemId(), sell, false) == null)
		{
			return "no Flippr " + (sell ? "sell" : "buy") + " suggestion with a " + box.name().toLowerCase()
				+ " for item " + setupItemId();
		}
		return null;
	}

	// Types the value into the open prompt, as typing it would (the input
	// line's text with its cursor, and the input VarClientStr). Never
	// submits: the player presses Enter.
	boolean type(long value)
	{
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (input == null)
		{
			return false;
		}
		input.setText(value + "*");
		client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value));
		return true;
	}
}
