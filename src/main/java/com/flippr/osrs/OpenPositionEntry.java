package com.flippr.osrs;

// A read-only snapshot of one of FlipTracker's currently-open buy positions
// (bought, not sold yet) - the raw shape the Portfolio view needs (market
// value, unrealized profit/ROI, avg buy price, time held), computed from
// real tracked positions rather than a live inventory scan, since a
// position is only "Flippr's stock" if this plugin actually tracked buying
// it (as opposed to a player's own pre-owned/manually-banked items).
public class OpenPositionEntry
{
	final int itemId;
	final String itemName;
	final long quantity;
	final long avgBuyPrice;
	final long firstBuyTime;

	OpenPositionEntry(int itemId, String itemName, long quantity, long avgBuyPrice, long firstBuyTime)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
		this.avgBuyPrice = avgBuyPrice;
		this.firstBuyTime = firstBuyTime;
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getItemName()
	{
		return itemName;
	}

	public long getQuantity()
	{
		return quantity;
	}

	public long getAvgBuyPrice()
	{
		return avgBuyPrice;
	}

	public long getFirstBuyTime()
	{
		return firstBuyTime;
	}
}
