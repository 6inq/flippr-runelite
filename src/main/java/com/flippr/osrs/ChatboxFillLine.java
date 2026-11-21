package com.flippr.osrs;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

// While the GE price or quantity prompt is open for the suggested item, a
// line under it in the chatbox: "Flippr price: 1,234 gp" / "Flippr
// quantity: 500". Clicking it (the player's own click on this line) types the
// value into the prompt - GeChatInput.type, the same as typing it - and the
// player presses Enter to submit; nothing is ever submitted for them.
// geFillLine (default on) turns it off; watch modes / the engine off
// (GuidanceGate) hide it.
//
// Shown when the chatbox switches to a number prompt (FlipprPlugin's
// VarClientIntChanged on MESLAYERMODE) and kept current every tick; the
// chatbox rebuilds its own widgets when the prompt closes, which removes the
// line with them. Client thread only.
@Slf4j
@Singleton
public class ChatboxFillLine
{
	private static final int TEXT_COLOR = 0x0B3D91;
	private static final int HOVER_COLOR = 0x7A1F00;
	private static final int TITLE_SHIFT = 7;

	@Inject
	private Client client;

	@Inject
	private GeChatInput chatInput;

	@Inject
	private DecisionEngine decisionEngine;

	@Inject
	private FlipprConfig config;

	@Inject
	private GuidanceGate guidanceGate;

	private final ChangeLog changeLog = new ChangeLog();

	private Widget line;
	private Widget shiftedTitle;
	private int shiftedTitleY;
	private long lineValue;

	private boolean enabled()
	{
		return config.geFillLine() && !guidanceGate.blocked();
	}

	// The chatbox prompt changed, or a tick passed: show, update or hide.
	public void refresh()
	{
		Long value = enabled() ? chatInput.suggestedValue(decisionEngine.getCurrentSuggestions()) : null;
		Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (value == null || parent == null)
		{
			hide();
			return;
		}
		GeChatInput.Box box = chatInput.openBox();
		if (changeLog.changed(box + ":" + value))
		{
			log.info("Chatbox fill line: {} {}", box.name().toLowerCase(), value);
		}
		if (line == null || line.getParent() != parent || isGone(parent))
		{
			// Under the prompt's title, which moves down a little to make
			// room for it.
			line = parent.createChild(-1, WidgetType.TEXT);
			line.setFontId(FontID.VERDANA_11_BOLD);
			line.setTextColor(TEXT_COLOR);
			line.setXTextAlignment(WidgetTextAlignment.LEFT);
			line.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			line.setOriginalX(10);
			line.setOriginalY(40);
			line.setWidthMode(WidgetSizeMode.MINUS);
			line.setOriginalWidth(0);
			line.setOriginalHeight(20);
			Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
			// Once per prompt: a line re-made while the same title is up
			// (the suggestion blinked out and back) leaves it where it is.
			if (title != null && !(title == shiftedTitle && title.getOriginalY() == shiftedTitleY))
			{
				title.setOriginalY(title.getOriginalY() + TITLE_SHIFT);
				title.revalidate();
				shiftedTitle = title;
				shiftedTitleY = title.getOriginalY();
			}
			line.setHasListener(true);
			line.setOnMouseRepeatListener((JavaScriptCallback) ev -> line.setTextColor(HOVER_COLOR));
			line.setOnMouseLeaveListener((JavaScriptCallback) ev -> line.setTextColor(TEXT_COLOR));
			line.setOnOpListener((JavaScriptCallback) ev -> chatInput.type(lineValue));
		}
		lineValue = value;
		line.setText(GeChatInput.lineText(box, value, config.quickSetKeybind(), config.quickFillEnabled()));
		line.setAction(0, box == GeChatInput.Box.PRICE ? "Set price" : "Set quantity");
		line.setHidden(false);
		line.revalidate();
	}

	// Our line is no longer among the chatbox's children (it rebuilt).
	private boolean isGone(Widget parent)
	{
		Widget[] children = parent.getDynamicChildren();
		if (children == null)
		{
			return true;
		}
		for (Widget child : children)
		{
			if (child == line)
			{
				return false;
			}
		}
		return true;
	}

	private void hide()
	{
		changeLog.reset();
		if (line != null)
		{
			line.setHidden(true);
			line = null;
		}
	}
}
