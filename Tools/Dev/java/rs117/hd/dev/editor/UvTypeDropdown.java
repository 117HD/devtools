package rs117.hd.dev.editor;

import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import rs117.hd.scene.model_overrides.UvType;

public final class UvTypeDropdown extends JButton {
	private static final String[][] GROUPS = {
		{"Keep", "VANILLA", "GEOMETRY"},
		{"Model space", "MODEL_XY", "MODEL_XZ", "MODEL_YZ"},
		{"World space", "WORLD_XY", "WORLD_XZ", "WORLD_YZ"},
		{"Multi-plane", "BOX"}
	};
	private static final List<String> DESCRIPTIONS = List.of("Keep the model’s own UVs", "Derived from each triangle",
		"Front · along model Z", "Top · along model Y", "Side · along model X", "Front · fixed when the model rotates",
		"Top · ground-aligned, tiles seamlessly", "Side · fixed when the model rotates", "Nearest of 6 sides, per face");

	private static final Color SELECTED = new Color(0x334D73);
	private static final Color HOVER = new Color(0x333333);
	private UvType value;
	private PopupLayer.Handle popup;
	private Consumer<UvType> change = next -> {};

	public UvTypeDropdown(UvType initial, boolean compact) {
		setFont(WorkspaceStyle.MONO.deriveFont(11f));
		setForeground(WorkspaceStyle.TEXT);
		setBackground(WorkspaceStyle.INPUT);
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true),
			BorderFactory.createEmptyBorder(0, 4, 0, 6)));
		setHorizontalAlignment(LEFT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setPreferredSize(new Dimension(compact ? 130 : 220, compact ? 26 : 30));
		setValue(initial);
		addActionListener(e -> togglePopup());
	}

	public void setValue(UvType next) {
		value = next == null ? UvType.VANILLA : next;
		setIcon(diagram(value, 24));
		setText(value.name() + "  ▾");
		setToolTipText(value.name());
	}

	public void onChange(Consumer<UvType> listener) { change = listener; }

	private void togglePopup() {
		if (popup != null && !popup.isClosed()) {
			popup.close();
			return;
		}

		var content = new JPanel();
		content.setBackground(WorkspaceStyle.MENU);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		Consumer<UvType> pick = next -> {
			popup.close();
			setValue(next);
			change.accept(next);
		};
		int description = 0;
		for (String[] group : GROUPS) {
			var heading = EditorText.builder(group[0].toUpperCase(java.util.Locale.ROOT))
				.color(WorkspaceStyle.DIM).font(WorkspaceStyle.UI.deriveFont(10f))
				.border(BorderFactory.createEmptyBorder(6, 8, 4, 0)).build();
			heading.setAlignmentX(LEFT_ALIGNMENT);
			content.add(heading);
			for (int i = 1; i < group.length; i++) {
				UvType type = UvType.valueOf(group[i]);
				boolean selected = value == type || value.name().startsWith(type.name() + "_MIRROR_");
				var row = new JPanel(new BorderLayout(6, 0)) {
					@Override protected void paintComponent(Graphics graphics) {
						var g = (Graphics2D) graphics.create();
						g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
						g.setColor(selected ? SELECTED : HOVER);
						if (selected || getMousePosition(true) != null)
							g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
						g.dispose();
					}
				};
				row.setOpaque(false);
				row.setAlignmentX(LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
				row.setPreferredSize(new Dimension(300, 40));
				var hover = new java.awt.event.MouseAdapter() {
					@Override public void mouseEntered(java.awt.event.MouseEvent event) { row.repaint(); }
					@Override public void mouseExited(java.awt.event.MouseEvent event) { row.repaint(); }
				};
				row.addMouseListener(hover);

				var button = new JButton();
				button.setLayout(new BorderLayout(8, 0));
				button.setContentAreaFilled(false);
				button.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
				button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				button.getAccessibleContext().setAccessibleName(type.name());
				button.addMouseListener(hover);
				button.addActionListener(e -> pick.accept(type));
				var icon = new JLabel(diagram(type, 24), SwingConstants.CENTER);
				icon.setBackground(new Color(0x1C1C1C));
				icon.setOpaque(true);
				icon.setPreferredSize(new Dimension(28, 28));
				var iconSlot = new JPanel(new GridBagLayout());
				iconSlot.setOpaque(false);
				iconSlot.add(icon);
				button.add(iconSlot, BorderLayout.WEST);
				var labels = new JPanel();
				labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
				labels.setOpaque(false);
				labels.add(Box.createVerticalGlue());
				labels.add(EditorText.builder(type.name()).font((selected ? WorkspaceStyle.MONO_SEMIBOLD : WorkspaceStyle.MONO).deriveFont(11f))
					.color(selected ? Color.WHITE : WorkspaceStyle.TEXT).build());
				labels.add(EditorText.builder(DESCRIPTIONS.get(description++)).font(WorkspaceStyle.UI.deriveFont(10f))
					.color(selected ? new Color(0xB9C9E6) : new Color(0x7D7D7D)).build());
				labels.add(Box.createVerticalGlue());
				button.add(labels);
				row.add(button);

				if (type.name().startsWith("MODEL_")) {
					var mirror = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 9));
					mirror.setOpaque(false);
					mirror.addMouseListener(hover);
					mirror.add(EditorText.builder("Mirror").font(WorkspaceStyle.UI.deriveFont(10f))
						.color(new Color(0x8A8A8A)).build());
					var track = new JPanel(new GridLayout(1, 3));
					track.setBackground(new Color(0x1A1A1A));
					track.setBorder(BorderFactory.createLineBorder(new Color(0x3A3A3A), 1, true));
					track.setPreferredSize(new Dimension(90, 22));
					track.addMouseListener(hover);
					for (String option : List.of("Off", "A", "B")) {
						var variant = option.equals("Off") ? type : UvType.valueOf(type.name() + "_MIRROR_" + option);
						var segment = EditorButton.builder().text(option)
							.style(value == variant ? WorkspaceStyle.Button.PRIMARY : WorkspaceStyle.Button.TAB)
							.onClick(() -> pick.accept(variant)).build();
						segment.setMargin(new Insets(0, 0, 0, 0));
						segment.setBorder(BorderFactory.createEmptyBorder());
						segment.setFont((value == variant ? WorkspaceStyle.MONO_SEMIBOLD : WorkspaceStyle.MONO).deriveFont(10f));
						segment.addMouseListener(hover);
						track.add(segment);
					}
					mirror.add(track);
					row.add(mirror, BorderLayout.EAST);
				}
				content.add(row);
			}
		}
		var scroll = new EditorScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		popup = PopupLayer.show(this, scroll, 330, 420);
	}

	private static Icon diagram(UvType type, int size) {
		return WorkspaceStyle.icon("uv/" + type.name(), size);
	}
}
