package com.flippr.osrs;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The Plan tab's "Plan setup": the website's "Set up your plan" form -
// same fields, options, defaults and capital shorthand as the site and the
// RS3 app. Build / Rebuild posts the body to
// POST /api/v1/osrs/plan/build. Pure, so it is unit tested; PlanSetupView
// draws it.
final class PlanSetup
{
	static final int MAX_SLOTS = 8;
	static final long MAX_CAPITAL = 10_000_000_000_000L;
	static final double[] FILL_WINDOWS = {0.5, 2, 4, 12, 24};
	static final String[] FILL_WINDOW_LABELS = {"30m", "2h", "4h", "12h", "24h"};
	static final String[] STYLES = {"volume", "highTicket"};
	static final String[] STYLE_LABELS = {"Volume", "High ticket"};
	static final String[] STYLE_HINTS = {"Spread across liquid items", "Big margins per unit"};
	// null = no cap.
	static final List<Integer> MAX_PER_SLOT = Collections.unmodifiableList(Arrays.asList(null, 25, 33, 50));
	static final String[] ACCOUNTS = {"both", "members", "f2p"};
	static final String[] ACCOUNT_LABELS = {"Both", "Members", "F2P"};
	static final String FILL_WINDOW_HINT = "How long each slot's full trade (buy then sell) should take.";
	static final String CAPITAL_ERROR = "Enter capital like 1.5m, 250k or 15000000.";
	static final String REBUILD_QUESTION = "Rebuild? Positions in progress keep their slots.";
	static final String BUILD_PATH = "/api/v1/osrs/plan/build";
	static final String SETTINGS_PATH = "/api/v1/osrs/plan/settings";

	// Suffix -> power of ten; RuneScape slang stacks k's (1kk = 1m).
	private static final Map<String, Integer> UNIT_EXPONENT = new LinkedHashMap<>();
	private static final Pattern CAPITAL_RE;

	static
	{
		UNIT_EXPONENT.put("thousand", 3);
		UNIT_EXPONENT.put("million", 6);
		UNIT_EXPONENT.put("billion", 9);
		UNIT_EXPONENT.put("mill", 6);
		UNIT_EXPONENT.put("bill", 9);
		UNIT_EXPONENT.put("kkk", 9);
		UNIT_EXPONENT.put("mil", 6);
		UNIT_EXPONENT.put("bil", 9);
		UNIT_EXPONENT.put("kk", 6);
		UNIT_EXPONENT.put("bn", 9);
		UNIT_EXPONENT.put("k", 3);
		UNIT_EXPONENT.put("m", 6);
		UNIT_EXPONENT.put("b", 9);
		// Longest first, so "kkk" isn't read as "k" + leftovers.
		CAPITAL_RE = Pattern.compile("^(\\d+(?:\\.\\d+)?|\\.\\d+)(" + String.join("|", UNIT_EXPONENT.keySet()) + ")?$");
	}

	private PlanSetup()
	{
	}

	// The form as the site opens it.
	static final class Form
	{
		String capital = "";
		int slots = MAX_SLOTS;
		String style = "volume";
		double fillWindowHours = 4;
		// null = Off.
		Integer maxPerSlotPct;
		String account = "both";
		boolean reinvest;
	}

	// Whole gp, or null when the text isn't a valid amount: "1.5m", "250k",
	// "2b", "1kk", "5.6bil", "15000000", "15,000,000", "24m gp".
	static Long parseCapital(String input)
	{
		if (input == null)
		{
			return null;
		}
		String text = input.trim().toLowerCase(Locale.ROOT).replaceAll("[,_\\s]", "");
		if (text.endsWith("gp"))
		{
			text = text.substring(0, text.length() - 2);
		}
		Matcher m = CAPITAL_RE.matcher(text);
		if (!m.matches())
		{
			return null;
		}
		int exponent = m.group(2) == null ? 0 : UNIT_EXPONENT.get(m.group(2));
		// Exact decimal arithmetic: "0.29m" is 290000, not 289999.99...
		BigDecimal value = new BigDecimal(m.group(1)).scaleByPowerOfTen(exponent);
		if (value.compareTo(BigDecimal.valueOf(MAX_CAPITAL)) > 0)
		{
			return null;
		}
		return value.longValue();
	}

	// Short form for the input: 1_500_000 -> "1.5m", 250_000 -> "250k".
	static String formatCapitalInput(long gp)
	{
		String[] units = {"b", "m", "k"};
		long[] sizes = {1_000_000_000L, 1_000_000L, 1_000L};
		for (int i = 0; i < units.length; i++)
		{
			if (gp < sizes[i])
			{
				continue;
			}
			double rounded = Math.round(gp * 100.0 / sizes[i]) / 100.0;
			if (rounded < 1000 && Math.round(rounded * sizes[i]) == gp)
			{
				String n = rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
				return n + units[i];
			}
		}
		return NumberFormat.getIntegerInstance(Locale.US).format(gp);
	}

