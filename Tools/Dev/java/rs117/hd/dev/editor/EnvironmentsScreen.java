package rs117.hd.dev.editor;

import com.google.gson.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.cache.region.Position;
import rs117.hd.dev.overrides.AreaListField;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.ColorUtils;

final class EnvironmentsScreen extends JPanel {
	private enum Kind { TEXT, AREA, SWITCH, COLOR, NUMBER, ANGLES, SKY, CONDITION }
	private enum Group {
		OVERWORLD("Overworld"), DUNGEONS("Dungeons & instances"), SPECIAL("Special");
		final String label;
		Group(String label) { this.label = label; }
	}

	@lombok.RequiredArgsConstructor
	private static final class Field {
		final String key, label;
		final Kind kind;
		final Double min, max;
		final double step;
		final String unit;
		Field(String key, String label, Kind kind) { this(key, label, kind, null, null, 1, null); }
	}
	private static final class Section {
		final String label;
		final List<Field> fields;
		Section(String label, Field... fields) {
			this.label = label;
			this.fields = List.of(fields);
		}
	}
	@lombok.RequiredArgsConstructor
	private static final class BrowserRow {
		final int index;
		final String label, tags;
		final boolean dirty;
		boolean heading() { return index < 0; }
	}

	@lombok.RequiredArgsConstructor
	static final class PreviewRequest {
		final String json;
		final Area area;
		final Position position;
		final String key;
		final boolean testing, matches;
		final String excludedArea;
		final int excludedIndex;
		final long locationRevision;
		final Map<Integer, Integer> varbits, varps;
	}

	private static final Color ORANGE = new Color(0xE8A33D);
	private static final Color GREEN = new Color(0x6CB348);
	private static final Color RED = new Color(0xE06060);
	private static final List<Section> SECTIONS = List.of(
		new Section("General", new Field("key", "Key", Kind.TEXT), new Field("area", "Area", Kind.AREA),
			flag("isOverworld", "Overworld"), flag("force", "Force"), flag("instantTransition", "Instant transition"),
			flag("allowSkyOverride", "Allow sky override"), flag("allowRoofShadows", "Allow roof shadows")),
		new Section("Conditions", new Field("varbitCondition", "Varbit condition", Kind.CONDITION),
			new Field("varpCondition", "Varp condition", Kind.CONDITION)),
		new Section("Lighting", color("directionalColor", "Directional colour"), number("directionalStrength", "Directional strength", .05),
			color("ambientColor", "Ambient colour"), number("ambientStrength", "Ambient strength", .05),
			color("underglowColor", "Underglow colour"), number("underglowStrength", "Underglow strength", .05),
			new Field("shadowAngles", "Shadow angles", Kind.ANGLES), number("nightExposure", "Night exposure", .05)),
		new Section("Fog", color("fogColor", "Fog colour"), new Field("fogDepth", "Fog depth", Kind.NUMBER, 0., 100., 1, null),
			new Field("groundFogOpacity", "Ground fog opacity", Kind.NUMBER, 0., 1., .05, null),
			number("groundFogStart", "Ground fog start", 5), number("groundFogEnd", "Ground fog end", 5)),
		new Section("Water", color("waterColor", "Water colour"), color("waterCausticsColor", "Caustics colour"),
			number("waterCausticsStrength", "Caustics strength", .05)),
		new Section("Wind", new Field("windAngle", "Wind angle", Kind.NUMBER, 0., 360., 1, "°"),
			number("windSpeed", "Wind speed", .5), number("windStrength", "Wind strength", .05), number("windCeiling", "Wind ceiling", 10)),
		new Section("Sky", new Field("sky", "Sky preset", Kind.SKY), flag("hideVanillaSkyboxes", "Hide vanilla skyboxes")),
		new Section("Effects", flag("lightningEffects", "Lightning effects"), flag("isUnderwater", "Underwater"),
			flag("isPohTheme", "POH theme"), flag("isLegacyTob", "Legacy ToB")));

