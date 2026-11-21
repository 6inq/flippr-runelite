package com.flippr.osrs;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// Local dev-mode launcher - `gradlew run` executes this, which boots the
// actual RuneLite client with FlipprPlugin already loaded. Real login to a
// real OSRS account is required to see it do anything; this can't be
// verified from a headless environment, only run and watched by hand.
public class FlipprPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlipprPlugin.class);
		RuneLite.main(args);
	}
}
