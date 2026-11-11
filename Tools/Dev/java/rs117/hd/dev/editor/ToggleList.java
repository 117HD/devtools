package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** A vertical list of {@link ToggleSwitch} rows for a block of related booleans. */
public final class ToggleList extends JPanel {
	private static final int ROW_HEIGHT = 28;
	private static final int GUTTER = 8;
	private static final int DOT_SIZE = 5;
	private static final Color HOVER = new Color(0x262626);
	private static final Color DOT_CHANGED = new Color(0x7AA5E8);
	private static final Color LABEL_CHANGED = new Color(0xE6E6E6);
	private static final Color LABEL_DEFAULT = new Color(0x9A9A9A);
	private static final Font ROW_FONT = WorkspaceStyle.UI.deriveFont(12f);

	public static final class Item {
		final String label;
		final String normalizedLabel;
		final boolean value;
		final boolean changed;
		final Consumer<Boolean> onChange;

		public Item(String label, boolean value, boolean changed, Consumer<Boolean> onChange) {
			this.label = label;
			this.normalizedLabel = label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
			this.value = value;
			this.changed = changed;
			this.onChange = onChange;
		}
	}

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private final List<Item> items = new java.util.ArrayList<>();

		public Builder add(String label, boolean selected, boolean changed, Consumer<Boolean> action) {
			items.add(new Item(label, selected, changed, action));
			return this;
		}

		public ToggleList build() { return new ToggleList(items); }

		public void update(ToggleList list) { list.setItems(items); }
	}

	public ToggleList(List<Item> items) {
		setLayout(new net.miginfocom.swing.MigLayout("insets 0, gap 32 2, fillx, hidemode 3", "[0,grow,fill]"));
		setAlignmentX(LEFT_ALIGNMENT);
		addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent event) {
				revalidate();
			}
		});
		setOpaque(false);
		setItems(items);
	}

	private int columns(int width) {
		return Math.max(1, (width + 32) / 332);
	}

	@Override public Dimension getMaximumSize() {
		int visible = 0;
		for (Component child : getComponents())
			if (child.isVisible())
				visible++;
		int columns = columns(getParent() == null ? getWidth() : getParent().getWidth() - getParent().getInsets().left - getParent().getInsets().right);
		int rows = (visible + columns - 1) / columns;
		return new Dimension(Integer.MAX_VALUE, Math.max(0, rows * (ROW_HEIGHT + 2) - 2));
	}

	@Override public void doLayout() {
		var layout = (net.miginfocom.swing.MigLayout) getLayout();
		int columns = columns(getWidth());
		String constraints = "insets 0, gap 32 2, fillx, hidemode 3, wrap " + columns;
		if (!constraints.equals(layout.getLayoutConstraints())) {
			layout.setLayoutConstraints(constraints);
			layout.setColumnConstraints("[0,grow,fill]".repeat(columns));
		}
		super.doLayout();
	}

	private void setItems(List<Item> items) {
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			if (i < getComponentCount() && ((Row) getComponent(i)).item.label.equals(item.label)) {
				((Row) getComponent(i)).update(item);
			} else {
				if (i < getComponentCount())
					remove(i);
				var row = new Row(item);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				add(row, "h 28!", i);
			}
		}
		while (getComponentCount() > items.size())
			remove(getComponentCount() - 1);
		revalidate();
		repaint();
	}

	public boolean filter(String normalizedQuery, boolean wholeSection) {
		boolean found = false;
		for (Component child : getComponents()) {
			var row = (Row) child;
			boolean match = wholeSection || row.item.normalizedLabel.contains(normalizedQuery);
			row.setVisible(match);
			found |= match;
		}
		setVisible(found);
		return found;
	}

	private static final class Row extends JPanel {
		private Item item;
		private final ToggleSwitch toggle;
		private boolean hover;

		Row(Item item) {
			this.item = item;
			setLayout(new BorderLayout());
			setOpaque(false);
			setPreferredSize(new Dimension(0, ROW_HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText(item.label);

			toggle = ToggleSwitch.builder().selected(item.value).onChange(value -> this.item.onChange.accept(value)).build();
			var togglePanel = new JPanel(new GridBagLayout());
			togglePanel.setOpaque(false);
			togglePanel.setBorder(BorderFactory.createEmptyBorder(0, GUTTER, 0, 0));
			togglePanel.add(toggle);
			add(togglePanel, BorderLayout.EAST);

			addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
				@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
				@Override public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event))
						toggle.toggle();
				}
			});
		}

		void update(Item item) {
			this.item = item;
			toggle.setSelected(item.value);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			if (hover) {
				g.setColor(HOVER);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			}

			int dotX = getWidth() - getComponent(0).getWidth() - DOT_SIZE;
			if (item.changed) {
				g.setColor(DOT_CHANGED);
				g.fillOval(dotX, (getHeight() - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);
			}

			g.setFont(ROW_FONT);
			g.setColor(item.changed ? LABEL_CHANGED : LABEL_DEFAULT);
			FontMetrics metrics = g.getFontMetrics();
			int availableWidth = dotX - GUTTER;
			String text = ellipsize(item.label, metrics, availableWidth);
			g.drawString(text, 0, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
			g.dispose();
		}

		private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
			if (maxWidth <= 0)
				return "";
			if (metrics.stringWidth(text) <= maxWidth)
				return text;
			String ellipsis = "\u2026";
			int low = 0, high = text.length();
			while (low < high) {
				int mid = (low + high + 1) / 2;
				if (metrics.stringWidth(text.substring(0, mid) + ellipsis) <= maxWidth)
					low = mid;
				else
					high = mid - 1;
			}
			return low == 0 ? ellipsis : text.substring(0, low) + ellipsis;
		}
	}
}
