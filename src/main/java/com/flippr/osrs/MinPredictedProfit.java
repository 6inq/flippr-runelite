package com.flippr.osrs;

// A minimum real-profit gate for DUMP_BUY suggestions, as a dropdown
// option set (Auto/20K/50K/100K/200K/500K/1M). Public, not package-private:
// FlipprConfig.minPredictedProfit() returns this, and RuneLite's
// ConfigManager implements FlipprConfig via a JDK dynamic proxy in a
// different module - confirmed live earlier (RiskLevel) that a
// non-public return type here throws IllegalAccessError the moment it's
// actually invoked through the real proxy, not at compile time.
//
// AUTO deliberately behaves identically to 0 (no gate) here, not some
// smarter threshold - this plugin has no real profit forecast to back a
// genuinely "smart" auto value, and pretending otherwise would be
// a guess dressed up as a feature. Shown as a separate option anyway so
// a smarter AUTO can be added later without changing the option set.
public enum MinPredictedProfit
{
	AUTO(0),
	K20(20_000),
	K50(50_000),
	K100(100_000),
	K200(200_000),
	K500(500_000),
	M1(1_000_000);

	private final long threshold;

	MinPredictedProfit(long threshold)
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
			case AUTO:
				return "Auto";
			case K20:
				return "20K";
			case K50:
				return "50K";
			case K100:
				return "100K";
			case K200:
				return "200K";
			case K500:
				return "500K";
			case M1:
			default:
				return "1M";
		}
	}
}
