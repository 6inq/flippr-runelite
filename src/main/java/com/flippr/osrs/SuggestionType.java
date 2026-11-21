package com.flippr.osrs;

// What a slot's current suggestion (DecisionEngine.Suggestion) is asking
// for - drives which real GE widget SuggestionOverlay highlights. A slot
// with nothing due simply has no Suggestion at all (null), rather than a
// distinct "idle" type here.
public enum SuggestionType
{
	// A live dump on this exact item, with a real empty slot to catch it in
	// - Dump Mode, scoped down to what's honest to build
	// without silently forcing a slot to autonomously stay in reserve: this
	// only ever fires when a slot is ALREADY free, never by suggesting the
	// engine sacrifice a healthy flip to manufacture one (see DecisionEngine).
	DUMP_BUY,
	COLLECT,
	// A stale, 0%-filled SELL that would otherwise be due for ABANDON right
	// now, but BuyAndHoldAdvisor found the current instant-sell price is
	// unusually low specifically for this exact day-of-week/time-of-day slot
	// compared to its own real recent history - buy-and-hold ("hold instead
	// of force-selling"), scoped to only ever
	// intercept the SELL-side abandon decision, never BUY-side (which is a
	// completely different concern - failing to acquire, not failing to
	// dispose). Purely a backward-looking historical comparison, not a
	// forecast of when/whether it recovers - see BuyAndHoldAdvisor.
	HOLD,
	ABANDON,
	MODIFY,
	// Sell units of a held item into a free slot, at the website plan's (or
	// the server's) sell price - highlights the item in the inventory.
	SELL,
	// A free slot; carries a real item/price/quantity when the website has
	// a suggestion (ServerSuggestions), else just "slot N is free".
	START_NEW_FLIP
}
