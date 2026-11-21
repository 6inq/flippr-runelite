package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Dump detection from public price data only. Truly live dump alerts need
// aggregated, near-real-time transaction data from many players - a data
// source a personal plugin has no access to.
//
// All this plugin has is PriceFeed's already-polled OSRS Wiki snapshot (the
// same public, community-sourced, ~10s-latency feed used everywhere else in
// this project - see PriceFeed's own header comment). So this detects a
// much coarser, laggier signal instead: any item whose live instant-sell
// price has fallen by at least dumpThresholdPercent within the last
// dumpWindowMinutes, sampled periodically from that same feed. Real and
// useful, but built from public crowd-sourced data at plugin scale.
@Slf4j
@Singleton
public class DumpDetector
{
	// One sample of the whole catalog's instant-sell prices at a point in
	// time - kept only long enough to compare against the dump window, not
	// accumulated forever.
	private static class Sample
	{
		final long timestamp;
		final Map<Integer, Long> instantSellByItemId;

		Sample(long timestamp, Map<Integer, Long> instantSellByItemId)
		{
			this.timestamp = timestamp;
			this.instantSellByItemId = instantSellByItemId;
		}
	}

	// Several samples across the dump window, not just start/end - throttled
	// well below the multi-minute window itself.
	private static final long SAMPLE_INTERVAL_MS = 60_000;

	@Inject
	private PriceFeed priceFeed;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private FlipprConfig config;

	@Inject
	private Gson gson;

	// RuneLite's shared client (Plugin Hub requirement); see client().
	@Inject
	private OkHttpClient okHttpClient;

	private volatile OkHttpClient httpClient;

	private static final MediaType JSON = MediaType.parse("application/json");

	private final Deque<Sample> samples = new ArrayDeque<>();
	private volatile long lastSampleTime;
	// Per-item cooldown so one ongoing dump doesn't re-alert every sample
	// while the price stays down - only re-arms once the price has actually
	// recovered back above the threshold. Doubles as the live "is this item
	// currently dumping" source of truth DecisionEngine reads for Dump Mode
	// (see its class comment) - the same real signal driving the chat/tray
	// alert is what drives the on-screen DUMP_BUY suggestion, so they can
	// never disagree about which items are actually dumping right now.
	private final Map<Integer, Boolean> alertedItemIds = new HashMap<>();

	public boolean isDumping(int itemId)
	{
		return alertedItemIds.getOrDefault(itemId, false);
	}

	// Every item currently flagged as dumping, for DecisionEngine to scan
	// when looking for a free slot to catch one in - alertedItemIds only
	// ever holds true values (removed entirely on recovery, never flipped
	// to false), so its key set already is the "currently dumping" set.
	public Set<Integer> getActiveDumpItemIds()
	{
		return new HashSet<>(alertedItemIds.keySet());
	}

	public void onGameTick()
	{
		// Deliberately built independent of engineEnabled elsewhere (this
		// alert isn't a "suggestion," it's meant to fire even while the
		// engine's paused) - but both watch modes are a stronger,
		// different guarantee ("nothing talks to you or acts on your
		// behalf at all"), so they override this too. Confirmed
		// explicitly: Watch Mode (Dump) is NOT "keep dump alerts active" -
		// it only records in the background. Skipping the
		// scan itself here (not just the alert) isn't wasted work either -
		// DecisionEngine's own DUMP_BUY logic already stops consuming this
		// class's state entirely under either watch mode.
		if (config.dumpAlertTier() == DumpAlertTier.OFF || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastSampleTime < SAMPLE_INTERVAL_MS)
		{
			return;
		}
		lastSampleTime = now;

		Map<Integer, Long> currentInstantSell = new HashMap<>();
		for (Map.Entry<String, PriceFeed.PricePoint> entry : priceFeed.getSnapshot().entrySet())
		{
			if (entry.getValue().low == null)
			{
				continue;
			}
			try
			{
				currentInstantSell.put(Integer.parseInt(entry.getKey()), entry.getValue().low);
			}
			catch (NumberFormatException e)
			{
				// Not expected from this API, but not worth crashing the
				// whole sample over one unparseable key.
				log.debug("DumpDetector: non-numeric item id in price feed snapshot: {}", entry.getKey());
			}
		}

		samples.addLast(new Sample(now, currentInstantSell));

		long windowMs = config.dumpWindowMinutes() * 60_000L;
		while (samples.size() > 1 && now - samples.peekFirst().timestamp > windowMs)
		{
			samples.removeFirst();
		}

		if (samples.size() < 2)
		{
			// Not enough history yet to compare against.
			return;
		}

		checkForDumps(samples.peekFirst(), currentInstantSell);
	}

