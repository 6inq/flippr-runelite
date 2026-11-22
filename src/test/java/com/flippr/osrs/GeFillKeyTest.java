package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Keybind;
import org.junit.Test;

// Requirement: the optional fill key. Off until the player picks one; Enter
// (the submit key) and modifier-only keys are never usable; the key press is
// consumed only when it actually filled the prompt; the chatbox line names
// the key only when one is usable.
public class GeFillKeyTest
{
	private static final Keybind F = new Keybind(KeyEvent.VK_F, 0);

	@Test
	public void offByDefault()
	{
		assertFalse("unset", GeFillKey.usable(Keybind.NOT_SET));
		assertFalse("null", GeFillKey.usable(null));
	}

	@Test
	public void enterNeverUsable()
	{
		assertFalse(GeFillKey.usable(new Keybind(KeyEvent.VK_ENTER, 0)));
		assertFalse("with a modifier too", GeFillKey.usable(new Keybind(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)));
	}

	@Test
	public void modifierOnlyNotUsable()
	{
		assertFalse("shift", GeFillKey.usable(Keybind.SHIFT));
		assertFalse("ctrl", GeFillKey.usable(Keybind.CTRL));
		assertFalse("alt", GeFillKey.usable(Keybind.ALT));
		assertFalse("shift key code", GeFillKey.usable(new Keybind(KeyEvent.VK_SHIFT, InputEvent.SHIFT_DOWN_MASK)));
	}

	@Test
	public void plainKeysUsable()
	{
		assertTrue("F", GeFillKey.usable(F));
		assertTrue("F1", GeFillKey.usable(new Keybind(KeyEvent.VK_F1, 0)));
		assertTrue("space", GeFillKey.usable(new Keybind(KeyEvent.VK_SPACE, 0)));
	}

	@Test
	public void consumeOnlyAfterAFill()
	{
		assertTrue("filled with F", GeFillKey.shouldConsume(true, KeyEvent.VK_F));
		assertFalse("nothing filled", GeFillKey.shouldConsume(false, KeyEvent.VK_F));
		assertFalse("modifier key", GeFillKey.shouldConsume(true, KeyEvent.VK_SHIFT));
	}

	@Test
	public void lineNamesTheKeyOnlyWhenUsable()
	{
		assertEquals("Flippr price: 1,234  (press F)", GeChatInput.lineText(GeChatInput.Box.PRICE, 1_234, F));
		assertEquals("Flippr quantity: 500  (press F)", GeChatInput.lineText(GeChatInput.Box.QUANTITY, 500, F));
		assertEquals("Flippr price: 1,234", GeChatInput.lineText(GeChatInput.Box.PRICE, 1_234, Keybind.NOT_SET));
		assertEquals("Flippr price: 1,234", GeChatInput.lineText(GeChatInput.Box.PRICE, 1_234, Keybind.SHIFT));
		assertEquals("Flippr price: 1,234", GeChatInput.lineText(GeChatInput.Box.PRICE, 1_234, new Keybind(KeyEvent.VK_ENTER, 0)));
	}
}
