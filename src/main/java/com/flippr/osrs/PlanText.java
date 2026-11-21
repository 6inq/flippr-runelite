package com.flippr.osrs;

import java.text.NumberFormat;
import java.util.Locale;

// Wording and number formats shared by the sidebar's Plan cards and the
// notifications - the same text as the website and the RS3 Alt1 app. Pure,
// so it is unit tested.
final class PlanText
{
	// The site's UPDATE_HINT says to open the offer afterwards so the Alt1 app
	// can read the new price; RuneLite sees the price directly, so changing
	// the offer is all it takes.
	static final String UPDATE_HINT = "then update the offer in the GE";
	static final String NO_MARGIN_TEXT = "Market moved: no profit at current prices - consider Stop & sell or Drop";

	private PlanText()
	{
	}

	// Exact gp, as typed into the GE price box: "47,040,818".
	static String exactGp(double n)
	{
		return NumberFormat.getIntegerInstance(Locale.US).format(Math.round(n));
	}

	// "47.9M", "312.4K", "9,950" (the site's formatGpCompact: one decimal,
	// rounded down, ".0" dropped; exact below 10K).
	static String compactGp(double n)
	{
		String sign = n < 0 ? "-" : "";
		double a = Math.abs(n);
		if (a >= 1e9)
		{
			return sign + trim(a / 1e9) + "B";
		}
		if (a >= 1e6)
		{
			return sign + trim(a / 1e6) + "M";
		}
		if (a >= 1e4)
		{
			return sign + trim(a / 1e3) + "K";
		}
		return sign + exactGp(a);
	}

	private static String trim(double v)
	{
		String s = String.format(Locale.US, "%.1f", Math.floor(v * 10) / 10);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	// "+1.2M gp", "-950 gp", "0 gp".
	static String signedGp(double n)
	{
		return (n > 0 ? "+" : "") + compactGp(n) + " gp";
	}

	// Fill time: "25m", "3.5h", "14h"; "-" when unknown.
	static String hours(Double h)
	{
		if (h == null || h.isNaN() || h.isInfinite())
		{
			return "-";
		}
		if (h < 1)
		{
			return Math.max(1, Math.round(h * 60)) + "m";
		}
		if (h < 10)
		{
			double r = Math.round(h * 10) / 10.0;
			return (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r)) + "h";
		}
		return Math.round(h) + "h";
	}

	// "4m ago", "2h ago".
	static String ago(long thenMs, long nowMs)
	{
		long min = Math.max(0, (nowMs - thenMs) / 60_000);
		return min < 60 ? min + "m ago" : (min / 60) + "h ago";
	}

	// The site's alert text with the OSRS hint:
	// exact prices, so they can be typed straight into the GE. Null for no
	// (or an unknown) alert.
	static String alertText(String kind, Double suggestedPrice)
	{
		if (kind == null)
		{
			return null;
		}
		String to = suggestedPrice != null ? " to " + exactGp(suggestedPrice) + " - " + UPDATE_HINT : "";
		switch (kind)
		{
			case "raise_buy":
			case "lower_buy":
				return "Set buy" + to;
			case "lower_sell":
				return "Set sell" + to;
			case "raise_sell":
				return "Can raise sell" + to;
			case "below_break_even":
				return suggestedPrice != null
					? "Market below break-even - set sell to " + exactGp(suggestedPrice) + " (lowest price without a loss), " + UPDATE_HINT
					: "Market below break-even";
			case "no_margin":
				return NO_MARGIN_TEXT;
			default:
				return null;
		}
	}

	static final String PAUSED_LABEL = "Paused (sell-only)";

	// Sell-only mode pauses a Pending position whose buy isn't listed yet
	// (nothing bought, not in a GE slot): Flippr won't ask for that buy.
	static boolean isPaused(ServerSuggestions.PlanPosition p, boolean sellOnly, boolean inGe)
	{
		return sellOnly && "pending".equals(p.status) && p.boughtQty <= 0 && !inGe;
	}

	// The status pill: the site's labels.
	static String statusLabel(String status)
	{
		if (status == null)
		{
			return "";
		}
		switch (status)
		{
			case "pending":
				return "Pending";
			case "buying":
				return "Buying";
			case "bought":
				return "Bought";
			case "selling":
				return "Selling";
			case "done":
				return "Done";
			case "stopped":
				return "Stopped";
			default:
				return status;
		}
	}

