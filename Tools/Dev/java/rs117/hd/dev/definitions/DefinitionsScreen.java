package rs117.hd.dev.definitions;

import com.google.gson.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import rs117.hd.dev.editor.*;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

public final class DefinitionsScreen extends JPanel {
	private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private enum Type {
		WATER("Water types", "water_types.json", "rlhd.water-types-path"),
		GROUND("Ground materials", "ground_materials.json", "rlhd.ground-materials-path");

		final String label, file, property;
		Type(String label, String file, String property) {
			this.label = label;
			this.file = file;
			this.property = property;
		}
	}

	private static final class Document {
		final ResourcePath resource;
		final List<JsonObject> entries = new ArrayList<>();
		final Map<JsonObject, String> originalNames = new IdentityHashMap<>();
		final Map<JsonObject, JsonObject> resetBaselines = new IdentityHashMap<>();
		final Set<JsonObject> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		JsonObject selected;
		String source;
		boolean changed;

		Document(Type type) throws IOException {
			resource = Props.getFile(type.property, () -> ResourcePath.path(rs117.hd.scene.WaterTypeManager.class, type.file));
			try (var reader = resource.toReader()) {
				var text = new StringBuilder();
				char[] buffer = new char[4096];
				for (int count; (count = reader.read(buffer)) != -1;)
					text.append(buffer, 0, count);
				source = text.toString();
			}
			for (var element : new JsonParser().parse(source).getAsJsonArray()) {
				var entry = element.getAsJsonObject();
				entries.add(entry);
				originalNames.put(entry, name(entry));
				resetBaselines.put(entry, copyEntry(entry));
			}
			selected = entries.isEmpty() ? null : entries.get(0);
		}
	}

	private final Map<Type, Document> documents = new EnumMap<>(Type.class);
	private final DefaultListModel<JsonObject> rows = new DefaultListModel<>();
	private final JList<JsonObject> list = new JList<>(rows);
	private final JPanel filters = new JPanel(new BorderLayout());
	private final JPanel properties = EditorPanels.propertyStack();
	private final JPanel selectionHeader = new JPanel(new BorderLayout(10, 0));
	private final JLabel selectionName = EditorText.builder("").font(WorkspaceStyle.MONO_SEMIBOLD).build();
	private final JLabel selectionMeta = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();
	private final JLabel selectionChip = new JLabel();
	private final Map<JsonObject, String> groupHeaders = new IdentityHashMap<>();
	private static final List<String> OCEAN_FAMILIES = List.of("STORMY", "SUNBAKED", "PROFANE", "CURSED",
		"SCALDING", "ICY", "ENDLESS", "CRYSTAL", "FETID", "TANGLED", "JAGGED");
	private final List<Runnable> fieldUpdates = new ArrayList<>();
	private final JLabel count = EditorText.builder("").font(WorkspaceStyle.MONO).build();
	private final JLabel status = EditorText.builder("").build();
	private final javax.swing.Timer filterRefresh = new javax.swing.Timer(150, event -> refreshRows());
	private JsonObject displayedGround;
	private String displayedGroundMaterials;
	private boolean displayedGroundSingle;
	private JLabel groundHeading;
	private final JPanel groundFieldsHost = new JPanel(new BorderLayout());

	private void scheduleFilterRefresh() {
		filterRefresh.setRepeats(false);
		filterRefresh.restart();
	}

