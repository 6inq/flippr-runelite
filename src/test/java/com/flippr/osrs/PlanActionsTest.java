package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: the Plan card's "Drop & recycle" / "Stop & sell n" sends
// POST /api/v1/osrs/positions/{id}/stop through the paired connection and
// reads the plan again at once (the freed slot auto-fills); the site's
// error is shown when it refuses. Also: the default website URL is
// https://flippr.exchange, and nothing talks to it until the plugin is paired;
// an /offers answer that moved a position refreshes the plan.
public class PlanActionsTest
{
	@Mock
	private WebsiteConnection website;
	@Mock
	private Client client;
	@Mock
	private FlipprConfig config;
	@Mock
	private InventoryTracker inventoryTracker;
	@Mock
	private FlipTracker flipTracker;
	@Mock
	private PriceFeed priceFeed;
	@Spy
	private Gson gson = new Gson();
	@InjectMocks
	private ServerSuggestions suggestions;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;
	private final List<String> posts = new ArrayList<>();
	private final List<String> gets = new ArrayList<>();
	private WebsiteConnection.ApiResponse answer;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		suggestions.clock = () -> now;
		when(website.isSyncEnabled()).thenReturn(true);
		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, "{\"position\":{},\"replacement\":null}");
		doAnswer(inv ->
		{
			posts.add(inv.getArgument(0) + " " + inv.getArgument(1));
			WebsiteConnection.ApiCallback cb = inv.getArgument(2);
			cb.onResult(answer);
			return null;
		}).when(website).postJsonAsync(anyString(), anyString(), any());
		doAnswer(inv ->
		{
			gets.add(inv.getArgument(0));
			WebsiteConnection.ApiCallback cb = inv.getArgument(1);
			cb.onResult(new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, "{\"plan\":{},\"positions\":[]}"));
			return null;
		}).when(website).getAsync(anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	// Req: the stop request's path and body; the plan is read again right after.
	@Test
	public void stopPostsAndRefreshesPlan()
	{
		suggestions.maybeFetch();
		assertEquals(1, gets.size());
		now += 3_000;
		List<ServerSuggestions.StopResult> results = new ArrayList<>();
		suggestions.stopPosition("cm3abc", results::add);
		assertEquals("/api/v1/osrs/positions/cm3abc/stop {}", posts.get(0));
		assertTrue(results.get(0).ok);
		suggestions.maybeFetch();
		assertEquals("plan read again without waiting 30s", 2, gets.size());
		assertEquals("/api/v1/osrs/positions/a%2Fb%20c/stop", ServerSuggestions.stopPath("a/b c"));
	}

	// Req: a refusal shows the site's message; not synced -> no request.
	@Test
	public void stopErrors()
	{
		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.FAILED, 400, "{\"error\":\"Position not found.\"}");
		List<ServerSuggestions.StopResult> results = new ArrayList<>();
		suggestions.stopPosition("x", results::add);
		assertFalse(results.get(0).ok);
		assertEquals("Position not found.", results.get(0).error);

		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.MAINTENANCE, 503, "{}");
		suggestions.stopPosition("x", results::add);
		assertEquals("Flippr is under maintenance - try again in a few minutes.", results.get(1).error);

		when(website.isSyncEnabled()).thenReturn(false);
		posts.clear();
		suggestions.stopPosition("x", results::add);
		assertTrue(posts.isEmpty());
		assertFalse(results.get(2).ok);
		assertNotNull(results.get(2).error);
	}

	// Req: an /offers answer with positionsUpdated > 0 refreshes the plan.
	@Test
	public void offersAnswerThatMovedAPosition()
	{
		assertTrue(OfferSync.movedPositions(gson, "{\"received\":2,\"stale\":0,\"positionsUpdated\":1}"));
		assertFalse(OfferSync.movedPositions(gson, "{\"received\":2,\"stale\":0,\"positionsUpdated\":0}"));
		assertFalse(OfferSync.movedPositions(gson, "not json"));
		assertFalse(OfferSync.movedPositions(gson, null));
	}

	// Req: default website URL is https://flippr.exchange (local testing overrides it).
	@Test
	public void defaultWebsiteUrl()
	{
		// Every FlipprConfig item has a default: this is RuneLite's out-of-the-box config.
		FlipprConfig defaults = new FlipprConfig()
		{
		};
		assertEquals("https://flippr.exchange", defaults.websiteBaseUrl());
		assertEquals("", defaults.pluginApiKey());
		assertEquals(PlanLayout.CARDS, defaults.planLayout());
		assertEquals("plan", defaults.sidebarTab());
		assertTrue(defaults.notificationsEnabled());
		assertTrue(defaults.notifyPriceAlerts());
		assertTrue(defaults.notifyOfferCompleted());
		assertFalse(defaults.notifyPartialFills());
		assertTrue(defaults.notifyAutoPrice());
		assertTrue(defaults.notifyNewFlip());
		assertTrue("Shift fills the price / quantity box by default", defaults.quickFillEnabled());
		assertEquals(net.runelite.client.config.Keybind.SHIFT, defaults.quickSetKeybind());
		assertFalse("sell-only is off by default", defaults.sellOnlyMode());
	}

	// Req: with the default URL but no pairing, nothing is fetched or sent.
	@Test
	public void nothingUntilPaired()
	{
		when(website.isSyncEnabled()).thenReturn(false);
		suggestions.maybeFetch();
		suggestions.stopPosition("x", r -> { });
		assertTrue(gets.isEmpty());
		assertTrue(posts.isEmpty());
		verify(website, never()).getAsync(anyString(), any());
	}
}