	// The line under the capital box.
	static final class Hint
	{
		final Long gp;
		final String text;
		final boolean invalid;

		Hint(Long gp, String text, boolean invalid)
		{
			this.gp = gp;
			this.text = text;
			this.invalid = invalid;
		}
	}

	static Hint capitalHint(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return new Hint(null, "Shorthand works: 250k, 24m, 5.6b, 1kk.", false);
		}
		Long gp = parseCapital(text);
		return gp == null ? new Hint(null, "Not a valid amount.", true)
			: new Hint(gp, "= " + NumberFormat.getIntegerInstance(Locale.US).format(gp) + " gp", false);
	}

	// Slots held by positions already buying / bought / selling: they keep them.
	static int countSlotsInUse(List<ServerSuggestions.PlanPosition> positions)
	{
		int n = 0;
		if (positions != null)
		{
			for (ServerSuggestions.PlanPosition p : positions)
			{
				if (p != null && ("buying".equals(p.status) || "bought".equals(p.status) || "selling".equals(p.status)))
				{
					n++;
				}
			}
		}
		return n;
	}

	// Fewest slots a rebuild may ask for: the ones in use plus one new.
	static int minSlots(int slotsInUse)
	{
		return Math.min(MAX_SLOTS, Math.max(0, slotsInUse) + 1);
	}

	static boolean isFillWindow(double hours)
	{
		for (double w : FILL_WINDOWS)
		{
			if (w == hours)
			{
				return true;
			}
		}
		return false;
	}

	// The active plan's settings, else the defaults.
	static Form initialForm(ServerSuggestions.PlanInfo plan)
	{
		Form f = new Form();
		if (plan != null)
		{
			f.capital = plan.capital > 0 ? formatCapitalInput((long) plan.capital) : "";
			f.slots = plan.slots > 0 ? Math.min(MAX_SLOTS, plan.slots) : MAX_SLOTS;
			f.style = "highTicket".equals(plan.style) ? "highTicket" : "volume";
			f.fillWindowHours = plan.fillWindowHours != null && isFillWindow(plan.fillWindowHours) ? plan.fillWindowHours : 4;
			Integer pct = plan.maxPerSlotPct == null ? null : (int) Math.round(plan.maxPerSlotPct);
			f.maxPerSlotPct = MAX_PER_SLOT.contains(pct) ? pct : null;
			f.account = Arrays.asList(ACCOUNTS).contains(plan.account) ? plan.account : "both";
			f.reinvest = plan.reinvest;
		}
		return f;
	}

	// The build request: { body } or { error }.
	static final class Request
	{
		final Map<String, Object> body;
		final String error;

		Request(Map<String, Object> body, String error)
		{
			this.body = body;
			this.error = error;
		}
	}

	// maxPerSlotPct is left out when Off; the account is the one the player
	// picked; slots never drop below the ones in use + 1.
	static Request buildRequest(Form form, int slotsInUse)
	{
		Long capital = parseCapital(form.capital);
		if (capital == null)
		{
			return new Request(null, CAPITAL_ERROR);
		}
		if (capital < 1)
		{
			return new Request(null, "Capital must be at least 1 gp.");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("capital", capital);
		body.put("slots", Math.min(MAX_SLOTS, Math.max(form.slots, minSlots(slotsInUse))));
		body.put("style", "highTicket".equals(form.style) ? "highTicket" : "volume");
		body.put("fillWindowHours", isFillWindow(form.fillWindowHours) ? form.fillWindowHours : 4.0);
		if (form.maxPerSlotPct != null && MAX_PER_SLOT.contains(form.maxPerSlotPct))
		{
			body.put("maxPerSlotPct", form.maxPerSlotPct);
		}
		body.put("account", Arrays.asList(ACCOUNTS).contains(form.account) ? form.account : "both");
		body.put("reinvest", form.reinvest);
		return new Request(body, null);
	}

	// The plan's "Auto-update prices" / "Auto-fill free slots" (absent = on).
	static boolean autoSellPrice(ServerSuggestions.PlanInfo plan)
	{
		return plan == null || plan.autoSellPrice == null || plan.autoSellPrice;
	}

	static boolean autoFill(ServerSuggestions.PlanInfo plan)
	{
		return plan == null || plan.autoFill == null || plan.autoFill;
	}

	// Sell-only mode (absent = off).
	static boolean sellOnly(ServerSuggestions.PlanInfo plan)
	{
		return plan != null && Boolean.TRUE.equals(plan.sellOnly);
	}

	// PATCH /plan/settings body for one switch.
	static Map<String, Object> settingBody(String field, boolean on)
	{
		if (!"autoSellPrice".equals(field) && !"autoFill".equals(field) && !"sellOnly".equals(field))
		{
			throw new IllegalArgumentException(field);
		}
		return Collections.singletonMap(field, on);
	}
}
