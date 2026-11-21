package com.flippr.osrs;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

// Requirement: when several slots have suggestions, exactly one is
// highlighted, by priority DUMP_BUY > COLLECT > ABANDON > HOLD > MODIFY >
// START_NEW_FLIP (SuggestionOverlay.pickOneToHighlight, also used by
// ChatNotifier). Ties go to the lowest slot.
public class SuggestionPriorityTest
{
	private static DecisionEngine.Suggestion s(SuggestionType type, int slot)
	{
		return new DecisionEngine.Suggestion(type + ":" + slot, type.name(), type, slot, 0, null);
	}

	private static DecisionEngine.Suggestion[] slots(DecisionEngine.Suggestion... bySlot)
	{
		DecisionEngine.Suggestion[] all = new DecisionEngine.Suggestion[8];
		System.arraycopy(bySlot, 0, all, 0, bySlot.length);
		return all;
	}

	// Req: COLLECT beats ABANDON, MODIFY and START_NEW_FLIP regardless of slot order.
	@Test
	public void collectBeatsAbandonModifyAndStart()
	{
		DecisionEngine.Suggestion collect = s(SuggestionType.COLLECT, 3);
		DecisionEngine.Suggestion[] all = slots(
			s(SuggestionType.START_NEW_FLIP, 0), s(SuggestionType.MODIFY, 1), s(SuggestionType.ABANDON, 2), collect);
		assertSame(collect, SuggestionOverlay.pickOneToHighlight(all));
	}

	// Req: ABANDON > MODIFY.
	@Test
	public void abandonBeatsModify()
	{
		DecisionEngine.Suggestion abandon = s(SuggestionType.ABANDON, 2);
		assertSame(abandon, SuggestionOverlay.pickOneToHighlight(
			slots(s(SuggestionType.MODIFY, 0), s(SuggestionType.START_NEW_FLIP, 1), abandon)));
	}

	// Req: MODIFY > START_NEW_FLIP.
	@Test
	public void modifyBeatsStartNewFlip()
	{
		DecisionEngine.Suggestion modify = s(SuggestionType.MODIFY, 5);
		assertSame(modify, SuggestionOverlay.pickOneToHighlight(
			slots(s(SuggestionType.START_NEW_FLIP, 0), null, null, null, null, modify)));
	}

	// Req: DUMP_BUY is the single highest priority.
	@Test
	public void dumpBuyBeatsEverything()
	{
		DecisionEngine.Suggestion dump = s(SuggestionType.DUMP_BUY, 7);
		assertSame(dump, SuggestionOverlay.pickOneToHighlight(slots(
			s(SuggestionType.COLLECT, 0), s(SuggestionType.ABANDON, 1), s(SuggestionType.HOLD, 2),
			s(SuggestionType.MODIFY, 3), s(SuggestionType.START_NEW_FLIP, 4), null, null, dump)));
	}

	// Req: HOLD sits between ABANDON and MODIFY.
	@Test
	public void holdSitsBetweenAbandonAndModify()
	{
		DecisionEngine.Suggestion hold = s(SuggestionType.HOLD, 1);
		assertSame(hold, SuggestionOverlay.pickOneToHighlight(slots(s(SuggestionType.MODIFY, 0), hold)));

		DecisionEngine.Suggestion abandon = s(SuggestionType.ABANDON, 1);
		assertSame(abandon, SuggestionOverlay.pickOneToHighlight(slots(s(SuggestionType.HOLD, 0), abandon)));
	}

	// Req: same type in several slots -> the lowest slot wins.
	@Test
	public void tieGoesToLowestSlot()
	{
		DecisionEngine.Suggestion first = s(SuggestionType.COLLECT, 2);
		assertSame(first, SuggestionOverlay.pickOneToHighlight(
			slots(null, null, first, s(SuggestionType.COLLECT, 3))));
	}

	// Req (edge): nothing due anywhere -> no highlight.
	@Test
	public void noSuggestionsMeansNoHighlight()
	{
		assertNull(SuggestionOverlay.pickOneToHighlight(new DecisionEngine.Suggestion[8]));
		assertNull(SuggestionOverlay.pickOneToHighlight(new DecisionEngine.Suggestion[0]));
	}
}
