package com.flippr.osrs;

// How much slack the decision engine gives before flagging something as
// due - LOW is patient (fewer false alarms, might sit on a slow-moving
// opportunity a beat too long), HIGH is twitchy (catches things faster,
// noisier/more false positives). Deliberately scoped down: a fuller risk
// level would also control the mix of quick-hitter vs. longer-hold flips -
// that needs real item ranking (deciding WHAT to hold longer on) this
// plugin doesn't do itself, so this only covers the half that's actually
// buildable today: scaling the existing manually-tunable timing/percent
// thresholds (staleFillMinutes, modifyFallbackMinutes,
// priceMoveThresholdPercent, dumpThresholdPercent) rather than replacing
// them - a player who's already tuned those numbers keeps that tuning,
// riskLevel just multiplies it.
// Public, not package-private: FlipprConfig.riskLevel() returns this, and
// RuneLite's ConfigManager implements FlipprConfig via a JDK dynamic proxy
// created at runtime in a different module (jdk.proxy2) - a non-public return type
// is inaccessible across that module boundary and throws
// IllegalAccessError the moment riskLevel() is actually invoked through the
// real proxy (confirmed live - this doesn't surface in compileJava/test,
// since neither ever constructs a real ConfigManager proxy).
public enum RiskLevel
{
	LOW(1.5),
	MEDIUM(1.0),
	HIGH(0.6);

	private final double thresholdMultiplier;

	RiskLevel(double thresholdMultiplier)
	{
		this.thresholdMultiplier = thresholdMultiplier;
	}

	// MEDIUM is exactly 1.0 so a player who has never touched this setting
	// gets identical behavior to every threshold before riskLevel existed.
	double scaleThreshold(double baseValue)
	{
		return baseValue * thresholdMultiplier;
	}

	@Override
	public String toString()
	{
		switch (this)
		{
			case LOW:
				return "Low";
			case HIGH:
				return "High";
			case MEDIUM:
			default:
				return "Medium";
		}
	}
}
