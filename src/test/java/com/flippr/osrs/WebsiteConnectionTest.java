package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import net.runelite.client.config.ConfigManager;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: every website call shares one status rule - 2xx ok, 401
// stops syncing until re-paired, a 503 {error:'maintenance'} pauses all
// calls for Retry-After seconds (default 300, the site's
// MAINTENANCE_RETRY_AFTER_SECONDS); an empty base URL turns everything off.
public class WebsiteConnectionTest
{
	@Mock
	private FlipprConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private OkHttpClient okHttpClient;
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private WebsiteConnection website;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		website.clock = () -> now;
		when(config.websiteBaseUrl()).thenReturn("http://localhost:3030/");
		when(config.pluginApiKey()).thenReturn("abc");
		when(config.syncWithWebsite()).thenReturn(true);
		website.setCharacter("6ing");
	}

	// Account keys: calls wait for the logged-in character, and a 409
	// character_taken stops syncing until a different character logs in.
	@Test
	public void characterGatesSyncAndConflictHoldsUntilCharacterChanges()
	{
		website.setCharacter(null);
		assertFalse(website.isSyncEnabled());
		website.setCharacter("Iron Man");
		assertEquals("Iron Man", website.getCharacter());
		assertTrue(website.isSyncEnabled());

		assertEquals(WebsiteConnection.Outcome.CONFLICT,
			website.handle(409, null, "{\"error\":\"character_taken\",\"message\":\"taken\"}").outcome);
		assertFalse(website.isSyncEnabled());
		assertTrue(website.getCharacterError().contains("different Flippr account"));

		website.setCharacter("Other");
		assertTrue(website.isSyncEnabled());
	}

	// RuneLite reports the name's space as a non-breaking space; the header
	// carries a plain space, and the same name again is not a change.
	@Test
	public void characterHeaderIsSentWithEveryCall()
	{
		assertTrue(website.setCharacter("Iron Man"));
		assertFalse(website.setCharacter("Iron Man"));
		okhttp3.Request request = website.authorized(WebsiteConnection.STATUS_PATH).get().build();
		assertEquals("Iron Man", request.header(WebsiteConnection.CHARACTER_HEADER));
		assertEquals("Bearer abc", request.header("Authorization"));
		assertEquals("osrs", request.url().queryParameter("game"));

		website.setCharacter(null);
		assertEquals(null, website.authorized("/api/v1/osrs/plan").get().build().header(WebsiteConnection.CHARACTER_HEADER));
	}

	// Logging out and back in to the same character retries it (the 409 is
	// held only while that character stays logged in).
	@Test
	public void relogRetriesHeldConflict()
	{
		website.handle(409, null, "{\"error\":\"character_taken\",\"message\":\"taken\"}");
		assertFalse(website.isSyncEnabled());
		website.setCharacter(null);
		website.setCharacter("6ing");
		assertTrue(website.isSyncEnabled());
	}

	@Test
	public void characterRequiredShowsServerMessage()
	{
		website.handle(409, null, "{\"error\":\"character_required\",\"message\":\"Pick which character to use.\"}");
		assertEquals("Pick which character to use.", website.getCharacterError());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	@Test
	public void okIsOk()
	{
		assertEquals(WebsiteConnection.Outcome.OK, website.handle(200, null, "{}").outcome);
		assertTrue(website.isSyncEnabled());
		assertEquals("http://localhost:3030", website.baseUrl());
	}

	@Test
	public void unauthorizedStopsSync()
	{
		assertEquals(WebsiteConnection.Outcome.UNAUTHORIZED, website.handle(401, null, "{\"error\":\"Invalid\"}").outcome);
		assertTrue(website.isAuthRejected());
		assertFalse(website.isSyncEnabled());
	}

	@Test
	public void maintenancePausesForRetryAfter()
	{
		WebsiteConnection.ApiResponse r = website.handle(503, "120",
			"{\"error\":\"maintenance\",\"message\":\"Upgrading\",\"etaText\":null}");
		assertEquals(WebsiteConnection.Outcome.MAINTENANCE, r.outcome);
		assertTrue(website.isInMaintenance());
		assertEquals(120_000, website.getMaintenanceRemainingMs());
		assertEquals("Upgrading", website.getMaintenanceMessage());

		now += 120_000;
		assertFalse(website.isInMaintenance());
	}

	@Test
	public void maintenanceDefaultsTo300Seconds()
	{
		website.handle(503, null, "{\"error\":\"maintenance\",\"message\":\"\"}");
		assertEquals(300_000, website.getMaintenanceRemainingMs());
		assertEquals(300, WebsiteConnection.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
	}

	@Test
	public void otherErrorsAreFailures()
	{
		assertEquals(WebsiteConnection.Outcome.FAILED, website.handle(503, null, "<html>bad gateway</html>").outcome);
		assertEquals(WebsiteConnection.Outcome.FAILED, website.handle(500, null, "{}").outcome);
		assertFalse(website.isInMaintenance());
		assertFalse(website.isAuthRejected());
	}

	@Test
	public void emptyUrlOrSyncOffDisablesEverything()
	{
		when(config.websiteBaseUrl()).thenReturn("  ");
		assertFalse(website.isConfigured());
		assertFalse(website.isSyncEnabled());

		when(config.websiteBaseUrl()).thenReturn("http://localhost:3030");
		when(config.syncWithWebsite()).thenReturn(false);
		assertFalse(website.isSyncEnabled());

		when(config.syncWithWebsite()).thenReturn(true);
		when(config.pluginApiKey()).thenReturn("");
		assertFalse(website.isSyncEnabled());
	}

	@Test
	public void backoffDoublesUpToTheCap()
	{
		Backoff backoff = new Backoff(30_000, 600_000);
		assertEquals(30_000, backoff.fail(0));
		assertEquals(60_000, backoff.fail(0));
		assertEquals(120_000, backoff.fail(0));
		assertEquals(240_000, backoff.fail(0));
		assertEquals(480_000, backoff.fail(0));
		assertEquals(600_000, backoff.fail(0));
		assertEquals(600_000, backoff.fail(0));
		backoff.reset();
		assertFalse(backoff.isWaiting(0));
		assertEquals(30_000, backoff.fail(0));
	}
}