	private final DefaultListModel<BrowserRow> rows = new DefaultListModel<>();
	private final JList<BrowserRow> list = new JList<>(rows);
	private final EditorSearch search = EditorSearch.builder("Search by key or area").onChange(text -> refreshList()).build();
	private final JLabel heading = EditorText.builder("Environments").font(WorkspaceStyle.SEMIBOLD)
		.icon(WorkspaceStyle.icon("grip", 12)).build();
	private final JLabel previewKey = label("", 10, WorkspaceStyle.DIM, true);
	private final JLabel caption = label("", 11, WorkspaceStyle.TEXT, true);
	private final JLabel banner = label("", 11, Color.WHITE, true);
	private final JLabel title = label("", 13, WorkspaceStyle.TEXT, true);
	private final JLabel summary = label("", 10, WorkspaceStyle.DIM, true);
	private final JLabel status = label("Loading environments…", 11, WorkspaceStyle.DIM, false);
	private final JButton add = EditorButton.builder().text("+ New").onClick(this::create).build();
	private final JButton reload = EditorButton.builder().text("Reload").icon("refresh", 14)
		.style(WorkspaceStyle.Button.PRIMARY).tooltip("Reload the preview and reset the camera")
		.onClick(this::reloadPreview).build();
	private final JButton sceneOptions = EditorButton.builder().text("Scene").icon("chevron-down", 10)
		.tooltip("Preview location and scene options").onClick(this::showSceneOptions).build();
	private final JToggleButton roofToggle = EditorButton.builder().text("Hide roofs")
		.tooltip("Hide roofs in the preview").onToggle(this::setRoofsHidden).buildToggle(false);
	private final JButton duplicate = EditorButton.builder().icon("duplicate", 14).size(26, 26)
		.tooltip("Duplicate (Ctrl+D)").onClick(this::duplicate).build();
	private final JButton delete = EditorButton.builder().icon("context/trash", 14).size(26, 26)
		.style(WorkspaceStyle.Button.DANGER).tooltip("Delete").onClick(this::delete).build();
	private final JButton save = EditorButton.builder().text("Save environments").icon("save", 13)
		.style(WorkspaceStyle.Button.PRIMARY).onClick(this::save).build();
	private final JPanel browser = new JPanel(new BorderLayout());
	private final WorkspaceDocks docks;
	private final JPanel properties = EditorPanels.propertyStack();
	private final EditorScrollPane propertyScroll = new EditorScrollPane(properties,
		ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
	private volatile boolean roofsHidden;
	private volatile boolean showObjects = true;
	private volatile boolean animateObjects = true;
	private volatile boolean showPlanesBelow = true;
	private final EditorPreview viewport = EditorPreview.builder().freecam(0, -950, -950, 0, Math.PI / 4)
		.location(3222, 3218, 0).roofs(() -> !roofsHidden)
		.objects(() -> showObjects).animations(() -> animateObjects).planesBelow(() -> showPlanesBelow).build();
	private final PreviewPanel preview = new PreviewPanel();
	private final JTextArea json = new JTextArea();
	private final Map<String, Runnable> fieldUpdates = new LinkedHashMap<>();
	private final Map<String, PropertySection> sections = new HashMap<>();
	private final Map<String, Boolean> expanded = new HashMap<>();
	private final Map<String, ConditionBuilder> conditions = new HashMap<>();
	private final Map<String, Map<String, ConditionBuilder.Gameval>> gamevals = new HashMap<>();
	private final Map<String, Area> areas = new HashMap<>();
	private final Map<Area, Boolean> overworld = new IdentityHashMap<>();
	private WorkspaceActions actions;
	private EnvironmentDocument document;
	private int selected = -1;
	private boolean updatingList;
	private long locationRevision;
	private volatile boolean active;
	private Position scenePosition;
	private volatile PreviewRequest request;
	private Position previewLocation;
	private String previewMessage = "Select an environment";
	private Image frame;

	EnvironmentsScreen() {
		super(new BorderLayout());
		var browserTools = EditorPanels.stack();
		var browserHeader = bar(heading, add, 30);
		browserHeader.setBackground(WorkspaceStyle.HEADER);
		browserTools.add(browserHeader);
		browserTools.add(search);
		browser.add(browserTools, BorderLayout.NORTH);

		list.setBackground(new Color(0x232323));
		list.setFixedCellHeight(28);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(this::renderRow);
		list.addListSelectionListener(e -> {
			var row = list.getSelectedValue();
			if (!e.getValueIsAdjusting() && !updatingList && row != null && !row.heading())
				select(row.index);
		});
		browser.add(new EditorScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));

		var center = new JPanel(new BorderLayout());
		center.setMinimumSize(new Dimension(310, 200));
		center.setPreferredSize(new Dimension(640, 480));
		var previewHeading = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 7 0, fillx",
			"[shrink][grow,fill][pref!][pref!][pref!]", "[center]"));
		previewHeading.setOpaque(false);
		var previewTitle = label("In-game preview", 12, WorkspaceStyle.TEXT, false);
		previewTitle.setIcon(statusDot(GREEN));
		previewHeading.add(previewTitle);
		previewHeading.add(previewKey, "wmin 0");
		previewHeading.add(sceneOptions);
		previewHeading.add(roofToggle);
		previewHeading.add(reload);
		var previewHeader = bar(null, null, 32);
		previewHeader.add(previewHeading);
		center.add(previewHeader, BorderLayout.NORTH);
		center.add(preview);
		preview.setLayout(new BorderLayout());
		banner.setOpaque(true);
		banner.setBorder(new EmptyBorder(4, 8, 4, 8));
		var top = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
		top.setOpaque(false);
		top.add(banner);
		preview.add(top, BorderLayout.NORTH);
		caption.setOpaque(true);
		caption.setBackground(new Color(20, 26, 14, 220));
		caption.setBorder(new EmptyBorder(6, 8, 6, 8));
		var bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
		bottom.setOpaque(false);
		bottom.add(caption);
		preview.add(bottom, BorderLayout.SOUTH);

		var right = new JPanel(new BorderLayout());
		var info = EditorPanels.stack();
		title.setAlignmentX(LEFT_ALIGNMENT);
		summary.setAlignmentX(LEFT_ALIGNMENT);
		info.add(title);
		info.add(summary);
		var row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		row.setOpaque(false);
		row.add(duplicate);
		row.add(delete);
		var grip = EditorText.builder("").icon(WorkspaceStyle.icon("grip", 12)).build();
		var propertyHeader = bar(grip, row, 48);
		propertyHeader.setBackground(WorkspaceStyle.HEADER);
		propertyHeader.add(info, BorderLayout.CENTER);
		right.add(propertyHeader, BorderLayout.NORTH);
		propertyScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.MENU));
		propertyScroll.getViewport().setBackground(WorkspaceStyle.INPUT);
		right.add(propertyScroll);
		var footer = new JPanel(new BorderLayout(0, 6));
		footer.setBackground(new Color(0x262626));
		footer.setBorder(new EmptyBorder(8, 10, 10, 10));
		save.setPreferredSize(new Dimension(0, 32));
		footer.add(status, BorderLayout.NORTH);
		footer.add(save);
		right.add(footer, BorderLayout.SOUTH);
		json.setEditable(false);
		json.setFont(WorkspaceStyle.MONO.deriveFont(11f));
		json.setRows(12);
		docks = WorkspaceDocks.builder(center)
			.primaryPanel(browser, WorkspaceDocks.Side.LEFT)
			.panel(right, WorkspaceDocks.Side.RIGHT)
			.width(WorkspaceDocks.Side.LEFT, 270)
			.width(WorkspaceDocks.Side.RIGHT, 400)
			.movable(browser, browserHeader, "Environments")
			.movable(browser, heading, "Environments")
			.movable(right, propertyHeader, "Properties")
			.movable(right, title, "Properties")
			.movable(right, summary, "Properties")
			.build();
		add(docks);
		refreshForm();
	}

	private static Field flag(String key, String label) { return new Field(key, label, Kind.SWITCH); }
	private static Field color(String key, String label) { return new Field(key, label, Kind.COLOR); }
	private static Field number(String key, String label, double step) { return new Field(key, label, Kind.NUMBER, null, null, step, null); }

	private static JLabel label(String text, float size, Color color, boolean mono) {
		return EditorText.builder(text).font((mono ? WorkspaceStyle.MONO : WorkspaceStyle.UI).deriveFont(size)).color(color).build();
	}

	static Icon statusDot(Color color) {
		return new Icon() {
			@Override public int getIconWidth() { return 6; }
			@Override public int getIconHeight() { return 6; }
			@Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(color);
				g.fillOval(x, y, 6, 6);
				g.dispose();
			}
		};
	}

	private static JPanel bar(Component left, Component right, int height) {
		var panel = new JPanel(new BorderLayout(5, 0));
		panel.setBackground(new Color(0x2C2C2C));
		panel.setBorder(new EmptyBorder(2, 8, 2, 8));
		panel.setPreferredSize(new Dimension(0, height));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		if (left != null)
			panel.add(EditorPanels.centered(left), BorderLayout.WEST);
		if (right != null)
			panel.add(EditorPanels.centered(right), BorderLayout.EAST);
		return panel;
	}

	void attachActions(WorkspaceActions actions) { this.actions = actions; }

	void load(EnvironmentDocument loaded, Map<String, ConditionBuilder.Gameval> varbits, Map<String, ConditionBuilder.Gameval> varps) {
		for (Area area : AreaManager.AREAS)
			areas.put(area.name, area);
		gamevals.put("varbitCondition", varbits);
		gamevals.put("varpCondition", varps);
		document = loaded;
		selected = loaded.entries.size() == 0 ? -1 : 0;
		previewMessage = selected < 0 ? "Select an environment" : "Loading area…";
		refreshList();
		refreshForm();
		actions.reset("Environments");
	}

	void loadFailed(String message) {
		status.setText("Could not load environments · " + message);
		status.setForeground(RED);
	}

	void setSidebarVisible(boolean visible) { docks.setPrimaryPanelVisible(visible); }

	void setPreviewActive(boolean visible) {
		active = visible;
		if (!visible) {
			viewport.camera().stopMoving();
			conditions.values().forEach(ConditionBuilder::endTest);
			publishRequest();
		}
	}

	PreviewRequest previewRequest() { return active ? request : null; }

	EditorPreview viewport() { return viewport; }

	private void showSceneOptions() {
		var entry = current();
		if (entry == null)
			return;
		Position origin = scenePosition != null ? scenePosition : locationFor(resolveArea(entry));
		if (origin == null)
			origin = viewport.location();
		var fields = EditorPanels.stack();
		fields.setBorder(new EmptyBorder(8, 10, 8, 10));
		var x = EditorText.propertyField(Integer.toString(origin.getX()));
		var y = EditorText.propertyField(Integer.toString(origin.getY()));
		var plane = new int[] {origin.getZ()};
		var planes = SegmentedControl.builder(List.of("0", "1", "2", "3")).values()
			.selected(Integer.toString(plane[0])).build();
		planes.onChange(value -> plane[0] = Integer.parseInt(value));
		fields.add(new PropertyRow("World X", x, PropertyRow.Layout.SIMPLE));
		fields.add(new PropertyRow("World Y", y, PropertyRow.Layout.SIMPLE));
		fields.add(new PropertyRow("Plane", planes, PropertyRow.Layout.SIMPLE));
		fields.add(ToggleList.builder()
			.add("Objects", showObjects, false, value -> { showObjects = value; publishRequest(); })
			.add("Object animations", animateObjects, false, value -> animateObjects = value)
			.add("Planes below", showPlanesBelow, false, value -> showPlanesBelow = value)
			.build());
		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
		actions.setOpaque(false);
		actions.setAlignmentX(LEFT_ALIGNMENT);
		fields.add(actions);
		var popup = new PopupLayer.Handle[1];
		actions.add(EditorButton.builder().text("Use area").onClick(() -> PopupLayer.select(popup[0], () -> {
			scenePosition = null;
			reloadPreview();
		})).build());
		var load = EditorButton.builder().text("Load").style(WorkspaceStyle.Button.PRIMARY)
			.onClick(() -> PopupLayer.select(popup[0], () -> {
				scenePosition = new Position(worldCoordinate(x.getText()), worldCoordinate(y.getText()), plane[0]);
				reloadPreview();
			})).build();
		Runnable validate = () -> {
			boolean valid = worldCoordinate(x.getText()) >= 0 && worldCoordinate(y.getText()) >= 0;
			load.setEnabled(valid);
			load.setToolTipText(valid ? null : "Enter whole-number coordinates from 0 to 16383");
		};
		EditorSearch.onChange(x, value -> validate.run());
		EditorSearch.onChange(y, value -> validate.run());
		x.addActionListener(event -> load.doClick());
		y.addActionListener(event -> load.doClick());
		validate.run();
		actions.add(load);
		popup[0] = PopupLayer.show(sceneOptions, fields, 298);
	}

	private static int worldCoordinate(String text) {
		try {
			int value = Integer.parseInt(text.strip());
			return value >= 0 && value <= 16383 ? value : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void setRoofsHidden(boolean hidden) {
		roofsHidden = hidden;
		roofToggle.setText(hidden ? "Show roofs" : "Hide roofs");
		roofToggle.setToolTipText(hidden ? "Show roofs in the preview" : "Hide roofs in the preview");
		preview.requestFocusInWindow();
	}

	void showFrame(PreviewRequest rendered, Image image, String message) {
		if (previewRequest() != rendered)
			return;
		if (image != null || message != null)
			frame = image;
		previewMessage = message;
		preview.repaint();
	}

	void previewLocation(PreviewRequest rendered, Position location) {
		var latest = previewRequest();
		if (latest != null && latest.area == rendered.area && latest.locationRevision == rendered.locationRevision) {
			previewLocation = location;
			sceneOptions.setToolTipText(location.getX() + ", " + location.getY() + " · Plane " + location.getZ());
		}
	}

	void previewReloaded(PreviewRequest rendered) {
		var latest = previewRequest();
		if (latest != null && latest.locationRevision == rendered.locationRevision)
			notice("Reloaded " + rendered.key);
	}

	private Area resolveArea(JsonObject entry) {
		return areas.getOrDefault(EnvironmentDocument.area(entry), Area.NONE);
	}

	private Group group(JsonObject entry) {
		if (flagValue(entry, "isOverworld", false))
			return Group.OVERWORLD;
		if (flagValue(entry, "isUnderwater", false) || flagValue(entry, "isPohTheme", false) || flagValue(entry, "voidShader", false))
			return Group.SPECIAL;
		boolean inferred = !entry.has("isOverworld") && overworld.computeIfAbsent(resolveArea(entry),
			area -> area == Area.OVERWORLD || (area.areaBounds != null && area.areaBounds.minY < 4000 && Area.OVERWORLD.intersects(area)));
		return inferred ? Group.OVERWORLD : Group.DUNGEONS;
	}

	private static boolean flagValue(JsonObject entry, String key, boolean fallback) {
		var value = entry.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
	}

	private JsonObject current() {
		return document == null || selected < 0 || selected >= document.entries.size()
			? null : document.entries.get(selected).getAsJsonObject();
	}

	private void select(int index) {
		if (selected == index)
			return;
		selected = index;
		scenePosition = null;
		viewport.camera().reset();
		frame = null;
		previewMessage = "Loading area…";
		refreshForm();
	}

	private Component renderRow(JList<? extends BrowserRow> source, BrowserRow item, int index, boolean selected, boolean focused) {
		var row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(selected && !item.heading() ? new Color(0x334D73) : item.heading() ? new Color(0x262626) : source.getBackground());
		row.setBorder(new EmptyBorder(0, 10, 0, 10));
		row.add(label(item.label, item.heading() ? 10 : 11, item.heading() ? WorkspaceStyle.DIM : selected ? Color.WHITE : WorkspaceStyle.TEXT, !item.heading()));
		var tags = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 6));
		tags.setOpaque(false);
		var badges = label(item.tags, 9, selected && !item.heading() ? WorkspaceStyle.TEXT : WorkspaceStyle.DIM, true);
		if (item.dirty)
			badges.setIcon(statusDot(ORANGE));
		tags.add(badges);
		row.add(tags, BorderLayout.EAST);
		return row;
	}

	private void refreshList() {
		updatingList = true;
		try {
			rows.clear();
			if (document == null)
				return;
			String query = search.field().getText().strip().toLowerCase(Locale.ROOT);
			var savedCounts = counts(document.saved);
			var categories = new EnumMap<Group, List<BrowserRow>>(Group.class);
			for (Group type : Group.values())
				categories.put(type, new ArrayList<>());
			for (int i = 0; i < document.entries.size(); i++) {
				var entry = document.entries.get(i).getAsJsonObject();
				boolean dirty = savedCounts.merge(entry, -1, Integer::sum) < 0;
				String key = EnvironmentDocument.key(entry);
				String area = EnvironmentDocument.area(entry);
				if (!(key + " " + area).toLowerCase(Locale.ROOT).contains(query))
					continue;
				var tags = new StringJoiner(" ");
				if (flagValue(entry, "force", false)) tags.add("FORCE");
				if (entry.has("varbitCondition") || entry.has("varpCondition")) tags.add("COND");
				if (entry.has("sky")) tags.add("SKY");
				categories.get(group(entry)).add(new BrowserRow(i, key, tags.toString(), dirty));
			}
			for (var category : categories.entrySet()) {
				var items = category.getValue();
				if (items.isEmpty())
					continue;
				items.sort(Comparator.comparing((BrowserRow row) -> row.label, String.CASE_INSENSITIVE_ORDER));
				rows.addElement(new BrowserRow(-1, category.getKey().label.toUpperCase(Locale.ROOT), Integer.toString(items.size()), false));
				for (var item : items) {
					rows.addElement(item);
					if (item.index == selected)
						list.setSelectedIndex(rows.size() - 1);
				}
			}
			if (rows.isEmpty())
				rows.addElement(new BrowserRow(-1, "No definitions match.", "", false));
			heading.setText("Environments   " + document.entries.size());
		} finally {
			updatingList = false;
		}
	}

	private static Map<JsonElement, Integer> counts(JsonArray entries) {
		var counts = new HashMap<JsonElement, Integer>();
		for (var entry : entries)
			counts.merge(entry, 1, Integer::sum);
		return counts;
	}

	private int dirtyCount() {
		if (document == null || document.entries.equals(document.saved))
			return 0;
		var savedCounts = counts(document.saved);
		int added = 0, removed = document.saved.size() - document.entries.size();
		for (var entry : document.entries)
			if (savedCounts.merge(entry, -1, Integer::sum) < 0)
				added++;
		return Math.max(1, Math.max(added, added + removed));
	}

	private void refreshForm() {
		properties.removeAll();
		conditions.clear();
		fieldUpdates.clear();
		sections.clear();
		var entry = current();
		add.setEnabled(document != null);
		duplicate.setEnabled(entry != null);
		delete.setEnabled(entry != null);
		if (entry != null) {
			for (var section : SECTIONS) {
				var fields = EditorPanels.stack();
				if (section.label.equals("Lighting"))
					fields.add(label("Unset ambient is derived from directional", 11, WorkspaceStyle.DIM, false));
				var switches = new ArrayList<Field>();
				for (var field : section.fields) {
					if (field.kind == Kind.SWITCH)
						switches.add(field);
					else
						addField(fields, entry, field);
				}
				if (!switches.isEmpty()) {
					var toggles = ToggleList.builder().build();
					fields.add(toggles);
					fieldUpdates.put(section.label, () -> {
						var items = ToggleList.builder();
						for (var field : switches) {
							var key = field.key;
							var fallback = document.defaults.get(key);
							boolean defaultValue = fallback != null && fallback.getAsBoolean();
							items.add(field.label, flagValue(entry, key, defaultValue), changed(entry, key),
								value -> editField(key, new JsonPrimitive(value), false), () -> editField(key, null, false));
						}
						items.update(toggles);
					});
				}
				for (Component child : fields.getComponents())
					((JComponent) child).setAlignmentX(LEFT_ALIGNMENT);
				var propertiesSection = new PropertySection(section.label, fields, open -> expanded.put(section.label, open));
				if (section.label.equals("Conditions"))
					propertiesSection.setInlineNote("Both must be true · empty = always");
				propertiesSection.setExpanded(expanded.getOrDefault(section.label, !section.label.equals("Effects")));
				sections.put(section.label, propertiesSection);
				properties.add(propertiesSection);
			}
			var jsonFields = EditorPanels.stack();
			jsonFields.add(EditorButton.builder().text("Copy").icon("context/copy", 12).onClick(() ->
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json.getText()), null)).build());
			jsonFields.add(new EditorScrollPane(json));
			var jsonSection = new PropertySection("JSON", jsonFields, open -> expanded.put("JSON", open));
			jsonSection.setExpanded(expanded.getOrDefault("JSON", false));
			properties.add(jsonSection);
		}
		refreshDetails();
		properties.revalidate();
		properties.repaint();
	}

	private void addField(JPanel fields, JsonObject entry, Field field) {
		var key = field.key;
		var value = entry.get(key);
		var fallback = document.defaults.get(key);
		if (field.kind == Kind.CONDITION) {
			if (!conditions.isEmpty())
				fields.add(Box.createVerticalStrut(8));
			fields.add(EditorText.builder(field.label).font(WorkspaceStyle.SEMIBOLD)
				.border(new EmptyBorder(0, 0, 4, 0)).build());
			var editor = new ConditionBuilder(key, conditionText(value), gamevals.getOrDefault(key, Map.of()),
				text -> editField(key, text.isBlank() ? null : new JsonPrimitive(text), false), this::publishRequest);
			conditions.put(key, editor);
			fields.add(editor);
			return;
		}
		JComponent control;
		switch (field.kind) {
			case TEXT:
				control = textField(field, EnvironmentDocument.key(entry), text -> stringEdit(key, text, false));
				break;
			case AREA:
				control = AreaListField.areaCard(resolveArea(entry), () -> previewLocation, area -> {
					if (editField(key, new JsonPrimitive(area.name), true))
						notice("Area set to " + area.name);
				});
				break;
			case COLOR:
				control = colorControl(entry, field, fallback);
				break;
			case NUMBER:
				control = numberControl(field, value, fallback);
				break;
			case ANGLES:
				control = anglesField(field, value);
				break;
			case SKY:
				control = skyControl(entry);
				break;
			default:
				throw new IllegalStateException("Unsupported field " + key);
		}
		var row = new PropertyRow(field.label, control, PropertyRow.Layout.SIMPLE,
			() -> editField(key, null, true));
		fieldUpdates.put(key, () -> row.setChanged(changed(entry, key)));
		fields.add(row);
	}

	private static String conditionText(JsonElement value) {
		if (value == null || value.isJsonNull())
			return "";
		return rs117.hd.utils.ExpressionParser.mergeJsonExpressions("||", value);
	}

	private JTextField textField(Field field, String current, Consumer<String> update) {
		var input = EditorText.propertyField(current);
		input.setToolTipText(field.label);
		EditorSearch.onChange(input, update);
		return input;
	}

	private JTextField anglesField(Field field, JsonElement value) {
		String text = value != null && value.isJsonArray() ? value.toString().replace("[", "").replace("]", "") : "";
		return textField(field, text, next -> {
			if (next.isBlank()) {
				editField(field.key, null, false);
				return;
			}
			String[] parts = next.split(",", -1);
			if (parts.length != 2)
				return;
			try {
				var angles = new JsonArray();
				for (String part : parts) {
					float angle = Float.parseFloat(part.strip());
					if (!Float.isFinite(angle))
						return;
					angles.add(angle);
				}
				editField(field.key, angles, false);
			} catch (NumberFormatException ignored) {
				// A partial pitch/yaw edit keeps the last complete pair.
			}
		});
	}

	private JComponent numberControl(Field field, JsonElement value, JsonElement fallback) {
		Double explicit = value != null && value.isJsonPrimitive() ? value.getAsDouble() : null;
		var number = NumberField.builder().value(explicit).step(field.step).nullable(true)
			.onChange(next -> editField(field.key, next == null ? null : new JsonPrimitive(next), false));
		if (field.min != null)
			number.min(explicit == null ? field.min : Math.min(field.min, explicit));
		if (field.max != null)
			number.max(explicit == null ? field.max : Math.max(field.max, explicit));
		if (field.unit != null)
			number.unit(field.unit);
		if (fallback != null)
			number.defaultValue(fallback.getAsDouble());
		return number.build();
	}

	private JButton colorControl(JsonObject entry, Field field, JsonElement fallback) {
		var key = field.key;
		var button = ColorPicker.propertyButton("Choose " + field.label.toLowerCase(Locale.ROOT));
		Runnable display = () -> {
			Color color = unpack(entry.get(key));
			ColorPicker.setPropertyColor(button, color == null ? unpack(fallback) : color, color != null);
		};
		ColorPicker.builder(button, () -> {
			Color color = unpack(entry.get(key));
			if (color == null)
				color = unpack(fallback);
			return color == null ? Color.BLACK : color;
		}).palette("From other environments", this::palette).onApply(color -> {
			editField(key, new JsonPrimitive(ColorPicker.hex(color)), false);
			display.run();
		}).bind();
		display.run();
		return button;
	}

	private List<Color> palette() {
		var colors = new LinkedHashSet<Color>();
		var selectedEntry = current();
		for (var element : document.entries) {
			if (element == selectedEntry)
				continue;
			for (var field : element.getAsJsonObject().entrySet()) {
				if (!field.getKey().endsWith("Color"))
					continue;
				var color = unpack(field.getValue());
				if (color != null)
					colors.add(color);
			}
		}
		return colors.stream().limit(20).collect(java.util.stream.Collectors.toList());
	}

	private static Color unpack(JsonElement color) {
		if (color == null || color.isJsonNull())
			return null;
		try {
			if (color.isJsonPrimitive())
				return new Color(ColorUtils.packSrgb(ColorUtils.srgb(color.getAsString())));
			var values = color.getAsJsonArray();
			float[] srgb = new float[3];
			for (int i = 0; i < srgb.length; i++)
				srgb[i] = values.get(i).getAsFloat() / 255;
			return new Color(ColorUtils.packSrgb(srgb));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private JComponent skyControl(JsonObject entry) {
		String current = EnvironmentDocument.string(entry, "sky", entry.has("sky") ? "Custom sky" : "default");
		var presets = new ArrayList<>(SkyManager.PRESETS.keySet());
		presets.sort(String.CASE_INSENSITIVE_ORDER);
		if (!current.equals("default") && !presets.contains(current))
			presets.add(0, current);
		presets.add(0, "default");
		return EditorSelect.<String>builder().options(presets).selected(current)
			.onChange(value -> {
				if (!value.equals("Custom sky"))
					stringEdit("sky", value.equals("default") ? "" : value, true);
			}).build();
	}

	private void stringEdit(String key, String value, boolean rebuild) {
		editField(key, value.isBlank() ? null : new JsonPrimitive(value.strip()), rebuild);
	}

	private boolean editField(String key, JsonElement value, boolean rebuild) {
		var entry = current();
		if (entry == null || Objects.equals(entry.get(key), value))
			return false;
		if (key.equals("area") && value == null)
			value = new JsonPrimitive("NONE");
		final JsonElement next = value;
		if (key.equals("area"))
			scenePosition = null;
		actions.edit("Edit " + key + (key.equals("key") ? "" : " · " + EnvironmentDocument.key(entry)), () -> {
			if (next == null)
				entry.remove(key);
			else
				entry.add(key, next);
		});
		refreshList();
		if (rebuild)
			refreshForm();
		else
			refreshDetails();
		return true;
	}

	private boolean changed(JsonObject entry, String key) {
		return entry.has(key) && !entry.get(key).isJsonNull() && !document.isDefault(key, entry.get(key))
			&& !(key.equals("key") && EnvironmentDocument.key(entry).equals(EnvironmentDocument.area(entry)));
	}

	private void refreshDetails() {
		var entry = current();
		save.setEnabled(dirtyCount() > 0);
		showSaveStatus();
		reload.setEnabled(entry != null);
		sceneOptions.setEnabled(entry != null);
		roofToggle.setEnabled(entry != null);
		caption.setVisible(entry != null);
		if (entry == null) {
			title.setText("");
			title.setIcon(null);
			summary.setText("");
			previewKey.setText("");
			publishRequest();
			return;
		}
		int total = 0;
		for (var section : SECTIONS) {
			int count = (int) section.fields.stream().filter(f -> changed(entry, f.key)).count();
			sections.get(section.label).setChangedCount(count);
			total += count;
		}
		fieldUpdates.values().forEach(Runnable::run);
		String key = EnvironmentDocument.key(entry);
		Color fog = unpack(entry.get("fogColor"));
		title.setIcon(ColorPicker.swatch(fog == null ? new Color(0x1A1A1A) : fog, 12));
		title.setText(key);
		summary.setText(EnvironmentDocument.area(entry) + " · " + group(entry).label.toLowerCase(Locale.ROOT) + " · " + total + " fields set");
		previewKey.setText(key);
		title.setToolTipText(key);
		summary.setToolTipText(summary.getText());
		caption.setText("<html>" + escape(key) + "<br>" + escape(EnvironmentDocument.area(entry)) + " · fog "
			+ escape(defaultText(entry, "fogDepth")) + " · directional " + escape(defaultText(entry, "directionalStrength")) + " · live world</html>");
		json.setText(EnvironmentDocument.JSON.toJson(document.sparse(entry)));
		json.setCaretPosition(0);
		publishRequest();
	}

	private String defaultText(JsonObject entry, String key) {
		return EnvironmentDocument.string(entry, key, String.valueOf(document.defaults.get(key)));
	}

	private void publishRequest() {
		var entry = current();
		if (entry == null) {
			request = null;
			banner.setVisible(false);
			return;
		}
		boolean testing = conditions.values().stream().anyMatch(ConditionBuilder::isTesting);
		boolean matches = !testing || conditions.values().stream().allMatch(ConditionBuilder::matches);
		String key = EnvironmentDocument.key(entry);
		String area = EnvironmentDocument.area(entry);
		int occurrence = 0;
		for (int i = 0; i < selected; i++)
			if (EnvironmentDocument.area(document.entries.get(i).getAsJsonObject()).equals(area))
				occurrence++;
		var varbit = conditions.get("varbitCondition");
		var varp = conditions.get("varpCondition");
		request = new PreviewRequest(entry.toString(), resolveArea(entry), scenePosition, key, testing, matches, area, occurrence, locationRevision,
			varbit == null ? Map.of() : varbit.testValues(), varp == null ? Map.of() : varp.testValues());
		banner.setVisible(testing);
		banner.setBackground(new Color(matches ? 0x2E5024 : 0x5A2626));
		banner.setText("Testing conditions · " + (matches ? "TRUE · " + key + " active" : "FALSE · falls back to the next matching environment"));
	}

	static Position locationFor(Area area) {
		if (area == Area.NONE || area == Area.ALL || area.areaBounds == null || area.aabbs.length == 0)
			return null;
		var center = area.areaBounds;
		int x = (center.minX + center.maxX) / 2, y = (center.minY + center.maxY) / 2;
		var closest = Arrays.stream(area.aabbs).min(Comparator.comparingDouble(box -> {
			int dx = x - Math.max(box.minX, Math.min(x, box.maxX));
			int dy = y - Math.max(box.minY, Math.min(y, box.maxY));
			return Math.hypot(dx, dy);
		})).orElseThrow();
		return new Position(Math.max(closest.minX, Math.min(x, closest.maxX)),
			Math.max(closest.minY, Math.min(y, closest.maxY)), closest.hasZ() ? Math.max(0, Math.min(3, closest.minZ)) : 0);
	}

	private void reloadPreview() {
		if (current() == null)
			return;
		viewport.camera().reset();
		preview.requestFocusInWindow();
		locationRevision++;
		frame = null;
		previewMessage = "Reloading preview…";
		preview.repaint();
		publishRequest();
	}

	private void create() {
		if (document == null)
			return;
		var entry = new JsonObject();
		String name = uniqueKey("NEW_ENVIRONMENT_", false);
		entry.addProperty("key", name);
		entry.addProperty("area", "NONE");
		entry.addProperty("fogColor", "#1A1A1A");
		entry.addProperty("fogDepth", 25);
		append("Create " + name, entry);
		notice("Created " + name);
	}

	void duplicate() {
		var entry = current();
		if (entry == null)
			return;
		var copy = entry.deepCopy();
		String base = EnvironmentDocument.key(entry) + "_COPY";
		String name = uniqueKey(base, true);
		copy.addProperty("key", name);
		append("Duplicate " + EnvironmentDocument.key(entry), copy);
		notice("Created " + name);
	}

	private String uniqueKey(String base, boolean allowBase) {
		var keys = new HashSet<String>();
		for (var entry : document.entries)
			keys.add(EnvironmentDocument.key(entry.getAsJsonObject()).toUpperCase(Locale.ROOT));
		if (allowBase && !keys.contains(base.toUpperCase(Locale.ROOT)))
			return base;
		String prefix = base + (allowBase ? "_" : "");
		int counter = 1;
		while (keys.contains((prefix + counter).toUpperCase(Locale.ROOT)))
			counter++;
		return prefix + counter;
	}

	private void append(String label, JsonObject entry) {
		search.field().setText("");
		actions.edit(label, () -> {
			selected = document.entries.size();
			scenePosition = null;
			document.entries.add(entry);
		});
		refreshList();
		list.ensureIndexIsVisible(list.getSelectedIndex());
		refreshForm();
	}

	void delete() {
		var entry = current();
		if (entry == null)
			return;
		String key = EnvironmentDocument.key(entry);
		if (JOptionPane.showConfirmDialog(this, "Delete " + key + "?", "Delete environment", JOptionPane.OK_CANCEL_OPTION)
			!= JOptionPane.OK_OPTION)
			return;
		actions.edit("Delete " + key, () -> {
			document.entries.remove(selected);
			selected = Math.min(selected, document.entries.size() - 1);
			scenePosition = null;
		});
		refreshList();
		refreshForm();
		notice("Deleted " + key);
	}

	boolean save() {
		if (dirtyCount() == 0)
			return true;
		try {
			document.save();
			refreshList();
			refreshDetails();
			notice("Saved environments.json");
			return true;
		} catch (IOException | RuntimeException e) {
			status.setText("Save failed · " + e.getMessage());
			status.setForeground(RED);
			status.setToolTipText(String.valueOf(document.path));
			return false;
		}
	}

	boolean confirmClose() {
		if (dirtyCount() == 0)
			return true;
		int answer = JOptionPane.showOptionDialog(this, "Save changes to environments before closing?", "Environments",
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[] { "Save", "Discard", "Cancel" }, "Save");
		return answer == 1 || (answer == 0 && save());
	}

	private void showSaveStatus() {
		if (document == null)
			return;
		int count = dirtyCount();
		status.setForeground(count == 0 ? GREEN : ORANGE);
		status.setIcon(statusDot(status.getForeground()));
		status.setText(count == 0 ? "All changes saved" : count + " unsaved change" + (count == 1 ? "" : "s"));
		save.setToolTipText(document.path.toString());
	}

	private void notice(String message) {
		actions.status(message);
	}

	JsonObject capture() {
		var snapshot = new JsonObject();
		if (document != null) {
			snapshot.add("environments", document.entries.deepCopy());
			snapshot.addProperty("selected", selected);
		}
		return snapshot;
	}

	void restore(JsonObject state) {
		if (document == null || !state.has("environments"))
			return;
		document.entries = state.getAsJsonArray("environments").deepCopy();
		int restored = Math.min(state.get("selected").getAsInt(), document.entries.size() - 1);
		if (restored != selected)
			scenePosition = null;
		selected = restored;
		refreshList();
		refreshForm();
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private final class PreviewPanel extends JPanel {
		PreviewPanel() {
			setBackground(new Color(0x242A22));
			setCursor(WorkspaceStyle.cursor("grab"));
			setToolTipText("Click to fly · WASD to move · Q/E down/up · Drag to look");
			viewport.camera().installControls(this, () -> previewRequest() != null);
		}
		@Override protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			if (frame != null) {
				int width = frame.getWidth(this), height = frame.getHeight(this);
				if (width > 0 && height > 0) {
					double scale = Math.max(getWidth() / (double) width, getHeight() / (double) height);
					int w = (int) (width * scale), h = (int) (height * scale);
					graphics.drawImage(frame, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, this);
				}
			}
			if (previewMessage != null) {
				graphics.setFont(WorkspaceStyle.MONO.deriveFont(11f));
				graphics.setColor(WorkspaceStyle.TEXT);
				int x = Math.max(8, (getWidth() - graphics.getFontMetrics().stringWidth(previewMessage)) / 2);
				graphics.drawString(previewMessage, x, getHeight() / 2);
			}
		}
	}
}
