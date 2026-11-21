package com.flippr.osrs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A Plan position whose buy offer was listed but is no longer in any GE
// slot - the player cancelled it (and maybe collected it) in game, so the
// plan still shows a buy that isn't happening. RuneLite sees every slot
// exactly, so the plugin can tell: the position is on its buy leg (pending /
// buying, not everything bought), its buy was listed (status buying, or
// this plugin saw its item in a buy slot before), and no slot holds a live
// or finished-uncollected buy offer for the item now. Then: nothing bought ->
// drop it; something bought -> Stop & sell (POST positions/{id}/stop either
// way).
//
// Never acts on a guess: only once the GE state has loaded after login, and
// only after the condition held on 2 consecutive plan reads (so a cancel /
// re-list, or the site catching up with a fill, never trips it). A Pending
// position that was never listed isn't touched (sell-only just pauses it).
// Pure (no RuneLite types), so it is unit tested; CancelledOfferWatcher
// feeds it.
final class CancelledBuys
{
	static final int CONFIRMATIONS = 2;
	static final int MAX_SEEN = 200;

	enum Fix
	{
		DROP,
		STOP_AND_SELL
	}

	static final class Finding
	{
		final String positionId;
		final String name;
		final Fix fix;
		final int bought;

		Finding(String positionId, String name, Fix fix, int bought)
		{
			this.positionId = positionId;
			this.name = name;
			this.fix = fix;
			this.bought = bought;
		}

		// The card's line and button.
		String text()
		{
			return "Buy offer cancelled in the GE";
		}

		String action()
		{
			return fix == Fix.DROP ? "Drop" : "Stop & sell " + PlanText.exactGp(bought);
		}
	}

	// Positions whose buy this plugin saw in a GE slot (kept across restarts).
	private final LinkedHashSet<String> seen = new LinkedHashSet<>();
	private final Map<String, Integer> confirmations = new HashMap<>();
	private final Map<String, Long> countedAt = new HashMap<>();
	// Fixed (or being fixed): not offered again.
	private final Set<String> handled = new HashSet<>();
	private boolean seenChanged;

	CancelledBuys(Collection<String> savedSeen)
	{
		if (savedSeen != null)
		{
			seen.addAll(savedSeen);
		}
	}

	static boolean onBuyLeg(ServerSuggestions.PlanPosition p)
	{
		return ("pending".equals(p.status) || "buying".equals(p.status)) && p.boughtQty < p.targetQty;
	}

	// A buy offer for the item still in a slot: buying, or bought and not
	// collected. A cancelled one doesn't count.
	static boolean hasBuyOffer(List<ListingReporter.SlotOffer> slots, int itemId)
	{
		for (ListingReporter.SlotOffer s : slots)
		{
			if (s.itemId == itemId && "buy".equals(s.side) && (s.open || s.completed))
			{
				return true;
			}
		}
		return false;
	}

	// One look at the GE. geLoaded: logged in and the slots have loaded;
	// fetchedAt: the plan read this is based on (a confirmation counts once
	// per plan read). Returns the confirmed findings not handled yet.
	List<Finding> update(List<ServerSuggestions.PlanPosition> positions, List<ListingReporter.SlotOffer> slots,
		boolean geLoaded, long fetchedAt)
	{
		List<Finding> out = new ArrayList<>();
		if (!geLoaded || positions == null || slots == null)
		{
			return out;
		}
		Set<String> live = new HashSet<>();
		for (ServerSuggestions.PlanPosition p : positions)
		{
			if (p == null || p.id == null)
			{
				continue;
			}
			live.add(p.id);
			if (onBuyLeg(p) && hasBuyOffer(slots, p.itemId) && seen.add(p.id))
			{
				seenChanged = true;
			}
			boolean wasListed = "buying".equals(p.status) || seen.contains(p.id);
			boolean candidate = onBuyLeg(p) && wasListed && !hasBuyOffer(slots, p.itemId) && !handled.contains(p.id);
			if (!candidate)
			{
				confirmations.remove(p.id);
				countedAt.remove(p.id);
				continue;
			}
			Long last = countedAt.get(p.id);
			if (last == null || last != fetchedAt)
			{
				confirmations.merge(p.id, 1, Integer::sum);
				countedAt.put(p.id, fetchedAt);
			}
			if (confirmations.getOrDefault(p.id, 0) >= CONFIRMATIONS)
			{
				out.add(new Finding(p.id, p.name, p.boughtQty > 0 ? Fix.STOP_AND_SELL : Fix.DROP, p.boughtQty));
			}
		}
		// Positions gone from the plan: forget them.
		confirmations.keySet().retainAll(live);
		countedAt.keySet().retainAll(live);
		return out;
	}

	// A fix was sent (or the player chose it): don't offer it again.
	void handled(String positionId)
	{
		handled.add(positionId);
		confirmations.remove(positionId);
	}

	// The fix failed: may be offered again after fresh confirmations.
	void retry(String positionId)
	{
		handled.remove(positionId);
		confirmations.remove(positionId);
		countedAt.remove(positionId);
	}

	// After a login / character switch: start counting again.
	void resetConfirmations()
	{
		confirmations.clear();
		countedAt.clear();
	}

	// The seen list to save, newest last, capped - or null when unchanged.
	List<String> takeSeenToSave()
	{
		if (!seenChanged)
		{
			return null;
		}
		seenChanged = false;
		while (seen.size() > MAX_SEEN)
		{
			seen.remove(seen.iterator().next());
		}
		return new ArrayList<>(seen);
	}
}
