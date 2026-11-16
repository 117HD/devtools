package rs117.hd.dev.overrides;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import rs117.hd.dev.editor.*;
import rs117.hd.dev.sceneloader.map.MapProjection;
import rs117.hd.dev.sceneloader.map.MapTileCache;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

public final class AreaListField extends JPanel {
	private static final MapTileCache MAP = new MapTileCache(() -> SwingUtilities.invokeLater(() -> {
		for (Window window : Window.getWindows())
			if (window.isShowing())
				window.repaint();
	}));

	private final boolean hidden;
	private final Consumer<AABB[]> update;
	private final JButton viewButton;
	private final JLabel emptyHint;
	private List<Area> choices = List.of();
	private List<AABB> assignedBounds = List.of();
	private LinkedHashSet<Area> selected = new LinkedHashSet<>();

	AreaListField(String label, boolean hidden, Consumer<AABB[]> update) {
		this.hidden = hidden;
		this.update = update;
		setOpaque(false);
		setLayout(new BorderLayout(0, 4));
		setName("field:" + label);
		setAlignmentX(LEFT_ALIGNMENT);
		var actions = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 4 0, fillx, hidemode 3", "[grow,right][]"));
		actions.setOpaque(false);
		emptyHint = EditorText.builder(hidden ? "Not hidden anywhere" : "Applies everywhere")
			.font(WorkspaceStyle.UI.deriveFont(11f)).color(WorkspaceStyle.DIM).build();
		actions.add(emptyHint, "cell 0 0, wmin 0");
		viewButton = EditorButton.builder().text("View").onClick(() -> openList(label)).build();
		viewButton.setVisible(false);
		actions.add(viewButton, "cell 0 0");
		actions.add(DashedAddButton.create("Add area", () -> open(choices, selected)), "cell 1 0");
		add(new PropertyRow(label, actions, PropertyRow.Layout.WIDE), BorderLayout.NORTH);
	}

	@Override public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	void setAreas(AABB[] bounds) {
		assignedBounds = bounds == null ? List.of() : Arrays.asList(bounds);
		var remaining = new LinkedHashSet<>(assignedBounds);
		var selected = new LinkedHashSet<Area>();
		var choices = new ArrayList<Area>();
		for (Area area : AreaManager.AREAS) {
			if (area == Area.ALL || area == Area.NONE || area.areaBounds == null || area.aabbs.length == 0)
				continue;
			choices.add(area);
			if (remaining.containsAll(Arrays.asList(area.aabbs))) {
				selected.add(area);
				for (AABB box : area.aabbs)
					remaining.remove(box);
			}
		}
		for (AABB box : remaining) {
			var area = new Area("Custom area");
			area.areaBounds = box;
			area.aabbs = new AABB[] { box };
			selected.add(area);
		}
		this.choices = choices;
		this.selected = selected;
		viewButton.setText("View - " + selected.size());
		viewButton.setVisible(!selected.isEmpty());
		emptyHint.setVisible(selected.isEmpty());
		revalidate();
		repaint();
	}

	private void open(List<Area> choices, LinkedHashSet<Area> selected) {
		var preview = new AreaPreview();
		if (!selected.isEmpty())
			preview.showArea(selected.iterator().next());
		else if (!choices.isEmpty())
			preview.showArea(choices.get(0));
		new SelectionModal<Area>(hidden ? "Add Hide in areas" : "Add Areas", choices, selected, area -> area.name)
			.badge("A", new Color(0x3F7A6A)).rowMeta(area -> size(area.areaBounds))
			.search("Search areas", area -> area.name).preview(preview, preview::showArea)
			.open(this, draft -> update.accept(draft.stream().flatMap(area -> Arrays.stream(area.aabbs))
				.distinct().toArray(AABB[]::new)));
	}

	private void openList(String label) {
		var original = new LinkedHashSet<>(selected);
		var originalBounds = new ArrayList<>(assignedBounds);
		new SelectionModal<Area>("View " + label, new ArrayList<>(original), original, area -> area.name)
			.badge("A", new Color(0x3F7A6A))
			.search("Search areas", area -> area.name + " " + from(area.areaBounds) + " " + to(area.areaBounds))
			.rows(68, "areas", AreaListField::renderAreaRow)
			.open(this, draft -> {
				for (Area area : original)
					if (!draft.contains(area))
						for (AABB box : area.aabbs)
							originalBounds.remove(box);
				update.accept(originalBounds.toArray(AABB[]::new));
			});
	}

