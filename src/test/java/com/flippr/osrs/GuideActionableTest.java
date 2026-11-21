package com.flippr.osrs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// The guide only points at the GE when there's something concrete to do:
// a modify needs a new price, a buy needs an item.
public class GuideActionableTest
{
	private static GeGuide.Target t(SuggestionType type, int slot, int itemId, Long price)
	{
		return new GeGuide.Target(type, slot, itemId, true, price, null);
	}

	@Test
	public void modifyWithoutNewPriceIsNotActionable()
	{
		assertFalse(GeGuide.actionable(t(SuggestionType.MODIFY, 7, 31638, null)));
	}

	@Test
	public void modifyWithNewPriceIsActionable()
	{
		assertTrue(GeGuide.actionable(t(SuggestionType.MODIFY, 7, 31638, 6_650L)));
	}

	@Test
	public void newFlipWithoutItemIsNotActionable()
	{
		assertFalse(GeGuide.actionable(t(SuggestionType.START_NEW_FLIP, 0, 0, null)));
	}

	@Test
	public void newFlipWithItemIsActionable()
	{
		assertTrue(GeGuide.actionable(t(SuggestionType.START_NEW_FLIP, 0, 19580, 433L)));
	}

	@Test
	public void dumpBuyWithoutItemIsNotActionable()
	{
		assertFalse(GeGuide.actionable(t(SuggestionType.DUMP_BUY, 5, 0, null)));
	}

	@Test
	public void collectStaysActionable()
	{
		assertTrue(GeGuide.actionable(t(SuggestionType.COLLECT, 2, 21969, null)));
	}

	@Test
	public void abortStaysActionable()
	{
		assertTrue(GeGuide.actionable(t(SuggestionType.ABANDON, 5, 19927, null)));
	}
}
