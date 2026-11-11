package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;

/** A single-select segmented button group, similar to {@code WorkspaceStyle.Button.SEGMENT}. */
public final class SegmentedControl<T> extends JPanel {
	private static final int CONTROL_HEIGHT = 24;
	private static final int PADDING = 2;
	private static final int GAP = 2;
	private static final Color TRACK = new Color(0x1A1A1A);
	private static final Color TRACK_BORDER = new Color(0x333333);
	private static final Color SEGMENT_HOVER = new Color(0x2C2C2C);
	private final Function<T, Color> marker;
	private final Font optionFont;
	private final Font activeFont;
	private static final Color IDLE_TEXT = new Color(0xA9A9A9);

	private final Function<T, String> label;
	private final List<Segment> segments = new ArrayList<>();
	private T selected;
	private Consumer<T> listener;

	public static <T> Builder<T> builder(List<T> options) { return new Builder<>(options); }

	public static final class Builder<T> {
		private final List<T> options;
		private Function<T, String> label = String::valueOf;
		private T selected;
		private Consumer<T> action;
		private boolean values;
		private Function<T, Color> marker;

		private Builder(List<T> options) {
			this.options = List.copyOf(options);
			if (this.options.isEmpty())
				throw new IllegalArgumentException("A segmented control needs at least one option");
			selected = this.options.get(0);
		}

		public Builder<T> label(Function<T, String> value) { label = Objects.requireNonNull(value); return this; }
		public Builder<T> values() { values = true; return this; }
		public Builder<T> marker(Function<T, Color> value) { marker = value; return this; }
		public Builder<T> selected(T value) { selected = value; return this; }
		public Builder<T> onChange(Consumer<T> value) { action = value; return this; }

		public SegmentedControl<T> build() {
			if (!options.contains(selected))
				throw new IllegalArgumentException("The selected value must be one of the options");
			var control = new SegmentedControl<>(options, label, selected, values, marker);
			control.onChange(action);
			return control;
		}
	}

	public SegmentedControl(List<T> options, Function<T, String> label, T selected) {
		this(options, label, selected, false, null);
	}

	private SegmentedControl(List<T> options, Function<T, String> label, T selected, boolean values, Function<T, Color> marker) {
		this.marker = marker;
		optionFont = (values ? WorkspaceStyle.MONO : WorkspaceStyle.UI).deriveFont(11f);
		activeFont = (values ? WorkspaceStyle.MONO_SEMIBOLD : WorkspaceStyle.SEMIBOLD).deriveFont(11f);
		this.label = label;
		this.selected = selected;

		setLayout(new GridLayout(1, options.size(), GAP, 0));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));

		for (T option : options) {
			var segment = new Segment(option);
			segments.add(segment);
			add(segment);
		}
	}

	public T getSelected() { return selected; }

	/** Sets the selection without firing {@link #onChange}. */
	public void setSelected(T value) {
		selected = value;
		repaintSegments();
	}

	public void onChange(Consumer<T> listener) {
		this.listener = listener;
	}

	private void repaintSegments() {
		for (Segment segment : segments)
			segment.repaint();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(TRACK);
		g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		g.setColor(TRACK_BORDER);
		g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		g.dispose();
	}

	private final class Segment extends JComponent {
		private final T option;
		private boolean hover;

		Segment(T option) {
			this.option = option;
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
				@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
				@Override public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event) && !Objects.equals(option, selected)) {
						selected = option;
						repaintSegments();
						if (listener != null)
							listener.accept(selected);
					}
				}
			});
		}

		@Override
		public Dimension getPreferredSize() {
			FontMetrics metrics = getFontMetrics(activeFont);
			return new Dimension(metrics.stringWidth(label.apply(option)) + 16, CONTROL_HEIGHT - PADDING * 2);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			boolean active = Objects.equals(option, selected);
			if (active) {
				g.setColor(marker == null ? WorkspaceStyle.ACCENT : new Color(0x454545));
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
			} else if (hover) {
				g.setColor(SEGMENT_HOVER);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
			}
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(active ? activeFont : optionFont);
			g.setColor(active ? Color.WHITE : IDLE_TEXT);
			String text = label.apply(option);
			FontMetrics metrics = g.getFontMetrics();
			int x = (getWidth() - metrics.stringWidth(text)) / 2;
			if (marker != null) {
				x += 3;
				g.setColor(marker.apply(option));
				g.fillOval(x - 7, (getHeight() - 5) / 2, 5, 5);
				g.setColor(active ? Color.WHITE : IDLE_TEXT);
			}
			int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(text, x, y);
			g.dispose();
		}
	}
}
