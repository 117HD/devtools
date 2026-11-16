package rs117.hd.dev.editor;

import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/** Content-width segmented buttons for toolbars and inline mode switches. */
public final class EditorButtonGroup<T> extends JPanel {
	private final Map<T, JToggleButton> buttons = new LinkedHashMap<>();
	private final boolean track;

	private EditorButtonGroup(Builder<T> builder) {
		super(new FlowLayout(builder.track ? FlowLayout.LEFT : FlowLayout.RIGHT, builder.track ? 0 : 2, builder.track ? 3 : 0));
		track = builder.track;
		setOpaque(false);
		if (track)
			setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

		var group = new ButtonGroup();
		var onChange = builder.change;
		for (T option : builder.options) {
			var button = EditorButton.builder().style(WorkspaceStyle.Button.SEGMENT)
				.text(builder.label.apply(option)).onClick(() -> onChange.accept(option))
				.buildToggle(java.util.Objects.equals(option, builder.selected));
			if (track)
				button.setForeground(WorkspaceStyle.DIM);
			buttons.put(option, button);
			group.add(button);
			add(button);
		}
	}

	public JToggleButton button(T option) { return buttons.get(option); }

	public void setSelected(T option) { button(option).setSelected(true); }

	@Override protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		if (!track)
			return;
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(WorkspaceStyle.MENU);
		g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
		g.dispose();
	}

	public static <T> Builder<T> builder(List<T> options) { return new Builder<>(options); }

	public static final class Builder<T> {
		private final List<T> options;
		private T selected;
		private java.util.function.Function<T, String> label = String::valueOf;
		private Consumer<T> change = option -> {};
		private boolean track;

		private Builder(List<T> options) {
			this.options = List.copyOf(options);
			if (options.isEmpty() || new java.util.HashSet<>(options).size() != options.size())
				throw new IllegalArgumentException("Provide distinct button options");
			selected = this.options.get(0);
		}

		public Builder<T> label(java.util.function.Function<T, String> value) { label = value; return this; }
		public Builder<T> selected(T value) { selected = value; return this; }
		public Builder<T> onChange(Consumer<T> action) { change = action; return this; }
		public Builder<T> track() { track = true; return this; }

		public EditorButtonGroup<T> build() {
			if (!options.contains(selected))
				throw new IllegalArgumentException("Select one of the button options");
			return new EditorButtonGroup<>(this);
		}
	}
}
