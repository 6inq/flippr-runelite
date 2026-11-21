package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

// The sidebar's Tracker and History tabs: the website's read endpoints for
// the logged-in character, as the RS3 Alt1 app shows them:
// - GET /api/v1/osrs/tracker?range=today|7d|30d|all&page=&pageSize=
// - GET /api/v1/osrs/history?side=&q=&from=&to=&page=&pageSize=
// Fetched on demand (opening the tab, a filter, a page) with OkHttp enqueue
// through WebsiteConnection; callbacks run on an OkHttp thread.
@Singleton
public class WebsiteViews
{
	static final String TRACKER_PATH = "/api/v1/osrs/tracker";
	static final String HISTORY_PATH = "/api/v1/osrs/history";
	static final int PAGE_SIZE = 25;
	static final String[] RANGES = {"today", "7d", "30d", "all"};
	static final String[] RANGE_LABELS = {"Today", "7 days", "30 days", "All time"};

	static class Summary
	{
		int flips;
		double invested;
		double profit;
		double taxPaid;
		int wins;
		Double hitRate;
		Double roi;
	}

	static class BestItem
	{
		int itemId;
		String itemName;
		int flips;
		long quantity;
		double profit;
	}

	static class TrackerFlip
	{
		String id;
		int itemId;
		String itemName;
		long quantity;
		double buyPrice;
		double sellPrice;
		double tax;
		double profit;
		Double roi;
		long durationMs;
		// ISO time.
		String soldAt;
	}

	static class Tracker
	{
		String character;
		Map<String, Summary> totals;
		String range;
		List<BestItem> best;
		List<TrackerFlip> flips;
		int page;
		int pages;
		int total;
	}

	static class HistoryEvent
	{
		String id;
		long ts;
		// fill | flip | import | price | stopped | done
		String kind;
		// buy | sell | null
		String side;
		int itemId;
		String itemName;
		Long qty;
		Double price;
		Double gp;
		Double profit;
		Double previousPrice;
	}

	static class History
	{
		String character;
		List<HistoryEvent> events;
		int page;
		int pages;
		int total;
	}

	// History filters: side all|buy|sell, item text, from / to YYYY-MM-DD.
	static final class HistoryFilter
	{
		final String side;
		final String q;
		final String from;
		final String to;

		HistoryFilter(String side, String q, String from, String to)
		{
			this.side = side;
			this.q = q;
			this.from = from;
			this.to = to;
		}
	}

	// A fetch's result: the parsed body, or why there is none.
	static final class Result<T>
	{
		final T value;
		final String error;

		Result(T value, String error)
		{
			this.value = value;
			this.error = error;
		}
	}

	@Inject
	private WebsiteConnection website;

	@Inject
	private Gson gson;

	static String trackerQuery(String range, int page)
	{
		return "?range=" + enc(range) + "&page=" + Math.max(1, page) + "&pageSize=" + PAGE_SIZE;
	}

	static String historyQuery(HistoryFilter f, int page)
	{
		StringBuilder sb = new StringBuilder("?side=").append(enc(f == null || f.side == null || f.side.isEmpty() ? "all" : f.side));
		if (f != null && f.q != null && !f.q.trim().isEmpty())
		{
			sb.append("&q=").append(enc(f.q.trim()));
		}
		if (f != null && f.from != null && !f.from.trim().isEmpty())
		{
			sb.append("&from=").append(enc(f.from.trim()));
		}
		if (f != null && f.to != null && !f.to.trim().isEmpty())
		{
			sb.append("&to=").append(enc(f.to.trim()));
		}
		return sb.append("&page=").append(Math.max(1, page)).append("&pageSize=").append(PAGE_SIZE).toString();
	}

	private static String enc(String s)
	{
		try
		{
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static Tracker parseTracker(Gson gson, String body)
	{
		Tracker t = gson.fromJson(body, Tracker.class);
		if (t == null)
		{
			return null;
		}
		if (t.best == null)
		{
			t.best = new ArrayList<>();
		}
		if (t.flips == null)
		{
			t.flips = new ArrayList<>();
		}
		return t;
	}

	static History parseHistory(Gson gson, String body)
	{
		History h = gson.fromJson(body, History.class);
		if (h == null)
		{
			return null;
		}
		if (h.events == null)
		{
			h.events = new ArrayList<>();
		}
		return h;
	}

	public void fetchTracker(String range, int page, Consumer<Result<Tracker>> done)
	{
		fetch(TRACKER_PATH + trackerQuery(range, page), body -> parseTracker(gson, body), done);
	}

	public void fetchHistory(HistoryFilter filter, int page, Consumer<Result<History>> done)
	{
		fetch(HISTORY_PATH + historyQuery(filter, page), body -> parseHistory(gson, body), done);
	}

	private <T> void fetch(String path, java.util.function.Function<String, T> parse, Consumer<Result<T>> done)
	{
		if (!website.isSyncEnabled())
		{
			done.accept(new Result<>(null, notConnectedText()));
			return;
		}
		website.getAsync(path, response ->
		{
			if (response.outcome == WebsiteConnection.Outcome.OK)
			{
				try
				{
					T value = parse.apply(response.body);
					done.accept(value == null ? new Result<>(null, "Nothing came back from Flippr.") : new Result<>(value, null));
				}
				catch (RuntimeException e)
				{
					done.accept(new Result<>(null, "Flippr sent something unexpected - try again."));
				}
				return;
			}
			done.accept(new Result<>(null, errorText(response)));
		});
	}

	private String notConnectedText()
	{
		if (!website.isPaired())
		{
			return "Pair with Flippr (Plan tab) to see this.";
		}
		if (website.getCharacter() == null)
		{
			return "Log in to a character to see this.";
		}
		if (website.getCharacterError() != null)
		{
			return website.getCharacterError();
		}
		return "Website sync is off.";
	}

	private static String errorText(WebsiteConnection.ApiResponse response)
	{
		switch (response.outcome)
		{
			case MAINTENANCE:
				return "Flippr is under maintenance - try again in a few minutes.";
			case UNAUTHORIZED:
				return "This plugin key is no longer valid - pair again with a new code.";
			case CONFLICT:
				return "This character is linked to a different Flippr account.";
			default:
				return response.code == 0 ? "Couldn't reach Flippr - check your connection." : "Couldn't load this (HTTP " + response.code + ").";
		}
	}
}