	public static JComponent areaCard(Area area,
		java.util.function.Supplier<net.runelite.cache.region.Position> previewLocation, Consumer<Area> update) {
		var card = EditorPanels.stack();
		var map = new AreaMap(true);
		map.area = area.areaBounds == null ? null : area;
		map.setPreferredSize(new Dimension(188, 96));
		map.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
		map.setAlignmentX(LEFT_ALIGNMENT);
		card.add(map);
		var header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		var info = EditorPanels.stack();
		var name = text(area.name, 11);
		name.setAlignmentX(LEFT_ALIGNMENT);
		info.add(name);
		var detail = text(area.areaBounds == null ? "Applies nowhere until an area is set"
			: from(area.areaBounds) + " → " + to(area.areaBounds), 10);
		detail.setForeground(WorkspaceStyle.DIM);
		detail.setToolTipText(detail.getText());
		detail.setAlignmentX(LEFT_ALIGNMENT);
		info.add(detail);
		header.add(info);
		header.add(EditorButton.builder().text("Change").icon("map", 14).onClick(() -> chooseArea(card, area, previewLocation.get(), update)).build(), BorderLayout.EAST);
		card.add(header);
		return card;
	}

	private static void chooseArea(JComponent anchor, Area current, net.runelite.cache.region.Position location, Consumer<Area> update) {
		var choices = Arrays.stream(AreaManager.AREAS).filter(area -> area == Area.NONE || area == Area.ALL || area.areaBounds != null)
			.sorted(java.util.Comparator.comparing(area -> area.name, String.CASE_INSENSITIVE_ORDER)).collect(java.util.stream.Collectors.toList());
		var preview = new AreaPreview();
		preview.showArea(current);
		new SelectionModal<Area>("Choose area", choices, java.util.Set.of(current), area -> area.name)
			.singleChoice().badge("A", new Color(0x3F7A6A))
			.rowMeta(area -> area.areaBounds == null ? "" : size(area.areaBounds))
			.nearby(area -> location != null && area != Area.ALL && area.containsPoint(location.getX(), location.getY(), location.getZ())
				? "You are here" : null)
			.search("Search areas", area -> area.name).preview(preview, preview::showArea)
			.open(anchor, picked -> picked.stream().findFirst().ifPresent(update));
	}

