package com.flippr.osrs;

import javax.inject.Singleton;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

// Tracks, per GE slot, when that slot's offer last actually made buy/sell
// progress - deliberately separate from (and simpler than) the precise
// per-offer timestamps DecisionEngine/FlipTracker already keep for their own
// purposes (stale/modify thresholds measured from when the offer FIRST
// opened, completed-flip records). This is the "on-screen stopwatch" signal:
// SlotTimerOverlay draws it directly on the GE widget so the player can see,
// at a glance, how long it's been since a slot last did anything - not
// since it was first placed. A slot slowly trickling fills every couple
// minutes reads as "still alive" here even after sitting open a long time;
// a slot stuck at the same fill amount does not.
//
// Not wired into DecisionEngine yet - this is a standalone display primitive
// for now, tracked and drawn independently of the suggestion engine so it
// works even while that's paused. Feeding "time since last activity" into
// the ABANDON/MODIFY checks is a real decision-logic change, worth doing as
// its own deliberate step once this is confirmed live on screen.
// @Singleton is required, not defensive, here: this is injected into both
// FlipprPlugin (which feeds it real events) and SlotTimerOverlay (which
// reads elapsed time back out) - see DecisionEngine's class comment for the
// full story of why an unscoped class @Inject'd into more than one place
// silently forks into disconnected instances under RuneLite's plugin
// injector.
@Singleton
class SlotActivityTracker
{
	private static final int SLOT_COUNT = 8;

	private final long[] lastActivityTime = new long[SLOT_COUNT];
	private final long[] lastQuantitySold = new long[SLOT_COUNT];
	private final boolean[] active = new boolean[SLOT_COUNT];

	void onOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (slot < 0 || slot >= SLOT_COUNT)
		{
			return;
		}

		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			active[slot] = false;
			return;
		}

		// getQuantitySold() is a CUMULATIVE total for the current offer, not a
		// per-event delta (same fact FlipTracker's own comments establish) -
		// so "progress" is a rise since the last observed value, and a drop
		// (a brand new offer replacing whatever was here, including a price-
		// edit's cancel-and-relist) is itself a restart too.
		long quantitySold = offer.getQuantitySold();
		boolean isRestart = !active[slot] || quantitySold != lastQuantitySold[slot];
		if (isRestart)
		{
			lastActivityTime[slot] = System.currentTimeMillis();
		}

		lastQuantitySold[slot] = quantitySold;
		active[slot] = true;
	}

	// Milliseconds since this slot's offer was placed or last made buy/sell
	// progress, whichever is more recent - or -1 if the slot is empty.
	long getElapsedMs(int slot)
	{
		if (slot < 0 || slot >= SLOT_COUNT || !active[slot])
		{
			return -1;
		}
		return System.currentTimeMillis() - lastActivityTime[slot];
	}
}
