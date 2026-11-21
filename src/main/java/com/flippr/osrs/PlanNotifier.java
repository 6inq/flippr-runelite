package com.flippr.osrs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;

// Sends the Plan notifications (PlanNotifications) through RuneLite's own
// Notifier - tray popup / sound / flash as the player set them up in
// RuneLite - once per plan refresh, on the client thread (game tick). The
// sent keys are kept in the plugin's config (hidden, size-capped) so a
// restart doesn't repeat anything; the toggles are FlipprConfig's
// "Notifications" section.
@Slf4j
@Singleton
public class PlanNotifier
{
	static final String SENT_KEYS_KEY = "notifySentKeys";
	private static final Type KEY_LIST = new TypeToken<List<String>>()
	{
	}.getType();

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private WebsiteConnection website;

	@Inject
	private FlipprConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Notifier notifier;

	@Inject
	private Gson gson;

	LongSupplier clock = System::currentTimeMillis;

	private PlanNotifications notifications;
	private long checkedFetchedAt;
	private String checkedCharacter;

	static PlanNotifications.Settings settingsOf(FlipprConfig config, boolean sellOnly)
	{
		return new PlanNotifications.Settings(
			config.notificationsEnabled(),
			config.notifyPriceAlerts(),
			config.notifyOfferCompleted(),
			config.notifyPartialFills(),
			config.notifyAutoPrice(),
			config.notifyNewFlip(),
			sellOnly
		);
	}

	public void onGameTick()
	{
		ServerSuggestions.Data data = serverSuggestions.getData();
		if (data == null || !data.hasPlan() || data.fetchedAt == checkedFetchedAt)
		{
			return;
		}
		checkedFetchedAt = data.fetchedAt;
		PlanNotifications n = notifications();
		String character = website.getCharacter();
		if (!Objects.equals(character, checkedCharacter))
		{
			// Another character's plan: what's true now is not news.
			checkedCharacter = character;
			n.unseed();
		}
		n.check(data.planPositions, data.planFreeSlots, data.planInfo, settingsOf(config, serverSuggestions.isSellOnly()), clock.getAsLong(),
			e -> notifier.notify(e.message()));
	}

	private PlanNotifications notifications()
	{
		if (notifications == null)
		{
			List<String> saved = null;
			try
			{
				String json = configManager.getConfiguration("flippr", SENT_KEYS_KEY);
				saved = json == null || json.isEmpty() ? null : gson.fromJson(json, KEY_LIST);
			}
			catch (RuntimeException e)
			{
				log.debug("PlanNotifier: unreadable sent keys, starting fresh");
			}
			notifications = new PlanNotifications(saved, keys -> configManager.setConfiguration("flippr", SENT_KEYS_KEY, gson.toJson(keys)));
		}
		return notifications;
	}
}
