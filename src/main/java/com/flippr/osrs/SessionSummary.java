package com.flippr.osrs;

// One completed session's totals, written once when the player resets the
// session (see SessionLogger) - the durable record "session separation"
// actually needs. Without this, resetting the in-memory counters in
// FlipTracker would just silently discard the numbers instead of drawing a
// real boundary between one session and the next.
public class SessionSummary
{
	long startTime;
	long endTime;
	// Same rationale as CompletedFlip.rsn - sessions.jsonl is also a single
	// file shared by every plugin instance on this machine.
	String rsn;
	int flipCount;
	long profit;
	// True if "Watch mode (dump)" was on when the session was reset - see
	// CompletedFlip.dumpModeSession for the same rationale, applied at the
	// whole-session grain instead of per-flip.
	boolean dumpModeSession;
}
