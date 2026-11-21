package com.flippr.osrs;

import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

// Turning sell-only mode on / off from the sidebar (Plan tab header and the
// Plan setup card) or RuneLite's config ("Sell-only mode"), and keeping the
// two in step. The source of truth is ServerSuggestions.isSellOnly(): the
// plan's server-side setting (PATCH /plan/settings {sellOnly}) when there is
// a plan, the config item without one. Depends on ServerSuggestions, never
// the other way round (no startup dependency loop).
@Singleton
public class SellOnlyControl
{
	static final String CONFIG_KEY = "sellOnlyMode";

	@Inject
	private ServerSuggestions serverSuggestions;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FlipprConfig config;

	public boolean isOn()
	{
		return serverSuggestions.isSellOnly();
	}

	// The player flipped a toggle. done runs on an OkHttp thread (or at once).
	public void set(boolean on, Consumer<ServerSuggestions.StopResult> done)
	{
		serverSuggestions.setSellOnly(on, done);
		if (config.sellOnlyMode() != on)
		{
			configManager.setConfiguration("flippr", CONFIG_KEY, on);
		}
	}

	// The config item changed (RuneLite's settings, or our own sync below):
	// send it to the plan when it differs from what's in effect.
	public void onConfigChanged()
	{
		boolean wanted = config.sellOnlyMode();
		if (shouldSend(serverSuggestions.planSellOnly(), isOn(), wanted))
		{
			serverSuggestions.setSellOnly(wanted, r ->
			{
			});
		}
	}

	// Every game tick: the plan's value (set on the website or in the Alt1
	// app) wins - copy it into the config so RuneLite's settings show it.
	public void onGameTick()
	{
		Boolean plan = serverSuggestions.planSellOnly();
		if (shouldMirror(plan, isOn(), config.sellOnlyMode()))
		{
			configManager.setConfiguration("flippr", CONFIG_KEY, plan);
		}
	}

	// A config change goes to the server only with a plan and when it
	// differs from what's in effect.
	static boolean shouldSend(Boolean planValue, boolean inEffect, boolean wanted)
	{
		return planValue != null && wanted != inEffect;
	}

	// The plan's value is copied to the config once nothing is pending (the
	// value in effect is the plan's) and the config shows something else.
	static boolean shouldMirror(Boolean planValue, boolean inEffect, boolean configValue)
	{
		return planValue != null && inEffect == planValue && configValue != planValue;
	}
}
