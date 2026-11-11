package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.Timer;

/** A 28x16 on/off switch with an animated knob. */
public final class ToggleSwitch extends JComponent {
	private static final int WIDTH = 28;
	private static final int HEIGHT = 16;
	private static final int KNOB_SIZE = 12;
	private static final int KNOB_OFF_X = 2;
	private static final int KNOB_ON_X = 14;
	private static final Color TRACK_OFF = WorkspaceStyle.BORDER;
	private static final Color TRACK_ON = WorkspaceStyle.ACCENT;
	private static final Color KNOB_COLOR = new Color(0xF0F0F0);
	private static final int DURATION_MS = 120;
	private static final int TICK_MS = 16;

	private boolean selected;
	private double knobX;
	private final Timer timer = new Timer(TICK_MS, event -> tickAnimation());
	private long animStart;
	private double animFrom;
	private double animTo;
	private Consumer<Boolean> listener;

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private boolean selected;
		private String tooltip;
		private Consumer<Boolean> action;

		public Builder selected(boolean value) { selected = value; return this; }
		public Builder tooltip(String value) { tooltip = value; return this; }
		public Builder onChange(Consumer<Boolean> value) { action = value; return this; }

		public ToggleSwitch build() {
			var toggle = new ToggleSwitch(selected);
			toggle.setToolTipText(tooltip);
			toggle.onChange(action);
			return toggle;
		}
	}

	public ToggleSwitch(boolean initial) {
		selected = initial;
		knobX = selected ? KNOB_ON_X : KNOB_OFF_X;
		setPreferredSize(new Dimension(WIDTH, HEIGHT));
		setMinimumSize(new Dimension(WIDTH, HEIGHT));
		setMaximumSize(new Dimension(WIDTH, HEIGHT));
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) {
				if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
					requestFocusInWindow();
					toggle();
				}
			}
		});
		addKeyListener(new KeyAdapter() {
			@Override public void keyPressed(KeyEvent event) {
				if (event.getKeyCode() == KeyEvent.VK_SPACE || event.getKeyCode() == KeyEvent.VK_ENTER)
					toggle();
			}
		});
		addFocusListener(new FocusAdapter() {
			@Override public void focusGained(FocusEvent event) { repaint(); }
			@Override public void focusLost(FocusEvent event) { repaint(); }
		});
	}

	public boolean isSelected() { return selected; }

	/** Sets the value without animating. Intended for programmatic/initial state. */
	public void setSelected(boolean value) {
		if (selected == value)
			return;
		timer.stop();
		selected = value;
		knobX = selected ? KNOB_ON_X : KNOB_OFF_X;
		repaint();
	}

	/** Flips the value and animates the knob. Intended for user-driven toggles. */
	public void toggle() {
		selected = !selected;
		animate();
		if (listener != null)
			listener.accept(selected);
	}

	public void onChange(Consumer<Boolean> listener) {
		this.listener = listener;
	}

	private void animate() {
		animFrom = knobX;
		animTo = selected ? KNOB_ON_X : KNOB_OFF_X;
		animStart = System.nanoTime();
		timer.restart();
	}

	private void tickAnimation() {
		double progress = Math.min(1.0, (System.nanoTime() - animStart) / (DURATION_MS * 1_000_000.0));
		double eased = 1 - Math.pow(1 - progress, 3);
		knobX = animFrom + (animTo - animFrom) * eased;
		repaint();
		if (progress >= 1.0)
			timer.stop();
	}

	@Override public void removeNotify() {
		timer.stop();
		knobX = selected ? KNOB_ON_X : KNOB_OFF_X;
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double t = Math.max(0, Math.min(1, (knobX - KNOB_OFF_X) / (double) (KNOB_ON_X - KNOB_OFF_X)));
		g.setColor(blend(TRACK_OFF, TRACK_ON, t));
		g.fillRoundRect(0, 0, WIDTH, HEIGHT, 8, 8);
		if (isFocusOwner()) {
			g.setColor(WorkspaceStyle.ACCENT);
			g.drawRoundRect(0, 0, WIDTH - 1, HEIGHT - 1, 8, 8);
		}
		int knobY = (HEIGHT - KNOB_SIZE) / 2;
		int x = (int) Math.round(knobX);
		g.setColor(new Color(0, 0, 0, 102));
		g.fillOval(x, knobY + 1, KNOB_SIZE, KNOB_SIZE);
		g.setColor(KNOB_COLOR);
		g.fillOval(x, knobY, KNOB_SIZE, KNOB_SIZE);
		g.dispose();
	}

	private static Color blend(Color a, Color b, double t) {
		int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
		int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
		int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
		return new Color(r, g, bl);
	}
}
