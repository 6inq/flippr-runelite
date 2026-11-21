package com.flippr.osrs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// Requirement: GE tax is 2% of the per-unit sell price, rounded down, capped
// at 5,000,000 per unit; tax-exempt names pay 0. Exercises the single source
// of truth, FlipTracker.computeTaxPerUnit (rule unchanged).
public class GeTaxTest
{
	private static final String TAXED = "Abyssal whip";

	// Req: 2% per unit, floored - ordinary price.
	@Test
	public void twoPercentOfOrdinaryPrice()
	{
		assertEquals(20_000, FlipTracker.computeTaxPerUnit(TAXED, 1_000_000));
	}

	// Req: rounded down - 2% of 149 is 2.98 -> 2.
	@Test
	public void fractionalTaxRoundsDown()
	{
		assertEquals(2, FlipTracker.computeTaxPerUnit(TAXED, 149));
	}

	// Boundary: very cheap items - under 50gp the floored 2% is 0.
	@Test
	public void veryCheapItemsPayNoTax()
	{
		assertEquals(0, FlipTracker.computeTaxPerUnit(TAXED, 0));
		assertEquals(0, FlipTracker.computeTaxPerUnit(TAXED, 1));
		assertEquals(0, FlipTracker.computeTaxPerUnit(TAXED, 49));
	}

	// Boundary: 50gp is the first price that pays 1gp.
	@Test
	public void fiftyGpIsFirstTaxedPrice()
	{
		assertEquals(1, FlipTracker.computeTaxPerUnit(TAXED, 50));
		assertEquals(1, FlipTracker.computeTaxPerUnit(TAXED, 99));
		assertEquals(2, FlipTracker.computeTaxPerUnit(TAXED, 100));
	}

	// Boundary: just below the cap is still the plain 2%.
	@Test
	public void justBelowCapIsUncapped()
	{
		assertEquals(4_999_999, FlipTracker.computeTaxPerUnit(TAXED, 249_999_950));
	}

	// Boundary: exactly at the cap (2% of 250M = 5M).
	@Test
	public void exactlyAtCap()
	{
		assertEquals(5_000_000, FlipTracker.computeTaxPerUnit(TAXED, 250_000_000));
	}

	// Req: capped at 5M per unit above the cap.
	@Test
	public void aboveCapIsCapped()
	{
		assertEquals(5_000_000, FlipTracker.computeTaxPerUnit(TAXED, 250_000_050));
		assertEquals(5_000_000, FlipTracker.computeTaxPerUnit(TAXED, 2_147_483_647L));
		assertEquals(5_000_000, FlipTracker.computeTaxPerUnit(TAXED, 10_000_000_000L));
	}

	// Req: exempt names pay 0 even far above the cap.
	@Test
	public void exemptItemsPayZero()
	{
		assertEquals(0, FlipTracker.computeTaxPerUnit("Old school bond", 10_000_000));
		assertEquals(0, FlipTracker.computeTaxPerUnit("Lobster", 500));
		assertEquals(0, FlipTracker.computeTaxPerUnit("Games necklace(8)", 2_000));
		assertEquals(0, FlipTracker.computeTaxPerUnit("Spade", 300_000_000));
	}

	// Req: Energy potion is exempt at every dose (prefix match).
	@Test
	public void energyPotionExemptAtEveryDose()
	{
		for (int dose = 1; dose <= 4; dose++)
		{
			assertEquals(0, FlipTracker.computeTaxPerUnit("Energy potion(" + dose + ")", 1_000));
		}
	}

	// Req: exemption is by exact name - other charge counts / case are taxed.
	@Test
	public void nearMissNamesAreTaxed()
	{
		assertEquals(20, FlipTracker.computeTaxPerUnit("Games necklace(7)", 1_000));
		assertEquals(20, FlipTracker.computeTaxPerUnit("Ring of dueling(1)", 1_000));
		assertEquals(20, FlipTracker.computeTaxPerUnit("old school bond", 1_000));
		assertEquals(20, FlipTracker.computeTaxPerUnit("Super energy(4)", 1_000));
	}
}
