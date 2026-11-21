package com.flippr.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

// Draws "time since this slot last actually made buy/sell progress" directly
// on top of each active GE slot - independent of DecisionEngine and the
// suggestion system entirely (SlotActivityTracker is its only input), so it
// works even with the engine paused. Purely a glance-readable stopwatch for
// the player to judge staleness themselves right now; feeding this same
// signal into the ABANDON/MODIFY suggestion checks is a deliberate later
// step, not bundled in here.
public class SlotTimerOverlay extends Overlay
{
	private static final Color TIMER_COLOR = Color.WHITE;
	// Past this, the readout switches to a warning color - a plain visual
	// cue with its own fixed cutoff, not tied to FlipprConfig.staleFillMinutes()
	// (that threshold is specifically about a buy stuck at 0% for the
	// decision engine; this is a simpler always-on signal about ANY slot,
	// filling or not, going quiet).
	private static final Color TIMER_STALE_COLOR = new Color(255, 120, 90);
	private static final long STALE_AFTER_MS = 5 * 60_000L;

	@Inject
	private Client client;

	@Inject
	private SlotActivityTracker activityTracker;

	@Inject
	private FlipprConfig config;

	@Inject
	public SlotTimerOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSlotTimers() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			return null;
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());

		for (int slot = 0; slot < GeWidgets.SLOT_COMPONENT_IDS.length; slot++)
		{
			long elapsedMs = activityTracker.getElapsedMs(slot);
			if (elapsedMs < 0)
			{
				continue;
			}

			Widget widget = client.getWidget(GeWidgets.SLOT_COMPONENT_IDS[slot]);
			if (widget == null || widget.isHidden())
			{
				continue;
			}

			Rectangle bounds = widget.getBounds();
			Color color = elapsedMs >= STALE_AFTER_MS ? TIMER_STALE_COLOR : TIMER_COLOR;
			OverlayUtil.renderTextLocation(graphics, new Point(bounds.x + 4, bounds.y + 12), formatElapsed(elapsedMs), color);
		}

		return null;
	}

	private static String formatElapsed(long ms)
	{
		long totalSeconds = ms / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return minutes > 0 ? String.format("%dm%02ds", minutes, seconds) : String.format("%ds", seconds);
	}
}
