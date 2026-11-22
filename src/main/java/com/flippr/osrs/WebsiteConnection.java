package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

// Talks to the real Flippr website's plugin-pairing API: pair a code typed in
// from the website's /account page for a real, permanent API key, then use
// that key for the account status (refreshStatusAsync). pair() is
// synchronous - FlipprPanel runs it off the Swing EDT via a background
// thread, since it is a user-initiated button click, not a polling loop.
//
// Also the shared front door for the background website features
// (ServerSuggestions, OfferSync, TradeUploader): getAsync()/postJsonAsync()
// always use OkHttp enqueue, never blocking the client thread, and every
// response goes through handle() so a 401 (key revoked) or a 503
// maintenance answer pauses all of them the same way.
//
// The website resolves the character from which account this specific API
// key was paired to, so the plugin doesn't need to know or reason about the
// player's other accounts at all.
// @Singleton - see DecisionEngine's class comment for why every stateful
// tracker in this plugin gets this regardless of current injection-site
// count.
@Slf4j
@Singleton
public class WebsiteConnection
{
	static class Status
	{
		String characterName;
		String game;
		// Which real Flippr account this key resolves to, and whether that
		// account has Discord linked at all (discordUsername is null if not).
		// Any other fields the server sends are ignored.
		String email;
		boolean discordLinked;
		String discordUsername;
		// Account keys: "account" (one pairing for every character, both
		// games) or "character" (legacy key for one character).
		String scope;
		MaintenanceBody characterError;
	}

	static class Result<T>
	{
		final T value;
		final String error;

		private Result(T value, String error)
		{
			this.value = value;
			this.error = error;
		}

		static <T> Result<T> ok(T value)
		{
			return new Result<>(value, null);
		}

		static <T> Result<T> error(String error)
		{
			return new Result<>(null, error);
		}

		boolean isError()
		{
			return error != null;
		}
	}

	// How an async API call ended. MAINTENANCE/UNAUTHORIZED are also
	// recorded here (see handle()) so every caller pauses the same way.
	enum Outcome
	{
		OK,
		MAINTENANCE,
		UNAUTHORIZED,
		// 409 from an account key: the character is taken by another Flippr
		// account (or can't be resolved). Held until the character changes.
		CONFLICT,
		FAILED
	}

	static class ApiResponse
	{
		final Outcome outcome;
		// HTTP status, 0 for a network error.
		final int code;
		final String body;

		ApiResponse(Outcome outcome, int code, String body)
		{
			this.outcome = outcome;
			this.code = code;
			this.body = body;
		}
	}

	interface ApiCallback
	{
		void onResult(ApiResponse response);
	}

	private static final MediaType JSON = MediaType.parse("application/json");
	// The site's MAINTENANCE_RETRY_AFTER_SECONDS, used when the header is missing.
	static final long DEFAULT_MAINTENANCE_SECONDS = 300;

	@Inject
	private FlipprConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	// RuneLite's shared client (Plugin Hub requirement); see client().
	@Inject
	private OkHttpClient okHttpClient;

	private volatile OkHttpClient httpClient;

	private volatile Status lastStatus;
	// A 401 from any call: the key was revoked or replaced. Website calls
	// stop until the player pairs again (the stored key itself is kept).
	private volatile boolean authRejected;
	private volatile long maintenanceUntilMs;
	private volatile String maintenanceMessage;
	private volatile boolean statusRequestInFlight;
	// The logged-in character, sent as X-Flippr-Character on every call so
	// one account key works for every character (and both games). Website
	// calls wait until it's known.
	private volatile String characterName;
	private volatile String characterError;

	LongSupplier clock = System::currentTimeMillis;

