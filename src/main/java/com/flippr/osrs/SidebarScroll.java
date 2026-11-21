package com.flippr.osrs;

import java.awt.Component;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

// Mouse-wheel scrolling for the sidebar. RuneLite's PluginPanel puts the
// panel in a plain JScrollPane whose scroll bar keeps Swing's default unit
// increment of 1px - with a JPanel (not Scrollable) as the view, one wheel
// notch moved ~3px, which read as "barely moves". apply() sets a ~20px unit
// and a block of about one screen (kept current as the viewport resizes).
// forwardAtEdge() makes a nested scroll pane hand the wheel to the outer one
// once it can't scroll further that way, so wheel events over it never get
// swallowed. EDT only; the arithmetic is static and unit tested.
final class SidebarScroll
{
	static final int UNIT_INCREMENT = 20;

	private SidebarScroll()
	{
	}

	// About one visible height, keeping a unit of overlap for context.
	static int blockIncrement(int visibleHeight)
	{
		return Math.max(UNIT_INCREMENT, visibleHeight - UNIT_INCREMENT);
	}

	// Can a bar at this value scroll further in this wheel direction
	// (rotation < 0 = up)?
	static boolean canScroll(int value, int extent, int min, int max, int rotation)
	{
		if (rotation < 0)
		{
			return value > min;
		}
		if (rotation > 0)
		{
			return value + extent < max;
		}
		return false;
	}

	static void apply(JScrollPane pane)
	{
		if (pane == null)
		{
			return;
		}
		JScrollBar bar = pane.getVerticalScrollBar();
		bar.setUnitIncrement(UNIT_INCREMENT);
		bar.setBlockIncrement(blockIncrement(pane.getViewport().getHeight()));
		pane.setWheelScrollingEnabled(true);
		pane.getViewport().addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				bar.setBlockIncrement(blockIncrement(pane.getViewport().getHeight()));
			}
		});
	}

	// A scroll pane inside the sidebar: scrolls itself while it can, else
	// passes the wheel event to the nearest outer scroll pane.
	static void forwardAtEdge(JScrollPane inner)
	{
		apply(inner);
		inner.setWheelScrollingEnabled(false);
		inner.addMouseWheelListener(e ->
		{
			JScrollBar bar = inner.getVerticalScrollBar();
			int rotation = e.getWheelRotation();
			if (bar.isVisible() && canScroll(bar.getValue(), bar.getModel().getExtent(), bar.getMinimum(), bar.getMaximum(), rotation))
			{
				bar.setValue(bar.getValue() + rotation * e.getScrollAmount() * bar.getUnitIncrement(rotation));
				return;
			}
			Component outer = SwingUtilities.getAncestorOfClass(JScrollPane.class, inner);
			if (outer != null)
			{
				outer.dispatchEvent(SwingUtilities.convertMouseEvent(inner, e, outer));
			}
		});
	}
}
