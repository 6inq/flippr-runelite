package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: the Tracker and History tabs read the site's
// GET /api/v1/osrs/tracker and /history for the logged-in character - the
// query strings the routes parse, and their JSON - and say why when they
// can't.
public class WebsiteViewsTest
{
	private static final String TRACKER_JSON = "{\"game\":\"osrs\",\"character\":\"Zezima\","
		+ "\"totals\":{\"today\":{\"flips\":3,\"invested\":3000000,\"profit\":45000,\"taxPaid\":6000,\"wins\":3,\"hitRate\":1,\"roi\":0.015},"
		+ "\"7d\":{\"flips\":10,\"invested\":1,\"profit\":-2500,\"taxPaid\":1,\"wins\":4,\"hitRate\":0.4,\"roi\":null},"
		+ "\"30d\":{\"flips\":10,\"invested\":1,\"profit\":1,\"taxPaid\":1,\"wins\":4,\"hitRate\":0.4,\"roi\":0.1},"
		+ "\"all\":{\"flips\":99,\"invested\":1,\"profit\":1,\"taxPaid\":1,\"wins\":4,\"hitRate\":0.4,\"roi\":0.1}},"
		+ "\"range\":\"7d\",\"best\":[{\"itemId\":4151,\"itemName\":\"Abyssal whip\",\"iconUrl\":null,\"flips\":2,\"quantity\":20,"
		+ "\"invested\":20000000,\"profit\":300000,\"avgFlipMs\":3600000}],"
		+ "\"flips\":[{\"id\":\"f1\",\"itemId\":4151,\"itemName\":\"Abyssal whip\",\"iconUrl\":null,\"quantity\":10,\"buyPrice\":1000000,"
		+ "\"sellPrice\":1030000,\"tax\":206000,\"profit\":94000,\"roi\":0.0094,\"durationMs\":5400000,\"soldAt\":\"2026-11-20T10:00:00.000Z\"}],"
		+ "\"page\":2,\"pages\":3,\"total\":51}";

	private static final String HISTORY_JSON = "{\"game\":\"osrs\",\"character\":\"Zezima\",\"events\":["
		+ "{\"id\":\"e1\",\"ts\":1763600000000,\"kind\":\"fill\",\"side\":\"buy\",\"itemId\":4151,\"itemName\":\"Abyssal whip\","
		+ "\"qty\":10,\"price\":1000000,\"gp\":10000000,\"profit\":null,\"previousPrice\":null},"
		+ "{\"id\":\"e2\",\"ts\":1763600001000,\"kind\":\"price\",\"side\":\"sell\",\"itemId\":4151,\"itemName\":\"Abyssal whip\","
		+ "\"qty\":null,\"price\":1029000,\"gp\":null,\"profit\":null,\"previousPrice\":1030000},"
		+ "{\"id\":\"e3\",\"ts\":1763600002000,\"kind\":\"stopped\",\"side\":null,\"itemId\":4151,\"itemName\":\"Abyssal whip\","
		+ "\"qty\":4,\"price\":null,\"gp\":null,\"profit\":-1200,\"previousPrice\":null}],"
		+ "\"page\":1,\"pages\":1,\"total\":3}";

	@Mock
	private WebsiteConnection website;
	@Spy
	private Gson gson = new Gson();
	@InjectMocks
	private WebsiteViews views;

