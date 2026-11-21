package com.flippr.osrs;

// Exponential retry delay for the website calls: min, 2x min, 4x min, ...
// capped at max. Not thread-safe on its own; callers synchronize.
final class Backoff
{
	private final long minMs;
	private final long maxMs;
	private int failures;
	private long retryAtMs;

	Backoff(long minMs, long maxMs)
	{
		this.minMs = minMs;
		this.maxMs = maxMs;
	}

	// Records a failure at `now` and returns the delay before the next try.
	long fail(long now)
	{
		long delay = minMs;
		for (int i = 0; i < failures && delay < maxMs; i++)
		{
			delay *= 2;
		}
		delay = Math.min(delay, maxMs);
		failures++;
		retryAtMs = now + delay;
		return delay;
	}

	void reset()
	{
		failures = 0;
		retryAtMs = 0;
	}

	boolean isWaiting(long now)
	{
		return now < retryAtMs;
	}

	boolean isFailing()
	{
		return failures > 0;
	}

	long remainingMs(long now)
	{
		return Math.max(0, retryAtMs - now);
	}
}
