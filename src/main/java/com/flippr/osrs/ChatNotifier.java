package com.flippr.osrs;

import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

// Posts the current primary suggestion to the game chatbox - the same one
// SuggestionOverlay.pickOneToHighlight() picks to draw an outline around, so
// the chat line and the on-screen highlight are always in sync by
// construction. Deliberately independent of the "highlightSuggestions"
// config flag (which only ever gated the overlay drawing) - only gated by
// engineEnabled(), the master pause - so a chat line still appears even if
// the overlay itself has a rendering problem (wrong widget ID, hidden
// widget, z-order). That independence is the point: it gives an unmissable,
// un-occludable signal for isolating "DecisionEngine has real suggestions"
// from "the overlay successfully draws them."
//
// DecisionEngine.Suggestion.message is deliberately NOT reused verbatim here
// - it's written for the client log/decisions.jsonl audience (it can carry
// extra diagnostic detail), not a player-facing chat line.
// @Singleton - see DecisionEngine's class comment for why every stateful
// tracker in this plugin gets this regardless of current injection-site
// count.
@Slf4j
@Singleton
public class ChatNotifier
{
	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private FlipprConfig config;

	private String lastAnnouncedKey;

	public void onTick()
	{
		// Watch Mode (Dump) is identical silence to plain Watch Mode - in
		// either, Flippr only records in the background and never alerts.
		// See DecisionEngine.evaluate()'s own comment.
		if (!config.engineEnabled() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			lastAnnouncedKey = null;
			return;
		}

		DecisionEngine.Suggestion suggestion = SuggestionOverlay.pickOneToHighlight(decisionEngine.getCurrentSuggestions());
		if (suggestion == null)
		{
			lastAnnouncedKey = null;
			return;
		}

		if (suggestion.key.equals(lastAnnouncedKey))
		{
			return;
		}
		lastAnnouncedKey = suggestion.key;

		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("Flippr: ")
			.append(ChatColorType.NORMAL)
			.append(chatText(suggestion))
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());

		log.info("Chat: {}", chatText(suggestion));
	}

	// "1,234,567" - grouped digits regardless of the player's locale.
	static String gp(long value)
	{
		return String.format(Locale.US, "%,d", value);
	}

	static String chatText(DecisionEngine.Suggestion s)
	{
		boolean concrete = s.itemName != null && s.suggestedPrice != null && s.suggestedQuantity != null;
		switch (s.type)
		{
			case DUMP_BUY:
				if (concrete)
				{
					return "DUMP: " + s.itemName + " just crashed - buy " + gp(s.suggestedQuantity) + " at "
						+ gp(s.suggestedPrice) + " gp in slot " + s.slot + " now";
				}
				return "DUMP: " + s.itemName + " just crashed - buy in slot " + s.slot + " now";
			case SELL:
				if (concrete)
				{
					return "Sell " + gp(s.suggestedQuantity) + " x " + s.itemName + " at " + gp(s.suggestedPrice) + " gp";
				}
				return "Sell " + s.itemName;
			case COLLECT:
				if (s.key != null && s.key.startsWith(DecisionEngine.COLLECT_DONE_KEY))
				{
					return "Slot " + s.slot + ": collect " + s.itemName;
				}
				return "Slot " + s.slot + ": collect " + s.itemName + " - nearly filled";
			case ABANDON:
				return "Slot " + s.slot + ": abandon " + s.itemName + " - stuck at 0% filled";
			case HOLD:
				return "Slot " + s.slot + ": hold " + s.itemName + " - unusually low for this time, historically recovers";
			case MODIFY:
				if (s.suggestedPrice != null)
				{
					return "Slot " + s.slot + ": change the price on " + s.itemName + " to " + gp(s.suggestedPrice) + " gp";
				}
				return "Slot " + s.slot + ": nudge the price on " + s.itemName;
			case START_NEW_FLIP:
			default:
				// A concrete buy when the website suggested one
				// (ServerSuggestions); otherwise only that a slot is free.
				if (concrete)
				{
					return "Buy " + gp(s.suggestedQuantity) + " x " + s.itemName + " at " + gp(s.suggestedPrice) + " gp";
				}
				return "Slot " + s.slot + " is free - pick something to flip";
		}
	}
}
