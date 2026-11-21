package com.flippr.osrs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// What to notify about from the Plan state - the same events as the RS3
// Alt1 app: price alerts, finished buys / sells,
// partial fills, automatic price changes / market below break-even on
// unlisted legs, a new flip auto-filled into a free slot, and a slot left
// free. Pure (no RuneLite types), so it is unit tested; PlanNotifier feeds
// it every plan refresh and sends what it returns through RuneLite's
// Notifier.
//
// Every event has a key (position + kind + count). Keys already sent are
// remembered (and saved, at most MAX_SENT), so a refresh or a restart
// never repeats one. The first check after start (or after switching
// character) only remembers what is already true - no storm on login.
// Partial fills of one position notify at most once per
// PARTIAL_COOLDOWN_MS. Sticky conditions (below break-even, a free slot)
// are forgotten when they end, so they notify again next time.
final class PlanNotifications
{
	static final int MAX_SENT = 500;
	static final long PARTIAL_COOLDOWN_MS = 10 * 60_000;
	// A pending position created this long after the plan was built was
	// auto-filled into a free slot (not part of the original build).
	static final long NEW_FLIP_AFTER_BUILD_MS = 60_000;
	private static final Pattern STICKY = Pattern.compile("^(bbe|free):.*");

	enum Type
	{
		PRICE_ALERTS,
		COMPLETED,
		PARTIAL,
		AUTO_PRICE,
		SLOT_FREE
	}

	static final class Settings
	{
		final boolean enabled;
		final boolean priceAlerts;
		final boolean completed;
		final boolean partial;
		final boolean autoPrice;
		final boolean slotFree;
		// Sell-only mode: buy events are silenced.
		final boolean sellOnly;

		Settings(boolean enabled, boolean priceAlerts, boolean completed, boolean partial, boolean autoPrice, boolean slotFree)
		{
			this(enabled, priceAlerts, completed, partial, autoPrice, slotFree, false);
		}

		Settings(boolean enabled, boolean priceAlerts, boolean completed, boolean partial, boolean autoPrice, boolean slotFree,
			boolean sellOnly)
		{
			this.sellOnly = sellOnly;
			this.enabled = enabled;
			this.priceAlerts = priceAlerts;
			this.completed = completed;
			this.partial = partial;
			this.autoPrice = autoPrice;
			this.slotFree = slotFree;
		}

		// The RS3 app's defaults.
		static Settings defaults()
		{
			return new Settings(true, true, true, false, true, true);
		}

		boolean allows(Type type)
		{
			if (!enabled)
			{
				return false;
			}
			switch (type)
			{
				case PRICE_ALERTS:
					return priceAlerts;
				case COMPLETED:
					return completed;
				case PARTIAL:
					return partial;
				case AUTO_PRICE:
					return autoPrice;
				case SLOT_FREE:
					return slotFree;
				default:
					return false;
			}
		}
	}

	static final class Event
	{
		final String key;
		final Type type;
		final String title;
		final String body;
		final String positionId;

		Event(String key, Type type, String title, String body, String positionId)
		{
			this.key = key;
			this.type = type;
			this.title = title;
			this.body = body;
			this.positionId = positionId;
		}

		// One line for RuneLite's Notifier.
		String message()
		{
			return title + " - " + body;
		}
	}

	private final Consumer<List<String>> save;
	private final List<String> sent = new ArrayList<>();
	private final Set<String> sentSet = new LinkedHashSet<>();
	private final Map<String, Long> lastPartial = new HashMap<>();
	private boolean seeded;

	// saved: the keys from the last run (oldest first). save is called
	// with the whole list whenever it changed.
	PlanNotifications(List<String> saved, Consumer<List<String>> save)
	{
		this.save = save;
		if (saved != null)
		{
			for (String k : saved)
			{
				if (k != null && sentSet.add(k))
				{
					sent.add(k);
				}
			}
		}
	}

	// The next check only remembers (another character's plan, a restart).
	void unseed()
	{
		seeded = false;
	}

	private static String exact(double n)
	{
		return PlanText.exactGp(n);
	}

	private static long ms(Double v)
	{
		return v == null ? 0 : v.longValue();
	}