	private final JPanel groundPreview = EditorPanels.stack();
	private final CardLayout previewLayout = new CardLayout();
	private final JPanel previewCards = new JPanel(previewLayout);
	private final JLabel groundName = EditorText.builder("").font(WorkspaceStyle.MONO).color(WorkspaceStyle.DIM).build();
	private final JLabel previewTitle = EditorText.builder("").font(WorkspaceStyle.MONO_SEMIBOLD).build();
	private final JTextArea json = new JTextArea();
	private PropertySection jsonSection;
	private final JButton create = EditorButton.builder().text("New").icon("plus", 12).onClick(this::create).build();
	private final JButton save = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY).icon("save", 12).onClick(this::saveCurrent).build();
	private final JPanel preview = new JPanel() {
		@Override protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			paintSwatch((Graphics2D) graphics);
		}
	};
	private volatile WaterRequest waterPreview;
	private volatile boolean showWaterObjects, isolateWater;
	private final JLabel waterLocation = EditorText.builder("").font(WorkspaceStyle.MONO).color(WorkspaceStyle.DIM).build();

	static final class WaterRequest {
		final String json, originalName;
		WaterRequest(String json, String originalName) {
			this.json = json;
			this.originalName = originalName;
		}
	}

	boolean isolateWater() { return isolateWater; }

	void showWaterLocation(WaterRequest request, String location) {
		if (waterPreviewRequest() == request)
			waterLocation.setText(location);
	}
	private volatile boolean waterPreviewVisible;
	private final EditorPreview waterViewport = EditorPreview.builder().pan(.8, .4, 5)
		.location(1569, 2799, 0).objects(() -> showWaterObjects && !isolateWater).build();

	EditorPreview waterViewport() { return waterViewport; }
	private void updateWaterCursor() {
		preview.setCursor(waterPreview == null ? Cursor.getDefaultCursor() : WorkspaceStyle.cursor("grab"));
	}

	private Image waterFrame;
	private String waterPreviewMessage;

	WaterRequest waterPreviewRequest() { return waterPreviewVisible ? waterPreview : null; }

	public void setPreviewActive(boolean active) {
		if (waterPreviewVisible == active)
			return;
		if (active && waterPreview != null)
			waterPreview = new WaterRequest(waterPreview.json, waterPreview.originalName);
		waterPreviewVisible = active;
		if (!active) {
			waterFrame = null;
			waterPreviewMessage = null;
			refreshGroundPreview(null);
		} else if (type == Type.GROUND && document() != null) {
			refreshGroundPreview(document().selected);
		}
		preview.repaint();
	}

	public void showWaterFrame(WaterRequest request, Image image, String message) {
		if (!Objects.equals(request, waterPreviewRequest()))
			return;
		waterFrame = image;
		waterPreviewMessage = message;
		preview.repaint();
	}

	private Type type = Type.WATER;
	private String query = "";
	private boolean refreshing;

	public DefinitionsScreen() {
		super(new BorderLayout());
		setBackground(WorkspaceStyle.GAP);
		var head = new JPanel(new BorderLayout(8, 0));
		head.setBackground(WorkspaceStyle.HEADER);
		head.setBorder(new EmptyBorder(0, 10, 0, 8));
		head.setPreferredSize(new Dimension(0, 30));
		var title = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 7));
		title.setOpaque(false);
		title.add(EditorText.builder("Definitions").font(WorkspaceStyle.SEMIBOLD).build());
		title.add(count);
		head.add(title);
		head.add(EditorPanels.centered(create), BorderLayout.EAST);
		var left = new JPanel(new BorderLayout());
		left.setBackground(WorkspaceStyle.PANEL);
		var controls = new JPanel(new BorderLayout());
		controls.setOpaque(false);
		controls.add(head, BorderLayout.NORTH);
		var modes = EditorButtonGroup.builder(List.of(Type.values())).label(value -> value.label)
			.onChange(this::selectType).track().build();
		modes.button(Type.WATER).setIcon(WorkspaceStyle.icon("water-drop", 12));
		modes.button(Type.GROUND).setIcon(WorkspaceStyle.icon("ground-layers", 12));
		modes.setLayout(new GridLayout(1, 2));
		modes.setBorder(new EmptyBorder(3, 2, 3, 2));
		var toolbar = new JPanel(new BorderLayout());
		toolbar.setOpaque(false);
		toolbar.setBorder(new EmptyBorder(6, 5, 3, 5));
		toolbar.add(modes);
		controls.add(toolbar, BorderLayout.CENTER);
		filters.setBorder(new EmptyBorder(0, 5, 8, 8));
		filters.setOpaque(false);
		controls.add(filters, BorderLayout.SOUTH);
		left.add(controls, BorderLayout.NORTH);
		left.add(scroll(list));
		left.setMinimumSize(new Dimension(275, 0));
		left.setPreferredSize(new Dimension(310, 0));
		list.setBackground(WorkspaceStyle.PANEL);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new DefinitionRowRenderer());
		list.addListSelectionListener(event -> {
			if (!refreshing && !event.getValueIsAdjusting() && list.getSelectedValue() != null) {
				document().selected = list.getSelectedValue();
				showProperties();
			}
		});

		var center = new JPanel(new BorderLayout());
		center.setBackground(WorkspaceStyle.PANEL);
		center.setMinimumSize(new Dimension(280, 0));
		var previewControls = EditorPanels.stack();
		previewControls.add(header("Preview"));
		var previewBar = new JPanel(new BorderLayout());
		previewBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		previewBar.setBackground(WorkspaceStyle.BAR);
		previewBar.setBorder(new EmptyBorder(5, 8, 5, 8));
		previewBar.add(waterLocation, BorderLayout.WEST);
		addPropertyChangeListener("waterPreview", event -> waterLocation.setText(Boolean.TRUE.equals(event.getNewValue())
			? "Finding water…" : "Material swatch"));
		previewControls.add(previewBar);
		var resetCamera = EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("frame-all", 12)
			.tooltip("Reset view · Drag to pan, scroll to zoom").onClick(() -> waterViewport.camera().reset()).build();
		var objects = EditorButton.builder().style(WorkspaceStyle.Button.SCENE_TOGGLE)
			.icon("scene-layers", 12).text("Show objects").buildToggle(false);
		objects.addActionListener(event -> showWaterObjects = objects.isSelected());
		var isolate = EditorButton.builder().style(WorkspaceStyle.Button.SCENE_TOGGLE)
			.icon("scene-isolated", 12).text("Isolate").tooltip("Only show tiles that use this water type").buildToggle(false);
		isolate.addActionListener(event -> isolateWater = isolate.isSelected());
		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setOpaque(false);
		actions.add(objects);
		actions.add(isolate);
		actions.add(resetCamera);
		previewBar.add(actions, BorderLayout.EAST);
		addPropertyChangeListener("waterPreview", event -> actions.setVisible(Boolean.TRUE.equals(event.getNewValue())));
		var waterPanel = new JPanel(new BorderLayout());
		waterPanel.add(previewControls, BorderLayout.NORTH);
		preview.setBackground(new Color(0x2A2A2A));
		preview.setLayout(new BorderLayout());
		previewTitle.setBorder(new EmptyBorder(5, 8, 5, 8));
		var caption = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
		caption.setOpaque(false);
		previewTitle.setBackground(new Color(28, 31, 22, 220));
		previewTitle.setOpaque(true);
		caption.add(previewTitle);
		preview.add(caption, BorderLayout.SOUTH);
		waterViewport.camera().installControls(preview, () -> waterPreview != null, caption, previewTitle);
		waterPanel.add(preview);
		previewCards.add(waterPanel, Type.WATER.name());
		var groundPanel = new JPanel(new BorderLayout());
		groundPanel.setBackground(new Color(0x1F1F1F));
		var groundHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		groundHeader.setBackground(WorkspaceStyle.HEADER);
		var materialsTitle = EditorText.builder("Materials").font(WorkspaceStyle.SEMIBOLD).build();
		materialsTitle.setIcon(WorkspaceStyle.badge("", new Color(0x78AD54)));
		groundHeader.add(materialsTitle);
		groundHeader.add(groundName);
		groundPanel.add(groundHeader, BorderLayout.NORTH);
		var groundScroll = scroll(new PropertiesView(groundPreview));
		groundScroll.getViewport().setBackground(groundPanel.getBackground());
		groundPreview.setBorder(new EmptyBorder(15, 16, 12, 16));
		groundPanel.add(groundScroll);
		previewCards.add(groundPanel, Type.GROUND.name());
		center.add(previewCards);

		var right = new JPanel(new BorderLayout());
		right.setBackground(WorkspaceStyle.INPUT);
		right.setMinimumSize(new Dimension(320, 0));
		right.setPreferredSize(new Dimension(385, 0));
		selectionHeader.setBackground(WorkspaceStyle.HEADER);
		selectionHeader.setBorder(new EmptyBorder(6, 14, 6, 6));
		selectionHeader.setPreferredSize(new Dimension(0, 46));
		right.add(selectionHeader, BorderLayout.NORTH);
		var propertyScroll = scroll(properties);
		propertyScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.MENU));
		propertyScroll.getViewport().setBackground(properties.getBackground());
		right.add(propertyScroll);
		var footer = new JPanel(new BorderLayout(6, 6));
		footer.setBackground(WorkspaceStyle.BAR);
		footer.setBorder(new EmptyBorder(8, 14, 10, 14));
		save.setPreferredSize(new Dimension(0, 30));
		footer.add(status);
		footer.add(save, BorderLayout.SOUTH);
		right.add(footer, BorderLayout.SOUTH);
		var centerRight = split(center, right, 1);
		add(split(left, centerRight, 0));
		selectType(type);
	}

	private static final class PropertiesView extends JPanel implements Scrollable {
		PropertiesView(JComponent content) {
			super(new BorderLayout());
			setOpaque(false);
			add(content, BorderLayout.NORTH);
		}

		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
		@Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
		@Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(24, visible.height - 24);
		}
	}

	private static JSplitPane split(Component left, Component right, double weight) {
		var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setBorder(null);
		split.setDividerSize(2);
		if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI)
			((javax.swing.plaf.basic.BasicSplitPaneUI) split.getUI()).getDivider().setBackground(WorkspaceStyle.GAP);
		split.setContinuousLayout(true);
		split.setResizeWeight(weight);
		split.resetToPreferredSizes();
		return split;
	}

	private static JComponent header(String text) {
		var header = EditorText.builder(text).font(WorkspaceStyle.SEMIBOLD).border(new EmptyBorder(0, 12, 0, 12)).build();
		header.setBackground(WorkspaceStyle.HEADER);
		header.setOpaque(true);
		header.setHorizontalAlignment(SwingConstants.LEFT);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		header.setPreferredSize(new Dimension(0, 30));
		return header;
	}

	private static JScrollPane scroll(Component content) {
		var scroll = new EditorScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		return scroll;
	}

	private Document document() { return documents.get(type); }

	private static String name(JsonObject item) { return item.get("name").getAsString(); }

	private void selectType(Type selected) {
		type = selected;
		query = "";
		filters.removeAll();
		var search = new JPanel(new BorderLayout());
		search.setOpaque(false);
		search.add(EditorSearch.builder(type == Type.WATER ? "Search water types" : "Search by name or material")
			.height(26).onChange(text -> { query = text.toLowerCase(Locale.ROOT).strip(); scheduleFilterRefresh(); }).build());
		filters.add(search);
		filters.revalidate();
		if (document() == null) {
			try {
				documents.put(type, new Document(type));
			} catch (IOException | RuntimeException e) {
				status.setText(e.getMessage());
			}
		}
		create.setEnabled(document() != null);
		save.setText("Save " + type.label.toLowerCase(Locale.ROOT));
		save.setEnabled(document() != null && document().resource.isFileSystemResource());
		refreshRows();
		showProperties();
	}

	private void refreshRows() {
		filterRefresh.stop();
		var doc = document();
		refreshing = true;
		try {
			rows.clear();
			groupHeaders.clear();
			if (doc != null) {
				var groups = new LinkedHashMap<String, List<JsonObject>>();
				for (var item : doc.entries) {
					String text = name(item);
					if (type == Type.GROUND && item.has("materials"))
						text += " " + item.get("materials");
					if (text.toLowerCase(Locale.ROOT).contains(query))
						groups.computeIfAbsent(group(item), key -> new ArrayList<>()).add(item);
				}
				groups.forEach((group, items) -> {
					groupHeaders.put(items.get(0), group + "  " + items.size());
					items.forEach(rows::addElement);
				});
				list.setSelectedValue(doc.selected, false);
			}
			count.setText(Integer.toString(rows.size()));
		} finally {
			refreshing = false;
		}
	}

	private String group(JsonObject item) {
		if (type == Type.GROUND)
			return materials(item).size() <= 1 ? "SINGLE MATERIAL" : "MIXED";
		String name = name(item);
		for (String family : OCEAN_FAMILIES)
			if (name.startsWith("WATER_" + family))
				return "OCEAN · " + family;
		return Set.of("WATER_OPEN", "WATER_SHELF", "WATER_DROPOFF", "WATER_DEEP",
			"WATER_TRENCH", "WATER_OUT_OF_BOUNDS").contains(name) ? "OCEAN · DEFAULT" : "SPECIAL";
	}

	private Icon chip(JsonObject item) {
		var colors = new ArrayList<Color>();
		var textures = new ArrayList<Image>();
		if (type == Type.WATER) {
			var surface = color(item.get("surfaceColor"), waterColorDefault("surfaceColor"));
			colors.add(surface);
			colors.add(color(item.get("depthColor"), surface));
		} else {
			var names = new LinkedHashSet<String>();
			for (var material : materials(item)) {
				String name = material.getAsString();
				if (names.add(name)) {
					textures.add(materialTexture(name, 32, list));
					colors.add(groundColor(name));
				}
				if (names.size() == 4)
					break;
			}
			if (colors.isEmpty())
				colors.add(WorkspaceStyle.DIM);
		}
		return new Icon() {
			@Override public int getIconWidth() { return 28; }
			@Override public int getIconHeight() { return 14; }
			@Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
				var g = (Graphics2D) graphics.create();
				try {
					g.clip(new java.awt.geom.RoundRectangle2D.Float(x, y, getIconWidth(), getIconHeight(), 4, 4));
					for (int i = 0; i < colors.size(); i++) {
						int start = i * getIconWidth() / colors.size();
						g.setColor(colors.get(i));
						int width = (i + 1) * getIconWidth() / colors.size() - start;
						g.fillRect(x + start, y, width, getIconHeight());
						if (i < textures.size() && textures.get(i) != null)
							g.drawImage(textures.get(i), x + start, y, width, getIconHeight(), component);
					}
				} finally {
					g.dispose();
				}
			}
		};
	}

	private static final Color ROW_SELECTED_BACKGROUND = new Color(0x334D73);
	private static final Color ROW_DIRTY_COLOR = new Color(0xE8A33D);

	/** Swing calls this for every visible row on every list repaint; reusing one instance and updating
	 * its fields avoids rebuilding a whole component per row on every scroll/resize/repaint. */
	private final class DefinitionRowRenderer extends JComponent implements ListCellRenderer<JsonObject> {
		private String group;
		private int headingHeight;
		private String tag;
		private boolean dirty;
		private boolean selected;
		private Icon swatch;
		private String name;

		@Override public Dimension getPreferredSize() { return new Dimension(245, 28 + headingHeight); }

		@Override
		public Component getListCellRendererComponent(
			JList<? extends JsonObject> list, JsonObject item, int index, boolean selected, boolean cellHasFocus
		) {
			group = groupHeaders.get(item);
			headingHeight = group == null ? 0 : 20;
			tag = type == Type.GROUND ? materials(item).size() + "x"
				: (item.has("flat") && item.get("flat").getAsBoolean() ? "FLAT " : "") + (item.has("material") ? "MAT" : "");
			dirty = document().dirty.contains(item);
			this.selected = selected;
			swatch = chip(item);
			name = name(item);
			return this;
		}

		@Override protected void paintComponent(Graphics graphics) {
			var g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setColor(WorkspaceStyle.PANEL);
				g.fillRect(0, 0, getWidth(), getHeight());
				if (group != null) {
					g.setFont(WorkspaceStyle.MONO.deriveFont(10f));
					g.setColor(WorkspaceStyle.DIM);
					g.drawString(group, 6, 15);
				}
				if (selected) {
					g.setColor(ROW_SELECTED_BACKGROUND);
					g.fillRect(0, headingHeight, getWidth(), 28);
				}
				swatch.paintIcon(this, g, 6, headingHeight + 7);
				g.setFont(WorkspaceStyle.MONO.deriveFont(10f));
				String meta = tag + (dirty ? "  *" : "");
				int metaX = getWidth() - 8 - g.getFontMetrics().stringWidth(meta);
				g.setColor(dirty ? ROW_DIRTY_COLOR : WorkspaceStyle.DIM);
				g.drawString(meta, metaX, headingHeight + 18);
				g.clipRect(42, headingHeight, Math.max(0, metaX - 48), 28);
				g.setColor(WorkspaceStyle.TEXT);
				g.drawString(name, 42, headingHeight + 18);
			} finally {
				g.dispose();
			}
		}
	}

	private static JsonObject copyEntry(JsonObject item) {
		return new JsonParser().parse(item.toString()).getAsJsonObject();
	}

	private void create() {
		var doc = document();
		var item = new JsonObject();
		item.addProperty("name", unique(type == Type.WATER ? "NEW_WATER" : "NEW_GROUND"));
		if (type == Type.WATER) {
			item.addProperty("surfaceColor", "#69809C");
			item.addProperty("fresnelAmount", .85);
		} else {
			var materials = new JsonArray();
			materials.add(new JsonPrimitive("DIRT_1"));
			item.add("materials", materials);
		}
		doc.entries.add(0, item);
		doc.resetBaselines.put(item, copyEntry(item));
		doc.selected = item;
		changed(item);
		showProperties();
	}

	private String unique(String prefix) {
		var names = new HashSet<String>();
		document().entries.forEach(item -> names.add(name(item)));
		if (!names.contains(prefix) && prefix.endsWith("_COPY"))
			return prefix;
		int suffix = 1;
		while (names.contains(prefix + "_" + suffix))
			suffix++;
		return prefix + "_" + suffix;
	}

	private void showProperties() {
		properties.removeAll();
		jsonSection = null;
		selectionHeader.removeAll();
		fieldUpdates.clear();
		var doc = document();
		if (doc == null || doc.selected == null) {
			updateStatus();
			properties.revalidate();
			properties.repaint();
			return;
		}
		var item = doc.selected;
		var general = EditorPanels.stack();
		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		actions.setOpaque(false);
		actions.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).tooltip("Duplicate").size(28, 26).icon("copy", 12).onClick(() -> {
			var copy = copyEntry(item);
			copy.addProperty("name", unique(name(item) + "_COPY"));
			doc.entries.add(doc.entries.indexOf(item) + 1, copy);
			doc.resetBaselines.put(copy, copyEntry(copy));
			doc.selected = copy;
			changed(copy);
			showProperties();
		}).build());
		actions.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).tooltip("Delete").size(28, 26).icon("trash", 12).onClick(() -> {
			doc.entries.remove(item);
			doc.resetBaselines.remove(item);
			doc.dirty.remove(item);
			doc.selected = doc.entries.isEmpty() ? null : doc.entries.get(0);
			changed(null);
			showProperties();
		}).build());
		var heading = EditorPanels.stack();
		heading.add(selectionName);
		heading.add(selectionMeta);
		selectionHeader.add(selectionChip, BorderLayout.WEST);
		selectionHeader.add(heading);
		selectionHeader.add(EditorPanels.centered(actions), BorderLayout.EAST);
		var name = EditorText.propertyField(name(item));
		var nameRow = new PropertyRow("Name", name, PropertyRow.Layout.SIMPLE);
		EditorSearch.onChange(name, value -> {
			item.addProperty("name", value.strip());
			changed(item, false);
			if (!query.isEmpty())
				scheduleFilterRefresh();
		});
		general.add(nameRow);
		if (type == Type.WATER) {
			general.add(field(item, "material", "Material", materialPicker(item, "material", false)));
			var toggles = ToggleList.builder().build();
			general.add(toggles);
			Runnable refreshToggles = () -> {
				var items = ToggleList.builder();
				for (String key : List.of("flat", "hasFoam", "skipUnderwaterTerrain")) {
					boolean baseline = Boolean.TRUE.equals(waterDefault(key));
					boolean value = item.has(key) ? item.get(key).getAsBoolean() : baseline;
					items.add(label(key), value, value != baseline,
						selected -> set(item, key, selected == baseline ? null : new JsonPrimitive(selected)),
						() -> set(item, key, null));
				}
				items.update(toggles);
			};
			refreshToggles.run();
			fieldUpdates.add(refreshToggles);
		}
		section("General", general, item, type == Type.WATER ? List.of("name", "material", "flat", "hasFoam", "skipUnderwaterTerrain") : List.of("name"));
		if (type == Type.WATER) {
			var colors = EditorPanels.stack();
			for (String key : List.of("surfaceColor", "depthColor", "foamColor"))
				colors.add(field(item, key, label(key), colorButton(item, key)));
			section("Colors", colors, item, List.of("surfaceColor", "depthColor", "foamColor"));
			var surface = EditorPanels.stack();
			for (String key : List.of("baseOpacity", "fresnelAmount", "specularStrength", "specularGloss", "normalStrength"))
				surface.add(number(item, key, 0, key.equals("specularGloss") ? 500 : 1));
			surface.add(field(item, "normalMap", "Normal map", materialPicker(item, "normalMap", true)));
			section("Surface", surface, item, List.of("baseOpacity", "fresnelAmount", "specularStrength", "specularGloss", "normalStrength", "normalMap"));
			section("Animation", number(item, "duration", 0, Double.MAX_VALUE), item, List.of("duration"));
			section("Other", fishingSpotColor(item), item, List.of("fishingSpotRecolor"));
		} else {
			groundFieldsHost.setOpaque(false);
			refreshGroundFields(item);
			section("Materials", groundFieldsHost, item, List.of("materials"));
		}
		json.setEditable(false);
		style(json);
		json.setRows(10);
		jsonSection = new PropertySection("JSON", scroll(json), expanded -> {
			if (expanded)
				refreshJson();
			else
				json.setText("");
		});
		jsonSection.setExpanded(false);
		properties.add(jsonSection);
		updateStatus();
		properties.revalidate();
		properties.repaint();
	}

	private void section(String title, JComponent content, JsonObject item, List<String> keys) {
		var section = new PropertySection(title, content);
		boolean materialList = keys.contains("materials");
		if (materialList)
			section.setInactiveNote("Picked at random per tile");
		var doc = document();
		section.onReset(() -> {
			var baseline = doc.resetBaselines.get(item);
			var restored = copyEntry(baseline);
			for (String key : keys) {
				if (restored.has(key))
					item.add(key, restored.get(key));
				else
					item.remove(key);
			}
			changed(item);
			showProperties();
		});
		Runnable refresh = () -> {
			var baseline = doc.resetBaselines.get(item);
			int configured = (int) keys.stream().filter(key -> {
				if (key.equals("name") || !item.has(key))
					return false;
				if (!key.equals("materials"))
					return true;
				var values = materials(item);
				return values.size() != 1 || !values.get(0).getAsString().equals("NONE");
			}).count();
			boolean edited = keys.stream().anyMatch(key -> !Objects.equals(item.get(key), baseline.get(key)));
			section.setChangedCount(materialList ? materials(item).size() : configured, edited);
		};
		refresh.run();
		fieldUpdates.add(refresh);
		properties.add(section);
	}

	private static void style(javax.swing.text.JTextComponent field) {
		field.setFont(WorkspaceStyle.MONO);
		field.setBackground(new Color(0x141414));
		field.setForeground(WorkspaceStyle.TEXT);
		field.setCaretColor(WorkspaceStyle.TEXT);
	}

	private JComponent number(JsonObject item, String key, double min, double max) {
		var builder = NumberField.builder().nullable(true).emptyText("Default").min(min).max(max)
			.step(key.equals("specularGloss") ? 1 : .01)
			.value(item.has(key) ? item.get(key).getAsDouble() : null)
			.onChange(value -> set(item, key, value == null ? null : new JsonPrimitive(value)));
		if (waterDefault(key) instanceof Number)
			builder.defaultValue(((Number) waterDefault(key)).doubleValue());
		var field = builder.build();
		return field(item, key, label(key), field);
	}

	private JComponent field(JsonObject item, String key, String label, JComponent control) {
		var row = new PropertyRow(label, control, PropertyRow.Layout.SIMPLE,
			() -> { set(item, key, null); showProperties(); });
		row.setChanged(item.has(key));
		fieldUpdates.add(() -> row.setChanged(item.has(key)));
		return row;
	}

	private JComponent materialPicker(JsonObject item, String key, boolean normal) {
		return materialPicker(item.has(key) ? item.get(key).getAsString() : null, normal, value -> {
			set(item, key, new JsonPrimitive(value));
		});
	}

	private PreviewDropdown<String> materialPicker(String initial, boolean normal, java.util.function.Consumer<String> change) {
		var picker = PreviewDropdown.builder(new PreviewDropdown.Provider<String>() {
			@Override public List<String> items() {
				updateMaterialIndex();
				if (!normal)
					return materialNames;
				return materialNames.stream().filter(name -> name.startsWith("WATER_NORMAL_MAP"))
					.collect(java.util.stream.Collectors.toList());
			}
			@Override public String label(String value) { return value; }
			@Override public Image preview(String value, int size) { return preview(value, size, DefinitionsScreen.this); }
			@Override public Image preview(String value, int size, JComponent observer) {
				return materialTexture(value, size, observer);
			}
		}).search(normal ? "Search normal maps" : "Search materials").compact(true).emptyText(normal ? "Default" : "None set").build();
		picker.setValue(initial);
		picker.onChange(change);
		return picker;
	}

	private JComponent fishingSpotColor(JsonObject item) {
		String key = "fishingSpotRecolor";
		return field(item, key, "Fishing spot recolor", colorButton(item, key));
	}

	private static Color hslColor(int value) {
		float[] rgb = ColorUtils.packedHslToSrgb(value);
		return new Color(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
	}

	private JComponent colorButton(JsonObject item, String key) {
		boolean packed = key.equals("fishingSpotRecolor");
		java.util.function.Supplier<Color> current = () -> {
			if (!packed)
				return color(item.get(key), waterColorDefault(key));
			int hsl = item.has(key) ? item.get(key).getAsInt() : -1;
			return hsl < 0 ? Color.WHITE : hslColor(hsl);
		};
		var button = ColorPicker.propertyButton("Choose " + label(key).toLowerCase(Locale.ROOT));
		Runnable update = () -> ColorPicker.setPropertyColor(button, current.get(), item.has(key));
		update.run();
		fieldUpdates.add(update);
		ColorPicker.builder(button, current).palette("FROM OTHER WATER", () -> waterPalette(item)).onApply(picked -> {
			JsonPrimitive next = packed ? new JsonPrimitive(ColorUtils.hsl(picked.getRGB() & 0xffffff))
				: new JsonPrimitive(hex(picked));
			if (!next.equals(item.get(key)))
				set(item, key, next);
		}).bind();
		return button;
	}

	private List<Color> waterPalette(JsonObject current) {
		var palette = new LinkedHashSet<Color>();
		for (var item : document().entries) {
			if (item == current)
				continue;
			for (String key : List.of("surfaceColor", "depthColor", "foamColor"))
				if (item.has(key))
					palette.add(color(item.get(key), waterColorDefault(key)));
		}
		return List.copyOf(palette);
	}

	private static JsonArray materials(JsonObject item) {
		return item.has("materials") ? item.getAsJsonArray("materials") : new JsonArray();
	}

	private Material[] materialSource;
	private Map<String, Material> materialsByName = Map.of();
	private List<String> materialNames = List.of();

	private void updateMaterialIndex() {
		var source = MaterialManager.MATERIALS;
		if (source == materialSource)
			return;
		materialSource = source;
		var byName = new TreeMap<String, Material>();
		if (source != null)
			for (Material material : source)
				byName.putIfAbsent(material.name, material);
		materialsByName = byName;
		materialNames = List.copyOf(byName.keySet());
	}

	private Image materialTexture(String name, int size, Component observer) {
		updateMaterialIndex();
		return rs117.hd.dev.overrides.MaterialThumbnails.preview(materialsByName.get(name), size, observer);
	}

	private void refreshGroundFields(JsonObject item) {
		groundFieldsHost.removeAll();
		groundFieldsHost.add(groundFields(item));
		groundFieldsHost.revalidate();
		groundFieldsHost.repaint();
	}

	private JComponent groundFields(JsonObject item) {
		var content = EditorPanels.stack();
		var values = materials(item);
		var mix = new JComponent() {
			@Override protected void paintComponent(Graphics graphics) {
				if (values.size() == 0)
					return;
				var g = (Graphics2D) graphics.create();
				try {
					g.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 6, 6));
					for (int i = 0; i < values.size(); i++) {
						String name = values.get(i).getAsString();
						int x = i * getWidth() / values.size();
						int width = (i + 1) * getWidth() / values.size() - x;
						g.setColor(groundColor(name));
						g.fillRect(x, 0, width, getHeight());
						var texture = materialTexture(name, 32, this);
						if (texture != null)
							g.drawImage(texture, x, 0, width, getHeight(), this);
					}
				} finally {
					g.dispose();
				}
			}
		};
		mix.setPreferredSize(new Dimension(0, 10));
		mix.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
		mix.setAlignmentX(LEFT_ALIGNMENT);
		content.add(mix);
		content.add(Box.createVerticalStrut(8));
		for (int i = 0; i < values.size(); i++) {
			int index = i;
			var row = new JPanel(new BorderLayout(5, 0));
			row.setOpaque(false);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true),
				new EmptyBorder(1, 7, 1, 2)));
			var number = EditorText.builder(Integer.toString(i + 1)).font(WorkspaceStyle.MONO.deriveFont(10f))
				.color(WorkspaceStyle.DIM).build();
			number.setPreferredSize(new Dimension(20, 0));
			row.add(number, BorderLayout.WEST);
			row.add(materialPicker(values.get(i).getAsString(), false, value -> {
				values.set(index, new JsonPrimitive(value));
				groundMaterialsChanged(item, values);
				mix.repaint();
			}));
			var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
			buttons.setOpaque(false);
			buttons.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("chevron-up", 10).size(22, 22).enabled(i > 0).tooltip("Move up").onClick(() -> {
				var prior = values.get(index - 1);
				values.set(index - 1, values.get(index));
				values.set(index, prior);
				groundMaterialsChanged(item, values);
				refreshGroundFields(item);
			}).build());
			buttons.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("close", 10).size(22, 22).enabled(values.size() > 1).tooltip("Remove material").onClick(() -> {
				values.remove(index);
				groundMaterialsChanged(item, values);
				refreshGroundFields(item);
			}).build());
			row.add(buttons, BorderLayout.EAST);
			content.add(row);
			content.add(Box.createVerticalStrut(4));
		}
		var footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
		footer.setOpaque(false);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		var add = DashedAddButton.create("Add material");
		var picker = materialPicker(null, false, material -> changeGroundCount(item, material, 1));
		add.addActionListener(event -> picker.togglePopup(add));
		footer.add(add);
		footer.add(EditorText.builder("Repeat a material to make it more common").color(WorkspaceStyle.DIM)
			.font(WorkspaceStyle.UI.deriveFont(10f)).border(new EmptyBorder(0, 8, 0, 0)).build());
		content.add(footer);
		return content;
	}

	private static String label(String key) {
		String text = key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static Object waterDefault(String key) {
		try {
			var field = WaterType.class.getDeclaredField(key);
			field.setAccessible(true);
			return field.get(WaterType.NONE);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Color waterColorDefault(String key) {
		Object value = waterDefault(key);
		return value instanceof float[] ? linear((float[]) value) : Color.GRAY;
	}

	private static Color linear(float[] value) {
		var rgb = ColorUtils.linearToSrgb(value.clone());
		return new Color(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
	}

	private static float channel(float value) { return Math.max(0, Math.min(1, value)); }

	private static Color color(JsonElement value, Color fallback) {
		if (value == null || value.isJsonNull())
			return fallback;
		if (value.isJsonArray()) {
			var array = value.getAsJsonArray();
			return linear(new float[] { array.get(0).getAsFloat() / 255, array.get(1).getAsFloat() / 255, array.get(2).getAsFloat() / 255 });
		}
		try { return Color.decode(value.getAsString()); }
		catch (NumberFormatException e) { return fallback; }
	}

	private static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xffffff); }

	private static Color groundColor(String name) {
		return name == null ? WorkspaceStyle.DIM : Color.getHSBColor(Math.floorMod(name.hashCode(), 360) / 360f, .28f, .64f);
	}

	private void paintSwatch(Graphics2D graphics) {
		var doc = document();
		if (doc == null || doc.selected == null)
			return;
		if (type == Type.WATER) {
			if (waterFrame != null) {
				int imageWidth = waterFrame.getWidth(null);
				int imageHeight = waterFrame.getHeight(null);
				if (imageWidth > 0 && imageHeight > 0) {
					double scale = Math.max(preview.getWidth() / (double) imageWidth, preview.getHeight() / (double) imageHeight);
					int width = (int) Math.ceil(imageWidth * scale), height = (int) Math.ceil(imageHeight * scale);
					graphics.drawImage(waterFrame, (preview.getWidth() - width) / 2, (preview.getHeight() - height) / 2,
						width, height, null);
				}
			} else {
				String message = waterPreviewMessage == null ? "Loading water preview…" : waterPreviewMessage;
				graphics.setFont(WorkspaceStyle.UI);
				graphics.setColor(WorkspaceStyle.DIM);
				graphics.drawString(message, Math.max(8, (preview.getWidth() - graphics.getFontMetrics().stringWidth(message)) / 2),
					preview.getHeight() / 2);
			}
		}
	}

	private void refreshGroundPreview(JsonObject item) {
		String materialList = item == null ? "" : materials(item).toString();
		groundName.setText(item == null ? "" : name(item));
		if (displayedGround == item && Objects.equals(displayedGroundMaterials, materialList)) {
			if (groundHeading != null)
				groundHeading.setText("Materials in " + name(item));
			return;
		}
		displayedGround = item;
		displayedGroundMaterials = materialList;
		displayedGroundSingle = item != null && materials(item).size() <= 1;
		groundPreview.removeAll();
		groundHeading = null;
		if (item != null) {
			var counts = new LinkedHashMap<String, Integer>();
			var entries = materials(item);
			for (var entry : entries)
				counts.merge(entry.getAsString(), 1, Integer::sum);
			var heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
			heading.setOpaque(false);
			heading.setAlignmentX(LEFT_ALIGNMENT);
			groundHeading = EditorText.builder("Materials in " + name(item)).font(WorkspaceStyle.SEMIBOLD).build();
			heading.add(groundHeading);
			heading.add(EditorText.builder(entries.size() + " entries · " + counts.size() + " materials")
				.color(WorkspaceStyle.DIM).build());
			groundPreview.add(heading);
			groundPreview.add(Box.createVerticalStrut(12));

			var layout = new GridLayout(0, 5, 10, 10);
			var cards = new JPanel(layout) {
				@Override public Dimension getMaximumSize() {
					return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
				}
			};
			cards.setOpaque(false);
			cards.setAlignmentX(LEFT_ALIGNMENT);
			cards.addComponentListener(new java.awt.event.ComponentAdapter() {
				@Override public void componentResized(java.awt.event.ComponentEvent event) {
					int columns = Math.max(1, Math.min(5, (cards.getWidth() + 10) / 145));
					if (columns != layout.getColumns()) {
						layout.setColumns(columns);
						cards.revalidate();
					}
				}
			});
			counts.forEach((material, count) -> cards.add(groundCard(item, material, count, entries.size())));
			var add = DashedAddButton.create("Add material");
			add.setVerticalTextPosition(SwingConstants.BOTTOM);
			add.setHorizontalTextPosition(SwingConstants.CENTER);
			add.setIcon(WorkspaceStyle.icon("plus", 16));
			var picker = materialPicker(null, false, material -> changeGroundCount(item, material, 1));
			add.addActionListener(event -> picker.togglePopup(add));
			cards.add(add);
			groundPreview.add(cards);
			groundPreview.add(Box.createVerticalStrut(10));
			var note = new JTextArea("Each tile picks one entry at random. Add more entries to make a material more common. Swap or reorder entries in the panel on the right.");
			note.setFont(WorkspaceStyle.UI.deriveFont(11f));
			note.setForeground(WorkspaceStyle.DIM);
			note.setOpaque(false);
			note.setEditable(false);
			note.setFocusable(false);
			note.setLineWrap(true);
			note.setWrapStyleWord(true);
			note.setAlignmentX(LEFT_ALIGNMENT);
			groundPreview.add(note);
		}
		groundPreview.revalidate();
		groundPreview.repaint();
	}

	private JPanel groundCard(JsonObject item, String material, int count, int total) {
		float share = count / (float) total;
		var card = new JPanel(new BorderLayout());
		card.setBackground(new Color(0x252525));
		card.setBorder(BorderFactory.createLineBorder(new Color(0x353535), 1, true));
		var texture = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6)) {
			@Override protected void paintComponent(Graphics graphics) {
				super.paintComponent(graphics);
				graphics.setColor(material.startsWith("GRASS") ? new Color(0x617F43)
					: material.startsWith("DIRT") ? new Color(0x785E43) : groundColor(material));
				graphics.fillRect(0, 0, getWidth(), getHeight());
				Image thumbnail = materialTexture(material, 192, this);
				if (thumbnail != null) {
					graphics.drawImage(thumbnail, 0, 0, getWidth(), getHeight(), this);
					return;
				}
				graphics.setColor(new Color(255, 255, 255, 24));
				for (int x = -getHeight(); x < getWidth(); x += 8)
					graphics.drawLine(x, 0, x + getHeight(), getHeight());
			}
		};
		texture.setPreferredSize(new Dimension(120, 124));
		var percent = EditorText.builder(Math.round(share * 100) + "%").font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(10f))
			.border(new EmptyBorder(1, 4, 1, 4)).build();
		percent.setOpaque(true);
		percent.setBackground(new Color(0, 0, 0, 175));
		texture.add(percent);
		card.add(texture, BorderLayout.NORTH);

		var details = EditorPanels.stack();
		details.setBorder(new EmptyBorder(7, 9, 8, 9));
		details.add(EditorText.builder(material).font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(11f)).build());
		var bar = new JComponent() {
			@Override protected void paintComponent(Graphics graphics) {
				graphics.setColor(WorkspaceStyle.INPUT);
				graphics.fillRect(0, 0, getWidth(), getHeight());
				graphics.setColor(WorkspaceStyle.ACCENT);
				graphics.fillRect(0, 0, Math.round(share * getWidth()), getHeight());
			}
		};
		bar.setPreferredSize(new Dimension(0, 4));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		bar.setAlignmentX(LEFT_ALIGNMENT);
		details.add(bar);
		var footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		footer.add(EditorText.builder(count + (count == 1 ? " entry" : " entries"))
			.font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build());
		var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
		controls.setOpaque(false);
		var remove = EditorButton.builder().icon("minus", 10).size(22, 22).tooltip("Remove one entry").enabled(total > 1)
			.onClick(() -> changeGroundCount(item, material, -1)).build();
		var increment = EditorButton.builder().icon("plus", 10).size(22, 22).tooltip("Add one entry")
			.onClick(() -> changeGroundCount(item, material, 1)).build();
		for (var button : List.of(remove, increment)) {
			button.setBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true));
			button.setBorderPainted(true);
			controls.add(button);
		}
		footer.add(controls, BorderLayout.EAST);
		details.add(footer);
		card.add(details);
		card.setPreferredSize(new Dimension(130, 190));
		return card;
	}

	private void changeGroundCount(JsonObject item, String material, int delta) {
		var entries = materials(item);
		if (delta > 0) {
			entries.add(new JsonPrimitive(material));
		} else {
			for (int i = entries.size() - 1; i >= 0; i--) {
				if (material.equals(entries.get(i).getAsString())) {
					entries.remove(i);
					break;
				}
			}
		}
		groundMaterialsChanged(item, entries);
		refreshGroundFields(item);
	}

	private void groundMaterialsChanged(JsonObject item, JsonArray entries) {
		item.add("materials", entries);
		// A count change only moves the row when it crosses the single-material boundary.
		boolean regroup = displayedGround != item || displayedGroundMaterials == null
			|| (entries.size() <= 1) != displayedGroundSingle;
		changed(item, regroup);
		if (!regroup && !query.isEmpty())
			scheduleFilterRefresh();
	}

	private void set(JsonObject item, String key, JsonElement value) {
		if (value == null)
			item.remove(key);
		else
			item.add(key, value);
		changed(item, false);
	}

	private void changed(JsonObject item) {
		changed(item, true);
	}

	private void changed(JsonObject item, boolean rebuildRows) {
		var doc = document();
		doc.changed = true;
		if (item != null)
			doc.dirty.add(item);
		if (rebuildRows)
			refreshRows();
		else
			list.repaint();
		updateStatus();
		fieldUpdates.forEach(Runnable::run);
	}

	private void refreshJson() {
		var selected = document().selected;
		json.setText(selected == null ? "" : JSON.toJson(selected));
		json.setCaretPosition(0);
	}

	private void updateStatus() {
		previewLayout.show(previewCards, type.name());
		if (type == Type.GROUND)
			refreshGroundPreview(document() == null ? null : document().selected);
		var doc = document();
		if (doc == null) {
			waterPreview = null;
			return;
		}
		if (jsonSection != null && jsonSection.isExpanded())
			refreshJson();
		var selected = doc.selected;
		String nextJson = type == Type.WATER && selected != null ? selected.toString() : null;
		String originalName = doc.originalNames.get(selected);
		if (!Objects.equals(nextJson, waterPreview == null ? null : waterPreview.json)
			|| !Objects.equals(originalName, waterPreview == null ? null : waterPreview.originalName)) {
			if (waterPreview == null || nextJson == null || !Objects.equals(originalName, waterPreview.originalName)) {
				waterViewport.camera().reset();
				waterFrame = null;
			}
			waterPreviewMessage = null;
			waterPreview = nextJson == null ? null : new WaterRequest(nextJson, originalName);
			updateWaterCursor();
		}
		firePropertyChange("waterPreview", type != Type.WATER, type == Type.WATER);
		previewTitle.setVisible(selected != null);
		if (selected != null) {
			selectionChip.setIcon(chip(selected));
			selectionName.setText(name(selected));
			boolean flat = selected.has("flat") && selected.get("flat").getAsBoolean();
			selectionMeta.setText((type == Type.GROUND ? materials(selected).size() + " materials" : flat ? "flat" : "animated")
				+ "  ·  " + (selected.entrySet().size() - 1) + " fields set");
			previewTitle.setText(name(selected));
		}
		selectionHeader.revalidate();
		selectionHeader.repaint();
		preview.repaint();
		status.setText(doc.changed ? Math.max(1, doc.dirty.size()) + " unsaved changes" : "All changes saved");
		status.setForeground(new Color(doc.changed ? 0xE8A33D : 0x6CB348));
	}

	public boolean confirmClose() {
		if (documents.values().stream().noneMatch(doc -> doc.changed))
			return true;
		int answer = JOptionPane.showOptionDialog(this, "Save changes to definitions before closing?", "Definitions",
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[] { "Save", "Discard", "Cancel" }, "Save");
		if (answer == 1)
			return true;
		if (answer != 0)
			return false;
		for (var entry : documents.entrySet())
			if (entry.getValue().changed && !save(entry.getKey(), entry.getValue()))
				return false;
		return true;
	}

	public void saveCurrent() {
		if (document() != null && save(type, document())) {
			updateStatus();
			fieldUpdates.forEach(Runnable::run);
		}
	}

	// Consume quoted strings as tokens so bracket text inside a string is never treated as an array.
	private static final String JSON_STRING = "\"(?:[^\"\\\\]|\\\\.)*\"";
	private static final java.util.regex.Pattern ARRAY_FIELDS = java.util.regex.Pattern.compile(
		"(" + JSON_STRING + "\\s*:\\s*)(\\[(?:" + JSON_STRING + "|[^\\[\\]{}\"])*\\])|" + JSON_STRING);

	private static String retainInlineArrays(String source, String output) {
		var inline = new HashMap<String, String>();
		var matcher = ARRAY_FIELDS.matcher(source);
		while (matcher.find()) {
			String array = matcher.group(2);
			if (array != null && !array.contains("\n") && !array.contains("\r")) {
				String key = matcher.group(1).substring(0, matcher.group(1).lastIndexOf(':')).stripTrailing();
				inline.putIfAbsent(key + new JsonParser().parse(array), array);
			}
		}
		matcher = ARRAY_FIELDS.matcher(output);
		var result = new StringBuffer();
		while (matcher.find()) {
			String array = matcher.group(2);
			if (array == null || !array.contains("\n"))
				continue;
			String prefix = matcher.group(1);
			String previous = inline.get(prefix.substring(0, prefix.lastIndexOf(':')).stripTrailing() + new JsonParser().parse(array));
			if (previous != null)
				matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(prefix + previous));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private boolean save(Type type, Document doc) {
		try {
			if (!doc.resource.isFileSystemResource())
				throw new IOException("Configure a writable path for " + type.file);
			Path path = doc.resource.toPath().toAbsolutePath();
			if (!Files.readString(path).equals(doc.source))
				throw new IOException(type.file + " changed on disk");
			var savedCounts = new HashMap<String, Integer>();
			for (var entry : new JsonParser().parse(doc.source).getAsJsonArray())
				savedCounts.merge(name(entry.getAsJsonObject()), 1, Integer::sum);
			var counts = new HashMap<String, Integer>();
			for (var entry : doc.entries) {
				String name = name(entry);
				if (name.isBlank())
					throw new IOException("Each definition needs a name");
				if (counts.merge(name, 1, Integer::sum) > Math.max(1, savedCounts.getOrDefault(name, 0)))
					throw new IOException("Duplicate definition name: " + name);
			}
			var output = new JsonArray();
			doc.entries.forEach(output::add);
			String newline = doc.source.contains("\r\n") ? "\r\n" : "\n";
			String result = retainInlineArrays(doc.source, JSON.toJson(output)).replace("\n", newline) + newline;
			Path temporary = Files.createTempFile(path.getParent(), ".definitions-", ".tmp");
			try {
				Files.writeString(temporary, result);
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
			doc.source = result;
			doc.changed = false;
			doc.dirty.clear();
			doc.entries.forEach(entry -> doc.resetBaselines.put(entry, copyEntry(entry)));
			list.repaint();
			return true;
		} catch (IOException e) {
			status.setText(e.getMessage());
			status.setForeground(new Color(0xE06060));
			return false;
		}
	}
}