	private static JComponent renderAreaRow(Area area) {
		var row = new JPanel(new BorderLayout(12, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		var thumbnail = new AreaMap(false);
		thumbnail.area = area;
		thumbnail.setPreferredSize(new Dimension(84, 56));
		row.add(thumbnail, BorderLayout.WEST);

		var details = new JPanel(new GridLayout(3, 1));
		details.setOpaque(false);
		details.add(text(area.name, 12));
		details.add(text(from(area.areaBounds) + " → " + to(area.areaBounds), 10));
		details.add(text(size(area.areaBounds), 10));
		row.add(details);
		return row;
	}

	private static final class AreaPreview extends JPanel {
		private final JLabel name = text("", 13);
		private final JTextArea detail = new JTextArea(4, 20);
		private final AreaMap map = new AreaMap(true);

		AreaPreview() {
			super(new BorderLayout(0, 8));
			setBackground(new Color(0x1F1F1F));
			setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
			detail.setFont(WorkspaceStyle.MONO.deriveFont(11f));
			detail.setForeground(WorkspaceStyle.TEXT);
			detail.setOpaque(false);
			detail.setEditable(false);
			add(name, BorderLayout.NORTH);
			add(map);
			add(detail, BorderLayout.SOUTH);
		}

		void showArea(Area area) {
			var box = area.areaBounds;
			name.setText(area.name);
			map.area = box == null ? null : area;
			map.repaint();
			if (box == null) {
				detail.setText(area == Area.ALL ? "Entire world" : "No area");
				return;
			}
			detail.setText("From     " + from(box) + "\nTo       " + to(box) + "\nSize     " + AreaListField.size(box)
				+ "\nRegions  " + regions(box));
		}
	}

	private static JLabel text(String value, float size) {
		return EditorText.builder(value).font(WorkspaceStyle.MONO.deriveFont(size)).build();
	}

	private static String from(AABB b) { return b.minX + ", " + b.minY + (b.hasZ() ? ", " + b.minZ : ""); }
	private static String to(AABB b) { return b.maxX + ", " + b.maxY + (b.hasZ() ? ", " + b.maxZ : ""); }
	private static String size(AABB b) { return (1L + b.maxX - b.minX) + " × " + (1L + b.maxY - b.minY) + " tiles"; }

	private static String regions(AABB b) {
		int x1 = b.minX >> 6, y1 = b.minY >> 6, x2 = b.maxX >> 6, y2 = b.maxY >> 6;
		long total = (1L + x2 - x1) * (1L + y2 - y1);
		var regions = new ArrayList<String>();
		for (int x = x1; x <= x2 && regions.size() < 6; x++)
			for (int y = y1; y <= y2 && regions.size() < 6; y++)
				regions.add(Integer.toString(x << 8 | y));
		return String.join(", ", regions) + (total > regions.size() ? " +" + (total - regions.size()) : "");
	}

	private static final class AreaMap extends JComponent {
		private final boolean large;
		private Area area;

		AreaMap(boolean large) { this.large = large; }

		@Override protected void paintComponent(Graphics graphics) {
			if (getWidth() == 0 || getHeight() == 0)
				return;
			if (area == null) {
				graphics.setColor(new Color(0x24282A));
				graphics.fillRect(0, 0, getWidth(), getHeight());
				graphics.setColor(new Color(0x303638));
				for (int x = 0; x < getWidth(); x += 12)
					graphics.drawLine(x, 0, x, getHeight());
				for (int y = 0; y < getHeight(); y += 12)
					graphics.drawLine(0, y, getWidth(), y);
				return;
			}
			var box = area.areaBounds;
			var g = (Graphics2D) graphics.create();
			boolean underground = box.minY >= 4000;
			g.setColor(underground ? new Color(0x2A2420) : new Color(0x24282A));
			g.fillRect(0, 0, getWidth(), getHeight());
			double padding = large ? 1.9 : 2.4;
			double scale = Math.min(getWidth() / Math.max(48, (1.0 + box.maxX - box.minX) * padding),
				getHeight() / Math.max(32, (1.0 + box.maxY - box.minY) * padding));
			double centerX = (box.minX + (double) box.maxX + 1) / 2;
			double centerY = (box.minY + (double) box.maxY + 1) / 2;
			double left = centerX - getWidth() / (2 * scale);
			double top = centerY + getHeight() / (2 * scale);
			drawTiles(g, box.hasZ() ? box.minZ : 0, left, top, scale);
			for (AABB bounds : area.aabbs) {
				int x = (int) Math.round((bounds.minX - left) * scale);
				int y = (int) Math.round((top - bounds.maxY - 1) * scale);
				int w = Math.max(1, (int) Math.round((1.0 + bounds.maxX - bounds.minX) * scale));
				int h = Math.max(1, (int) Math.round((1.0 + bounds.maxY - bounds.minY) * scale));
				g.setColor(new Color(255, 179, 71, 56));
				g.fillRect(x, y, w, h);
				g.setColor(new Color(0xFFB347));
				g.drawRect(x, y, w, h);
			}
			if (large) {
				g.setColor(Color.WHITE);
				g.setFont(WorkspaceStyle.MONO.deriveFont(10f));
				g.drawString("N ↑", getWidth() - 25, 15);
				g.drawString(AreaListField.size(box) + (underground ? " · underground" : ""), 7, getHeight() - 8);
			}
			g.dispose();
		}

		private void drawTiles(Graphics2D g, int plane, double left, double top, double scale) {
			int zoom = MapProjection.MIN_ZOOM;
			while (zoom < MapProjection.MAX_ZOOM && MapProjection.WORLD_TILE_PX / MapProjection.scale(zoom) < scale)
				zoom++;
			int span = MapProjection.scale(zoom) * MapProjection.TILE_SIZE;
			double px = MapProjection.pixelX(0) + left * MapProjection.WORLD_TILE_PX;
			double py = MapProjection.pixelY(0) - top * MapProjection.WORLD_TILE_PX;
			double ratio = scale / MapProjection.WORLD_TILE_PX;
			int firstX = Math.max(0, MapProjection.tileIndex(px, zoom));
			int firstY = Math.max(0, MapProjection.tileIndex(py, zoom));
			int lastX = Math.min((1 << zoom) - 1, MapProjection.tileIndex(px + getWidth() / ratio, zoom));
			int lastY = Math.min((1 << zoom) - 1, MapProjection.tileIndex(py + getHeight() / ratio, zoom));
			for (int y = firstY; y <= lastY; y++)
				for (int x = firstX; x <= lastX; x++) {
					var image = MAP.get(plane, zoom, x, MapProjection.flipTileY(y, zoom));
					if (image != null)
						g.drawImage(image, (int) Math.round((x * span - px) * ratio), (int) Math.round((y * span - py) * ratio),
							(int) Math.ceil(span * ratio), (int) Math.ceil(span * ratio), this);
				}
		}
	}
}
