package rs117.hd.dev.overrides;

import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;
import rs117.hd.dev.editor.*;
import rs117.hd.scene.model_overrides.WindDisplacement;

final class WindModeDropdown extends JButton {
	private enum Mode {
		DISABLED("No wind", "The model never moves.", "disabled", "Off"),
		OBJECT("Object sway", "Bends upward and reacts to nearby characters.", "object", "Whole object \u00b7 sways as one piece"),
		OBJECT_NO_GROUND_DISPLACEMENT("Object sway \u00b7 loose base", "Sways without reacting to characters.", "object-no-ground", null),
		VERTEX("Vertex", "Higher vertices move more.", "vertex", "Per vertex \u00b7 each point moves on its own"),
		VERTEX_WITH_HEMISPHERE_BLEND("Vertex \u00b7 smooth blend", "Blends by height and distance from center.", "vertex-hemisphere", null),
		VERTEX_JIGGLE("Vertex jiggle", "Vertices jiggle more higher up.", "vertex-jiggle", null);

		final WindDisplacement value = WindDisplacement.valueOf(name());
		final String title, description, icon, group;

		Mode(String title, String description, String icon, String group) {
			this.title = title;
			this.description = description;
			this.icon = "wind-" + icon;
			this.group = group;
		}
	}

	private static final Color MUTED = new Color(0x7D7D7D);
	private static final Color BORDER = new Color(0x3A3A3A);
	private static final Color BLUE = new Color(0x4772B3);
	private final JLabel icon = new JLabel("", SwingConstants.CENTER);
	private final JLabel title = label("", WorkspaceStyle.UI.deriveFont(12f), new Color(0xDDDDDD));
	private final JLabel enumName = label("", WorkspaceStyle.MONO.deriveFont(9f), MUTED);
	private Mode selected;
	private PopupLayer.Handle popup;
	private Consumer<WindDisplacement> onChange = value -> {};

	WindModeDropdown() {
		setLayout(new BorderLayout(7, 0));
		setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
		setContentAreaFilled(false);
		setRolloverEnabled(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setPreferredSize(new Dimension(210, 30));
		add(tile(icon, 22, new Color(0x262626)), BorderLayout.WEST);
		add(lines(title, enumName));
		add(new JLabel(WorkspaceStyle.icon("chevron-down", 10)), BorderLayout.EAST);
		addActionListener(event -> toggle());
		setSelected(WindDisplacement.DISABLED);
	}

	WindDisplacement getSelected() {
		return selected.value;
	}

	void setSelected(WindDisplacement value) {
		selected = Mode.valueOf(value.name());
		title.setText(selected.title);
		enumName.setText(value.name());
		icon.setIcon(modeIcon(selected, 16, 0x9FB8E0));
		setToolTipText(selected.title + " (" + value.name() + ")");
		getAccessibleContext().setAccessibleName("Wind mode: " + selected.title);
		repaint();
	}

	void onChange(Consumer<WindDisplacement> action) {
		onChange = action;
	}

	private void toggle() {
		if (popup != null) {
			popup.close();
			return;
		}
		var list = new OptionsList();
		list.setBackground(new Color(0x262626));
		for (Mode mode : Mode.values()) {
			if (mode.group != null) {
				var group = label(mode.group.toUpperCase(java.util.Locale.ROOT), WorkspaceStyle.SEMIBOLD.deriveFont(10f), MUTED);
				group.setBorder(BorderFactory.createEmptyBorder(3, 6, 1, 6));
				list.add(group);
			}
			list.add(option(mode));
		}
		var scroll = new EditorScrollPane(list);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(list.getBackground());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		popup = PopupLayer.show(this, scroll, 340, 0, Integer.MAX_VALUE);
		popup.onClose(() -> { popup = null; repaint(); });
		repaint();
	}

	private static final class OptionsList extends JPanel implements Scrollable {
		OptionsList() {
			super(new net.miginfocom.swing.MigLayout("insets 4, gapy 1, fillx, wrap 1, novisualpadding", "[0,grow,fill]"));
		}

		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
		@Override public int getScrollableUnitIncrement(Rectangle bounds, int axis, int direction) { return 20; }
		@Override public int getScrollableBlockIncrement(Rectangle bounds, int axis, int direction) {
			return Math.max(20, bounds.height - 20);
		}
	}

	private JButton option(Mode mode) {
		boolean current = mode == selected;
		var row = new JButton() {
			@Override protected void paintComponent(Graphics graphics) {
				paintBackground(graphics, this, current ? new Color(0x334D73)
					: getModel().isRollover() || isFocusOwner() ? new Color(0x333333) : null, null);
				super.paintComponent(graphics);
			}
		};
		row.setLayout(new BorderLayout(8, 0));
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		row.setContentAreaFilled(false);
		row.setRolloverEnabled(true);
		row.setCursor(getCursor());
		row.add(tile(new JLabel(modeIcon(mode, 16, current ? 0xCFE0FF : 0x8A8A8A), SwingConstants.CENTER),
			22, new Color(0x1C1C1C)), BorderLayout.WEST);
		var heading = label(mode.title, WorkspaceStyle.SEMIBOLD.deriveFont(12f), WorkspaceStyle.TEXT);
		var description = label(mode.description, WorkspaceStyle.UI.deriveFont(11f),
			current ? new Color(0xB9C9E6) : MUTED);
		row.add(lines(heading, description));
		row.getAccessibleContext().setAccessibleName(mode.title);
		row.getAccessibleContext().setAccessibleDescription(mode.name() + ". " + mode.description);
		row.addActionListener(event -> {
			if (popup == null)
				return;
			popup.close();
			requestFocusInWindow();
			if (mode != selected) {
				setSelected(mode.value);
				onChange.accept(mode.value);
			}
		});
		return row;
	}

	private static JPanel lines(JComponent top, JComponent bottom) {
		var lines = new JPanel(new BorderLayout());
		lines.setOpaque(false);
		lines.add(top, BorderLayout.NORTH);
		lines.add(bottom);
		return lines;
	}

	private static JLabel label(String text, Font font, Color color) {
		return EditorText.builder(text).font(font).color(color).build();
	}

	private static Icon modeIcon(Mode mode, int size, int tint) {
		ImageIcon source = WorkspaceStyle.icon(mode.icon, size);
		var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		var g = image.createGraphics();
		source.paintIcon(null, g, 0, 0);
		g.setComposite(AlphaComposite.SrcIn);
		g.setColor(new Color(tint));
		g.fillRect(0, 0, size, size);
		g.dispose();
		return new ImageIcon(image);
	}

	private static JPanel tile(JLabel icon, int size, Color color) {
		var tile = new JPanel(new GridBagLayout()) {
			@Override protected void paintComponent(Graphics graphics) {
				int y = (getHeight() - size) / 2;
				graphics.setColor(color);
				graphics.fillRoundRect(0, y, size, size, 4, 4);
			}
		};
		tile.setOpaque(false);
		tile.setPreferredSize(new Dimension(size, size));
		tile.add(icon);
		return tile;
	}

	@Override protected void paintComponent(Graphics graphics) {
		Color border = popup != null ? BLUE : getModel().isRollover() || isFocusOwner() ? new Color(0x555555) : BORDER;
		paintBackground(graphics, this, new Color(0x1F1F1F), border);
		super.paintComponent(graphics);
	}

	private static void paintBackground(Graphics graphics, JComponent component, Color fill, Color border) {
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if (fill != null) {
			g.setColor(fill);
			g.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 8, 8);
		}
		if (border != null) {
			g.setColor(border);
			g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 8, 8);
		}
		g.dispose();
	}
}
