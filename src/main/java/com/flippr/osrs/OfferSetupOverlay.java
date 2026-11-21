package com.flippr.osrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

// Highlights on the "Grand Exchange: Set up offer" screen itself (buy or
// sell), independent of per-slot Suggestions entirely - unlike
// SuggestionOverlay, this never needs to know WHAT quantity/price to
// suggest (DecisionEngine doesn't compute one yet), only WHICH fields are
// still empty and WHEN the form is ready to submit, both readable directly
// from the live offer state. A deliberately honest, scoped-down version of
// the reference behavior: it shows the player where to click, it just can't
// tell them the exact number yet.
public class OfferSetupOverlay extends Overlay
{
	private static final Color FILLABLE_COLOR = new Color(0, 200, 255);
	private static final Color CONFIRM_COLOR = new Color(0, 255, 0);

	@Inject
	private Client client;

	@Inject
	private FlipprConfig config;

	@Inject
	public OfferSetupOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Superseded by the step-by-step GE guidance (GeGuideOverlay) while
		// that's on.
		if (!config.highlightSuggestions() || config.geGuideHighlights() || config.watchModeEnabled() || config.watchModeDumpEnabled())
		{
			return null;
		}

		Widget setupScreen = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setupScreen == null || setupScreen.isHidden())
		{
			return null;
		}

		// The price is a 64-bit varp since the 2026 max-cash update.
		int quantity = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		long price = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);

		if (quantity <= 0)
		{
			highlight(graphics, GeWidgets.getSetupQuantitySetButton(client), FILLABLE_COLOR);
		}
		if (price <= 0)
		{
			highlight(graphics, GeWidgets.getSetupPriceSetButton(client), FILLABLE_COLOR);
		}
		if (quantity > 0 && price > 0)
		{
			Widget confirmButton = client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM);
			highlight(graphics, confirmButton, CONFIRM_COLOR);
		}

		return null;
	}

	private static void highlight(Graphics2D graphics, Widget widget, Color color)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}
		OverlayUtil.renderPolygon(graphics, widget.getBounds(), color);
	}
}
