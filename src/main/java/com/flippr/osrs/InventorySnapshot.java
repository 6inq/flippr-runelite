package com.flippr.osrs;

import java.util.List;

// One point-in-time read of liquid GP (coins) and full inventory contents -
// context for the player's flip records (how much capital and stock was on
// hand around a given flip), not itself a completed flip.
public class InventorySnapshot
{
	long timestamp;
	// "inventory_changed" (a real ItemContainerChanged event) or "heartbeat"
	// (periodic, so context exists even across idle stretches with no
	// actual change) - see InventoryTracker.
	String trigger;
	long coins;
	List<InventoryItemEntry> items;
}