	// Base URL without a trailing slash, "" when not set.
	String baseUrl()
	{
		String url = config.websiteBaseUrl();
		if (url == null)
		{
			return "";
		}
		url = url.trim();
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	// An empty URL turns every website feature off.
	public boolean isConfigured()
	{
		return !baseUrl().isEmpty();
	}

	public boolean isPaired()
	{
		String key = config.pluginApiKey();
		return key != null && !key.isEmpty();
	}

	public boolean isAuthRejected()
	{
		return authRejected;
	}

	// Suggestions, live slot sync and uploads only run when this is true.
	public boolean isSyncEnabled()
	{
		return isConfigured() && isPaired() && !authRejected && config.syncWithWebsite()
			&& characterName != null && characterError == null;
	}

	// Set from the local player's name once it's known (null at logout). A
	// new character clears any 409 held for the previous one. Returns true
	// when the character changed.
	public boolean setCharacter(String name)
	{
		String clean = name == null ? null : name.replace(' ', ' ').trim();
		if (clean != null && clean.isEmpty())
		{
			clean = null;
		}
		if (clean == null ? characterName == null : clean.equals(characterName))
		{
			return false;
		}
		if (clean != null)
		{
			characterError = null;
			lastStatus = null;
		}
		characterName = clean;
		return true;
	}

	public String getCharacter()
	{
		return characterName;
	}

	// Why this character can't sync (e.g. linked to another Flippr account).
	public String getCharacterError()
	{
		return characterError;
	}

	public boolean isInMaintenance()
	{
		return clock.getAsLong() < maintenanceUntilMs;
	}

	public long getMaintenanceRemainingMs()
	{
		return Math.max(0, maintenanceUntilMs - clock.getAsLong());
	}

	public String getMaintenanceMessage()
	{
		return maintenanceMessage;
	}

	public Status getLastStatus()
	{
		return lastStatus;
	}

	// Pooled client: OkHttp retries a stale pooled connection itself, so the old fresh-client-per-call workaround is gone.
	// Derived once from the injected client (shares its pool/dispatcher) to
	// keep the old timeouts: 5s connect, 10s for the response.
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

	// Async GET with the plugin key; the callback runs on an OkHttp thread.
	public void getAsync(String path, ApiCallback callback)
	{
		send(authorized(path).get().build(), callback);
	}

	// Async POST of a JSON body with the plugin key.
	public void postJsonAsync(String path, String json, ApiCallback callback)
	{
		// byte[] body: keeps Content-Type exactly "application/json".
		send(authorized(path).post(RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8))).build(), callback);
	}

	// Async PATCH of a JSON body with the plugin key.
	public void patchJsonAsync(String path, String json, ApiCallback callback)
	{
		send(authorized(path).patch(RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8))).build(), callback);
	}

	Request.Builder authorized(String path)
	{
		Request.Builder builder = new Request.Builder()
			.url(baseUrl() + path)
			.header("Authorization", "Bearer " + config.pluginApiKey());
		String character = characterName;
		if (character != null)
		{
			builder.header(CHARACTER_HEADER, character);
		}
		return builder;
	}

	static final String CHARACTER_HEADER = "X-Flippr-Character";
	static final String STATUS_PATH = "/api/plugin/status?game=osrs";

	private void send(Request request, ApiCallback callback)
	{
		Callback okCallback = new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Website: {} {} failed - {}", request.method(), request.url().encodedPath(), e.toString());
				callback.onResult(new ApiResponse(Outcome.FAILED, 0, null));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int code = response.code();
				String retryAfter = response.header("Retry-After");
				String body;
				try (ResponseBody responseBody = response.body())
				{
					body = responseBody == null ? "" : responseBody.string();
				}
				catch (IOException e)
				{
					callback.onResult(new ApiResponse(Outcome.FAILED, 0, null));
					return;
				}
				callback.onResult(handle(code, retryAfter, body));
			}
		};
		try
		{
			client().newCall(request).enqueue(okCallback);
		}
		catch (RuntimeException e)
		{
			// e.g. a malformed base URL in settings.
			log.debug("Website: could not send {} - {}", request.url(), e.toString());
			callback.onResult(new ApiResponse(Outcome.FAILED, 0, null));
		}
	}

	// Shared status handling: a 401 pauses everything until re-paired, a 503
	// maintenance body pauses every website call for Retry-After seconds.
	ApiResponse handle(int code, String retryAfter, String body)
	{
		if (code >= 200 && code < 300)
		{
			return new ApiResponse(Outcome.OK, code, body);
		}
		if (code == 401)
		{
			if (!authRejected)
			{
				log.warn("Website: plugin key rejected (401) - pair again to resume syncing");
			}
			authRejected = true;
			return new ApiResponse(Outcome.UNAUTHORIZED, code, body);
		}
		if (code == 409)
		{
			MaintenanceBody conflict = parseQuietly(body, MaintenanceBody.class);
			String name = characterName;
			characterError = conflict != null && "character_taken".equals(conflict.error)
				? "\"" + name + "\" is linked to a different Flippr account - sign in to that account's site to pair, or remove it there."
				: conflict != null && conflict.message != null ? conflict.message : "This character can't be used with your Flippr account.";
			log.warn("Website: character {} rejected (409 {})", name, conflict == null ? "?" : conflict.error);
			return new ApiResponse(Outcome.CONFLICT, code, body);
		}
		if (code == 503)
		{
			MaintenanceBody maintenance = parseQuietly(body, MaintenanceBody.class);
			if (maintenance != null && "maintenance".equals(maintenance.error))
			{
				long seconds = parseRetryAfterSeconds(retryAfter);
				maintenanceUntilMs = clock.getAsLong() + seconds * 1000;
				maintenanceMessage = maintenance.message;
				log.info("Website: under maintenance, pausing website calls for {}s", seconds);
				return new ApiResponse(Outcome.MAINTENANCE, code, body);
			}
		}
		return new ApiResponse(Outcome.FAILED, code, body);
	}

	static long parseRetryAfterSeconds(String header)
	{
		if (header != null)
		{
			try
			{
				long seconds = Long.parseLong(header.trim());
				if (seconds > 0)
				{
					return seconds;
				}
			}
			catch (NumberFormatException ignored)
			{
				// HTTP-date form or junk: use the default.
			}
		}
		return DEFAULT_MAINTENANCE_SECONDS;
	}

	private <T> T parseQuietly(String body, Class<T> type)
	{
		try
		{
			return body == null ? null : gson.fromJson(body, type);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// Fetches /api/plugin/status in the background (on login and after
	// pairing) so the panel shows the linked character without a click.
	public void refreshStatusAsync()
	{
		if (!isConfigured() || !isPaired() || authRejected || isInMaintenance() || statusRequestInFlight || characterName == null)
		{
			return;
		}
		statusRequestInFlight = true;
		getAsync(STATUS_PATH, response ->
		{
			statusRequestInFlight = false;
			if (response.outcome == Outcome.OK)
			{
				Status status = parseQuietly(response.body, Status.class);
				if (status != null)
				{
					lastStatus = status;
					if (status.characterError != null)
					{
						handle(409, null, gson.toJson(status.characterError));
					}
					else
					{
						// Resolved now (e.g. removed from the other account).
						characterError = null;
					}
				}
			}
		});
	}

	// Synchronous - call from a background thread, not the EDT.
	public Result<Void> pair(String code)
	{
		if (code == null || code.trim().isEmpty())
		{
			return Result.error("Enter a pairing code first.");
		}
		if (!isConfigured())
		{
			return Result.error("Set the Flippr website URL in the plugin settings first.");
		}

		String url = baseUrl() + "/api/plugin/pair";
		log.info("Website: pairing request -> {}", url);

		try
		{
			String json = gson.toJson(new PairRequest(code.trim()));
			Request request = new Request.Builder()
				.url(url)
				// byte[] body: keeps Content-Type exactly "application/json"
				// (the String overload would append "; charset=utf-8").
				.post(RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)))
				.build();

			String responseBody;
			int status;
			try (Response response = client().newCall(request).execute(); ResponseBody body = response.body())
			{
				status = response.code();
				responseBody = body.string();
			}
			if (status == 503 && handle(status, null, responseBody).outcome == Outcome.MAINTENANCE)
			{
				return Result.error("Flippr is under maintenance - try again in a few minutes.");
			}
			if (status != 200)
			{
				String message = extractError(responseBody);
				log.warn("Website: pairing failed - HTTP {} {}", status, responseBody);
				return Result.error(message != null ? message : "Pairing failed (" + status + ")");
			}

			PairResponse parsed = gson.fromJson(responseBody, PairResponse.class);
			if (parsed == null || parsed.apiKey == null)
			{
				log.warn("Website: pairing response missing apiKey: {}", responseBody);
				return Result.error("Unexpected response from the website.");
			}

			configManager.setConfiguration("flippr", "pluginApiKey", parsed.apiKey);
			authRejected = false;
			lastStatus = null;
			characterError = null;
			// Never logs the key itself - only that pairing succeeded.
			log.info("Website: paired successfully, API key stored ({} hex chars)", parsed.apiKey.length());
			return Result.ok(null);
		}
		catch (Exception e)
		{
			log.warn("Website: pairing request failed - {}", e.toString());
			return Result.error("Couldn't reach the website - check the URL in settings.");
		}
	}

	private String extractError(String body)
	{
		ErrorResponse err = parseQuietly(body, ErrorResponse.class);
		return err == null ? null : err.error;
	}

	private static class PairRequest
	{
		final String code;

		PairRequest(String code)
		{
			this.code = code;
		}
	}

	private static class PairResponse
	{
		String apiKey;
	}

	private static class ErrorResponse
	{
		String error;
	}

	private static class MaintenanceBody
	{
		String error;
		String message;
	}
}
