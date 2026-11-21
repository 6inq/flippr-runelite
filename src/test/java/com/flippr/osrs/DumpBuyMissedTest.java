package com.flippr.osrs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// A dump-buy is only cut on the tight clock once the market has recovered
// past the offer - not just because a few seconds passed.
public class DumpBuyMissedTest
{
	@Test
	public void priceRecoveredAboveOfferIsMissed()
	{
		assertTrue(DecisionEngine.dumpBuyMissed(78_696, 82_000L));
	}

	@Test
	public void priceStillAtOfferIsNotMissed()
	{
		assertFalse(DecisionEngine.dumpBuyMissed(78_696, 78_696L));
	}

	@Test
	public void priceStillBelowOfferIsNotMissed()
	{
		assertFalse(DecisionEngine.dumpBuyMissed(78_696, 75_000L));
	}

	@Test
	public void unknownPriceIsNotMissed()
	{
		assertFalse(DecisionEngine.dumpBuyMissed(78_696, null));
	}

	@Test
	public void noOfferPriceIsNotMissed()
	{
		assertFalse(DecisionEngine.dumpBuyMissed(0, 82_000L));
	}

	@Test
	public void oneGpAboveIsMissed()
	{
		assertTrue(DecisionEngine.dumpBuyMissed(1_000, 1_001L));
	}
}
