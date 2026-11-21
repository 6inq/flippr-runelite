package com.flippr.osrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

// Small Swing building blocks for the sidebar tabs, in RuneLite's own look
// (ColorScheme, FontManager): cards one step darker than the panel, small
// RuneScape fonts, the brand orange for headers. EDT only.
final class Ui
{
	static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	static final Color BOX = ColorScheme.DARK_GRAY_COLOR;
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;
	static final Color TEXT = Color.WHITE;
	static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color WARN = new Color(255, 190, 70);
	// Sidebar content width (PluginPanel.PANEL_WIDTH minus its padding),
	// and the width wrapped text is laid out at inside a card.
	static final int WIDTH = 205;
	static final int TEXT_WIDTH = 165;

	private Ui()
	{
	}

	static String esc(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static JPanel column(Color bg)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(bg);
		return p;
	}

	static JPanel card()
	{
		JPanel p = column(CARD);
		p.setBorder(new EmptyBorder(6, 6, 6, 6));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	// A vertical gap between cards.
	static Component gap(int h)
	{
		JPanel g = new JPanel();
		g.setOpaque(false);
		g.setPreferredSize(new Dimension(1, h));
		g.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		g.setAlignmentX(Component.LEFT_ALIGNMENT);
		return g;
	}

	static JLabel header(String text)
	{
		JLabel h = new JLabel(text.toUpperCase());
		h.setForeground(ACCENT);
		h.setFont(FontManager.getRunescapeBoldFont());
		h.setBorder(new EmptyBorder(10, 2, 4, 0));
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		return h;
	}

	static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	static JLabel bold(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	// Wrapped small text (HTML) at the card text width.
	static JLabel wrapped(String text, Color color)
	{
		return wrapped(text, color, TEXT_WIDTH);
	}

	static JLabel wrapped(String text, Color color, int width)
	{
		return small("<html><body style='width:" + width + "px'>" + esc(text) + "</body></html>", color);
	}

	// "LABEL" over a value, for the summary tiles and the card stats.
	static JPanel stat(String label, String value, Color valueColor, String hint)
	{
		JPanel p = column(BOX);
		p.setBorder(new EmptyBorder(3, 4, 3, 4));
		p.add(small(label, MUTED));
		JLabel v = bold(value, valueColor);
		p.add(v);
		if (hint != null)
		{
			p.add(small(hint, MUTED));
		}
		return p;
	}

	static JPanel grid(int cols, Component... items)
	{
		int rows = (items.length + cols - 1) / cols;
		JPanel p = new JPanel(new GridLayout(rows, cols, 3, 3));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Component c : items)
		{
			p.add(c);
		}
		return p;
	}

	static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setMargin(new java.awt.Insets(2, 4, 2, 4));
		return b;
	}

	// A rounded-ish status pill: "Buying".
	static JLabel pill(String text, Color color)
	{
		JLabel l = small(text, color);
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(color, 1),
			new EmptyBorder(0, 3, 0, 3)));
		return l;
	}

	static Color statusColor(String status)
	{
		if (status == null)
		{
			return MUTED;
		}
		switch (status)
		{
			case "buying":
				return new Color(100, 200, 255);
			case "bought":
				return new Color(170, 140, 255);
			case "selling":
				return ACCENT;
			case "done":
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			default:
				return MUTED;
		}
	}

	// A thin progress bar.
	static JComponent progressBar(double value, Color fill)
	{
		double v = Math.max(0, Math.min(1, value));
		JComponent bar = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(fill);
				g.fillRect(0, 0, (int) Math.round(getWidth() * v), getHeight());
			}
		};
		bar.setPreferredSize(new Dimension(10, 6));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		return bar;
	}

	// Left text, right text on one line.
	static JPanel line(Component left, Component right)
	{
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (left != null)
		{
			p.add(left, BorderLayout.CENTER);
		}
		if (right != null)
		{
			p.add(right, BorderLayout.EAST);
		}
		return p;
	}

	// Stops BoxLayout stretching a row taller than it needs.
	static <T extends JComponent> T fitHeight(T c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}
}
