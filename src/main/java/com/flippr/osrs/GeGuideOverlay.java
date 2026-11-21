package com.flippr.osrs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

// Draws the GE guidance steps (GeGuideController) on their widgets: a
// translucent, optionally pulsing fill in the step's colour with a solid
// outline. Drawing only - it never clicks. DYNAMIC so the drawing lands on
// the widgets' real screen bounds.
@Slf4j
public class GeGuideOverlay extends Overlay
{
	@Inject
	private GeGuideController controller;

	@Inject
	private FlipprConfig config;

	// Steps with nothing to draw on (widget missing / hidden), for the log.
	private final ChangeLog missingLog = new ChangeLog();

	@Inject
	public GeGuideOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long now = System.currentTimeMillis();
		StringBuilder missing = new StringBuilder();
		for (GeGuide.Step step : controller.steps())
		{
			Widget widget = controller.widgetFor(step);
			Rectangle bounds = widget == null || widget.isHidden() ? null : widget.getBounds();
			if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			{
				// NPC steps are drawn by GeGuideNpcOverlay.
				if (step.element != GeGuide.Element.CLERK && step.element != GeGuide.Element.BANKER)
				{
					missing.append(step).append(widget == null ? " (no widget) " : " (hidden) ");
				}
				continue;
			}
			Color base = GuideColors.base(step.tone, config.geGuideNextColor(), config.geGuideAttentionColor(), config.geGuideAbortColor());
			graphics.setColor(GuideColors.fill(base, config.geGuideFlash(), step.urgent, now));
			graphics.fill(bounds);
			graphics.setColor(base);
			graphics.setStroke(new BasicStroke(2));
			graphics.draw(bounds);
		}
		if (missingLog.changed(missing.toString()) && missing.length() > 0)
		{
			log.info("GE guide: nothing to highlight for {}", missing.toString().trim());
		}
		return null;
	}
}
