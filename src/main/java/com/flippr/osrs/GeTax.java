package com.flippr.osrs;

// OSRS GE tax for the sidebar's Plan cards and Prices tab: 2% of the sell
// price per item, rounded down, nothing under 50gp, at most 5,000,000 per
// item, and the wiki's tax-exempt items pay nothing - the same rule (one
// source of truth) as FlipTracker.computeTaxPerUnit for completed flips.
final class GeTax
{
	static final long EXEMPT_BELOW_GP = 50;

	private GeTax()
	{
	}

	static long taxPerUnit(String itemName, long sellPrice)
	{
		if (sellPrice < EXEMPT_BELOW_GP)
		{
			return 0;
		}
		return FlipTracker.computeTaxPerUnit(itemName == null ? "" : itemName, sellPrice);
	}

	// Margin per item flipping now: buy at the instant-sell price (low),
	// sell at the instant-buy price (high), minus the tax on that sale.
	// Null when either price is unknown.
	static Long margin(String itemName, Long instantBuy, Long instantSell)
	{
		if (instantBuy == null || instantSell == null || instantBuy <= 0 || instantSell <= 0)
		{
			return null;
		}
		return instantBuy - taxPerUnit(itemName, instantBuy) - instantSell;
	}
}
