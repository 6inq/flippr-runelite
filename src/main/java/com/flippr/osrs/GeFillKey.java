package com.flippr.osrs;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.util.HotkeyListener;

// The optional fill key (geFillKeybind, off until the player picks one): with
// the GE price or quantity prompt open for the suggested item, pressing it
// puts Flippr's value in the prompt - the input line's text and the input
// VarClientStr, what typing it does. It never presses Enter: the player
// submits the value themselves. Enter and modifier-only keys are refused.
// The key is consumed only when it filled the prompt, so otherwise it types
// as normal.
@Slf4j
@Singleton
public class GeFillKey extends HotkeyListener
{
	private final FlipprConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private GeChatInput chatInput;

	@Inject
	private GuidanceGate guidanceGate;

	private boolean filled;

	@Inject
	GeFillKey(FlipprConfig config)
	{
		super(config::geFillKeybind);
		this.config = config;
	}

	// A key the player may use: set, not Enter (the submit key) and not a
	// modifier on its own (stored as no key code plus the modifier, or as
	// the modifier's own key code).
	static boolean usable(Keybind keybind)
	{
		return keybind != null && !Keybind.NOT_SET.equals(keybind)
			&& keybind.getKeyCode() != KeyEvent.VK_UNDEFINED
			&& keybind.getKeyCode() != KeyEvent.VK_ENTER
			&& Keybind.getModifierForKeyCode(keybind.getKeyCode()) == null;
	}

	// Consume the press only when it filled the prompt and isn't a modifier.
	static boolean shouldConsume(boolean filled, int keyCode)
	{
		return filled && Keybind.getModifierForKeyCode(keyCode) == null;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		Keybind keybind = config.geFillKeybind();
		if (!usable(keybind) || !keybind.matches(e))
		{
			return;
		}
		hotkeyPressed();
		if (shouldConsume(filled, e.getKeyCode()))
		{
			e.consume();
		}
		filled = false;
	}

	@Override
	public void hotkeyPressed()
	{
		if (guidanceGate.blocked())
		{
			return;
		}
		Long value = chatInput.suggestedValue(decisionEngine.getCurrentSuggestions());
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (value == null || input == null)
		{
			return;
		}
		filled = true;
		input.setText(value + "*");
		clientThread.invoke(() -> client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value)));
		log.info("Fill key: {} {} (item {}) - the player presses Enter",
			chatInput.openBox().name().toLowerCase(), value, chatInput.setupItemId());
	}
}
