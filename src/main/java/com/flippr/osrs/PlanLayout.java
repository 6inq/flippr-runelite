package com.flippr.osrs;

// Sidebar Plan tab layout (FlipprConfig.planLayout): the website's detailed
// position cards, or one line per position.
public enum PlanLayout
{
	CARDS("Cards"),
	COMPACT("Compact");

	private final String label;

	PlanLayout(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
