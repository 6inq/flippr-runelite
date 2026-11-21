package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import net.runelite.client.config.Keybind;
import org.junit.Test;

// Requirement: filling the GE price / quantity prompt - the prompt is
// recognised for both number modes (quantity 32-bit, price 64-bit), the
// value is the suggestion's for that item and side, the chatbox line reads
// "Flippr price: 1,234 gp" / "Flippr quantity: 500", the fill key never is
// Enter or an unset key, and the GE search preset only ever touches rows
// carrying Flippr's own marker.
public class GeFillTest
{
	private static final int WHIP = 4151;
	private static final int COAL = 453;

	// Req: which prompt is open.
	@Test
	public void recognisesPrompts()
	{
		assertEquals(GeChatInput.Box.QUANTITY, GeChatInput.boxOf(7, "How many do you wish to buy?", "Buy offer"));
		assertEquals(GeChatInput.Box.QUANTITY, GeChatInput.boxOf(7, "How many do you wish to sell?", "Sell offer"));
		assertEquals("64-bit price prompt", GeChatInput.Box.PRICE, GeChatInput.boxOf(30, "Set a price for each item:", "Sell offer"));
		assertEquals(GeChatInput.Box.PRICE, GeChatInput.boxOf(7, "Set a price for each item:", "Buy offer"));
		assertEquals("price title outside an offer", GeChatInput.Box.NONE, GeChatInput.boxOf(30, "Set a price for each item:", "Something"));
		assertEquals("item search", GeChatInput.Box.NONE, GeChatInput.boxOf(14, "What would you like to buy?", "Buy offer"));
		assertEquals(GeChatInput.Box.NONE, GeChatInput.boxOf(7, null, "Buy offer"));
	}

	private static DecisionEngine.Suggestion sug(SuggestionType type, int itemId, boolean sell, Long price, Long qty)
	{
		return new DecisionEngine.Suggestion(type + ":0", "", type, 0, itemId, "Whip", price, qty, sell);
	}

