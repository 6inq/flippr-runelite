package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.client.config.ConfigManager;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Opt-in end-to-end check against a running Flippr site with a REAL
// OkHttpClient - skipped unless FLIPPR_E2E_URL is set, so normal builds
// never touch the network:
//   FLIPPR_E2E_URL=http://localhost:3030 ./gradlew test --tests '*WebsiteE2ETest'
// With FLIPPR_E2E_KEY (a plugin key from pairing) it also reads status,
// plan and flips, and POSTs EMPTY offers/trades arrays - both routes
// authenticate and validate them, but an empty list writes nothing.
// Every call carries X-Flippr-Character like the plugin does
// (FLIPPR_E2E_CHARACTER, default "6ingtest"); with an account key an OSRS
// name the account doesn't have yet is created on first use.
public class WebsiteE2ETest
{
	private static final String URL = System.getenv("FLIPPR_E2E_URL");
	private static final String KEY = System.getenv("FLIPPR_E2E_KEY");
	private static final String CHARACTER = System.getenv("FLIPPR_E2E_CHARACTER") == null
		? "6ingtest" : System.getenv("FLIPPR_E2E_CHARACTER");

	@Mock
	private FlipprConfig config;
	@Mock
	private ConfigManager configManager;
	@Spy
	private OkHttpClient okHttpClient = new OkHttpClient();
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private WebsiteConnection website;

	private AutoCloseable mocks;

	@Before
	public void setUp()
	{
		assumeTrue("set FLIPPR_E2E_URL to run", URL != null && !URL.isEmpty());
		mocks = MockitoAnnotations.openMocks(this);
		when(config.websiteBaseUrl()).thenReturn(URL);
		when(config.syncWithWebsite()).thenReturn(true);
		website.setCharacter(CHARACTER);
	}

	@After
	public void tearDown() throws Exception
	{
		if (mocks != null)
		{
			mocks.close();
		}
	}

	private WebsiteConnection.ApiResponse get(String path) throws Exception
	{
		CompletableFuture<WebsiteConnection.ApiResponse> result = new CompletableFuture<>();
		website.getAsync(path, result::complete);
		return result.get(15, TimeUnit.SECONDS);
	}

	private WebsiteConnection.ApiResponse post(String path, String json) throws Exception
	{
		CompletableFuture<WebsiteConnection.ApiResponse> result = new CompletableFuture<>();
		website.postJsonAsync(path, json, result::complete);
		return result.get(15, TimeUnit.SECONDS);
	}

	// A revoked/unknown key is answered 401 and stops syncing.
	@Test
	public void unknownKeyIsRejected() throws Exception
	{
		when(config.pluginApiKey()).thenReturn("not-a-real-key");
		assertEquals(WebsiteConnection.Outcome.UNAUTHORIZED, get(WebsiteConnection.STATUS_PATH).outcome);
		assertTrue(website.isAuthRejected());
		assertFalse(website.isSyncEnabled());
	}

	@Test
	public void pairedKeyReadsAndPostsWithoutWriting() throws Exception
	{
		assumeTrue("set FLIPPR_E2E_KEY to a paired plugin key", KEY != null && !KEY.isEmpty());
		when(config.pluginApiKey()).thenReturn(KEY);

		WebsiteConnection.ApiResponse status = get(WebsiteConnection.STATUS_PATH);
		assertEquals(status.body, WebsiteConnection.Outcome.OK, status.outcome);
		WebsiteConnection.Status parsed = gson.fromJson(status.body, WebsiteConnection.Status.class);
		assertNotNull(status.body, parsed.characterName);
		if ("account".equals(parsed.scope))
		{
			assertTrue(status.body, parsed.characterName.equalsIgnoreCase(CHARACTER));
		}
		assertNotNull("status must include seat", parsed.seat);
		assertNotNull("status must include premiumGames", parsed.premiumGames);

		WebsiteConnection.ApiResponse plan = get(ServerSuggestions.PLAN_PATH);
		assertEquals(plan.body, WebsiteConnection.Outcome.OK, plan.outcome);
		assertNotNull(gson.fromJson(plan.body, ServerSuggestions.PlanResponse.class));

		WebsiteConnection.ApiResponse flips = get(ServerSuggestions.FLIPS_PATH);
		assertEquals(flips.body, WebsiteConnection.Outcome.OK, flips.outcome);
		assertNotNull(gson.fromJson(flips.body, ServerSuggestions.FlipsResponse.class).flips);

		WebsiteConnection.ApiResponse offers = post(OfferSync.PATH, "[]");
		assertEquals(offers.body, WebsiteConnection.Outcome.OK, offers.outcome);
		assertEquals(0, gson.fromJson(offers.body, JsonObject.class).get("received").getAsInt());

		WebsiteConnection.ApiResponse trades = post(TradeUploader.PATH, "[]");
		assertEquals(trades.body, WebsiteConnection.Outcome.OK, trades.outcome);
		assertEquals(0, gson.fromJson(trades.body, JsonObject.class).get("inserted").getAsInt());
	}
}
