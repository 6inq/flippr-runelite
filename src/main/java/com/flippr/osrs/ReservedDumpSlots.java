package com.flippr.osrs;

// How many of the 8 GE slots are treated as reserved specifically for
// dump-buying, as a dropdown option set (Auto/0/1/2/.../8). Public, not
// package-private - see MinPredictedProfit's own comment for why (a real,
// live IllegalAccessError otherwise, confirmed earlier this session with
// RiskLevel).
//
// AUTO behaves identically to ZERO here (no reservation, existing behavior
// unchanged) - same honesty rationale as MinPredictedProfit.AUTO: this
// plugin has no real logic to back a "smart" auto-sized reserve, so it
// doesn't pretend to.
public enum ReservedDumpSlots
{
	AUTO(0),
	ZERO(0),
	ONE(1),
	TWO(2),
	THREE(3),
	FOUR(4),
	FIVE(5),
	SIX(6),
	SEVEN(7),
	EIGHT(8);

	private final int count;

	ReservedDumpSlots(int count)
	{
		this.count = count;
	}

	int getCount()
	{
		return count;
	}

	@Override
	public String toString()
	{
		return this == AUTO ? "Auto" : String.valueOf(count);
	}
}
