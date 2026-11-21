package com.flippr.osrs;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

// Panel copy for the website's in-game name check: Premium is for a
// character name verified in
// game, and the plugin verifies it just by being logged in - every website
// call sends the RuneLite local player name (X-Flippr-Character). The
// status reply says per character: premiumState 'verified' | 'grace' |
// 'unverified' | 'mismatch' | 'no_seat', graceUntil, lastObservedName.
// Pure - unit tested in PremiumVerificationTest.
final class PremiumVerification
{
	enum Tone
	{
		GOOD,
		WARN,
		BAD
	}

	static final class Line
	{
		final String text;
		final Tone tone;

		Line(String text, Tone tone)
		{
			this.text = text;
			this.tone = tone;
		}
	}

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	private PremiumVerification()
	{
	}

	// The line under "Premium: ..." for this status and the logged-in
	// character, or null when there's nothing to add (e.g. no seat at all).
	static Line statusLine(WebsiteConnection.Status status, String loggedIn)
	{
		if (status == null)
		{
			return null;
		}
		String name = status.characterName != null ? status.characterName : loggedIn;
		String state = status.premiumState;
		if ("verified".equals(state))
		{
			return new Line("Premium verified for " + name, Tone.GOOD);
		}
		if ("grace".equals(state))
		{
			String until = formatDate(status.graceUntil);
			return new Line("Verify by logging in" + (until == null ? "" : " - grace until " + until), Tone.WARN);
		}
		if ("mismatch".equals(state))
		{
			String seen = status.lastObservedName != null ? status.lastObservedName : loggedIn;
			return new Line("Premium is for '" + name + "' - you're logged in as '" + seen + "'", Tone.BAD);
		}
		if ("unverified".equals(state))
		{
			return new Line("Premium for '" + name + "' isn't verified yet - log in to it with the plugin", Tone.WARN);
		}
		// No seat on this character: say which OSRS character has one.
		String seated = seatedElsewhere(status, loggedIn);
		if (seated != null)
		{
			return new Line("Premium is for '" + seated + "' - you're logged in as '" + loggedIn + "'", Tone.BAD);
		}
		return null;
	}

	private static String seatedElsewhere(WebsiteConnection.Status status, String loggedIn)
	{
		if (status.characters == null || status.allCharactersIncluded)
		{
			return null;
		}
		for (WebsiteConnection.CharacterInfo c : status.characters)
		{
			if (c != null && "OSRS".equals(c.game) && c.seatAssigned && c.name != null
				&& (loggedIn == null || !c.name.equalsIgnoreCase(loggedIn)))
			{
				return c.name;
			}
		}
		return null;
	}

	// The 403 / premiumBlocked body's message, or null when it isn't a
	// premium_unverified refusal.
	static String blockedMessage(WebsiteConnection.PremiumBlocked blocked)
	{
		if (blocked == null || !"premium_unverified".equals(blocked.error))
		{
			return null;
		}
		if (blocked.message != null && !blocked.message.isEmpty())
		{
			return blocked.message;
		}
		if (blocked.observed != null && blocked.expected != null)
		{
			return "Flippr read '" + blocked.observed + "' in game, but this Premium seat is for '" + blocked.expected + "'.";
		}
		return "Premium needs an in-game check of this character's name.";
	}

	// ISO-8601 -> "27 Nov 2026" (UTC), null when missing or unreadable.
	static String formatDate(String iso)
	{
		if (iso == null || iso.isEmpty())
		{
			return null;
		}
		try
		{
			return DATE.format(Instant.parse(iso));
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}
}
