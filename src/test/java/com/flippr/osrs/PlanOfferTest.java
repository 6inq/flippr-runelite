package com.flippr.osrs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Requirement: an open offer for a plan position follows the plan, not the
// minutes-based stale clock - so a plan sell at 0% after a few minutes is
// never "abandon, then re-list at the same price" (the live Dagannoth bones
// case). Offers outside the plan, and buys in sell-only mode, keep the
// ordinary rules.
public class PlanOfferTest
{
	@Test
	public void planSellFollowsPlan()
	{
		assertTrue(DecisionEngine.followsPlan(true, false, false, true));
	}

	@Test
	public void planBuyFollowsPlan()
	{
		assertTrue(DecisionEngine.followsPlan(true, true, false, true));
	}

	@Test
	public void offerOutsideThePlanKeepsOrdinaryRules()
	{
		assertFalse(DecisionEngine.followsPlan(true, false, false, false));
		assertFalse(DecisionEngine.followsPlan(true, true, false, false));
	}

	@Test
	public void sellOnlyBuyKeepsOrdinaryRules()
	{
		assertFalse(DecisionEngine.followsPlan(true, true, true, true));
		assertTrue("a sell in sell-only still follows the plan", DecisionEngine.followsPlan(true, false, true, true));
	}

	@Test
	public void finishedOrEmptySlotsAreNotOpenOffers()
	{
		assertFalse(DecisionEngine.followsPlan(false, false, false, true));
	}
}
