package com.flippr.osrs;

import java.awt.Color;

// Fill colour of a GE guidance highlight at a moment in time: the player's
// colour for the step's tone, its alpha pulsing when flashing is on (faster
// for a live dump), or a steady translucent fill when it's off. Pure.
final class GuideColors
{
	// Alpha range of the fill; the outline is drawn opaque.
	static final int STEADY_ALPHA = 90;
	static final int MIN_ALPHA = 40;
	static final int MAX_ALPHA = 150;
	// One pulse per period.
	static final long PULSE_MS = 1200;
	static final long URGENT_PULSE_MS = 500;

	private GuideColors()
	{
	}

	static Color base(GeGuide.Tone tone, Color next, Color attention, Color abort)
	{
		switch (tone)
		{
			case ATTENTION:
				return attention;
			case ABORT:
				return abort;
			case NEXT:
			default:
				return next;
		}
	}

	static int alpha(boolean flash, boolean urgent, long nowMs)
	{
		if (!flash)
		{
			return STEADY_ALPHA;
		}
		long period = urgent ? URGENT_PULSE_MS : PULSE_MS;
		double phase = (nowMs % period) / (double) period;
		// Triangle wave 0..1..0 over one period.
		double wave = 1 - Math.abs(2 * phase - 1);
		return (int) Math.round(MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * wave);
	}

	static Color fill(Color base, boolean flash, boolean urgent, long nowMs)
	{
		return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha(flash, urgent, nowMs));
	}
}
