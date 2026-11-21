package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

// Requirement: the panel says where this character stands with the
// website's in-game name check - verified,
// grace (with the date), a different character logged in than the Premium
// seat, or not verified yet - and a 403 premium_unverified only explains
// itself: free features carry on.
public class PremiumVerificationTest
{
	private static final Gson GSON = new Gson();

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

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		when(config.websiteBaseUrl()).thenReturn("http://localhost:3030");
		when(config.pluginApiKey()).thenReturn("abc");
		when(config.syncWithWebsite()).thenReturn(true);
		website.setCharacter("6ing");
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private static WebsiteConnection.Status status(String json)
	{
		return GSON.fromJson(json, WebsiteConnection.Status.class);
	}

	@Test
	public void verifiedCharacter()
	{
		PremiumVerification.Line line = PremiumVerification.statusLine(
			status("{\"characterName\":\"6ing\",\"premium\":true,\"premiumState\":\"verified\",\"verified\":true,\"locked\":true}"), "6ing");
		assertEquals("Premium verified for 6ing", line.text);
		assertEquals(PremiumVerification.Tone.GOOD, line.tone);
	}

	@Test
	public void graceShowsTheDate()
	{
		PremiumVerification.Line line = PremiumVerification.statusLine(
			status("{\"characterName\":\"6ing\",\"premium\":true,\"premiumState\":\"grace\",\"graceUntil\":\"2026-11-27T20:37:01.437Z\"}"), "6ing");
		assertEquals("Verify by logging in - grace until 27 Nov 2026", line.text);
		assertEquals(PremiumVerification.Tone.WARN, line.tone);
	}

	@Test
	public void mismatchNamesBothCharacters()
	{
		// Legacy one-character key used while logged in to another character.
		PremiumVerification.Line line = PremiumVerification.statusLine(
			status("{\"characterName\":\"6ing\",\"premium\":false,\"premiumState\":\"mismatch\",\"lastObservedName\":\"Zezima\"}"), "Zezima");
		assertEquals("Premium is for '6ing' - you're logged in as 'Zezima'", line.text);
		assertEquals(PremiumVerification.Tone.BAD, line.tone);
	}

	@Test
	public void loggedInToAnUnseatedCharacterPointsAtTheSeatedOne()
	{
		WebsiteConnection.Status s = status("{\"characterName\":\"Alt\",\"premium\":false,\"premiumState\":\"no_seat\",\"characters\":["
			+ "{\"game\":\"OSRS\",\"name\":\"6ing\",\"seatAssigned\":true,\"premium\":true},"
			+ "{\"game\":\"OSRS\",\"name\":\"Alt\",\"seatAssigned\":false},"
			+ "{\"game\":\"RS3\",\"name\":\"Rs3Main\",\"seatAssigned\":true}]}");
		assertEquals("Premium is for '6ing' - you're logged in as 'Alt'", PremiumVerification.statusLine(s, "Alt").text);
	}

	@Test
	public void nothingToSayWithoutAnySeat()
	{
		assertNull(PremiumVerification.statusLine(status("{\"characterName\":\"6ing\",\"premiumState\":\"no_seat\",\"characters\":[]}"), "6ing"));
		assertNull(PremiumVerification.statusLine(null, "6ing"));
		// A status from before the name check existed.
		assertNull(PremiumVerification.statusLine(status("{\"characterName\":\"6ing\",\"premium\":true}"), "6ing"));
	}

	@Test
	public void unverifiedAsksToLogIn()
	{
		assertEquals("Premium for '6ing' isn't verified yet - log in to it with the plugin",
			PremiumVerification.statusLine(status("{\"characterName\":\"6ing\",\"premiumState\":\"unverified\"}"), "6ing").text);
	}

	@Test
	public void formatDate()
	{
		assertEquals("1 Dec 2026", PremiumVerification.formatDate("2026-12-01T00:00:00Z"));
		assertNull(PremiumVerification.formatDate("not a date"));
		assertNull(PremiumVerification.formatDate(null));
	}

	@Test
	public void forbiddenPremiumUnverifiedIsAPlainFailureWithAMessage()
	{
		String body = "{\"error\":\"premium_unverified\",\"premiumState\":\"mismatch\",\"expected\":\"6ing\",\"observed\":\"Zezima\","
			+ "\"message\":\"Flippr read 'Zezima' in game, but this Premium seat is for '6ing'.\",\"gated\":true}";
		WebsiteConnection.ApiResponse r = website.handle(403, null, body);
		assertEquals(WebsiteConnection.Outcome.FAILED, r.outcome);
		assertEquals("Flippr read 'Zezima' in game, but this Premium seat is for '6ing'.", website.getPremiumBlockedMessage());
		// Syncing (free features) keeps going.
		assertFalse(website.isAuthRejected());
		assertEquals(true, website.isSyncEnabled());
		// A new character starts clean.
		website.setCharacter("Other");
		assertNull(website.getPremiumBlockedMessage());
	}

	@Test
	public void otherForbiddenBodiesLeaveNoMessage()
	{
		website.handle(403, null, "{\"error\":\"This key belongs to a RS3 character.\"}");
		assertNull(website.getPremiumBlockedMessage());
	}

	@Test
	public void premiumBlockedOnAReplyIsNotedAndCleared()
	{
		WebsiteConnection.PremiumBlocked blocked = GSON.fromJson(
			"{\"error\":\"premium_unverified\",\"expected\":\"6ing\",\"observed\":\"Zezima\"}", WebsiteConnection.PremiumBlocked.class);
		website.notePremiumBlocked(blocked);
		assertEquals("Flippr read 'Zezima' in game, but this Premium seat is for '6ing'.", website.getPremiumBlockedMessage());
		website.notePremiumBlocked(null);
		assertNull(website.getPremiumBlockedMessage());
	}
}
