package com.flippr.osrs;

// Shared market-price lookup for anything showing a live mark-to-market
// figure for an open position (FlipprPanel's Portfolio tab,
// PortfolioTooltipOverlay) - one real source of truth so the two can never
// quietly disagree with each other.
final class PortfolioMath
{
	private PortfolioMath()
	{
	}

	// Prefers the item's own real, currently-listed sell offer price -
	// what will actually be received if/when it fills, strictly more
	// accurate for a position the player has already committed a price to
	// than any external index. Falls back to PriceFeed's mid price
	// (average of the real observed instant-buy/instant-sell spread) only
	// when there's no active offer to read a real price from - NOT the
	// bid-only instant-sell price alone, which is the most pessimistic end
	// of the spread and confirmed live (two separate real items, two
	// separate screenshots) as the actual source of a real inaccuracy:
	// valuing a still-held position at the worst-case instant-liquidation
	// price undervalued it; a fair mid-market estimate is closer to what
	// the stock is actually worth.
	static Long getMarketPrice(DecisionEngine decisionEngine, PriceFeed priceFeed, int itemId)
	{
		Long activeSellPrice = decisionEngine.getActiveSellPrice(itemId);
		return activeSellPrice != null ? activeSellPrice : priceFeed.getMidPrice(itemId);
	}
}
