package com.flippr.osrs;

// One matched buy-then-sell cycle for a single item. This is what gets
// persisted to flips.jsonl, not the raw per-event log lines.
public class CompletedFlip
{
	long timestamp;
	// The RSN of the account this flip happened on - every plugin instance
	// on this machine appends to the SAME flips.jsonl (see FlipLogger), so
	// this is what makes multi-account aggregation across those shared
	// records possible at all. Null only in the unlikely case neither the
	// display name nor login username was available at record time.
	String rsn;
	int itemId;
	String itemName;
	int quantity;
	long avgBuyPrice;
	long avgSellPrice;
	long tax;
	long profit;
	long firstBuyTime;
	long lastSellTime;
	// lastSellTime - firstBuyTime, computed once here so anything reading the
	// log doesn't need to recompute it - the total wall-clock time for the
	// whole flip, buy placed to sell completed.
	long totalDurationMs;
	// How long the buy side took to fully acquire the position (including any
	// cancel-and-retry fragments) - the buy-side counterpart to sellFillTimeMs.
	long buyFillTimeMs;
	// How long just the sell itself sat before it fully sold - a per-item
	// fill-time figure, captured as a side effect of tracking totalDurationMs.
	long sellFillTimeMs;
	// Liquidity varies by time of day/week (e.g. weekday daytime vs. late
	// night), which directly affects how long a flip takes to fill - denormalized
	// here (from lastSellTime, in a fixed America/New_York zone) so anything
	// reading the log doesn't have to redo the timezone math from the raw epoch
	// timestamp.
	String dayOfWeek;
	int hourOfDay;
	// The live OSRS Wiki instant-sell/instant-buy price (PriceFeed) at the
	// moment this buy/sell was first placed - the baseline avgBuyPrice/
	// avgSellPrice need to mean anything as a fill-time predictor. Without
	// this, "104gp" carries no signal on its own (was that aggressive or
	// lowballed relative to the market at the time?) - price AGGRESSIVENESS
	// relative to this baseline is the actual dominant driver of fill time,
	// not the raw price. Null if PriceFeed had no data for the item yet (a
	// real, expected case for rarely-traded items - not an error).
	Long referenceBuyPrice;
	Long referenceSellPrice;
	// True if "Watch mode (dump)" was on when this flip's sell completed -
	// the player was actively dump-hunting (reacting to a live price crash
	// themselves) rather than regular flipping. Dump-driven flips have a
	// genuinely different price-aggressiveness/fill-time profile than regular
	// ones (a huge, deliberate discount vs. an ordinary listed price), so this
	// lets the player's records keep the two kinds apart instead of mixing
	// them together. False for every flip logged before this field existed.
	boolean dumpModeSession;
}