	// The site card's stop button: only while
	// something is still to buy. Null when there is none.
	static String stopLabel(ServerSuggestions.PlanPosition p)
	{
		boolean canStop = "pending".equals(p.status) || "buying".equals(p.status)
			|| ("bought".equals(p.status) && p.boughtQty < p.targetQty);
		if (!canStop)
		{
			return null;
		}
		return p.boughtQty == 0 ? "Drop & recycle" : "Stop & sell " + exactGp(p.boughtQty);
	}

	// The confirm dialog's question for the stop button.
	static String stopQuestion(ServerSuggestions.PlanPosition p)
	{
		return p.boughtQty == 0
			? "Drop " + p.name + " from the plan and recycle its gp into the next flip?"
			: "Stop buying " + p.name + " and sell the " + exactGp(p.boughtQty) + " bought? The slot's gp is recycled once it sells.";
	}

	// Bought of target while buying, sold of bought while selling.
	// {done, of, label}.
	static final class Progress
	{
		final int done;
		final int of;
		final String label;
		final double value;

		Progress(int done, int of, String label)
		{
			this.done = done;
			this.of = of;
			this.label = label;
			this.value = Math.min(1, done / (double) Math.max(of, 1));
		}
	}

	static Progress progress(ServerSuggestions.PlanPosition p)
	{
		if ("selling".equals(p.status) || "done".equals(p.status))
		{
			return new Progress(p.soldQty, p.boughtQty, "sold");
		}
		return new Progress(p.boughtQty, p.targetQty, "bought");
	}

	// Net profit per unit after GE tax at the planned prices.
	static long netPerUnit(ServerSuggestions.PlanPosition p)
	{
		long sell = Math.round(p.sellPrice);
		return sell - GeTax.taxPerUnit(p.name, sell) - Math.round(p.buyPrice);
	}

	// gp the position ties up, capped at one slot's share of the plan.
	static double allocation(ServerSuggestions.PlanPosition p, ServerSuggestions.PlanInfo plan)
	{
		double cost = p.targetQty * p.buyPrice;
		if (plan == null || plan.capital <= 0)
		{
			return cost;
		}
		double cap = plan.maxPerSlotPct != null && plan.maxPerSlotPct > 0
			? Math.floor(plan.capital * plan.maxPerSlotPct / 100)
			: plan.capital;
		return Math.min(cost, cap);
	}

	// "Buy updated from 1,234 (4m ago)" / "Sell updated from ..." lines.
	static String priceUpdatedLine(ServerSuggestions.PlanPosition p, long now)
	{
		StringBuilder sb = new StringBuilder();
		if (p.previousBuyPrice != null && p.buyPriceUpdatedAt != null)
		{
			sb.append("Buy updated from ").append(exactGp(p.previousBuyPrice))
				.append(" (").append(ago(p.buyPriceUpdatedAt.longValue(), now)).append(")");
		}
		if (p.previousSellPrice != null && p.sellPriceUpdatedAt != null)
		{
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append("Sell updated from ").append(exactGp(p.previousSellPrice))
				.append(" (").append(ago(p.sellPriceUpdatedAt.longValue(), now)).append(")");
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	// The summary strip.
	static final class Summary
	{
		double deployed;
		double deployedShare;
		int slotsFilled;
		int slotsTotal;
		double expectedProfit;
		double realisedProfit;
		Double avgFillHours;
		Double maxFillHours;
	}

	static Summary summarize(ServerSuggestions.PlanInfo plan, java.util.List<ServerSuggestions.PlanPosition> positions)
	{
		Summary s = new Summary();
		double cost = 0;
		double fillSum = 0;
		int fills = 0;
		for (ServerSuggestions.PlanPosition p : positions)
		{
			if ("done".equals(p.status) || "stopped".equals(p.status))
			{
				continue;
			}
			cost += p.targetQty * p.buyPrice;
			if (!"pending".equals(p.status) || p.slot != null)
			{
				s.slotsFilled++;
			}
			s.expectedProfit += p.expectedProfit;
			s.realisedProfit += p.realisedProfit;
			if (p.estFillHours != null && !p.estFillHours.isNaN() && !p.estFillHours.isInfinite())
			{
				fillSum += p.estFillHours;
				fills++;
				s.maxFillHours = s.maxFillHours == null ? p.estFillHours : Math.max(s.maxFillHours, p.estFillHours);
			}
		}
		double capital = plan == null ? 0 : plan.capital;
		s.deployed = plan == null ? 0 : Math.min(cost, capital);
		s.deployedShare = capital > 0 ? s.deployed / capital : 0;
		s.slotsTotal = plan == null ? 0 : plan.slots;
		s.avgFillHours = fills > 0 ? fillSum / fills : null;
		return s;
	}
}
