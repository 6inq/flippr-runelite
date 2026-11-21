package com.flippr.osrs;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

// Whether the GE guidance (highlights, the Flippr item in the GE search,
// the chatbox fill line and fill-on-keypress) can run at all. All of it
// reads DecisionEngine's suggestions, which are empty while a watch mode is
// on or the suggestion engine is off - so without this, those settings
// silence every piece of guidance with no visible sign. The Plan tab shows
// a banner with the reason and a "Turn guidance on" button; the reason is
// logged once each time it changes.
@Slf4j
@Singleton
public class GuidanceGate
{
	enum Block
	{
		NONE(null),
		WATCH_MODE("Watch mode is on"),
		WATCH_MODE_DUMP("Watch mode (Dump) is on"),
		ENGINE_OFF("Suggestion engine is off");

		final String reason;

		Block(String reason)
		{
			this.reason = reason;
		}
	}

	@Inject
	private FlipprConfig config;

	@Inject
	private ConfigManager configManager;

	private Block lastLogged;

	// Watch mode first: it is what usually leaves the engine off as well.
	static Block blockOf(boolean engineEnabled, boolean watchMode, boolean watchModeDump)
	{
		if (watchMode)
		{
			return Block.WATCH_MODE;
		}
		if (watchModeDump)
		{
			return Block.WATCH_MODE_DUMP;
		}
		return engineEnabled ? Block.NONE : Block.ENGINE_OFF;
	}

	// "GE guidance is off: Watch mode is on", or null when nothing blocks it.
	static String bannerText(Block block)
	{
		return block == null || block == Block.NONE ? null : "GE guidance is off: " + block.reason;
	}

	Block current()
	{
		return blockOf(config.engineEnabled(), config.watchModeEnabled(), config.watchModeDumpEnabled());
	}

	boolean blocked()
	{
		return current() != Block.NONE;
	}

	// Once per tick; logs only when the reason changes.
	public void onGameTick()
	{
		Block now = current();
		if (now == lastLogged)
		{
			return;
		}
		if (now == Block.NONE)
		{
			if (lastLogged != null)
			{
				log.info("GE guidance on");
			}
		}
		else
		{
			log.info("GE guidance blocked: {} (watchModeEnabled={}, watchModeDumpEnabled={}, engineEnabled={})",
				now.reason, config.watchModeEnabled(), config.watchModeDumpEnabled(), config.engineEnabled());
		}
		lastLogged = now;
	}

	// The banner's button: both watch modes off, the engine on.
	void turnGuidanceOn()
	{
		configManager.setConfiguration("flippr", "watchModeEnabled", "false");
		configManager.setConfiguration("flippr", "watchModeDumpEnabled", "false");
		configManager.setConfiguration("flippr", "engineEnabled", "true");
		log.info("GE guidance turned on from the Plan tab banner");
	}
}
