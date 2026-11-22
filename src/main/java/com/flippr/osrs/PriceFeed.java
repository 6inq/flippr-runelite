package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

// Polls the OSRS Wiki's public Real-time Prices API
// (https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices) for the
// latest real instant-buy/instant-sell transaction per item - genuinely real
// trade data, community-sourced from RuneLite clients submitting their own
// completed GE transactions back to the wiki, not a synthetic guide price.
//
// Confirmed against the wiki's own docs: /latest is a poll endpoint, not a
// push stream - no WebSocket/streaming option exists. But each entry carries
// a real transaction timestamp (highTime/lowTime), so polling it every few
// seconds is functionally close to a live feed - you always know not just
// the last real price but when it actually happened.
//
// Field semantics, per the docs' own wording ("if we've never seen an
// instant-buy price, high/highTime will be null, and similarly for low/
// lowTime if we've never seen an instant-sell"): high = instant-buy price
// (what a buyer just paid to acquire instantly - the ask, what a flipper
// should target for a SELL offer), low = instant-sell price (what a seller
// just received to dispose instantly - the bid, what a flipper should target
// for a BUY offer). high >= low always, matching the field names.
//
// Their usage policy requires a descriptive User-Agent (not a generic HTTP
// client string) and asks callers to fetch the whole snapshot in one request
// rather than looping per item - /latest already returns every item at once.
// @Singleton - see DecisionEngine's class comment for why every stateful
// tracker in this plugin gets this regardless of current injection-site
// count. Doubly important here specifically: a second, un-shared instance
// wouldn't just show stale data, it would double the real poll rate against
// the OSRS Wiki's API, working against the "fetch the whole snapshot in one
// request" usage policy this class's own header comment describes.
@Slf4j
@Singleton
public class PriceFeed
{
	private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
	// The wiki's item metadata endpoint (id/name/limit/value/etc, one entry
	// per tradeable item) - a completely separate, much less volatile data set
	// from /latest's live prices. Buy limits essentially never change, so
	// this only needs fetching once per session, not polled.
	private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
	// Real historical OHLC-style data (confirmed live via curl, same API
	// family as /latest and /mapping) - per-item, bucketed averages rather
	// than /latest's single current snapshot. Always returns exactly 365
	// buckets regardless of timestep (confirmed live), so the bucket size
	// itself controls how far back the window reaches: 1h -> ~15 real days
	// (the sidebar chart's glance-at-the-trend window), 6h -> ~91 real days
	// / 13 weeks (BuyAndHoldAdvisor's weekly-pattern comparison window).
	// A genuinely different, much deeper data source than /latest - only
	// ever fetched for whichever single item is currently relevant (GE offer
	// screen open, or a stale sell about to be abandoned), not the whole
	// catalog up front the way /mapping is.
	private static final String TIMESERIES_URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries";
	private static final long TIMESERIES_CACHE_TTL_MS = 5 * 60_000;
	private static final String USER_AGENT = "Flippr RuneLite plugin - flippr.exchange";
	private static final long POLL_INTERVAL_MS = 10_000;

	static class PricePoint
	{
		Long high;
		Long highTime;
		Long low;
		Long lowTime;
	}

	private static class LatestResponse
	{
		Map<String, PricePoint> data;
	}

	// One entry in /mapping's response - only the fields this plugin
	// actually reads are declared, Gson ignores the rest.
	static class MappingEntry
	{
		int id;
		Integer limit;
		String name;
		boolean members;
	}

	private static final TypeToken<List<MappingEntry>> MAPPING_LIST_TYPE = new TypeToken<List<MappingEntry>>()
	{
	};

	// One bucket from /timeseries - avgHighPrice/avgLowPrice are the same
	// instant-buy/instant-sell semantics as PricePoint's high/low above,
	// just averaged over the bucket's timestep rather than being the single
	// latest observation. volume fields exist in the real response but
	// aren't read here - nothing today needs them.
	static class TimeseriesPoint
	{
		long timestamp;
		Long avgHighPrice;
		Long avgLowPrice;
		// Items traded in the bucket at the high / low price (the Prices
		// tab's volume).
		Long highPriceVolume;
		Long lowPriceVolume;
	}

	private static class TimeseriesResponse
	{
		List<TimeseriesPoint> data;
	}

	private static class TimeseriesCacheEntry
	{
		List<TimeseriesPoint> points;
		long fetchedAt;
	}

