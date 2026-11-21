package com.flippr.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

// Outlines the GE clerk (or a banker) when the guide says to go there -
// only when one is loaded within GeGuideController.NEARBY_TILES, i.e. the
// player is at the GE. Drawing only.
public class GeGuideNpcOverlay extends Overlay
{
	private static final int OUTLINE_WIDTH = 3;
	private static final int FEATHER = 3;

	@Inject
	private GeGuideController controller;

	@Inject
	private ModelOutlineRenderer outlineRenderer;

	@Inject
	private FlipprConfig config;

	@Inject
	public GeGuideNpcOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long now = System.currentTimeMillis();
		for (GeGuide.Step step : controller.steps())
		{
			NPC npc = controller.npcFor(step);
			if (npc == null)
			{
				continue;
			}
			Color base = GuideColors.base(step.tone, config.geGuideNextColor(), config.geGuideAttentionColor(), config.geGuideAbortColor());
			// An outline needs more opacity than a widget fill to read.
			Color fill = GuideColors.fill(base, config.geGuideFlash(), step.urgent, now);
			outlineRenderer.drawOutline(npc, OUTLINE_WIDTH, new Color(base.getRed(), base.getGreen(), base.getBlue(),
				Math.min(255, fill.getAlpha() * 2)), FEATHER);
		}
		return null;
	}
}