	private AutoCloseable mocks;
	private final List<String> paths = new ArrayList<>();

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	// Req: tracker JSON - totals per range, best items, a page of flips.
	@Test
	public void parsesTracker()
	{
		WebsiteViews.Tracker t = WebsiteViews.parseTracker(gson, TRACKER_JSON);
		assertEquals("7d", t.range);
		assertEquals(45_000, t.totals.get("today").profit, 0);
		assertEquals(3, t.totals.get("today").flips);
		assertNull(t.totals.get("7d").roi);
		assertEquals(99, t.totals.get("all").flips);
		assertEquals("Abyssal whip", t.best.get(0).itemName);
		assertEquals(300_000, t.best.get(0).profit, 0);
		WebsiteViews.TrackerFlip f = t.flips.get(0);
		assertEquals(10, f.quantity);
		assertEquals(94_000, f.profit, 0);
		assertEquals(5_400_000, f.durationMs);
		assertEquals("2026-11-20T10:00:00.000Z", f.soldAt);
		assertEquals(2, t.page);
		assertEquals(3, t.pages);
		assertEquals(51, t.total);
		assertEquals("1.5h", TrackerView.duration(f.durationMs));
		assertEquals("1.5%", TrackerView.pct(t.totals.get("today").roi));
	}

	// Req: history JSON - fills, price changes, stops; the row text.
	@Test
	public void parsesHistory()
	{
		WebsiteViews.History h = WebsiteViews.parseHistory(gson, HISTORY_JSON);
		assertEquals(3, h.events.size());
		assertEquals(3, h.total);
		assertEquals("10 x 1,000,000 = 10M", HistoryView.whatText(h.events.get(0)));
		assertEquals("1,030,000 -> 1,029,000", HistoryView.whatText(h.events.get(1)));
		assertEquals("4 sold", HistoryView.whatText(h.events.get(2)));
		assertNull(h.events.get(2).side);
		assertEquals(-1200, h.events.get(2).profit, 0);
		assertEquals("Price change", HistoryView.kindLabel("price"));
		assertTrue(WebsiteViews.parseHistory(gson, "{\"page\":1}").events.isEmpty());
	}

	// Req: query strings the routes parse (range/page/pageSize; side/q/from/to).
	@Test
	public void queries()
	{
		assertEquals("?range=today&page=1&pageSize=25", WebsiteViews.trackerQuery("today", 0));
		assertEquals("?side=sell&q=dragon%20bones&from=2026-11-01&to=2026-11-20&page=3&pageSize=25",
			WebsiteViews.historyQuery(new WebsiteViews.HistoryFilter("sell", " dragon bones ", "2026-11-01", "2026-11-20"), 3));
		assertEquals("?side=all&page=1&pageSize=25", WebsiteViews.historyQuery(new WebsiteViews.HistoryFilter("", "", " ", null), 1));
		assertEquals("?side=all&q=a%26b%3Dc&page=1&pageSize=25", WebsiteViews.historyQuery(new WebsiteViews.HistoryFilter("all", "a&b=c", "", ""), 1));
	}

	// Req: fetches go through the paired connection; not synced -> a reason, no request.
	@Test
	public void fetchUsesConnectionOrExplains()
	{
		doAnswer(inv ->
		{
			paths.add(inv.getArgument(0));
			WebsiteConnection.ApiCallback cb = inv.getArgument(1);
			cb.onResult(new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, TRACKER_JSON));
			return null;
		}).when(website).getAsync(anyString(), any());
		when(website.isSyncEnabled()).thenReturn(true);
		List<WebsiteViews.Result<WebsiteViews.Tracker>> got = new ArrayList<>();
		views.fetchTracker("7d", 2, got::add);
		assertEquals("/api/v1/osrs/tracker?range=7d&page=2&pageSize=25", paths.get(0));
		assertEquals(51, got.get(0).value.total);

		when(website.isSyncEnabled()).thenReturn(false);
		when(website.isPaired()).thenReturn(false);
		List<WebsiteViews.Result<WebsiteViews.History>> none = new ArrayList<>();
		views.fetchHistory(new WebsiteViews.HistoryFilter("all", "", "", ""), 1, none::add);
		assertNull(none.get(0).value);
		assertEquals("Pair with Flippr (Plan tab) to see this.", none.get(0).error);
		verify(website, never()).getAsync(org.mockito.ArgumentMatchers.startsWith(WebsiteViews.HISTORY_PATH), any());
	}
}