	// Req: the value for the open box, only for the same item and side.
	@Test
	public void valueForBox()
	{
		DecisionEngine.Suggestion[] all = {sug(SuggestionType.START_NEW_FLIP, WHIP, false, 1_234_567L, 12L)};
		assertEquals(Long.valueOf(1_234_567L), GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, false));
		assertEquals(Long.valueOf(12L), GeChatInput.valueFor(all, GeChatInput.Box.QUANTITY, WHIP, false));
		assertNull("sell box", GeChatInput.valueFor(all, GeChatInput.Box.PRICE, WHIP, true));
		assertNull("other item", GeChatInput.valueFor(all, GeChatInput.Box.PRICE, 995, false));
		assertNull("no box", GeChatInput.valueFor(all, GeChatInput.Box.NONE, WHIP, false));
	}

	// Req: line text, exact gp; the key is named only when fill-on-keypress is on.
	@Test
	public void lineText()
	{
		assertEquals("Flippr price: 1,234 gp", GeChatInput.lineText(GeChatInput.Box.PRICE, 1_234, Keybind.SHIFT, false));
		assertEquals("Flippr quantity: 500", GeChatInput.lineText(GeChatInput.Box.QUANTITY, 500, Keybind.SHIFT, false));
		assertEquals("Flippr price: 2,147,483,648 gp", GeChatInput.lineText(GeChatInput.Box.PRICE, 2_147_483_648L, null, true));
		assertTrue(GeChatInput.lineText(GeChatInput.Box.QUANTITY, 500, Keybind.SHIFT, true).startsWith("Flippr quantity: 500  (or press "));
		assertEquals("Flippr quantity: 500", GeChatInput.lineText(GeChatInput.Box.QUANTITY, 500, Keybind.NOT_SET, true));
	}

	private static KeyEvent key(int code, int modifiers)
	{
		// A real key press carries its extended key code (what RuneLite's
		// Keybind matches on); a constructed event leaves it 0, so report it.
		return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return code;
			}
		};
	}

	// Req: keybind matching - the configured key fills; Enter never does; unset never does.
	@Test
	public void keybindMatching()
	{
		Keybind f5 = new Keybind(KeyEvent.VK_F5, 0);
		assertTrue(QuickFillHandler.triggers(f5, key(KeyEvent.VK_F5, 0)));
		assertFalse(QuickFillHandler.triggers(f5, key(KeyEvent.VK_F6, 0)));
		assertTrue(QuickFillHandler.triggers(Keybind.SHIFT, key(KeyEvent.VK_SHIFT, InputEvent.SHIFT_DOWN_MASK)));
		assertFalse("Enter is the submit key", QuickFillHandler.triggers(new Keybind(KeyEvent.VK_ENTER, 0), key(KeyEvent.VK_ENTER, 0)));
		assertFalse(QuickFillHandler.triggers(Keybind.NOT_SET, key(KeyEvent.VK_F5, 0)));
		assertFalse(QuickFillHandler.triggers(null, key(KeyEvent.VK_F5, 0)));
	}

	// Req: the search row shows only a suggested buy with an item - never
	// a sell, a free slot without an item, or anything in sell-only mode.
	@Test
	public void searchPresetTarget()
	{
		DecisionEngine.Suggestion buy = new DecisionEngine.Suggestion("START_NEW_FLIP:2", "", SuggestionType.START_NEW_FLIP, 2, WHIP, "Abyssal whip", 1_000L, 5L, false);
		DecisionEngine.Suggestion dump = new DecisionEngine.Suggestion("DUMP_BUY:2", "", SuggestionType.DUMP_BUY, 2, WHIP, "Abyssal whip", 900L, 5L);
		DecisionEngine.Suggestion freeSlot = new DecisionEngine.Suggestion("START_NEW_FLIP:3", "", SuggestionType.START_NEW_FLIP, 3, 0, null);
		DecisionEngine.Suggestion sell = new DecisionEngine.Suggestion("SELL:1", "", SuggestionType.SELL, 1, WHIP, "Abyssal whip", 1_200L, 5L, true);
		assertEquals(buy, GeGuideController.presetFor(buy, false));
		assertEquals(dump, GeGuideController.presetFor(dump, false));
		assertNull(GeGuideController.presetFor(freeSlot, false));
		assertNull(GeGuideController.presetFor(sell, false));
		assertNull("sell-only", GeGuideController.presetFor(buy, true));
		assertNull(GeGuideController.presetFor(null, false));
	}

	// Req: search preset - create in an empty row, update ours, point the game's
	// own previous search at the item, never touch a row without Flippr's
	// marker, clear ours when there's nothing to preset or the player is typing.
	@Test
	public void searchPresetChoice()
	{
		String ours = "<col=40a0ff>Abyssal whip</col>";
		String native_ = "<col=ff9040>Coal</col>";
		assertEquals(SearchSuggestionInjector.Action.CREATE, SearchSuggestionInjector.decide(true, true, null, -1, false));
		assertEquals(SearchSuggestionInjector.Action.CREATE, SearchSuggestionInjector.decide(true, true, "", -1, false));
		assertEquals("no previous search, \"Show last searched\" ticked: still Flippr's row",
			SearchSuggestionInjector.Action.CREATE, SearchSuggestionInjector.decide(true, true, "", -1, true));
		assertEquals(SearchSuggestionInjector.Action.CREATE, SearchSuggestionInjector.decide(true, true, null, -1, true));
		assertEquals(SearchSuggestionInjector.Action.UPDATE_OWN, SearchSuggestionInjector.decide(true, true, ours, WHIP, true));
		assertEquals(SearchSuggestionInjector.Action.OVERWRITE_NATIVE, SearchSuggestionInjector.decide(true, true, native_, COAL, true));
		assertEquals("a row without Flippr's marker, not the game's previous search", SearchSuggestionInjector.Action.NONE,
			SearchSuggestionInjector.decide(true, true, native_, COAL, false));
		assertEquals(SearchSuggestionInjector.Action.CLEAR_OWN, SearchSuggestionInjector.decide(false, true, ours, WHIP, false));
		assertEquals("typing", SearchSuggestionInjector.Action.CLEAR_OWN, SearchSuggestionInjector.decide(true, false, ours, WHIP, false));
		assertEquals(SearchSuggestionInjector.Action.NONE, SearchSuggestionInjector.decide(false, true, native_, COAL, true));
	}
}
