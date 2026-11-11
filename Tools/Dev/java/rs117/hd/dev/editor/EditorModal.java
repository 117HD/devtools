package rs117.hd.dev.editor;

import java.awt.*;
import javax.swing.*;

/** Builds an editor dialog with an owner backdrop and Escape-to-close behavior. */
public final class EditorModal {
	private static final Color BACKDROP_TINT = new Color(10, 10, 10, 128);
	private static final Color SHADOW_COLOR = new Color(0, 0, 0, 9);

	private EditorModal() {}

	public static Builder builder(JFrame owner, String title) { return new Builder(owner, title); }

	public static final class Builder {
		private final JFrame owner;
		private final String title;
		private JComponent content;
		private Dimension size;
		private Integer topOffsetPercent;
		private JComponent initialFocus;

		private Builder(JFrame owner, String title) { this.owner = owner; this.title = title; }
		public Builder content(JComponent content) { this.content = content; return this; }
		public Builder size(int width, int height) { size = new Dimension(width, height); return this; }

		public Builder topOffsetPercent(int percent) { topOffsetPercent = percent; return this; }
		public Builder initialFocus(JComponent component) { initialFocus = component; return this; }

		public JDialog open() {
			var dialog = build();
			dialog.setVisible(true);
			if (initialFocus != null)
				initialFocus.requestFocusInWindow();
			return dialog;
		}

		public JDialog build() {
			var dialog = create(owner, title);
			if (content != null)
				dialog.setContentPane(content);
			if (size == null)
				dialog.pack();
			else
				dialog.setSize(size);
			dialog.setLocationRelativeTo(owner);
			if (topOffsetPercent != null) {
				int x = owner.getX() + (owner.getWidth() - dialog.getWidth()) / 2;
				int y = owner.getY() + owner.getHeight() * topOffsetPercent / 100;
				dialog.setLocation(x, y);
			}
			return dialog;
		}
	}

	public static JDialog create(JFrame owner, String title) {
		class BackdropDialog extends JDialog {
			private JComponent backdrop;
			private Component previousGlass;
			private boolean previouslyVisible;

			BackdropDialog() { super(owner, title, Dialog.ModalityType.MODELESS); }

			@Override public void setVisible(boolean visible) {
				if (visible && !isVisible() && backdrop != null && previousGlass == null) {
					previousGlass = owner.getGlassPane();
					previouslyVisible = previousGlass.isVisible();
					owner.setGlassPane(backdrop);
					backdrop.setVisible(true);
				}
				try {
					super.setVisible(visible);
				} finally {
					if (!isVisible())
						restoreGlassPane();
					else if (backdrop != null)
						backdrop.repaint();
				}
			}

			@Override public void dispose() {
				try {
					super.dispose();
				} finally {
					restoreGlassPane();
				}
			}

			private void restoreGlassPane() {
				if (previousGlass == null)
					return;
				backdrop.setVisible(false);
				if (owner.getGlassPane() == backdrop) {
					owner.setGlassPane(previousGlass);
					previousGlass.setVisible(previouslyVisible);
					owner.getRootPane().repaint();
				}
				previousGlass = null;
			}
		}
		var dialog = new BackdropDialog();
		dialog.setUndecorated(true);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		var backdrop = new JComponent() {
			@Override protected void paintComponent(Graphics g) {
				g.setColor(BACKDROP_TINT);
				g.fillRect(0, 0, getWidth(), getHeight());
				if (!dialog.isVisible() || !isShowing())
					return;
				var shadow = (Graphics2D) g.create();
				shadow.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				shadow.setColor(SHADOW_COLOR);
				Point origin = getLocationOnScreen();
				int x = dialog.getX() - origin.x;
				int y = dialog.getY() - origin.y + 12;
				for (int spread = 28; spread > 0; spread -= 2) {
					shadow.fillRoundRect(x - spread, y - spread, dialog.getWidth() + spread * 2,
						dialog.getHeight() + spread * 2, 16 + spread * 2, 16 + spread * 2);
				}
				shadow.dispose();
			}
		};
		backdrop.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override public void mousePressed(java.awt.event.MouseEvent e) { dialog.dispose(); }
		});
		dialog.backdrop = backdrop;
		dialog.getRootPane().setBorder(new javax.swing.border.LineBorder(WorkspaceStyle.BORDER, 1, true));
		dialog.addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override public void componentResized(java.awt.event.ComponentEvent e) {
				if (dialog.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT))
					dialog.setShape(new java.awt.geom.RoundRectangle2D.Float(0, 0, dialog.getWidth(), dialog.getHeight(), 16, 16));
				backdrop.repaint();
			}
		});
		dialog.getContentPane().setBackground(WorkspaceStyle.MENU);
		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		return dialog;
	}

}
