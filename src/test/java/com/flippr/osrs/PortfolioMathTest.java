package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

// Requirement: an open position's market price prefers the item's own active
// sell offer price over the wiki mid price; the value is gross (no tax
// deducted); an item with neither has no value (null).
public class PortfolioMathTest
{
	private static final int ITEM = 4151;

	private DecisionEngine decisionEngine;
	private PriceFeed priceFeed;

	@Before
	public void setUp()
	{
		decisionEngine = mock(DecisionEngine.class);
		priceFeed = mock(PriceFeed.class);
		// Mockito defaults Long returns to 0L; "no data" here is null.
		when(decisionEngine.getActiveSellPrice(ITEM)).thenReturn(null);
		when(priceFeed.getMidPrice(ITEM)).thenReturn(null);
	}

	// Req: active sell offer price wins over the wiki mid price.
	@Test
	public void activeSellPriceBeatsMidPrice()
	{
		when(decisionEngine.getActiveSellPrice(ITEM)).thenReturn(2_000_000L);
		when(priceFeed.getMidPrice(ITEM)).thenReturn(1_800_000L);
		assertEquals(Long.valueOf(2_000_000L), PortfolioMath.getMarketPrice(decisionEngine, priceFeed, ITEM));
	}

	// Req: no active sell offer -> wiki mid price.
	@Test
	public void fallsBackToMidPrice()
	{
		when(priceFeed.getMidPrice(ITEM)).thenReturn(1_800_000L);
		assertEquals(Long.valueOf(1_800_000L), PortfolioMath.getMarketPrice(decisionEngine, priceFeed, ITEM));
	}

	// Req: gross, no tax - an above-cap price comes back untaxed.
	@Test
	public void marketPriceIsGrossWithNoTax()
	{
		when(decisionEngine.getActiveSellPrice(ITEM)).thenReturn(400_000_000L);
		assertEquals(Long.valueOf(400_000_000L), PortfolioMath.getMarketPrice(decisionEngine, priceFeed, ITEM));

		when(decisionEngine.getActiveSellPrice(ITEM)).thenReturn(null);
		when(priceFeed.getMidPrice(ITEM)).thenReturn(1_000L);
		assertEquals(Long.valueOf(1_000L), PortfolioMath.getMarketPrice(decisionEngine, priceFeed, ITEM));
	}

	// Req: unknown item (no offer, never observed by the feed) -> no value.
	@Test
	public void unknownItemHasNoValue()
	{
		assertNull(PortfolioMath.getMarketPrice(decisionEngine, priceFeed, ITEM));
	}

	// Req: the wiki mid price is the floored average of instant-buy/sell.
	@Test
	public void midPriceIsAverageOfBothSides()
	{
		assertEquals(Long.valueOf(105L), PriceFeed.midPrice(point(110L, 100L)));
		assertEquals(Long.valueOf(100L), PriceFeed.midPrice(point(101L, 100L)));
	}

	// Req: only one side observed -> that side alone.
	@Test
	public void midPriceFallsBackToKnownSide()
	{
		assertEquals(Long.valueOf(110L), PriceFeed.midPrice(point(110L, null)));
		assertEquals(Long.valueOf(100L), PriceFeed.midPrice(point(null, 100L)));
	}

	// Req: never observed -> null, not a fabricated number.
	@Test
	public void midPriceUnknownIsNull()
	{
		assertNull(PriceFeed.midPrice(null));
		assertNull(PriceFeed.midPrice(point(null, null)));
	}

	private static PriceFeed.PricePoint point(Long high, Long low)
	{
		PriceFeed.PricePoint p = new PriceFeed.PricePoint();
		p.high = high;
		p.low = low;
		return p;
	}
}
