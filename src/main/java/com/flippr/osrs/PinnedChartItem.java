package com.flippr.osrs;

import javax.inject.Singleton;

// Set by PortfolioContextMenu's "Flippr chart" right-click option (built
// from this plugin's own already-real chart infrastructure) - lets
// FlipprPanel's price chart section show a specific item the player
// explicitly asked for. Lower priority than whatever's currently open in
// the real GE offer screen, which still takes over the moment one's open -
// this only fills the gap when nothing else is telling the chart what to
// show, matching "the player's most recent explicit signal wins."
@Singleton
public class PinnedChartItem
{
	private volatile int itemId = -1;

	public void set(int itemId)
	{
		this.itemId = itemId;
	}

	public int get()
	{
		return itemId;
	}
}