	// RuneLite's shared client (Plugin Hub requirement); a per-class derived
	// copy only to apply this class's own timeouts - it still shares the
	// injected client's connection pool and dispatcher.
	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private volatile OkHttpClient httpClient;

	private final AtomicReference<Map<String, PricePoint>> latestPrices =
		new AtomicReference<>(Collections.emptyMap());
	private volatile long lastPollTime;
	private volatile boolean pollInFlight;

	private final AtomicReference<Map<Integer, Integer>> buyLimits = new AtomicReference<>(Collections.emptyMap());
	// Every tradeable item (id, name, members, limit), for the Prices tab's
	// item search.
	private final AtomicReference<List<MappingEntry>> catalog = new AtomicReference<>(Collections.emptyList());
	private volatile boolean mappingFetched;
	private volatile boolean mappingFetchInFlight;

	// "itemId:timestep" -> its most recently fetched timeseries + when.
	// Keyed by timestep too (not just itemId): the sidebar price chart wants
	// 1h buckets (~15 real days), while BuyAndHoldAdvisor wants 6h buckets
	// (~91 real days - enough historical weeks to compare "this exact
	// day-of-week/time-of-day slot" against) - genuinely different windows
	// for genuinely different purposes, not the same data reused. A plain
	// synchronized HashMap (not an AtomicReference like latestPrices/
	// buyLimits above): this is per-key read-modify-write (check TTL, maybe
	// replace one entry), not a single wholesale snapshot swap, so a single
	// shared reference wouldn't fit the same pattern.
	private final Map<String, TimeseriesCacheEntry> timeseriesCache = new HashMap<>();
	private final java.util.Set<String> timeseriesFetchesInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// Same timeouts the previous HTTP client used: 5s connect, 10s for
	// the response (mapped to OkHttp's per-read/write timeouts).
	private OkHttpClient client()
	{
		OkHttpClient c = httpClient;
		if (c == null)
		{
			c = okHttpClient.newBuilder()
				.connectTimeout(5, TimeUnit.SECONDS)
				.readTimeout(10, TimeUnit.SECONDS)
				.writeTimeout(10, TimeUnit.SECONDS)
				.build();
			httpClient = c;
		}
		return c;
	}

	// Every wiki call carries the descriptive User-Agent the wiki's usage
	// policy requires.
	private static Request wikiRequest(String url)
	{
		return new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.get()
			.build();
	}

