package com.flippr.osrs;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;

// "Fill on keypress": with the GE price or quantity prompt open for the
// suggested item, the fill key types the suggestion's value into the prompt
// (GeChatInput.type - the input text and its VarClientStr, exactly what
// typing it does). On by default (quickFillEnabled, Shift); it only ever acts on
// the player's own keypress, and it never presses Enter: the player submits
// the value themselves. Enter is refused as the fill key so a fill can never
// coincide with a submit. The clickable chatbox line (ChatboxFillLine) does
// the same on a click.
@Slf4j
@Singleton
public class QuickFillHandler
{
	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private GeChatInput chatInput;

	@Inject
	private FlipprConfig config;

	@Inject
	private GuidanceGate guidanceGate;

	private final KeyListener keyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (!config.quickFillEnabled() || !triggers(config.quickSetKeybind(), e))
			{
				return;
			}
			clientThread.invokeLater(QuickFillHandler.this::fillSuggestedValue);
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	public void register()
	{
		keyManager.registerKeyListener(keyListener);
	}

	public void unregister()
	{
		keyManager.unregisterKeyListener(keyListener);
	}

	// Is this key press the fill key? Never Enter (the real submit key), never
	// an unset keybind.
	static boolean triggers(Keybind keybind, KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_ENTER || keybind == null || Keybind.NOT_SET.equals(keybind))
		{
			return false;
		}
		return keybind.matches(e);
	}

	// Client thread. One log line per key press (the player's own action,
	// so never per tick).
	private void fillSuggestedValue()
	{
		// Both watch modes (and the engine off) mean "touch nothing".
		if (guidanceGate.blocked())
		{
			log.info("Quick-fill skipped: {}", GuidanceGate.bannerText(guidanceGate.current()));
			return;
		}
		DecisionEngine.Suggestion[] suggestions = decisionEngine.getCurrentSuggestions();
		Long value = chatInput.suggestedValue(suggestions);
		if (value == null)
		{
			log.info("Quick-fill skipped: {}", chatInput.skipReason(suggestions));
			return;
		}
		GeChatInput.Box box = chatInput.openBox();
		if (chatInput.type(value))
		{
			log.info("Quick-fill typed {} {} (item {}) - the player presses Enter", box.name().toLowerCase(), value, chatInput.setupItemId());
		}
		else
		{
			log.info("Quick-fill skipped: chatbox input line not found");
		}
	}

	// The suggestion to fill from: for this item, on the same side (buy/sell)
	// as the open box, with a value for the box (quantity or price). The one
	// the overlay highlights wins when it qualifies; otherwise the first
	// qualifying slot. Null = nothing to fill.
	static DecisionEngine.Suggestion findSuggestion(DecisionEngine.Suggestion[] suggestions, int itemId, boolean sellBox,
		boolean quantityBox)
	{
		DecisionEngine.Suggestion highlighted = SuggestionOverlay.pickOneToHighlight(suggestions);
		if (canFill(highlighted, itemId, sellBox, quantityBox))
		{
			return highlighted;
		}
		for (DecisionEngine.Suggestion suggestion : suggestions)
		{
			if (canFill(suggestion, itemId, sellBox, quantityBox))
			{
				return suggestion;
			}
		}
		return null;
	}

	private static boolean canFill(DecisionEngine.Suggestion s, int itemId, boolean sellBox, boolean quantityBox)
	{
		return s != null && itemId > 0 && s.itemId == itemId && s.sellSide == sellBox
			&& (quantityBox ? s.suggestedQuantity != null : s.suggestedPrice != null);
	}
}
