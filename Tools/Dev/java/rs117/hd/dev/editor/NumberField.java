package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * A compact numeric input with an optional unit suffix. The caller's own label for this field
 * can be made "scrubbable" (drag left/right to change the value) by passing it to
 * {@link #attachScrub(JComponent)}.
 */
public final class NumberField extends JPanel {
	private static final int HEIGHT = 24;
	private static final int MAX_WIDTH = 200;
	private static final int PIXELS_PER_STEP = 4;
	private static final Color UNIT_COLOR = new Color(0x6F6F6F);

	private final JTextField field;
	private final double step;
	private final Double min;
	private final Double max;
	private final boolean nullable;
	private String emptyText = "Default";

	private Double value;
	private final JPanel valueCards = new JPanel(new CardLayout());
	private final JButton display;
	private Double defaultValue;
	private boolean editing;
	private boolean scrubbing;
	private Consumer<Double> listener;

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private Double value = 0.0;
		private double step = 1;
		private Double min, max;
		private String unit;
		private boolean nullable;
		private String emptyText = "Default";
		private Consumer<Double> listener;
		private Double defaultValue;

		public Builder value(Double initial) { value = initial; return this; }
		public Builder step(double increment) { step = increment; return this; }
		public Builder min(double bound) { min = bound; return this; }
		public Builder max(double bound) { max = bound; return this; }
		public Builder unit(String suffix) { unit = suffix; return this; }
		public Builder nullable(boolean allowEmpty) { nullable = allowEmpty; return this; }
		public Builder defaultValue(double value) { defaultValue = value; return this; }
		public Builder emptyText(String text) { emptyText = text; return this; }
		public Builder onChange(Consumer<Double> action) { listener = action; return this; }

		public NumberField build() {
			var field = new NumberField(value, step, min, max, unit, nullable);
			field.emptyText = emptyText;
			field.defaultValue = defaultValue;
			field.refreshText();
			field.onChange(listener);
			return field;
		}
	}

	public NumberField(Double initial, double step, Double min, Double max, String unit, boolean nullable) {
		this.step = step;
		this.min = min;
		this.max = max;
		this.nullable = nullable;
		this.value = clamp(initial);

		setLayout(new BorderLayout());
		setOpaque(false);
		setPreferredSize(new Dimension(100, HEIGHT));
		setMaximumSize(new Dimension(MAX_WIDTH, HEIGHT));

		field = new JTextField();
		field.setOpaque(false);
		field.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		field.setHorizontalAlignment(SwingConstants.CENTER);
		field.setFont(WorkspaceStyle.MONO.deriveFont(11f));
		field.setForeground(WorkspaceStyle.TEXT);
		field.setCaretColor(WorkspaceStyle.TEXT);

		display = EditorButton.builder().style(WorkspaceStyle.Button.TAB)
			.tooltip("Drag to adjust or click to type").onClick(this::startEditing).build();
		display.setBorder(BorderFactory.createEmptyBorder());
		display.setRolloverEnabled(false);
		display.setFont(field.getFont());
		attachScrub(display);
		valueCards.setOpaque(false);
		valueCards.add(display, "display");
		valueCards.add(field, "edit");

		var center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(valueCards);
		if (unit != null && !unit.isEmpty()) {
			var unitLabel = EditorText.builder(unit).font(WorkspaceStyle.MONO.deriveFont(10f)).color(UNIT_COLOR)
				.border(BorderFactory.createEmptyBorder(0, 2, 0, 3)).build();
			center.add(unitLabel, BorderLayout.EAST);
		}
		add(stepButton("‹", -1), BorderLayout.WEST);
		add(center);
		add(stepButton("›", 1), BorderLayout.EAST);

		refreshText();
		field.addActionListener(event -> finishEditing(true));
		field.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent event) { finishEditing(true); }
		});
		field.addKeyListener(new KeyAdapter() {
			@Override public void keyPressed(KeyEvent event) {
				switch (event.getKeyCode()) {
					case KeyEvent.VK_ESCAPE:
						event.consume();
						finishEditing(false);
						break;
					case KeyEvent.VK_UP:
					case KeyEvent.VK_DOWN:
						event.consume();
						commit(adjusted(base(), (event.getKeyCode() == KeyEvent.VK_UP ? 1 : -1) * step * multiplier(event)));
						break;
				}
			}
		});
	}

	private JButton stepButton(String caption, int direction) {
		var button = EditorButton.builder().style(WorkspaceStyle.Button.TAB).text(caption)
			.size(20, HEIGHT).tooltip(direction < 0 ? "Decrease" : "Increase")
			.onClick(() -> commit(adjusted(base(), direction * step))).build();
		button.setBorder(BorderFactory.createEmptyBorder());
		button.setFocusable(false);
		button.setRolloverEnabled(false);
		return button;
	}

	private void startEditing() {
		if (!isEnabled() || scrubbing)
			return;
		editing = true;
		((CardLayout) valueCards.getLayout()).show(valueCards, "edit");
		field.requestFocusInWindow();
		field.selectAll();
	}

	private void finishEditing(boolean apply) {
		if (!editing)
			return;
		editing = false;
		if (apply)
			commitTyped();
		else
			refreshText();
		((CardLayout) valueCards.getLayout()).show(valueCards, "display");
	}

	/** Makes dragging the given component horizontally scrub this field's value. */
	public void attachScrub(JComponent target) {
		target.setCursor(WorkspaceStyle.cursor("scrub"));
		var handler = new MouseAdapter() {
			private int startX;
			private Double original;
			private boolean active;

			@Override public void mousePressed(MouseEvent event) {
				if (!isEnabled() || !SwingUtilities.isLeftMouseButton(event))
					return;
				startX = event.getXOnScreen();
				original = value;
				active = true;
			}

			@Override public void mouseDragged(MouseEvent event) {
				if (!active)
					return;
				if (!isEnabled() || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) {
					cancelDrag();
					return;
				}
				int pixels = event.getXOnScreen() - startX;
				if (!scrubbing && Math.abs(pixels) < PIXELS_PER_STEP)
					return;
				scrubbing = true;
				if (target instanceof AbstractButton)
					((AbstractButton) target).getModel().setArmed(false);
				double start = original == null ? fallback() : original;
				double delta = (pixels / PIXELS_PER_STEP) * step * multiplier(event);
				value = delta == 0 ? original : clamp(adjusted(start, delta));
				refreshText();
			}

			@Override public void mouseMoved(MouseEvent event) {
				if (active && (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0)
					cancelDrag();
			}

			private void cancelDrag() {
				active = false;
				if (scrubbing) {
					scrubbing = false;
					setValue(original);
				}
			}

			@Override public void mouseReleased(MouseEvent event) {
				if (!active || !SwingUtilities.isLeftMouseButton(event))
					return;
				active = false;
				if (scrubbing) {
					Double next = value;
					value = original;
					scrubbing = false;
					commit(next);
				}
			}
		};
		target.addMouseListener(handler);
		target.addMouseMotionListener(handler);
	}

	public Double getValue() { return value; }

	public void setValue(Double value) {
		this.value = clamp(value);
		refreshText();
	}

	/** Fires on commit (Enter/blur/arrow/scrub-release), not on every keystroke. */
	public void onChange(Consumer<Double> listener) {
		this.listener = listener;
	}

	private double base() {
		return value != null ? value : fallback();
	}

	private double fallback() { return defaultValue != null ? defaultValue : 0; }

	private static double multiplier(InputEvent event) {
		return event.isShiftDown() ? 10 : event.isAltDown() ? 0.1 : 1;
	}

	private void commitTyped() {
		String text = field.getText().trim();
		Double parsed;
		if (text.isEmpty()) {
			parsed = nullable ? null : base();
		} else {
			try {
				parsed = Double.parseDouble(text);
				if (!Double.isFinite(parsed))
					parsed = value;
			} catch (NumberFormatException e) {
				parsed = value;
			}
		}
		commit(parsed);
	}

	private void commit(Double newValue) {
		Double previous = value;
		value = clamp(newValue);
		refreshText();
		if (listener != null && !java.util.Objects.equals(previous, value))
			listener.accept(value);
	}

	private void refreshText() {
		String text = value == null ? "" : formatValue(value);
		field.setText(text);
		display.setText(value == null && defaultValue != null ? emptyText + " · " + formatValue(defaultValue)
			: value == null ? emptyText : text);
		display.setForeground(value == null ? WorkspaceStyle.DIM : WorkspaceStyle.TEXT);
		repaint();
	}

	private Double clamp(Double v) {
		if (v == null)
			return nullable ? null : (value != null ? value : (min != null ? min : 0.0));
		double clamped = v;
		if (min != null)
			clamped = Math.max(min, clamped);
		if (max != null)
			clamped = Math.min(max, clamped);
		return clamped;
	}

	private static double adjusted(double base, double delta) {
		return java.math.BigDecimal.valueOf(base).add(java.math.BigDecimal.valueOf(delta))
			.round(java.math.MathContext.DECIMAL64).doubleValue();
	}

	private static String formatValue(double v) {
		return Double.isFinite(v) ? java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() : Double.toString(v);
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(WorkspaceStyle.INPUT);
		g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		var shape = new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		var fill = (Graphics2D) g.create();
		fill.clip(shape);
		double fraction = min != null && max != null && max > min && max < Double.MAX_VALUE
			? (base() - min) / (max - min) : 0;
		int fillWidth = (int) Math.round(Math.max(0, Math.min(1, fraction)) * getWidth());
		if (value == null) {
			fill.setColor(new Color(255, 255, 255, 14));
			fill.clipRect(0, 0, Math.max(20, fillWidth), getHeight());
			fill.setStroke(new BasicStroke(2));
			for (int x = -getHeight(); x < fillWidth + 20; x += 7)
				fill.drawLine(x, getHeight(), x + getHeight(), 0);
		} else if (fillWidth > 0 || scrubbing) {
			fill.setColor(new Color(0x30425E));
			fill.fillRect(0, 0, Math.max(fillWidth, scrubbing ? 20 : 0), getHeight());
		}
		fill.dispose();
		g.setColor(editing ? WorkspaceStyle.ACCENT : WorkspaceStyle.BORDER);
		g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		g.dispose();
	}
}
