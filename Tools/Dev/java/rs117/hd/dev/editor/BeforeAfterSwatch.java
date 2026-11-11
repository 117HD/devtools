package rs117.hd.dev.editor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

public final class BeforeAfterSwatch extends JComponent {
	public enum Split { HORIZONTAL, VERTICAL }

	private final Split split;
	private Color before;
	private Color after;

	public BeforeAfterSwatch(int size, Split split, Color before, Color after) {
		this.split = split;
		this.before = before;
		this.after = after;
		setOpaque(false);
		var dimension = new Dimension(size, size);
		setPreferredSize(dimension);
		setMinimumSize(dimension);
		setMaximumSize(dimension);
	}

	public void setColors(Color before, Color after) {
		this.before = before;
		this.after = after;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		int width = getWidth();
		int height = getHeight();
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Shape clip = new RoundRectangle2D.Float(0, 0, width, height, 3, 3);
		Shape previousClip = g.getClip();
		g.clip(clip);
		if (before.getAlpha() < 255 || after.getAlpha() < 255) {
			int cell = 5;
			for (int y = 0; y < height; y += cell) {
				for (int x = 0; x < width; x += cell) {
					g.setColor((x / cell + y / cell) % 2 == 0 ? new Color(0x777777) : new Color(0x444444));
					g.fillRect(x, y, cell, cell);
				}
			}
		}
		if (split == Split.HORIZONTAL) {
			int half = width / 2;
			g.setColor(before);
			g.fillRect(0, 0, half, height);
			g.setColor(after);
			g.fillRect(half, 0, width - half, height);
		} else {
			int half = height / 2;
			g.setColor(before);
			g.fillRect(0, 0, width, half);
			g.setColor(after);
			g.fillRect(0, half, width, height - half);
		}
		g.setClip(previousClip);
		g.setColor(WorkspaceStyle.BORDER);
		g.drawRoundRect(0, 0, width - 1, height - 1, 3, 3);
		g.dispose();
	}
}