	// Called from a GameTick heartbeat - throttles itself to POLL_INTERVAL_MS
	// and runs the actual HTTP call off the game thread (OkHttp enqueue) so it
	// never blocks the client waiting on a network round trip.
	public void maybePoll()
	{
		long now = System.currentTimeMillis();
		if (pollInFlight || now - lastPollTime < POLL_INTERVAL_MS)
		{
			return;
		}
		pollInFlight = true;
		lastPollTime = now;

		client().newCall(wikiRequest(LATEST_URL)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Price feed poll failed", e);
				pollInFlight = false;
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				handleResponse(response);
			}
		});
	}

	// Called from the same GameTick heartbeat as maybePoll() - fires once per
	// session (guarded by mappingFetched), not on a recurring interval, since
	// buy limits are effectively static game data, unlike live prices.
	public void maybeFetchMapping()
	{
		if (mappingFetched || mappingFetchInFlight)
		{
			return;
		}
		mappingFetchInFlight = true;

		client().newCall(wikiRequest(MAPPING_URL)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Item mapping fetch failed", e);
				mappingFetchInFlight = false;
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				handleMappingResponse(response);
			}
		});
	}

	private void handleMappingResponse(Response response)
	{
		try (Response r = response; ResponseBody body = r.body())
		{
			if (r.code() != 200)
			{
				log.warn("Item mapping fetch returned status {}", r.code());
				return;
			}
			List<MappingEntry> entries = gson.fromJson(body.string(), MAPPING_LIST_TYPE.getType());
			if (entries != null)
			{
				Map<Integer, Integer> limits = new HashMap<>();
				for (MappingEntry entry : entries)
				{
					if (entry.limit != null)
					{
						limits.put(entry.id, entry.limit);
					}
				}
				buyLimits.set(limits);
				List<MappingEntry> named = new java.util.ArrayList<>();
				for (MappingEntry entry : entries)
				{
					if (entry.name != null)
					{
						named.add(entry);
					}
				}
				catalog.set(Collections.unmodifiableList(named));
				mappingFetched = true;
				log.info("Item mapping fetched: {} buy limits loaded", limits.size());
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse item mapping response", e);
		}
		finally
		{
			mappingFetchInFlight = false;
		}
	}

	// The GE's real 4-hour buy limit for this item, or null if the mapping
	// hasn't been fetched yet or this item has no limit on record.
	public Integer getBuyLimit(int itemId)
	{
		return buyLimits.get().get(itemId);
	}

	private void handleResponse(Response response)
	{
		try (Response r = response; ResponseBody body = r.body())
		{
			if (r.code() != 200)
			{
				log.warn("Price feed poll returned status {}", r.code());
				return;
			}
			LatestResponse parsed = gson.fromJson(body.string(), LatestResponse.class);
			if (parsed != null && parsed.data != null)
			{
				latestPrices.set(parsed.data);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse price feed response", e);
		}
		finally
		{
			pollInFlight = false;
		}
	}

	// The wiki catalogue entry for an item, or null.
	public MappingEntry getItem(int itemId)
	{
		for (MappingEntry e : catalog.get())
		{
			if (e.id == itemId)
			{
				return e;
			}
		}
		return null;
	}

	public boolean hasCatalog()
	{
		return !catalog.get().isEmpty();
	}

	// The Prices tab's autocomplete (best match first), matching the RS3 app's
	// item search.
	public List<MappingEntry> searchItems(String query, int limit)
	{
		return searchItems(catalog.get(), query, limit);
	}

	static String normName(String s)
	{
		return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\u2018\\u2019\\u02bc`\\u00b4]", "'")
			.replaceAll("\\s+", " ").trim();
	}

	// 0 exact, 1 starts with the query, 2 a word starts with it, 3 contains
	// it; -1 no match.
	static int matchRank(String name, String q)
	{
		String n = normName(name);
		if (n.equals(q))
		{
			return 0;
		}
		if (n.startsWith(q))
		{
			return 1;
		}
		for (String w : n.split("[\\s(\\-]+"))
		{
			if (w.startsWith(q))
			{
				return 2;
			}
		}
		return n.contains(q) ? 3 : -1;
	}

	// At least 2 characters; ties go to the shorter, then alphabetical name.
	static List<MappingEntry> searchItems(List<MappingEntry> rows, String query, int limit)
	{
		String q = normName(query);
		if (q.length() < 2)
		{
			return Collections.emptyList();
		}
		List<MappingEntry> hits = new java.util.ArrayList<>();
		Map<MappingEntry, Integer> rank = new java.util.IdentityHashMap<>();
		for (MappingEntry row : rows)
		{
			int r = matchRank(row.name, q);
			if (r >= 0)
			{
				hits.add(row);
				rank.put(row, r);
			}
		}
		hits.sort(java.util.Comparator.<MappingEntry>comparingInt(rank::get)
			.thenComparingInt(e -> e.name.length())
			.thenComparing(e -> e.name));
		return hits.size() <= limit ? hits : hits.subList(0, limit);
	}

	// The latest instant prices and their times (Unix seconds), or null.
	PricePoint getLatest(int itemId)
	{
		return latestPrices.get().get(Integer.toString(itemId));
	}

	// Items traded over the newest `buckets` buckets of a fetched
	// timeseries (both sides), or null when nothing is fetched yet.
	static Long volume(List<TimeseriesPoint> points, int buckets)
	{
		if (points == null || points.isEmpty())
		{
			return null;
		}
		long sum = 0;
		for (int i = Math.max(0, points.size() - buckets); i < points.size(); i++)
		{
			TimeseriesPoint p = points.get(i);
			sum += (p.highPriceVolume == null ? 0 : p.highPriceVolume) + (p.lowPriceVolume == null ? 0 : p.lowPriceVolume);
		}
		return sum;
	}

	// Convenience overloads for the sidebar price chart's own 1h-bucket
	// window - see the general (itemId, timestep) versions below for the
	// actual implementation.
	public void maybeFetchTimeseries(int itemId)
	{
		maybeFetchTimeseries(itemId, "1h");
	}

	public List<TimeseriesPoint> getTimeseries(int itemId)
	{
		return getTimeseries(itemId, "1h");
	}

	// Kicks off (or skips, if already cached and fresh, or already in
	// flight) a real background fetch of this item's recent price history
	// at the given bucket size - called from FlipprPanel (1h, chart) and
	// BuyAndHoldAdvisor (6h, weekly-pattern comparison) on their own
	// cadences, not polled for every item ever seen.
	public void maybeFetchTimeseries(int itemId, String timestep)
	{
		String cacheKey = itemId + ":" + timestep;
		TimeseriesCacheEntry cached;
		synchronized (timeseriesCache)
		{
			cached = timeseriesCache.get(cacheKey);
		}
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TIMESERIES_CACHE_TTL_MS)
		{
			return;
		}
		if (!timeseriesFetchesInFlight.add(cacheKey))
		{
			return;
		}

		String url = TIMESERIES_URL + "?id=" + itemId + "&timestep=" + timestep;
		client().newCall(wikiRequest(url)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Timeseries fetch for {} failed", cacheKey, e);
				timeseriesFetchesInFlight.remove(cacheKey);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				handleTimeseriesResponse(cacheKey, response);
			}
		});
	}

	private void handleTimeseriesResponse(String cacheKey, Response response)
	{
		try (Response r = response; ResponseBody body = r.body())
		{
			if (r.code() != 200)
			{
				log.warn("Timeseries fetch for {} returned status {}", cacheKey, r.code());
				return;
			}
			TimeseriesResponse parsed = gson.fromJson(body.string(), TimeseriesResponse.class);
			if (parsed != null && parsed.data != null)
			{
				TimeseriesCacheEntry entry = new TimeseriesCacheEntry();
				entry.points = parsed.data;
				entry.fetchedAt = System.currentTimeMillis();
				synchronized (timeseriesCache)
				{
					timeseriesCache.put(cacheKey, entry);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse timeseries response for {}", cacheKey, e);
		}
		finally
		{
			timeseriesFetchesInFlight.remove(cacheKey);
		}
	}

	// The most recently fetched price history for this item at this bucket
	// size, oldest point first - empty (not null) if nothing's been fetched
	// yet, so callers can render "no data yet" without a null check. Real
	// bucketed data from the wiki's own /timeseries endpoint, not
	// synthesized from /latest.
	public List<TimeseriesPoint> getTimeseries(int itemId, String timestep)
	{
		synchronized (timeseriesCache)
		{
			TimeseriesCacheEntry cached = timeseriesCache.get(itemId + ":" + timestep);
			return cached == null ? Collections.emptyList() : cached.points;
		}
	}

	// The current instant-buy price (ask) - what a flipper should target for
	// a SELL offer. Null if this item has never been observed.
	public Long getInstantBuyPrice(int itemId)
	{
		PricePoint p = latestPrices.get().get(Integer.toString(itemId));
		return p == null ? null : p.high;
	}

	// The current instant-sell price (bid) - what a flipper should target for
	// a BUY offer. Null if this item has never been observed.
	public Long getInstantSellPrice(int itemId)
	{
		PricePoint p = latestPrices.get().get(Integer.toString(itemId));
		return p == null ? null : p.low;
	}

	// A single representative "mark-to-market" price - the midpoint of the
	// real observed instant-buy/instant-sell spread when both are known,
	// falling back to whichever single side is known if only one is. For
	// valuing a still-held position (PortfolioMath), not for pricing an
	// actual GE offer: getInstantSellPrice()/getInstantBuyPrice() alone are
	// each the most PESSIMISTIC end of the real spread from a given side
	// (the bid a seller would actually receive selling right now, or the
	// ask a buyer would actually pay buying right now) - correct for
	// "target this price for an offer," but a real, confirmed-live source
	// of inaccuracy when used alone as a portfolio's "what is this worth"
	// figure (bid-only undervalues a still-held position vs. a fair
	// mid-market estimate). Null only if neither side has ever been
	// observed for this item.
	public Long getMidPrice(int itemId)
	{
		return midPrice(latestPrices.get().get(Integer.toString(itemId)));
	}

	// Pure form of getMidPrice() - package-visible for unit tests.
	static Long midPrice(PricePoint p)
	{
		if (p == null)
		{
			return null;
		}
		if (p.high != null && p.low != null)
		{
			return (p.high + p.low) / 2;
		}
		return p.high != null ? p.high : p.low;
	}

	// The whole current catalog snapshot, not one item at a time - for
	// DumpDetector, which needs to scan every item every sample rather than
	// look up one at a time. Package-visible only; same underlying poll data
	// as the per-item getters above.
	Map<String, PricePoint> getSnapshot()
	{
		return latestPrices.get();
	}
}