	private void checkForDumps(Sample oldest, Map<Integer, Long> current)
	{
		double thresholdRatio = config.riskLevel().scaleThreshold(config.dumpThresholdPercent()) / 100.0;

		for (Map.Entry<Integer, Long> entry : current.entrySet())
		{
			int itemId = entry.getKey();
			long currentPrice = entry.getValue();
			Long oldPrice = oldest.instantSellByItemId.get(itemId);
			if (oldPrice == null || oldPrice <= 0)
			{
				continue;
			}

			double drop = (oldPrice - currentPrice) / (double) oldPrice;
			boolean isDumping = drop >= thresholdRatio;
			boolean alreadyAlerted = alertedItemIds.getOrDefault(itemId, false);

			if (isDumping && !alreadyAlerted)
			{
				if (!meetsAlertTier(itemId, currentPrice))
				{
					// A real price crash, just not a big enough real
					// opportunity to bother flagging - Dump alerts' own
					// size tier (see DumpAlertTier), independent of the
					// percentage-drop threshold above: a 50% drop on a
					// 200gp item is still nothing.
					continue;
				}
				alertedItemIds.put(itemId, true);
				announce(itemId, oldPrice, currentPrice, drop);
			}
			else if (!isDumping && alreadyAlerted)
			{
				alertedItemIds.remove(itemId);
			}
		}
	}

	// Dump alerts' real size-tier gate: the MAXIMUM real capturable profit
	// if this exact dump were bought at its full real GE buy limit and
	// resold at today's real instant-buy price, minus real GE tax - an
	// uncapped opportunity-size estimate, deliberately NOT constrained by
	// the player's own current capital (that's a separate, later concern -
	// see DecisionEngine.meetsMinPredictedProfit(), which IS
	// capital-constrained, for the suggestion-level gate). OFF already
	// short-circuits before this is ever called (see onGameTick()).
	private boolean meetsAlertTier(int itemId, long crashedBuyPrice)
	{
		long threshold = config.dumpAlertTier().getThreshold();
		if (threshold <= 0)
		{
			return true;
		}

		Long sellAt = priceFeed.getInstantBuyPrice(itemId);
		Integer buyLimit = priceFeed.getBuyLimit(itemId);
		if (sellAt == null || buyLimit == null || sellAt <= crashedBuyPrice)
		{
			// No real, currently-favorable spread (or no known buy limit)
			// to size an opportunity estimate from - can't confirm it
			// clears the bar, so don't claim it does.
			return false;
		}

		String itemName = itemManager.getItemComposition(itemId).getName();
		long taxPerUnit = FlipTracker.computeTaxPerUnit(itemName, sellAt);
		long maxProfit = ((sellAt - taxPerUnit) - crashedBuyPrice) * buyLimit;
		return maxProfit >= threshold;
	}

	private void announce(int itemId, long oldPrice, long currentPrice, double drop)
	{
		String itemName = itemManager.getItemComposition(itemId).getName();
		String text = String.format(
			"%s down %.0f%% in %dmin (%s -> %s gp)",
			itemName, drop * 100, config.dumpWindowMinutes(),
			QuantityFormatter.formatNumber(oldPrice), QuantityFormatter.formatNumber(currentPrice)
		);

		log.info("DumpDetector: {}", text);

		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("Flippr dump alert: ")
			.append(ChatColorType.NORMAL)
			.append(text)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());

		if (config.dumpNotificationEnabled())
		{
			// RuneLite's own tray popup + notification sound - respects the
			// player's RuneLite-wide Notifications settings (sound on/off,
			// tray on/off) rather than this plugin reimplementing either;
			// this config item is only a per-dump-alert enable/disable gate
			// on calling it at all, not a volume/sound-file picker.
			notifier.notify("Flippr dump alert: " + text);
		}

		postWebhook(text);
	}

	// Fire-and-forget, off the game thread - a webhook POST failing (bad
	// URL, Discord rate limit, network blip) should never affect the actual
	// detection loop.
	// Pooled client: OkHttp retries a stale pooled connection itself, so the old fresh-client-per-call workaround is gone.
	private void postWebhook(String text)
	{
		String url = config.dumpWebhookUrl();
		if (url == null || url.isBlank())
		{
			return;
		}

		String json = gson.toJson(new WebhookPayload("Flippr dump alert: " + text));
		Request request;
		try
		{
			request = new Request.Builder()
				.url(url)
				// byte[] body: keeps Content-Type exactly "application/json".
				.post(RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)))
				.build();
		}
		catch (IllegalArgumentException e)
		{
			log.warn("DumpDetector: invalid webhook URL, skipping - {}", e.toString());
			return;
		}

		client().newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("DumpDetector: webhook POST failed - {}", e.toString());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				// Body discarded, as before - only closed.
				response.close();
			}
		});
	}

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

	// Discord's own webhook JSON shape ({"content": "..."}) - the simplest,
	// most common target for a "webhook URL" field, matching what Flippr's
	// website already posts to Discord elsewhere.
	private static class WebhookPayload
	{
		final String content;

		WebhookPayload(String content)
		{
			this.content = content;
		}
	}
}