	static List<Event> eventsFor(List<ServerSuggestions.PlanPosition> positions, List<ServerSuggestions.FreeSlot> freeSlots,
		ServerSuggestions.PlanInfo plan)
	{
		List<Event> out = new ArrayList<>();
		Double builtAt = plan == null ? null : plan.createdAt;
		for (ServerSuggestions.PlanPosition p : positions == null ? Collections.<ServerSuggestions.PlanPosition>emptyList() : positions)
		{
			String name = p.name;
			// Auto-fill started this slot's next flip.
			if (builtAt != null && p.slot != null && "pending".equals(p.status) && p.boughtQty <= 0
				&& p.createdAt != null && p.createdAt > builtAt + NEW_FLIP_AFTER_BUILD_MS)
			{
				out.add(new Event("newflip:" + p.id, Type.SLOT_FREE, "Flippr: new flip",
					"Slot " + p.slot + ": new flip - buy " + exact(p.targetQty) + " x " + name + " at " + exact(p.buyPrice), p.id));
			}
			// Price-move alert (site).
			String alert = PlanText.alertText(p.alertKind, p.suggestedPrice);
			if (alert != null)
			{
				out.add(new Event("alert:" + p.id + ":" + p.alertKind + ":" + (p.alertAt == null ? "" : String.valueOf(ms(p.alertAt))),
					Type.PRICE_ALERTS, "Flippr: " + name, alert, p.id));
			}
			// Buy finished, or partly bought.
			if (p.targetQty > 0 && p.boughtQty >= p.targetQty)
			{
				out.add(new Event("bought:" + p.id + ":" + p.boughtQty, Type.COMPLETED, "Flippr: buy finished",
					"Bought " + exact(p.boughtQty) + " x " + name + " - list the sell at " + exact(p.sellPrice), p.id));
			}
			else if (p.boughtQty > 0 && p.soldQty == 0)
			{
				out.add(new Event("buyfill:" + p.id + ":" + p.boughtQty, Type.PARTIAL, "Flippr: buying",
					"Bought " + exact(p.boughtQty) + " / " + exact(p.targetQty) + " " + name, p.id));
			}
			// Sell finished, or partly sold.
			if (p.boughtQty > 0 && ("done".equals(p.status) || p.soldQty >= p.boughtQty))
			{
				String result = p.realisedProfit < 0
					? PlanText.compactGp(-p.realisedProfit) + " loss"
					: (p.realisedProfit > 0 ? "+" : "") + PlanText.compactGp(p.realisedProfit) + " profit";
				out.add(new Event("sold:" + p.id + ":" + p.soldQty, Type.COMPLETED, "Flippr: sell finished",
					"Sold " + exact(p.soldQty) + " x " + name + " - " + result, p.id));
			}
			else if (p.soldQty > 0)
			{
				out.add(new Event("sellfill:" + p.id + ":" + p.soldQty, Type.PARTIAL, "Flippr: selling",
					"Sold " + exact(p.soldQty) + " / " + exact(p.boughtQty) + " " + name, p.id));
			}
			// Automatic price changes - only while that leg isn't listed.
			boolean unlisted = "pending".equals(p.status) || "bought".equals(p.status);
			if ("pending".equals(p.status) && p.previousBuyPrice != null && p.buyPriceUpdatedAt != null)
			{
				out.add(new Event("autobuy:" + p.id + ":" + ms(p.buyPriceUpdatedAt), Type.AUTO_PRICE, "Flippr: " + name,
					"Buy price updated to " + exact(p.buyPrice) + " (was " + exact(p.previousBuyPrice) + ")", p.id));
			}
			if (unlisted && p.previousSellPrice != null && p.sellPriceUpdatedAt != null)
			{
				out.add(new Event("autosell:" + p.id + ":" + ms(p.sellPriceUpdatedAt), Type.AUTO_PRICE, "Flippr: " + name,
					"Sell price updated to " + exact(p.sellPrice) + " (was " + exact(p.previousSellPrice) + ")", p.id));
			}
			if (unlisted && p.sellBelowBreakEven)
			{
				out.add(new Event("bbe:" + p.id, Type.AUTO_PRICE, "Flippr: " + name,
					"Market below break-even - sell held at " + exact(p.sellPrice), p.id));
			}
		}
		for (ServerSuggestions.FreeSlot f : freeSlots == null ? Collections.<ServerSuggestions.FreeSlot>emptyList() : freeSlots)
		{
			// With auto-fill, a slot still free has nothing that qualifies yet.
			String why = f.reason != null && !f.reason.isEmpty() ? " - " + f.reason : " - open Flippr for the next flip";
			out.add(new Event("free:" + f.slot, Type.SLOT_FREE, "Flippr: slot free", "Slot " + f.slot + " is free" + why, null));
		}
		return out;
	}

	// The events to send now (already handed to send).
	List<Event> check(List<ServerSuggestions.PlanPosition> positions, List<ServerSuggestions.FreeSlot> freeSlots,
		ServerSuggestions.PlanInfo plan, Settings settings, long now, Consumer<Event> send)
	{
		Settings s = settings == null ? Settings.defaults() : settings;
		List<Event> events = eventsFor(positions, freeSlots, plan);
		Set<String> live = new LinkedHashSet<>();
		for (Event e : events)
		{
			live.add(e.key);
		}
		// Sticky conditions that ended: next time is news again.
		boolean changed = sent.removeIf(k -> STICKY.matcher(k).matches() && !live.contains(k));
		if (changed)
		{
			sentSet.clear();
			sentSet.addAll(sent);
		}
		List<Event> out = new ArrayList<>();
		for (Event e : events)
		{
			if (!sentSet.add(e.key))
			{
				continue;
			}
			sent.add(e.key);
			changed = true;
			// First check after start: remember only. Sell-only: buy news
			// is remembered too, so turning it off later doesn't replay it.
			if (!seeded || !s.allows(e.type) || (s.sellOnly && isBuyEvent(e)))
			{
				continue;
			}
			if (e.type == Type.PARTIAL && e.positionId != null)
			{
				Long last = lastPartial.get(e.positionId);
				if (last != null && now - last < PARTIAL_COOLDOWN_MS)
				{
					continue;
				}
				lastPartial.put(e.positionId, now);
			}
			out.add(e);
			if (send != null)
			{
				send.accept(e);
			}
		}
		seeded = true;
		if (changed)
		{
			while (sent.size() > MAX_SENT)
			{
				sentSet.remove(sent.remove(0));
			}
			if (save != null)
			{
				save.accept(new ArrayList<>(sent));
			}
		}
		return out;
	}

	// New flips, free slots, buy fills, auto buy prices and buy price alerts.
	static boolean isBuyEvent(Event e)
	{
		String k = e.key;
		if (k.startsWith("newflip:") || k.startsWith("free:") || k.startsWith("buyfill:") || k.startsWith("autobuy:"))
		{
			return true;
		}
		return k.startsWith("alert:") && (k.contains(":raise_buy:") || k.contains(":lower_buy:") || k.contains(":no_margin:"));
	}

	List<String> sentKeys()
	{
		return Collections.unmodifiableList(sent);
	}
}
