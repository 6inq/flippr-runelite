package com.flippr.osrs;

import java.util.Objects;

// Rate limit for field-debugging log lines: changed() is true only when the
// state differs from the last one seen, so a line logged behind it appears
// once per state change instead of every tick or frame.
final class ChangeLog
{
	private String last;

	boolean changed(String state)
	{
		if (Objects.equals(state, last))
		{
			return false;
		}
		last = state;
		return true;
	}

	void reset()
	{
		last = null;
	}
}
