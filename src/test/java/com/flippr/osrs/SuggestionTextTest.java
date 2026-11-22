package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

// Requirement: the chat line names the concrete item/quantity/price when a
// suggestion carries them, SELL ranks between MODIFY and START_NEW_FLIP,
// and quick-fill only ever uses a suggestion for the item and side (buy or
// sell) of the box that's open.
public class SuggestionTextTest
{
	private static final int BOND = 13190;
	private static final int WHIP = 4151;

	private static DecisionEngine.Suggestion withValues(SuggestionType type, int slot, int itemId, String name, Long price,
		Long qty, boolean sell)
	{
		return new DecisionEngine.Suggestion(type + ":" + slot, type.name(), type, slot, itemId, name, price, qty, sell);
	}

	@Test
	public void startNewFlipNamesItemQuantityAndPrice()
	{
		DecisionEngine.Suggestion s = withValues(SuggestionType.START_NEW_FLIP, 0, BOND, "Old school bond",
			10_592_523L, 97L, false);
		assertEquals("Buy 97 x Old school bond at 10,592,523 gp", ChatNotifier.chatText(s));
	}

	@Test
	public void startNewFlipWithoutServerDataKeepsTheOldLine()
	{
		DecisionEngine.Suggestion s = new DecisionEngine.Suggestion("START_NEW_FLIP:2", "", SuggestionType.START_NEW_FLIP, 2, 0, null);
		assertEquals("Slot 2 is free - pick something to flip", ChatNotifier.chatText(s));
	}

	@Test
	public void sellAndModifyLines()
	{
		assertEquals("Sell 3 x Abyssal whip at 1,620,000 gp", ChatNotifier.chatText(
			withValues(SuggestionType.SELL, 1, WHIP, "Abyssal whip", 1_620_000L, 3L, true)));
		assertEquals("Slot 4: change the price on Abyssal whip to 1,555,000 gp", ChatNotifier.chatText(
			withValues(SuggestionType.MODIFY, 4, WHIP, "Abyssal whip", 1_555_000L, null, false)));
		assertEquals("Slot 4: nudge the price on Abyssal whip", ChatNotifier.chatText(
			withValues(SuggestionType.MODIFY, 4, WHIP, "Abyssal whip", null, null, false)));
	}

	@Test
	public void sellSitsBetweenModifyAndStartNewFlip()
	{
		DecisionEngine.Suggestion start = withValues(SuggestionType.START_NEW_FLIP, 0, BOND, "Old school bond", 1L, 1L, false);
		DecisionEngine.Suggestion sell = withValues(SuggestionType.SELL, 1, WHIP, "Abyssal whip", 1L, 1L, true);
		DecisionEngine.Suggestion modify = withValues(SuggestionType.MODIFY, 2, WHIP, "Abyssal whip", null, null, false);
		DecisionEngine.Suggestion[] all = new DecisionEngine.Suggestion[8];
		all[0] = start;
		all[1] = sell;
		assertSame(sell, SuggestionOverlay.pickOneToHighlight(all));
		all[2] = modify;
		assertSame(modify, SuggestionOverlay.pickOneToHighlight(all));
	}

	// Req: the hint comes from whatever suggestion matches the open item and
	// side - not only DUMP_BUY - and never from a different item.
	@Test
	public void hintPicksTheMatchingSuggestion()
	{
		DecisionEngine.Suggestion[] all = new DecisionEngine.Suggestion[8];
		all[0] = withValues(SuggestionType.START_NEW_FLIP, 0, BOND, "Old school bond", 10_592_523L, 97L, false);
		all[1] = withValues(SuggestionType.SELL, 1, WHIP, "Abyssal whip", 1_620_000L, 3L, true);
		all[2] = withValues(SuggestionType.MODIFY, 2, WHIP, "Abyssal whip", null, null, false);

		assertSame(all[0], GeChatInput.findSuggestion(all, BOND, false, true));
		assertSame(all[0], GeChatInput.findSuggestion(all, BOND, false, false));
		assertSame(all[1], GeChatInput.findSuggestion(all, WHIP, true, true));
		assertNull("buy box for the whip: only a MODIFY without a price", GeChatInput.findSuggestion(all, WHIP, false, false));
		assertNull("sell box for the bond: wrong side", GeChatInput.findSuggestion(all, BOND, true, true));
		assertNull("unrelated item", GeChatInput.findSuggestion(all, 995, false, true));
	}
}
