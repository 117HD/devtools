package rs117.hd.dev.editor;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Scroll panes with the editor's arrow buttons and rounded thumb. */
public class EditorScrollPane extends JScrollPane {
	public EditorScrollPane(Component view) {
		this(view, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);
	}

	public EditorScrollPane(Component view, int verticalPolicy, int horizontalPolicy) {
		super(view, verticalPolicy, horizontalPolicy);
		getVerticalScrollBar().setUI(new ScrollBarStyle());
		getHorizontalScrollBar().setUI(new ScrollBarStyle());
	}

	private static final class ScrollBarStyle extends BasicScrollBarUI {
		private static final int WIDTH = 15;
		private static final Color TRACK = new Color(0x292929);
		private static final Color THUMB = new Color(0xA0A0A0);

		@Override public Dimension getPreferredSize(JComponent component) {
			return new Dimension(WIDTH, WIDTH);
		}

		@Override protected Dimension getMinimumThumbSize() {
			return scrollbar.getOrientation() == JScrollBar.VERTICAL ? new Dimension(WIDTH, 20) : new Dimension(20, WIDTH);
		}

		@Override protected JButton createDecreaseButton(int direction) { return arrow(direction); }
		@Override protected JButton createIncreaseButton(int direction) { return arrow(direction); }

		@Override protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
			graphics.setColor(TRACK);
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		}

		@Override protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
			if (bounds.isEmpty() || !scrollbar.isEnabled())
				return;
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(isDragging || isThumbRollover() ? new Color(0xB8B8B8) : THUMB);
			int xInset = scrollbar.getOrientation() == JScrollBar.VERTICAL ? 3 : 0;
			int yInset = xInset == 0 ? 3 : 0;
			g.fillRoundRect(bounds.x + xInset, bounds.y + yInset, bounds.width - 2 * xInset, bounds.height - 2 * yInset, 9, 9);
			g.dispose();
		}

		private static JButton arrow(int direction) {
			var button = new JButton() {
				@Override protected void paintComponent(Graphics graphics) {
					var g = (Graphics2D) graphics.create();
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setColor(getModel().isRollover() ? new Color(0x353535) : TRACK);
					g.fillRect(0, 0, getWidth(), getHeight());
					g.translate(getWidth() / 2.0, getHeight() / 2.0);
					int quarterTurns = direction == SwingConstants.NORTH ? 0 : direction == SwingConstants.EAST ? 1
						: direction == SwingConstants.SOUTH ? 2 : 3;
					g.rotate(quarterTurns * Math.PI / 2);
					g.setColor(THUMB);
					g.fillPolygon(new int[] {-4, 0, 4}, new int[] {3, -3, 3}, 3);
					g.dispose();
				}
			};
			button.setPreferredSize(new Dimension(WIDTH, WIDTH));
			button.setBorderPainted(false);
			button.setFocusable(false);
			button.setRolloverEnabled(true);
			return button;
		}
	}
}
