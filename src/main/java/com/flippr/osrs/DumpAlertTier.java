package com.flippr.osrs;

// The "Dump alerts" size options (Off/100K+/200K+/500K+/1M+/2M+/5M+) -
// a coarser, EARLIER filter than MinPredictedProfit: this
// decides whether DumpDetector even flags/alerts on a dump at all, based on
// the dump's own maximum real capturable size (buying the full GE buy limit
// at the crashed price, reselling at today's real instant-buy price), not
// the player's own current capital. MinPredictedProfit is a separate, later
// filter on top of that - whether a specific candidate is actually worth
// FlipprOSRS *suggesting a buy* for, capital-constrained. The two can be
// tuned independently - e.g. wanting AWARENESS of even modest 100K+ dumps
// while only wanting
// active buy suggestions for bigger ones.
//
// Public, not package-private - see MinPredictedProfit's own comment for
// why (a real, live IllegalAccessError otherwise).
public enum DumpAlertTier
{
	OFF(-1),
	K100(100_000),
	K200(200_000),
	K500(500_000),
	M1(1_000_000),
	M2(2_000_000),
	M5(5_000_000);

	private final long threshold;

	DumpAlertTier(long threshold)
	{
		this.threshold = threshold;
	}

	long getThreshold()
	{
		return threshold;
	}

	@Override
	public String toString()
	{
		switch (this)
		{
			case OFF:
				return "Off";
			case K100:
				return "100K+";
			case K200:
				return "200K+";
			case K500:
				return "500K+";
			case M1:
				return "1M+";
			case M2:
				return "2M+";
			case M5:
			default:
				return "5M+";
		}
	}
}
