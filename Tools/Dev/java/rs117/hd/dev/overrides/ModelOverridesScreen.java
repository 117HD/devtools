package rs117.hd.dev.overrides;

import com.google.gson.JsonObject;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Renderable;
import rs117.hd.dev.editor.*;
import rs117.hd.dev.sceneloader.scene.*;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;
import rs117.hd.dev.sceneloader.standalone.client.Fake;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;

/** The Model Overrides workspace: entity browser, per-entity override list, 3D preview and the properties panel. */
@Slf4j
public final class ModelOverridesScreen extends JPanel {
	private static final int TILE_X = 34;
	private static final int TILE_Z = 52;
	private static final int GROUND_CLEARANCE = 8;
	private static final int VANILLA_PASS = 1;
	private static final int OVERRIDE_PASS = 2;
	private static final int DOCK_PASS = 4;

	private final DefaultListModel<CacheModels.Group> rows = new DefaultListModel<>();
	private final JList<CacheModels.Group> list = new JList<>(rows);
	private final EditorSearch browserSearch = EditorSearch.builder("Filter by name, ID or gameval").build();
	private final JTextField search = browserSearch.field();
	private final JLabel status = EditorText.builder("Select a model").color(WorkspaceStyle.DIM).build();
	private final OverridePreviewSurface centerPreview = new OverridePreviewSurface(true);
	private final OverridePreviewSurface dockPreview = new OverridePreviewSurface(false);
	private JPanel previewToolbar;
	private JPanel previewInfo;
	private volatile String previewMode = "Override";
	private volatile String previewShading = "Material";
	private volatile boolean previewActive = true;
	private volatile long previewGeneration;

	public void setPreviewActive(boolean active) {
		if (previewActive == active)
			return;
		previewActive = active;
		previewGeneration++;
		if (!active) {
			cancelFinding();
			previewModel = null;
			vanillaFacePicker = overrideFacePicker = null;
			properties.setPreviewModel(null);
			centerPreview.clear();
			dockPreview.clear();
		}
	}

	private volatile boolean dockVisible = true;
	private volatile String previewOverrideJson;
	private final JPanel viewport = new JPanel(new BorderLayout());
	private final JLayeredPane viewerStage = new JLayeredPane();
	private final AtomicReference<CacheModels.Entry> pending = new AtomicReference<>();
	private final AtomicReference<CacheModels.Entry> worldSearch = new AtomicReference<>();
	private JButton findInWorld;
	private final CardLayout gamePreviewCards = new CardLayout();
	private final JPanel gamePreviewBody = new JPanel(gamePreviewCards);
	private final FindingOverlay findingOverlay = new FindingOverlay();
		private volatile ScenePreset scenePreset = ScenePreset.SKY;
	private volatile boolean showSurroundings;
	private volatile boolean showAllPlacements = true;
	private volatile CacheModels.Entry findingEntry;
	private enum ScenePreset {
		SKY("Sky", 0, 0), LIVE("Live world", 3234, 3252), CAVE("Cave", 3170, 9565),
		GRASS("Empty grass", 3194, 3236), LUMBRIDGE("Lumbridge", 3234, 3252);
		final String label;
		final int x, z;
		ScenePreset(String label, int x, int z) { this.label = label; this.x = x; this.z = z; }
		@Override public String toString() { return label; }
	}
	private volatile net.runelite.cache.region.Location foundLocation;
	private JComboBox<ScenePreset> sceneSelector;
	private final EditorSession session;
	private final JFrame owner;
	private CacheModels.Category category = CacheModels.Category.NPCs;
	private final java.util.EnumMap<CacheModels.Category, JToggleButton> categories = new java.util.EnumMap<>(CacheModels.Category.class);
	private final java.util.EnumMap<CacheModels.Category, JLabel> counts = new java.util.EnumMap<>(CacheModels.Category.class);
	private final java.util.concurrent.atomic.AtomicBoolean refreshRequested = new java.util.concurrent.atomic.AtomicBoolean();
	private final Timer browserSearchDelay = new Timer(120, event -> filter(this.browserOverrideTargets));
	private final Timer overrideSearchDelay = new Timer(120, event -> refreshOverrideList());
	private List<CacheModels.Group> browserGroups = List.of();
	private CacheModels.Group activeGroup;
	private boolean filteringBrowser;
	private enum OverrideFilter { ALL, WITH, WITHOUT }
	private OverrideFilter overrideFilter = OverrideFilter.ALL;
	private final java.util.EnumMap<OverrideFilter, JToggleButton> overrideFilterBadges = new java.util.EnumMap<>(OverrideFilter.class);
	private Set<Integer> browserOverrideTargets;
	private final JPanel variantPanel = new JPanel(new BorderLayout(6, 3));
	private final JLabel variantLabel = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(11f)).build();
	private volatile double yaw = .55;
	private volatile double pitch = .30;
	private volatile double zoom = 1;
	private volatile Orbit gameCamera = new Orbit(.55, .30, 1);
	private volatile CacheModels.Entry selectedEntity;
	private volatile Model previewModel;
	private PreviewFacePicker vanillaFacePicker, overrideFacePicker;
	private volatile boolean windPreview;
	private volatile float[] windPointer;
	private volatile java.util.Map<Integer, Integer> nearbyObjects = java.util.Map.of();

	// Populated once the HD runtime (and its Gson) is ready — see render().
	private volatile ModelOverrideDocument document;
	private Runnable selectionChangedListener = () -> {};
	private ModelOverrideDocument.Entry selectedOverride;
	private final DefaultListModel<ModelOverrideDocument.Entry> overrideRows = new DefaultListModel<>();
	private final JList<ModelOverrideDocument.Entry> overrideList = new JList<>(overrideRows);
	private final JLabel overrideCount = EditorText.builder("0 / 0").font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();
	private boolean projectileTarget;
	private final JLabel overrideEmpty = EditorText.builder("").color(WorkspaceStyle.DIM).build();
	private final JTextField overrideSearch = WorkspaceStyle.search("Search overrides");
	private final JLabel saveStatus = EditorText.builder("").build();
	private final JButton saveButton = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY)
		.text("Save overrides").icon("save", 13).enabled(false).build();
	private SegmentedControl<String> scope;
	private boolean scopeAll;
	private OverrideProperties properties;
	private FloatingDockWindow inGameView;
	private WorkspaceDocks docks;
	private JToggleButton pickColorButton;
	private JToggleButton inGameButton;
	private boolean pickColorActive;

	public ModelOverridesScreen(JFrame owner, EditorSession session) {
		super(new BorderLayout(1, 0));
		this.owner = owner;
		this.session = session;
		setBackground(WorkspaceStyle.GAP);
		list.setFont(WorkspaceStyle.UI);
		list.setFixedCellHeight(24);
		list.setCellRenderer(new DefaultListCellRenderer() {
			@Override public Component getListCellRendererComponent(JList<?> source, Object value, int index, boolean selected, boolean focus) {
				super.getListCellRendererComponent(source, value, index, selected, focus);
				var group = (CacheModels.Group) value;
				if (group != null)
					setIcon(browserBadge(group.root.category));
				setFont(WorkspaceStyle.MONO.deriveFont(12f));
				setIconTextGap(8);
				setForeground(WorkspaceStyle.TEXT);
				setBackground(selected ? new Color(0x334D73) : WorkspaceStyle.PANEL);
				setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
				return this;
			}
		});
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(e -> {
			if (!filteringBrowser && !e.getValueIsAdjusting() && list.getSelectedValue() != null)
				selectGroup(list.getSelectedValue());
		});
		search.setPreferredSize(new Dimension(0, 24));
		browserSearchDelay.setRepeats(false);
		EditorSearch.onChange(search, query -> browserSearchDelay.restart());
		var grid = new JPanel(new GridLayout(2, 2, 2, 2));
		grid.setBackground(WorkspaceStyle.MENU);
		grid.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		var group = new ButtonGroup();
		for (var item : CacheModels.Category.values()) {
			var button = EditorButton.builder().style(WorkspaceStyle.Button.SEGMENT)
				.text(item.name()).onClick(() -> switchCategory(item)).buildToggle(item == category);
			button.setHorizontalAlignment(SwingConstants.LEFT);
			button.setFont(WorkspaceStyle.UI.deriveFont(11f));
			button.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			button.setLayout(new BorderLayout());
			var count = EditorText.builder("0").font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();
			button.add(count, BorderLayout.EAST);
			counts.put(item, count);
			categories.put(item, button);
			group.add(button);
			grid.add(button);
		}
		grid.add(Box.createGlue());
		var refresh = EditorButton.builder().icon("refresh", 13).tooltip("Refresh cache index")
			.size(26, 48).onClick(() -> refreshRequested.set(true)).build();
		var categoryRow = new JPanel(new BorderLayout(6, 0));
		categoryRow.setOpaque(false);
		categoryRow.add(grid);
		categoryRow.add(refresh, BorderLayout.EAST);


		var controls = new JPanel(new BorderLayout(0, 6));
		controls.setBackground(WorkspaceStyle.PANEL);
		controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		controls.add(categoryRow, BorderLayout.NORTH);
		controls.add(browserSearch);
		var overrideFilters = new JPanel(new GridBagLayout());
		overrideFilters.setOpaque(false);
		var overrideFilterButtons = new ButtonGroup();
		String[] filterLabels = {"All", "With overrides", "Without"};
		for (var option : OverrideFilter.values()) {
			var button = EditorButton.builder().style(WorkspaceStyle.Button.SEGMENT)
				.text(filterLabels[option.ordinal()]).tooltip(option == OverrideFilter.WITHOUT ? "Without overrides" : null)
				.onClick(() -> { overrideFilter = option; filter(); }).buildToggle(option == overrideFilter);
			button.setFont(WorkspaceStyle.UI.deriveFont(11f));
			button.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
			if (option != OverrideFilter.ALL) {
				button.setHorizontalTextPosition(SwingConstants.LEFT);
				button.setIconTextGap(4);
				overrideFilterBadges.put(option, button);
			}
			overrideFilterButtons.add(button);
			var cell = new GridBagConstraints();
			cell.gridx = option.ordinal();
			cell.weightx = 1;
			cell.fill = GridBagConstraints.HORIZONTAL;
			cell.insets = new Insets(0, 0, 0, option == OverrideFilter.WITHOUT ? 0 : 4);
			overrideFilters.add(button, cell);
		}
		controls.add(overrideFilters, BorderLayout.SOUTH);
		var browserHeader = header("Browser");
		var browser = panel(browserHeader);
		var browseBody = new JPanel(new BorderLayout());
		browseBody.add(controls, BorderLayout.NORTH);
		browseBody.add(WorkspaceStyle.listScroll(list));
		browseBody.add(buildVariantPanel(), BorderLayout.SOUTH);
		browser.add(browseBody);

		var overridesHeader = header("Model overrides");
		var overridesPanel = panel(overridesHeader);
		overridesPanel.add(buildOverrideList(), BorderLayout.CENTER);

		var propertiesHeader = header("Properties");
		var propertiesPanel = panel(propertiesHeader);
		properties = new OverrideProperties(buildPropertiesContext());
		properties.onChange(this::onPropertyChanged);
		properties.onCreate(this::createNewOverride);
		properties.onCopyJson(this::copySelectedOverrideJson);
		properties.onPickColorRequested(this::beginColorPick);
		properties.onColorPickCancelled(() -> setColorPicking(false));
		registerKeyboardAction(e -> {
			if (pickColorActive) {
				properties.cancelColorPicking();
				setColorPicking(false);
			}
		}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		propertiesPanel.add(properties);

		viewport.setMinimumSize(new Dimension(335, 0));
		viewport.add(centerPreview);

		previewToolbar = buildPreviewToolbar();
		previewInfo = buildPreviewInfo();
		viewerStage.setOpaque(false);
		// null-layout containers report getPreferredSize() == getSize() (0,0 before the first real layout),
		// which throws off WorkspaceDocks' nested JSplitPanes' initial divider placement — give it a real size hint.
		viewerStage.setMinimumSize(new Dimension(260, 200));
		viewerStage.setPreferredSize(new Dimension(640, 480));
		viewerStage.add(viewport, JLayeredPane.DEFAULT_LAYER);
		viewerStage.add(previewToolbar, JLayeredPane.PALETTE_LAYER);
		viewerStage.add(previewInfo, JLayeredPane.PALETTE_LAYER);
		viewerStage.addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override public void componentResized(java.awt.event.ComponentEvent e) { layoutViewerStage(); }
		});

		docks = WorkspaceDocks.builder(viewerStage)
			.primaryPanel(browser, WorkspaceDocks.Side.LEFT)
			.panel(overridesPanel, WorkspaceDocks.Side.LEFT)
			.panel(propertiesPanel, WorkspaceDocks.Side.RIGHT)
			.width(WorkspaceDocks.Side.LEFT, 270)
			.leftStackWeight(.48)
			.width(WorkspaceDocks.Side.RIGHT, 400)
			.movable(browser, browserHeader, "Browser")
			.movable(overridesPanel, overridesHeader, "Model overrides")
			.movable(propertiesPanel, propertiesHeader, "Properties")
			.build();

		inGameView = new FloatingDockWindow(owner, viewerStage, "In-game view");
		inGameView.setStatus(false, "");
		inGameView.content().add(inGameViewBody());
		inGameView.onHide(() -> { inGameButton.setSelected(false); setInGameVisible(false); });
		inGameView.onDockChanged(dock -> {
			if (dock == FloatingDockWindow.Dock.PANEL) {
				docks.dockPanel(inGameView, inGameView.getPanelSide());
			} else if (inGameView.getParent() != viewerStage) {
				docks.undockPanel(inGameView);
				viewerStage.add(inGameView, JLayeredPane.MODAL_LAYER);
			}
			viewerStage.repaint();
		});
		docks.dockPanel(inGameView, inGameView.getPanelSide());
		inGameView.setVisible(true);

		var body = new JPanel(new BorderLayout());
		body.setBackground(WorkspaceStyle.GAP);
		body.add(docks);
		status.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
		status.setOpaque(true);
		status.setBackground(WorkspaceStyle.BAR);
		add(body);
		add(status, BorderLayout.SOUTH);

		var mouse = new MouseAdapter() {
			private Point previous;
			private Point downAt;
			private Integer dividerGrabOffset;
			private boolean orbiting;

			@Override public void mouseEntered(MouseEvent e) { mouseMoved(e); }
			@Override public void mouseMoved(MouseEvent e) {
				updateViewportCursor(e.getPoint(), dividerGrabOffset != null, orbiting);
				var bounds = centerPreview.imageBounds(false);
				var point = e.getPoint();
				centerPreview.hoverPick(pickColorActive && !orbiting && dividerGrabOffset == null ? point : null);
				windPointer = windPreview && !orbiting && dividerGrabOffset == null && bounds.contains(point)
					&& !"Vanilla".equals(previewMode) && (!"Split".equals(previewMode) || point.x >= centerPreview.dividerX())
					? new float[] { 2f * (point.x - bounds.x) / bounds.width - 1, 1 - 2f * (point.y - bounds.y) / bounds.height }
					: null;
			}
			@Override public void mouseExited(MouseEvent e) {
				centerPreview.hoverPick(null);
				windPointer = null;
				updateViewportCursor(null, dividerGrabOffset != null, orbiting);
			}

			@Override public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e) && centerPreview.isOverDivider(e.getX())) {
					dividerGrabOffset = e.getX() - centerPreview.dividerX();
					mouseMoved(e);
					return;
				}
				centerPreview.requestFocusInWindow();
				previous = downAt = e.getPoint();
			}

			@Override public void mouseReleased(MouseEvent e) {
				boolean pick = SwingUtilities.isLeftMouseButton(e) && dividerGrabOffset == null && pickColorActive && downAt != null
					&& downAt.distance(e.getPoint()) < 4;
				dividerGrabOffset = null;
				orbiting = false;
				previous = downAt = null;
				if (pick)
					pickPreviewFace(e.getPoint());
				mouseMoved(e);
			}

			@Override public void mouseDragged(MouseEvent e) {
				if (dividerGrabOffset != null) {
					centerPreview.moveDivider(e.getX() - dividerGrabOffset);
				} else if (previous != null) {
					orbiting = true;
					yaw -= (e.getX() - previous.x) * .012;
					pitch = Math.max(-1.35, Math.min(1.35, pitch + (e.getY() - previous.y) * .012));
					previous = e.getPoint();
				}
				mouseMoved(e);
			}

			@Override public void mouseWheelMoved(MouseWheelEvent e) {
				zoom = Math.max(.1, Math.min(12, zoom * Math.pow(1.1, -e.getPreciseWheelRotation())));
			}
		};
		centerPreview.setFocusable(true);
		centerPreview.addMouseListener(mouse);
		HdContextMenu.install(centerPreview, this::showFaceMenu);
		centerPreview.addMouseMotionListener(mouse);
		centerPreview.addMouseWheelListener(mouse);
		centerPreview.setCursor(WorkspaceStyle.cursor("grab"));
		setEntries(session.modelCatalog());
		selectInitialEntry();
	}

	// ---- Browser ----

	private void setEntries(CacheModels.Index indexed) {
		browserGroups = indexed.groups;
		var totals = new java.util.EnumMap<CacheModels.Category, Integer>(CacheModels.Category.class);
		for (var group : browserGroups)
			totals.merge(group.root.category, 1, Integer::sum);
		for (var item : CacheModels.Category.values())
			counts.get(item).setText(Integer.toString(totals.getOrDefault(item, 0)));
		if (activeGroup != null) {
			var root = activeGroup.root;
			activeGroup = browserGroups.stream()
				.filter(group -> group.root.category == root.category && group.root.id == root.id)
				.findFirst().orElse(null);
			if (activeGroup != null)
				selectEntity(findVariant(activeGroup, selectedEntity.id));
		}
		filter();
	}

	private void switchCategory(CacheModels.Category next) {
		if (category == next)
			return;
		category = next;
		if (search.getText().isEmpty())
			filter();
		else
			search.setText("");
	}

	private static Icon browserBadge(CacheModels.Category category) {
		switch (category) {
			case NPCs: return WorkspaceStyle.badge("N", new Color(0xD97A7A));
			case Objects: return WorkspaceStyle.badge("O", new Color(0x5B8AC2));
			default: return WorkspaceStyle.badge("G", new Color(0x9A6BC2));
		}
	}

	private void filter() {
		var doc = document;
		filter(doc == null ? null : doc.targetIds(category));
	}

	private void filter(Set<Integer> targets) {
		browserSearchDelay.stop();
		browserOverrideTargets = targets;
		String query = search.getText().strip().toLowerCase(Locale.ROOT);
		var matches = new ArrayList<CacheModels.Group>();
		int with = 0;
		int without = 0;
		for (var group : browserGroups) {
			if (group.root.category != category)
				continue;
			boolean hasOverride = false, hasNoOverride = false;
			boolean rootMatches = group.root.searchLabel.contains(query);
			for (var variant : group.variants) {
				if (!rootMatches && !variant.searchLabel.contains(query))
					continue;
				if (targets != null && targets.contains(variant.id))
					hasOverride = true;
				else
					hasNoOverride = true;
				if (hasOverride && hasNoOverride)
					break;
			}
			if (hasOverride)
				with++;
			if (hasNoOverride)
				without++;
			boolean show = overrideFilter == OverrideFilter.ALL
				? hasOverride || hasNoOverride
				: targets != null && (overrideFilter == OverrideFilter.WITH ? hasOverride : hasNoOverride);
			if (show)
				matches.add(group);
		}
		updateFilterBadge(OverrideFilter.WITH, targets == null ? "–" : Integer.toString(with));
		updateFilterBadge(OverrideFilter.WITHOUT, targets == null ? "–" : Integer.toString(without));

		filteringBrowser = true;
		try {
			rows.clear();
			rows.addAll(matches);
			if (activeGroup != null)
				list.setSelectedValue(activeGroup, false);
		} finally {
			filteringBrowser = false;
		}
		updateVariantPanel();
	}

	private void updateFilterBadge(OverrideFilter filter, String count) {
		var button = overrideFilterBadges.get(filter);
		button.setIcon(WorkspaceStyle.countBadge(button, count));
		button.getAccessibleContext().setAccessibleName(button.getText() + " (" + count + ")");
	}

	private CacheModels.Entry matchingVariant(CacheModels.Group group, String query, Set<Integer> targets, OverrideFilter filter) {
		boolean rootMatches = group.root.searchLabel.contains(query);
		for (var variant : group.variants)
			if ((rootMatches || variant.searchLabel.contains(query))
				&& (filter == OverrideFilter.ALL || targets != null && targets.contains(variant.id) == (filter == OverrideFilter.WITH)))
				return variant;
		return null;
	}

	private void selectGroup(CacheModels.Group group) {
		activeGroup = group;
		var match = matchingVariant(group, search.getText().strip().toLowerCase(Locale.ROOT), browserOverrideTargets, overrideFilter);
		selectEntity(match == null ? group.root : match);
	}

	private static CacheModels.Entry findVariant(CacheModels.Group group, int id) {
		for (var variant : group.variants)
			if (variant.id == id)
				return variant;
		return group.root;
	}

	private JComponent buildVariantPanel() {
		var heading = EditorText.builder("Variants").font(WorkspaceStyle.SEMIBOLD).build();
		variantPanel.setBackground(WorkspaceStyle.HEADER);
		variantPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		variantPanel.add(heading, BorderLayout.NORTH);
		var picker = new JPanel(new BorderLayout(6, 0));
		picker.setOpaque(false);
		picker.add(EditorButton.builder().icon("chevron-left", 11).tooltip("Previous variant")
			.size(24, 24).onClick(() -> cycleVariant(-1)).build(), BorderLayout.WEST);
		picker.add(variantLabel);
		picker.add(EditorButton.builder().icon("chevron-right", 11).tooltip("Next variant")
			.size(24, 24).onClick(() -> cycleVariant(1)).build(), BorderLayout.EAST);
		variantPanel.add(picker);
		variantPanel.setVisible(false);
		return variantPanel;
	}

	private void cycleVariant(int direction) {
		if (activeGroup == null)
			return;
		var variants = activeGroup.variants;
		int current = variants.indexOf(selectedEntity);
		selectEntity(variants.get(Math.floorMod(current + direction, variants.size())));
	}

	private void updateVariantPanel() {
		boolean visible = activeGroup != null && list.getSelectedValue() == activeGroup && activeGroup.variants.size() > 1
			&& selectedEntity != null && activeGroup.variants.contains(selectedEntity);
		variantPanel.setVisible(visible);
		if (visible) {
			var variants = activeGroup.variants;
			variantLabel.setText((variants.indexOf(selectedEntity) + 1) + "/" + variants.size() + " · " + selectedEntity.label);
			variantLabel.setToolTipText(selectedEntity.label);
		}
	}

	private void refreshBrowserOverrides() {
		var doc = document;
		var targets = doc == null ? null : doc.targetIds(category);
		if (!java.util.Objects.equals(browserOverrideTargets, targets))
			filter(targets);
	}

	private void selectEntity(CacheModels.Entry entry) {
		selectedEntity = entry;
		previewModel = null;
		properties.setPreviewModel(null);
		worldSearch.set(null);
		foundLocation = null;
		finishFinding(false);
		if (sceneSelector.getSelectedIndex() < 0)
			sceneSelector.setSelectedItem(scenePreset);
		findInWorld.setEnabled(entry != null);
		findInWorld.setToolTipText("Find this entity in the game cache");
		updateVariantPanel();
		updatePreviewOverride();
		centerPreview.clear();
		dockPreview.clear();
		pending.set(entry);
		refreshOverrideList(true);
	}

	// ---- Model overrides list ----

	private JPanel buildOverrideList() {
		var headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		var duplicate = EditorButton.builder().icon("duplicate", 13).tooltip("Duplicate (Ctrl+D)").size(26, 24)
			.onClick(this::duplicateSelectedOverride).build();
		var add = EditorButton.builder().text("+ New").size(0, 22).onClick(this::createNewOverride).build();
		var actionsRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actionsRow.setOpaque(false);
		actionsRow.add(duplicate);
		actionsRow.add(add);
		headerRow.add(overrideCount, BorderLayout.WEST);
		headerRow.add(actionsRow, BorderLayout.EAST);

		scope = SegmentedControl.builder(List.of("This entity", "All overrides")).build();
		scope.onChange(value -> { scopeAll = value.equals("All overrides"); refreshOverrideList(); });

		overrideSearch.setPreferredSize(new Dimension(0, 24));
		overrideSearchDelay.setRepeats(false);
		EditorSearch.onChange(overrideSearch, query -> overrideSearchDelay.restart());

		overrideList.setFont(WorkspaceStyle.UI);
		overrideList.setFixedCellHeight(38);
		overrideList.setCellRenderer(new OverrideRowRenderer());
		overrideList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		overrideList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting())
				selectOverride(overrideList.getSelectedValue());
		});
		HdContextMenu.install(list, this::showBrowserMenu);
		HdContextMenu.install(overrideList, this::showOverrideMenu);
		overrideList.registerKeyboardAction(e -> renameOverride(), KeyStroke.getKeyStroke("F2"), JComponent.WHEN_FOCUSED);
		overrideList.registerKeyboardAction(e -> copySelectedOverrideJson(), KeyStroke.getKeyStroke("ctrl shift J"), JComponent.WHEN_FOCUSED);
		list.registerKeyboardAction(e -> {
			if (selectedEntity != null)
				copyText(Integer.toString(selectedEntity.id));
		}, KeyStroke.getKeyStroke("ctrl C"), JComponent.WHEN_FOCUSED);
		list.registerKeyboardAction(e -> {
			if (selectedEntity != null && selectedEntity.gamevalName() != null)
				copyText(selectedEntity.gamevalName());
		}, KeyStroke.getKeyStroke("ctrl shift C"), JComponent.WHEN_FOCUSED);
		list.registerKeyboardAction(e -> {
			if (selectedEntity != null)
				selectEntity(selectedEntity);
		}, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED);
		overrideList.registerKeyboardAction(e -> duplicateSelectedOverride(), KeyStroke.getKeyStroke("ctrl D"), JComponent.WHEN_FOCUSED);

		var top = new JPanel(new BorderLayout(0, 6));
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
		top.add(scope, BorderLayout.NORTH);
		top.add(overrideSearch);

		var listArea = new JPanel(new BorderLayout());
		listArea.setBackground(WorkspaceStyle.PANEL);
		overrideEmpty.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		var listCards = new CardLayout();
		var listSwap = new JPanel(listCards);
		listSwap.add(WorkspaceStyle.listScroll(overrideList), "list");
		listSwap.add(overrideEmpty, "empty");
		listArea.add(listSwap);

		var footer = new JPanel(new BorderLayout(0, 8));
		footer.setBackground(WorkspaceStyle.PANEL);
		footer.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.MENU), BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		saveStatus.setFont(WorkspaceStyle.UI.deriveFont(11f));
		footer.add(saveStatus, BorderLayout.NORTH);
		saveButton.addActionListener(e -> saveSelectedOverride());
		saveButton.setPreferredSize(new Dimension(0, 30));
		footer.add(saveButton, BorderLayout.SOUTH);

		var body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.add(headerRow, BorderLayout.NORTH);
		var middle = new JPanel(new BorderLayout());
		middle.setOpaque(false);
		middle.add(top, BorderLayout.NORTH);
		middle.add(listArea);
		body.add(middle);
		body.add(footer, BorderLayout.SOUTH);
		overrideListSwap = listSwap;
		return body;
	}

	// Set once buildOverrideList() runs; used by refreshOverrideList() to swap the empty-state card.
	private JPanel overrideListSwap;

	private static final Color SELECTED_ROW_BACKGROUND = new Color(0x334D73);
	private static final Color SELECTED_NAME_COLOR = new Color(0xE6E6E6);
	private static final Color NAME_COLOR = new Color(0xD6D6D6);

	/** Swing calls this for every visible row on every list repaint, so the row components are built once
	 * and only their text/colors are updated per call, instead of rebuilding the whole component tree. */
	private static final class OverrideRowRenderer implements ListCellRenderer<ModelOverrideDocument.Entry> {
		private final JPanel row = new JPanel(new BorderLayout(8, 0));
		private final JLabel swatch = new JLabel();
		private final JLabel name = EditorText.builder("").build();
		private final JLabel meta = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();

		OverrideRowRenderer() {
			row.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
			row.add(swatch, BorderLayout.WEST);
			var text = new JPanel(new GridLayout(2, 1));
			text.setOpaque(false);
			text.add(name);
			text.add(meta);
			row.add(text);
		}

		@Override
		public Component getListCellRendererComponent(
			JList<? extends ModelOverrideDocument.Entry> list, ModelOverrideDocument.Entry entry,
			int index, boolean selected, boolean cellHasFocus
		) {
			row.setBackground(selected ? SELECTED_ROW_BACKGROUND : WorkspaceStyle.PANEL);
			swatch.setIcon(WorkspaceStyle.badge("", materialColor(entry.override.material)));
			name.setText(entry.override.description);
			name.setForeground(selected ? SELECTED_NAME_COLOR : NAME_COLOR);
			meta.setText(metaFor(entry.override));
			return row;
		}
	}

	private static Color materialColor(rs117.hd.scene.materials.Material material) {
		return material == null ? WorkspaceStyle.DIM : new Color(0x7D7D7D);
	}

	private static String metaFor(ModelOverride override) {
		StringBuilder meta = new StringBuilder();
		if (override.model != null)
			meta.append("model ").append(override.model);
		if (!override.npcIds.isEmpty())
			append(meta, override.npcIds.size() + " NPC");
		if (!override.objectIds.isEmpty())
			append(meta, override.objectIds.size() + " Object");
		if (!override.graphicsObjectIds.isEmpty())
			append(meta, override.graphicsObjectIds.size() + " Graphic");
		return meta.length() == 0 ? "No targets" : meta.toString();
	}

	private static void append(StringBuilder meta, String part) {
		if (meta.length() > 0)
			meta.append(" · ");
		meta.append(part);
	}

	private void refreshOverrideList() { refreshOverrideList(false); }

	private void refreshOverrideList(boolean autoSelect) {
		overrideSearchDelay.stop();
		var previous = selectedOverride;
		overrideRows.clear();
		var doc = document;
		if (doc == null) {
			overrideEmpty.setText("Still loading overrides…");
			showEmptyOverrideCard();
			return;
		}
		String query = overrideSearch.getText().strip().toLowerCase(Locale.ROOT);
		List<ModelOverrideDocument.Entry> candidates = new ArrayList<>();
		ModelOverrideDocument.Entry firstTarget = null;
		int targetCount = 0;
		for (var entry : doc.entries()) {
			boolean targets = selectedEntity != null && doc.targets(entry.override, selectedEntity.category, selectedEntity.id);
			if (targets)
				targetCount++;
			boolean inScope = scopeAll || selectedEntity == null || targets;
			boolean matchesQuery = query.isEmpty() || entry.override.description.toLowerCase(Locale.ROOT).contains(query);
			if (inScope && matchesQuery) {
				candidates.add(entry);
				if (targets && firstTarget == null)
					firstTarget = entry;
			}
		}
		properties.setEmptyEntity(selectedEntity, targetCount);
		overrideCount.setText(candidates.size() + " / " + doc.entries().size());
		if (candidates.isEmpty()) {
			overrideEmpty.setText(selectedEntity == null ? "Select an entity to see its overrides."
				: "No overrides target " + selectedEntity.displayName + " yet. Use Create override in the Properties panel, or press New.");
			showEmptyOverrideCard();
			return;
		}
		overrideRows.addAll(candidates);
		var selection = autoSelect ? firstTarget : previous;
		if (selection != null)
			overrideList.setSelectedValue(selection, true);
		if (overrideListSwap != null)
			((CardLayout) overrideListSwap.getLayout()).show(overrideListSwap, "list");
	}

	private void showEmptyOverrideCard() {
		if (overrideListSwap != null)
			((CardLayout) overrideListSwap.getLayout()).show(overrideListSwap, "empty");
	}

	private void selectOverride(ModelOverrideDocument.Entry entry) {
		selectedOverride = entry;
		updatePreviewOverride();
		if (entry == null) {
			saveButton.setEnabled(false);
			properties.setOverride(null, null, null);
			return;
		}
		saveButton.setEnabled(true);
		properties.setOverride(entry.override, targetSummaryFor(entry), previewModel);
		selectionChangedListener.run();
	}

	private String targetSummaryFor(ModelOverrideDocument.Entry entry) {
		return selectedEntity == null ? entry.override.description
			: selectedEntity.category + " " + selectedEntity.id + " · " + selectedEntity.displayName;
	}

	/** Lets the owning shell reset its own undo history to whatever override is now selected. */
	public void onSelectionChanged(Runnable listener) { selectionChangedListener = listener; }

	/** The currently selected override's description, or null if nothing is selected — for the owning shell's undo history label. */
	public String selectedOverrideLabel() { return selectedOverride == null ? null : selectedOverride.override.description; }

	/** Shows/hides the left column (Browser + Model overrides) — the top bar's global sidebar toggle. */
	public void setSidebarVisible(boolean visible) { docks.setSideVisible(WorkspaceDocks.Side.LEFT, visible); }

	private static <T> Point selectMenuRow(JList<T> list, Point point) {
		int index = point == null ? list.getSelectedIndex() : list.locationToIndex(point);
		if (index < 0)
			return null;
		var bounds = list.getCellBounds(index, index);
		if (point != null && !bounds.contains(point))
			return null;
		list.setSelectedIndex(index);
		return point == null ? new Point(bounds.x, bounds.y + bounds.height) : point;
	}

	private void showBrowserMenu(Point point) {
		point = selectMenuRow(list, point);
		if (point == null || selectedEntity == null)
			return;
		var entry = selectedEntity;
		var doc = document;
		long count = doc == null ? 0 : doc.entries().stream()
			.filter(row -> doc.targets(row.override, entry.category, entry.id)).count();
		String badge = entry.category == CacheModels.Category.NPCs ? "N" : entry.category == CacheModels.Category.Objects ? "O" : "G";
		int color = badge.equals("N") ? 0xC25B5B : badge.equals("O") ? 0x5B8AC2 : 0x9A6BC2;
		var menu = new HdContextMenu(badge, new Color(color), entry.category + " · " + entry.label)
			.item("Load", "load", "Enter", () -> selectEntity(entry))
			.item("Find in world", "find", "", () -> { setInGameVisible(true); findInWorld.doClick(); })
			.separator()
			.item("New model override", "plus", "", this::createNewOverride)
			.disabled("New light definition", "light", "Light editing is not available");
		if (count > 0)
			menu.item("Show overrides (" + count + ")", "layers", "", this::showEntityOverrides);
		else
			menu.disabled("Show overrides (0)", "layers", "This entity has no overrides");
		menu.separator().item("Copy ID", "copy", "Ctrl+C", () -> copyText(Integer.toString(entry.id)));
		if (entry.gamevalName() != null)
			menu.item("Copy gameval name", "tag", "Ctrl+Shift+C", () -> copyText(entry.gamevalName()));
		else
			menu.disabled("Copy gameval name", "tag", "This entity has no gameval name");
		menu.show(list, point.x, point.y);
	}

	private void showEntityOverrides() {
		scopeAll = false;
		scope.setSelected("This entity");
		overrideSearch.setText("");
		refreshOverrideList(true);
	}

	private void showOverrideMenu(Point point) {
		point = selectMenuRow(overrideList, point);
		if (point == null || selectedOverride == null)
			return;
		var entry = selectedOverride;
		var menu = new HdContextMenu("O", materialColor(entry.override.material), entry.override.description)
			.item("Rename", "rename", "F2", this::renameOverride)
			.item("Duplicate", "duplicate", "Ctrl+D", this::duplicateSelectedOverride)
			.item("Copy JSON", "json", "Ctrl+Shift+J", this::copySelectedOverrideJson);
		if (!entry.isPersisted()) {
			menu.separator().danger("Delete override", "trash", "", () -> {
				document.discard(entry);
				refreshBrowserOverrides();
				refreshOverrideList(true);
			});
		} else {
			menu.separator().disabled("Delete override", "trash", "This override is saved in the project file");
		}
		menu.show(overrideList, point.x, point.y);
	}

	private void renameOverride() {
		var entry = selectedOverride;
		if (entry == null)
			return;
		var name = JOptionPane.showInputDialog(this, "Override name", entry.override.description);
		if (name != null && !name.isBlank() && !name.equals(entry.override.description)) {
			entry.override.description = name.strip();
			properties.setOverride(entry.override, targetSummaryFor(entry), previewModel);
			onPropertyChanged();
		}
	}

	private static void copyText(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
	}

	private void duplicateSelectedOverride() {
		var doc = document;
		if (doc == null || selectedOverride == null)
			return;
		var copy = doc.duplicate(selectedOverride);
		refreshBrowserOverrides();
		refreshOverrideList();
		overrideList.setSelectedValue(copy, true);
		markUnsaved();
	}

	private void createNewOverride() {
		var entity = selectedEntity;
		if (entity == null || entity.category != CacheModels.Category.Graphics) {
			createNewOverride(false);
			return;
		}
		var content = new JPanel(new BorderLayout(0, 10));
		content.setBackground(WorkspaceStyle.MENU);
		content.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
		content.add(EditorText.builder("New override for " + entity.displayName)
			.font(WorkspaceStyle.SEMIBOLD.deriveFont(14f)).build(), BorderLayout.NORTH);
		var choices = new JPanel(new GridLayout(2, 1, 0, 6));
		choices.setOpaque(false);
		var graphics = new JRadioButton("Graphics object  ·  scene spot animations", true);
		var projectile = new JRadioButton("Projectile  ·  travelling effects");
		var group = new ButtonGroup();
		for (var choice : List.of(graphics, projectile)) {
			group.add(choice);
			choice.setFont(WorkspaceStyle.UI.deriveFont(12f));
			choice.setBackground(new Color(0x292929));
			choice.setForeground(WorkspaceStyle.TEXT);
			choice.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			choices.add(choice);
		}
		content.add(choices);
		Runnable close = () -> SwingUtilities.getWindowAncestor(content).dispose();
		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		actions.setOpaque(false);
		actions.add(EditorButton.builder().text("Cancel").onClick(close).build());
		var create = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY).text("Create override").onClick(() -> {
			close.run();
			if (selectedEntity == entity)
				createNewOverride(projectile.isSelected());
		}).build();
		actions.add(create);
		content.add(actions, BorderLayout.SOUTH);
		var dialog = EditorModal.builder(owner, "New override").content(content)
			.size(Math.min(460, owner.getWidth() - 40), 218).initialFocus(graphics).open();
		dialog.getRootPane().setDefaultButton(create);
	}

	private void createNewOverride(boolean projectile) {
		var doc = document;
		if (doc == null)
			return;
		var seed = new ModelOverride();
		if (selectedEntity != null) {
			seed.description(selectedEntity.displayName);
			switch (selectedEntity.category) {
				case NPCs: seed.npcIds(new java.util.HashSet<>(Set.of(selectedEntity.id))); break;
				case Objects: seed.objectIds(new java.util.HashSet<>(Set.of(selectedEntity.id))); break;
				case Graphics:
					projectileTarget = projectile;
					if (projectileTarget)
						seed.projectileIds(new java.util.HashSet<>(Set.of(selectedEntity.id)));
					else
						seed.graphicsObjectIds(new java.util.HashSet<>(Set.of(selectedEntity.id)));
					break;
			}
		}
		var created = doc.create(seed);
		if (selectedEntity != null) {
			scopeAll = false;
			scope.setSelected("This entity");
			overrideSearch.setText("");
		}
		refreshBrowserOverrides();
		refreshOverrideList();
		overrideList.setSelectedValue(created, true);
		markUnsaved();
		status.setText("New override for " + seed.description);
		centerPreview.toast("Created override · " + seed.description);
	}

	private void saveSelectedOverride() {
		var doc = document;
		var entry = selectedOverride;
		if (doc == null || entry == null)
			return;
		var rules = entry.override.colorOverrides;
		if (rules != null) {
			for (int i = 0; i < rules.length; i++) {
				if (!ColorExpression.valid(ColorExpression.text(ModelOverrideFields.get(rules[i], "colors", com.google.gson.JsonElement.class)))) {
					centerPreview.toast("Rule " + (i + 1) + " has an invalid colors expression");
					return;
				}
			}
		}
		String json = doc.toSparseJson(entry);
		new Thread(() -> {
			try {
				doc.save(entry, json);
				SwingUtilities.invokeLater(() -> {
					saveStatus.setForeground(new Color(0x6CB348));
					saveStatus.setText("All changes saved");
					refreshBrowserOverrides();
					refreshOverrideList();
				});
			} catch (IOException e) {
				log.error("Could not save model overrides", e);
				SwingUtilities.invokeLater(() -> {
					saveStatus.setForeground(new Color(0xE06060));
					saveStatus.setText("Could not save · " + e.getMessage());
				});
			}
		}, "model-overrides save").start();
	}

	private void markUnsaved() {
		saveStatus.setForeground(new Color(0xE8A33D));
		saveStatus.setText("Unsaved changes");
	}

	// ---- Bridge for EditorShell's shared undo history / command palette (different package, no access to our internals) ----

	/** A snapshot of the currently selected override's state, for the owning shell's undo history. */
	public static JsonObject capture(ModelOverridesScreen screen) {
		var result = new JsonObject();
		var doc = screen.document;
		var entry = screen.selectedOverride;
		if (doc != null && entry != null)
			result.add("override", doc.gson().toJsonTree(entry.override, ModelOverride.class));
		return result;
	}

	/** Restores a snapshot previously returned by {@link #capture}, onto whatever override is currently selected. */
	public static void restore(ModelOverridesScreen screen, JsonObject state) {
		var doc = screen.document;
		var entry = screen.selectedOverride;
		if (doc == null || entry == null || !state.has("override"))
			return;
		entry.override = doc.gson().fromJson(state.get("override"), ModelOverride.class);
		screen.properties.setOverride(entry.override, screen.targetSummaryFor(entry), screen.previewModel);
		screen.updatePreviewOverride();
		screen.overrideList.repaint();
		screen.markUnsaved();
		screen.refreshBrowserOverrides();
	}

	/** Saves whatever override is currently selected — the Ctrl+K "Save model overrides" command. */
	public static void saveSelected(ModelOverridesScreen screen) { screen.saveSelectedOverride(); }

	private void onPropertyChanged() {
		updatePreviewOverride();
		refreshBrowserOverrides();
		markUnsaved();
		if (overrideList.getSelectedValue() != null)
			overrideList.repaint();
	}

	private void copySelectedOverrideJson() {
		var doc = document;
		if (doc == null || selectedOverride == null)
			return;
		var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		String json = doc.toSparseJson(selectedOverride);
		clipboard.setContents(new java.awt.datatransfer.StringSelection(json), null);
		int fields = doc.gson().fromJson(json, JsonObject.class).entrySet().size();
		status.setText("Copied " + fields + " fields as JSON");
	}

	// ---- Viewport face and color picking ----

	private void updateViewportCursor(Point point, boolean resizing, boolean orbiting) {
		Cursor cursor;
		if (resizing) {
			cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
		} else if (orbiting) {
			cursor = WorkspaceStyle.cursor("orbit");
		} else if (point != null && centerPreview.contains(point) && centerPreview.isOverDivider(point.x)) {
			cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
		} else {
			cursor = WorkspaceStyle.cursor(pickColorActive ? "pick-face" : "grab");
		}
		centerPreview.setDividerActive(cursor.getType() == Cursor.E_RESIZE_CURSOR);
		centerPreview.setCursor(cursor);
	}

	private void setColorPicking(boolean active) {
		pickColorActive = active;
		centerPreview.hoverPick(active ? centerPreview.getMousePosition() : null);
		if (pickColorButton == null)
			return;
		pickColorButton.setSelected(active);
		updateViewportCursor(centerPreview.getMousePosition(), false, false);
	}

	private void beginColorPick() {
		setColorPicking(true);
	}

	private int faceAt(Point point) {
		boolean vanilla = "Vanilla".equals(previewMode) || ("Split".equals(previewMode) && point.x < centerPreview.dividerX());
		var picker = vanilla ? vanillaFacePicker : overrideFacePicker;
		if (picker == null || !picker.matches(previewModel))
			return -1;
		var imageBounds = centerPreview.imageBounds(vanilla);
		if (!imageBounds.contains(point))
			return -1;
		return picker.pick(new Point(point.x - imageBounds.x, point.y - imageBounds.y), imageBounds.width, imageBounds.height);
	}

	private void showFaceMenu(Point point) {
		if (point == null)
			point = new Point(centerPreview.getWidth() / 2, centerPreview.getHeight() / 2);
		int face = faceAt(point);
		if (face < 0)
			return;
		var model = previewModel;
		if (model == null || face >= model.getFaceCount())
			return;
		var menu = new HdContextMenu("F", new Color(0x4772B3), "Face " + face)
			.disabled("Place light here", "light", "Light editing is not available")
			.disabled("Place light at face centre", "place-centre", "Light editing is not available")
			.separator();
		if (selectedOverride != null) {
			menu.item("Pick color for override", "eyedropper", "", () -> properties.colorPicked(PreviewFacePicker.faceColor(model, face)));
		} else {
			menu.disabled("Pick color for override", "eyedropper", "Select a model override first");
		}
		menu.separator().item("Copy face ID", "copy", "", () -> copyText(Integer.toString(face)))
			.show(centerPreview, point.x, point.y);
	}

	private void pickPreviewFace(Point point) {
		int face = faceAt(point);
		if (face < 0)
			return;
		properties.colorPicked(PreviewFacePicker.faceColor(previewModel, face));
		setColorPicking(false);
	}

	// ---- Preview toolbar / in-game view ----

	private JPanel buildPreviewToolbar() {
		var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		toolbar.setOpaque(false);
		toolbar.setBounds(8, 8, 900, 40);

		toolbar.add(EditorButtonGroup.builder(List.of("Vanilla", "Override", "Split")).selected(previewMode).track().onChange(value -> {
			previewMode = value;
			centerPreview.mode(value);
			updateViewportCursor(centerPreview.getMousePosition(), false, false);
		}).build());
		toolbar.add(EditorButtonGroup.builder(List.of("Material", "UV checker", "Normals", "Wire"))
			.selected(previewShading).track().onChange(value -> previewShading = value).build());

		pickColorButton = EditorButton.builder().style(WorkspaceStyle.Button.TRIGGER)
			.icon("eyedropper", 13).text("Pick color").tooltip("Pick color (I)").buildToggle(false);
		pickColorButton.addActionListener(e -> {
			boolean active = pickColorButton.isSelected();
			if (!active)
				properties.cancelColorPicking();
			setColorPicking(active);
		});
		registerKeyboardAction(e -> pickColorButton.doClick(), KeyStroke.getKeyStroke("pressed I"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		inGameButton = EditorButton.builder().style(WorkspaceStyle.Button.TRIGGER)
			.icon("in-game-toggle", 14).text("In-game").tooltip("Toggle in-game view (G)")
			.onToggle(this::setInGameVisible).buildToggle(true);
		registerKeyboardAction(e -> inGameButton.doClick(), KeyStroke.getKeyStroke("pressed G"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		var frame = EditorButton.builder().style(WorkspaceStyle.Button.TRIGGER).text("Frame").icon("frame-all", 13)
			.onClick(() -> { yaw = .55; pitch = .30; zoom = 1; }).build();

		toolbar.add(pickColorButton);
		toolbar.add(inGameButton);
		toolbar.add(frame);
		return toolbar;
	}

	private JPanel buildPreviewInfo() {
		var info = new JPanel(new GridLayout(0, 1, 0, 2));
		info.setOpaque(false);
		var line1 = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(11f)).color(new Color(0xD6D6D6)).build();
		var line2 = EditorText.builder("Drag to orbit · wheel to zoom").font(WorkspaceStyle.UI.deriveFont(11f)).color(new Color(0x6F6F6F)).build();
		info.add(line1);
		info.add(line2);
		info.setBounds(10, 400, 500, 40);
		previewInfoLine = line1;
		return info;
	}

	private JLabel previewInfoLine;

	private void layoutViewerStage() {
		int width = viewerStage.getWidth(), height = viewerStage.getHeight();
		viewport.setBounds(0, 0, width, height);
		int available = Math.max(0, width);
		int rowWidth = 8, rowHeight = 0, toolbarHeight = 0;
		for (Component item : previewToolbar.getComponents()) {

			var size = item.getPreferredSize();
			if (rowHeight != 0 && rowWidth + size.width + 8 > available) {
				toolbarHeight += rowHeight;
				rowWidth = 8;
				rowHeight = 0;
			}
			rowWidth += size.width + 8;
			rowHeight = Math.max(rowHeight, size.height);
		}
		previewToolbar.setBounds(0, 8, available, toolbarHeight + rowHeight);
		centerPreview.splitLabelBaseline(previewToolbar.getY() + previewToolbar.getHeight());
		previewToolbar.doLayout();
		for (Component item : previewToolbar.getComponents())
			if (item instanceof Container)
				((Container) item).doLayout();
		previewInfo.setBounds(10, Math.max(0, height - 50), Math.max(0, Math.min(500, width - 20)), 40);
		if (inGameView != null) {
			inGameView.setOverlayTopInset(previewToolbar.getY() + previewToolbar.getHeight());
			inGameView.layoutOverlay();
		}
		viewerStage.revalidate();
		viewerStage.repaint();
	}

	private static final class Orbit {
		final double yaw, pitch, zoom;

		Orbit(double yaw, double pitch, double zoom) {
			this.yaw = yaw;
			this.pitch = pitch;
			this.zoom = zoom;
		}
	}

	private JComponent inGameViewBody() {
		var panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);
		var controls = new JPanel(new net.miginfocom.swing.MigLayout("insets 4 6, gap 6 4, flowx", "", ""));
		controls.setBackground(WorkspaceStyle.BAR);
		controls.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x1A1A1A)));
		findInWorld = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY).icon("find-in-world", 12).text("Find in world")
			.tooltip("Find this entity in the game cache").enabled(false).onClick(() -> {
				var entry = selectedEntity;
				if (entry != null) {
					findingEntry = entry;
					findInWorld.setEnabled(false);
					findInWorld.setText("Finding…");
					gamePreviewCards.show(gamePreviewBody, "finding");
					findingOverlay.start(entry.displayName);
					worldSearch.set(entry);
					say("Searching map placements · " + entry.label);
				}
			}).build();
		findInWorld.setFont(WorkspaceStyle.UI.deriveFont(11f));
		controls.add(findInWorld);
		sceneSelector = EditorSelect.<ScenePreset>builder().options(List.of(ScenePreset.values()))
			.selected(ScenePreset.SKY).size(110, 22).tooltip("In-game backdrop")
			.renderer(new DefaultListCellRenderer() {
				@Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
					super.getListCellRendererComponent(list, value == null ? ScenePreset.LIVE : value, index, selected, focus);
					setFont(WorkspaceStyle.UI.deriveFont(11f));
					setIcon(WorkspaceStyle.badge("", new Color(value == ScenePreset.CAVE ? 0x4A3A2C : value == ScenePreset.GRASS ? 0x618342 : 0x8FB3CF)));
					return this;
				}
			})
			.onChange(value -> { scenePreset = value; foundLocation = null; cancelFinding(); }).build();
		controls.add(sceneSelector);
		var surrounding = EditorButton.builder().style(WorkspaceStyle.Button.SCENE_TOGGLE)
			.icon("scene-isolated", 12).text("Isolated").tooltip("Show or hide surrounding objects").buildToggle(false);
		surrounding.setFont(WorkspaceStyle.UI.deriveFont(11f));
		surrounding.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));
		surrounding.addActionListener(e -> {
			showSurroundings = surrounding.isSelected();
			surrounding.setText(showSurroundings ? "Scene" : "Isolated");
			surrounding.setIcon(WorkspaceStyle.icon(showSurroundings ? "scene-layers" : "scene-isolated", 12));
		});
		var instances = SegmentedControl.builder(List.of("One", "All")).selected("All").build();
		instances.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
		instances.onChange(value -> showAllPlacements = "All".equals(value));
		controls.add(surrounding);
		controls.add(instances, "height 22!");
		panel.add(controls, BorderLayout.NORTH);
		panel.addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override public void componentResized(java.awt.event.ComponentEvent event) {
				var layout = (net.miginfocom.swing.MigLayout) controls.getLayout();
				layout.setLayoutConstraints(panel.getWidth() < 390 ? "insets 4 6, gap 6 4, wrap 2" : "insets 4 6, gap 6 4");
				panel.revalidate();
			}
		});
		findingOverlay.addCancel(this::cancelFinding);
		gamePreviewBody.add(dockPreview, "preview");
		gamePreviewBody.add(findingOverlay, "finding");
		panel.add(gamePreviewBody);
		dockPreview.setCursor(WorkspaceStyle.cursor("grab"));
		var mouse = new MouseAdapter() {
			private Point previous;

			@Override public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e))
					previous = e.getPoint();
			}

			@Override public void mouseDragged(MouseEvent e) {
				if (previous == null)
					return;
				var camera = gameCamera;
				gameCamera = new Orbit(camera.yaw - (e.getX() - previous.x) * .012,
					Math.max(-1.35, Math.min(1.35, camera.pitch + (e.getY() - previous.y) * .012)), camera.zoom);
				previous = e.getPoint();
				dockPreview.setCursor(WorkspaceStyle.cursor("orbit"));
			}

			@Override public void mouseReleased(MouseEvent e) {
				previous = null;
				dockPreview.setCursor(WorkspaceStyle.cursor("grab"));
			}

			@Override public void mouseWheelMoved(MouseWheelEvent e) {
				var camera = gameCamera;
				gameCamera = new Orbit(camera.yaw, camera.pitch,
					Math.max(.25, Math.min(3, camera.zoom * Math.pow(1.1, -e.getPreciseWheelRotation()))));
			}
		};
		dockPreview.addMouseListener(mouse);
		dockPreview.addMouseMotionListener(mouse);
		dockPreview.addMouseWheelListener(mouse);
		return panel;
	}

	private void cancelFinding() {
		worldSearch.set(null);
		finishFinding(false);
	}

	private void finishFinding(boolean failed) {
		findingEntry = null;
		findingOverlay.stop();
		gamePreviewCards.show(gamePreviewBody, "preview");
		findInWorld.setEnabled(selectedEntity != null);
		findInWorld.setText("Find in world");
		if (failed)
			dockPreview.toast("Could not find");
	}

	private static final class FindingOverlay extends JPanel {
		private final JLabel entity = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f))
			.color(WorkspaceStyle.DIM).build();
		private final JComponent spinner = new JComponent() {
			@Override public Dimension getPreferredSize() { return new Dimension(24, 24); }

			@Override protected void paintComponent(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(WorkspaceStyle.BORDER);
				g.drawOval(2, 2, 20, 20);
				g.setColor(WorkspaceStyle.ACCENT);
				int angle = (int) (-(System.nanoTime() % 800_000_000L) * 360 / 800_000_000L);
				g.drawArc(2, 2, 20, 20, angle, 110);
				g.dispose();
			}
		};
		private final Timer animation = new Timer(16, e -> spinner.repaint());

		FindingOverlay() {
			setBackground(new Color(0x202020));
			setLayout(new GridBagLayout());
			addRow(spinner, 0);
			addRow(EditorText.builder("Finding in world…").build(), 1);
			entity.setHorizontalAlignment(SwingConstants.CENTER);
			entity.setPreferredSize(new Dimension(0, entity.getPreferredSize().height));
			addRow(entity, 2);
		}

		void addCancel(Runnable cancel) {
			addRow(EditorButton.builder().text("Cancel").onClick(cancel).build(), 3);
		}

		private void addRow(JComponent component, int row) {
			var cell = new GridBagConstraints();
			cell.gridx = 0;
			cell.gridy = row;
			cell.insets = new Insets(row == 0 ? 0 : 4, 8, 0, 8);
			if (component == entity) {
				cell.weightx = 1;
				cell.fill = GridBagConstraints.HORIZONTAL;
			}
			add(component, cell);
		}

		void start(String name) {
			entity.setText(name);
			entity.setToolTipText(name);
			animation.start();
		}

		void stop() { animation.stop(); }
		@Override public void removeNotify() { stop(); super.removeNotify(); }
		@Override public void addNotify() { super.addNotify(); if (isVisible()) animation.start(); }
	}

	private void updatePreviewOverride() {
		var doc = document;
		var entity = selectedEntity;
		var selection = selectedOverride;
		previewOverrideJson = doc != null && entity != null && selection != null
			&& doc.targets(selection.override, entity.category, entity.id) ? doc.toSparseJson(selection.override) : null;
	}

	private void setInGameVisible(boolean visible) {
		dockVisible = visible;
		if (!visible)
			dockPreview.clear();
		inGameView.setVisible(visible);
		if (!visible && findingEntry != null) {
			worldSearch.set(null);
			finishFinding(false);
		}
		if (inGameView.getDock() == FloatingDockWindow.Dock.PANEL) {
			if (visible)
				docks.dockPanel(inGameView, inGameView.getPanelSide());
			else
				docks.undockPanel(inGameView);
		} else if (visible) {
			if (inGameView.getParent() != viewerStage)
				viewerStage.add(inGameView, JLayeredPane.MODAL_LAYER);
			inGameView.layoutOverlay();
		}
		viewerStage.repaint();
	}

	private void refreshPreview() {
		var entry = selectedEntity;
		if (entry != null)
			pending.set(entry);
	}

	// ---- Properties <-> ModelOverride wiring ----

	private OverrideProperties.Context buildPropertiesContext() {
		return new OverrideProperties.Context() {
			private rs117.hd.scene.materials.Material[] materialSource;
			private List<rs117.hd.scene.materials.Material> sortedMaterials = List.of();
			@Override public void colorToast(String message) { centerPreview.toast(message); }

			@Override public void highlightColors(ModelOverride.AhslPredicate rule, Integer hover) {
				centerPreview.highlightColors(rule, hover);
			}

			@Override public void setWindPreview(boolean enabled) {
				windPointer = null;
				windPreview = enabled;
			}

			@Override public int[] vanillaTexturePixels(int textureId) {
				return session.vanillaTexturePixels(textureId);
			}

			@Override public List<rs117.hd.scene.materials.Material> materials() {
				var source = MaterialManager.MATERIALS;
				if (source == materialSource)
					return sortedMaterials;
				materialSource = source;
				if (source == null) {
					sortedMaterials = List.of();
					return sortedMaterials;
				}
				var featured = List.of("NONE", "UNLIT", "WOOD_GRAIN", "WOOD_GRAIN_LIGHT", "BARK", "STONE");
				sortedMaterials = java.util.Arrays.stream(source)
					.sorted(java.util.Comparator.comparingInt((rs117.hd.scene.materials.Material material) -> {
						int index = featured.indexOf(material.name);
						return index >= 0 ? index : featured.size();
					})
						.thenComparing(material -> material.name))
					.collect(java.util.stream.Collectors.toList());
				return sortedMaterials;
			}

			@Override public String nearbyObject(int id) {
				Integer count = nearbyObjects.get(id);
				return count == null ? null : "nearby · " + count + "×";
			}

			@Override public List<SearchableIdDropdown.Choice> npcChoices() { return choicesFor(CacheModels.Category.NPCs, "N", new Color(0xD97A7A)); }
			@Override public List<SearchableIdDropdown.Choice> objectChoices() { return choicesFor(CacheModels.Category.Objects, "O", new Color(0x7CB6A0)); }
			@Override public List<SearchableIdDropdown.Choice> spotanimChoices() { return choicesFor(CacheModels.Category.Graphics, "G", new Color(0xC79BE0)); }
		};
	}

	private CacheModels.Index choicesCatalog;
	private final java.util.Map<CacheModels.Category, List<SearchableIdDropdown.Choice>> cachedChoices =
		new java.util.EnumMap<>(CacheModels.Category.class);

	private List<SearchableIdDropdown.Choice> choicesFor(CacheModels.Category wanted, String letter, Color color) {
		var catalog = session.modelCatalog();
		if (choicesCatalog != catalog) {
			choicesCatalog = catalog;
			cachedChoices.clear();
		}
		return cachedChoices.computeIfAbsent(wanted, category -> {
			List<SearchableIdDropdown.Choice> choices = new ArrayList<>();
			for (var entry : catalog.entries)
				if (entry.category == category)
					choices.add(new SearchableIdDropdown.Choice(entry.id, entry.displayName, letter, color, entry.searchLabel));
			return List.copyOf(choices);
		});
	}

	// ---- Render loop ----

	private Runnable initialPreviewReady;

	public void whenInitialPreviewReady(Runnable action) {
		if (selectedEntity == null)
			action.run();
		else
			initialPreviewReady = action;
	}

	private void signalInitialPreviewReady() {
		var action = initialPreviewReady;
		initialPreviewReady = null;
		if (action != null)
			action.run();
	}

	public void render(HdRuntime runtime, BuiltScene scene, rs117.hd.dev.definitions.DefinitionsScreen definitions)
		throws InterruptedException {
		try (var gamevals = runtime.openGamevals()) {
			say("Cache loaded · Select a model");
			document = new ModelOverrideDocument(runtime.getGson(), modelOverridesFile(), gamevals);
			SwingUtilities.invokeLater(() -> { refreshBrowserOverrides(); refreshOverrideList(true); });
			double distance = 300;
			double targetY = -50;
			int modelBaseY = 0;
			int modelBottom = 0;
			int tileX = TILE_X, tileZ = TILE_Z, plane = 0, orientation = 0, shape = 10;
			BuiltScene previewScene = scene;
			BuiltScene indexedScene = null;
			ScenePreset appliedPreset = null;
			boolean appliedSurroundings = false;
			boolean appliedAll = true;
			net.runelite.cache.region.Location appliedLocation = null;
			CacheModels.Entry renderedEntry = null;
			String appliedJson = null;
			rs117.hd.scene.model_overrides.ModelOverride draft = null;
			int dirtyPasses = 0;
			int lastCenterPass = OVERRIDE_PASS;
			long nextWindFrame = 0;
			boolean previousWind = false;
			float[] windProjection = null;
			Orbit previousGameCamera = null;
			double previousYaw = Double.NaN, previousPitch = Double.NaN, previousZoom = Double.NaN;
			String previousMode = null, previousShading = null;
			boolean previousDock = false;
			var frameUpdates = new FrameUpdates();
			var previewGrid = new PreviewGrid();
			BuiltModel installedModel = null;
			BuiltModel baseModel = null;
			BuiltModel normals = null;
			BuiltModel vanillaUv = null;
			BuiltModel overrideUv = null;
			var water = new rs117.hd.dev.definitions.WaterPreview(session, definitions);
			long loadedGeneration = previewGeneration;
			while (!Thread.currentThread().isInterrupted()) {
				long generation = previewGeneration;
				if (generation != loadedGeneration) {
					loadedGeneration = generation;
					frameUpdates.clear();
					session.clearPreviewCaches();
					if (previewScene != scene || baseModel != null)
						runtime.releasePreviewScene(scene);
					previewScene = scene;
					indexedScene = null;
					nearbyObjects = java.util.Map.of();
					renderedEntry = null;
					baseModel = installedModel = normals = vanillaUv = overrideUv = null;
					previewModel = null;
					windProjection = null;
					draft = null;
					appliedJson = null;
					appliedPreset = null;
					previousMode = null;
					dirtyPasses = 0;
					if (previewActive && selectedEntity != null)
						pending.set(selectedEntity);
				}
				if (water.render(runtime)) {
					Thread.sleep(33);
					continue;
				}
				if (water.detach(runtime)) {
					runtime.releasePreviewScene(scene);
					session.clearPreviewCaches();
				}
				if (!previewActive) {
					Thread.sleep(50);
					continue;
				}

				if (refreshRequested.getAndSet(false)) {
					try {
						var updated = session.refreshModelCatalog(runtime);
						SwingUtilities.invokeLater(() -> setEntries(updated));
					} catch (IOException | RuntimeException e) {
						say("Could not refresh cache · " + e.getMessage());
					}
				}
				var entry = pending.getAndSet(null);
				if (entry != null) {
					try {
						BuiltModel model = entry.source.load();
						if (model == null) {
							runtime.setPreviewModel(null, null, null);
							renderedEntry = null;
							previewModel = null;
							say("Model unavailable · " + entry.label);
							SwingUtilities.invokeLater(this::signalInitialPreviewReady);
						} else {
							installedModel = null;
							windProjection = null;
							previousMode = null;
							renderedEntry = entry;
							baseModel = model;
							normals = vanillaUv = overrideUv = null;
							previewModel = model;
							double[] framing = framing(model);
							distance = framing[0];
							targetY = framing[1];
							modelBottom = (int) Math.ceil(framing[2]);
							appliedPreset = null;
							zoom = 1;
							say(entry.label + " · " + model.getFaceCount() + " faces");
							SwingUtilities.invokeLater(() -> {
								if (!previewActive || previewGeneration != generation || selectedEntity != entry || previewModel != model)
									return;
								properties.setPreviewModel(model);
								if (previewInfoLine != null)
									previewInfoLine.setText(selectedOverride == null ? entry.label : entry.label + " › " + selectedOverride.override.description);
							});
							log.info("Previewing {}", entry.label);
						}
					} catch (IOException | RuntimeException e) {
						runtime.setPreviewModel(null, null, null);
							renderedEntry = null;
						log.warn("Could not build {}", entry.label, e);
						say("Could not build " + entry.label + " · " + e.getMessage());
						SwingUtilities.invokeLater(this::signalInitialPreviewReady);
					}
				}
				var requested = worldSearch.getAndSet(null);
				if (requested != null && requested == selectedEntity) {
					String result = "No map placement found · " + requested.label;
					try {
						if (requested.category == CacheModels.Category.Objects && requested == renderedEntry) {
							var location = session.findObjectPlacement(requested.id, () -> previewActive && findingEntry == requested);
							if (location != null && requested == selectedEntity && findingEntry == requested) {
								foundLocation = location;
								var position = location.getPosition();
								result = "Found at " + position.getX() + ", " + position.getY() + " · plane " + position.getZ();
							}
						}
					} catch (RuntimeException e) {
						log.warn("Could not load map preview for {}", requested.label, e);
						result = "Could not load map placement · " + requested.label;
					}
					String message = result;
					SwingUtilities.invokeLater(() -> {
						if (selectedEntity == requested && findingEntry == requested) {
							findInWorld.setToolTipText(message);
							if (message.startsWith("Found at "))
								sceneSelector.setSelectedIndex(-1);
							else {
								sceneSelector.setSelectedItem(ScenePreset.SKY);
								finishFinding(true);
							}
							status.setText(message);
						}
					});
				}
				var preset = scenePreset;
				var placement = foundLocation;
				boolean surroundings = showSurroundings;
				if (!dockVisible) {
					preset = ScenePreset.SKY;
					placement = null;
					surroundings = false;
				}
				boolean all = showAllPlacements;
				if (renderedEntry != null && (preset != appliedPreset || placement != appliedLocation
					|| surroundings != appliedSurroundings || all != appliedAll)) {
					appliedPreset = preset;
					appliedLocation = placement;
					appliedSurroundings = surroundings;
					appliedAll = all;
					previewScene = scene;
					tileX = TILE_X;
					tileZ = TILE_Z;
					plane = orientation = modelBaseY = 0;
					shape = 10;
					if (placement != null || preset != ScenePreset.SKY) {
						try {
							var pos = placement == null ? null : placement.getPosition();
							int x = pos == null ? preset.x : pos.getX();
							int z = pos == null ? preset.z : pos.getY();
							int id = renderedEntry.category == CacheModels.Category.Objects ? renderedEntry.id : -1;
							previewScene = session.buildPreviewScene(x, z, id, placement, surroundings, all);
							tileX = x - previewScene.getBaseX();
							tileZ = z - previewScene.getBaseY();
							plane = pos == null ? 0 : pos.getZ();
							orientation = placement == null ? 0 : placement.getOrientation() * 512;
							shape = placement == null ? 10 : placement.getType();
							modelBaseY = previewGroundHeight(previewScene, plane, tileX, tileZ) - GROUND_CLEARANCE - modelBottom;
						} catch (IOException | RuntimeException e) {
							log.warn("Could not load preview scene", e);
							previewScene = scene;
							tileX = TILE_X;
							tileZ = TILE_Z;
							plane = orientation = modelBaseY = 0;
							shape = 10;
							say("Could not load preview scene");
							var failedEntry = renderedEntry;
							SwingUtilities.invokeLater(() -> {
								if (findingEntry == failedEntry)
									finishFinding(true);
							});
						}
					}
					runtime.setScene(previewScene);
					runtime.setLevels(plane, true, false);
					installedModel = null;
					previousMode = null;
				}
				if (previewScene != indexedScene) {
					indexedScene = previewScene;
					nearbyObjects = indexSceneObjects(previewScene);
				}
				double y = yaw;
				double p = pitch;
				double currentZoom = zoom;
				if (renderedEntry == null) {
					Thread.sleep(16);
					continue;
				}

				String mode = previewMode;
				String centerShading = previewShading;
				String json = previewOverrideJson;
				boolean showDock = dockVisible;
				var dockCamera = gameCamera;
				boolean cameraChanged = y != previousYaw || p != previousPitch || currentZoom != previousZoom;
				boolean overrideChanged = !java.util.Objects.equals(json, appliedJson);
				int centerPasses = "Split".equals(mode) ? VANILLA_PASS | OVERRIDE_PASS
					: "Vanilla".equals(mode) ? VANILLA_PASS : OVERRIDE_PASS;
				if (cameraChanged || !mode.equals(previousMode) || !centerShading.equals(previousShading))
					dirtyPasses = (dirtyPasses & DOCK_PASS) | centerPasses;
				if (overrideChanged)
					dirtyPasses |= centerPasses & OVERRIDE_PASS;
				if (showDock && (dockCamera != previousGameCamera || overrideChanged || !previousDock || previousMode == null)) {
					dirtyPasses |= DOCK_PASS;
				} else if (!showDock) {
					dirtyPasses &= ~DOCK_PASS;
				}
				previousYaw = y;
				previousPitch = p;
				previousZoom = currentZoom;
				previousMode = mode;
				previousShading = centerShading;
				previousDock = showDock;
				previousGameCamera = dockCamera;

				if (overrideChanged) {
					appliedJson = json;
					overrideUv = null;
					draft = null;
					if (json != null) {
						try {
							draft = document.gson().fromJson(json, ModelOverride.class);
							if (draft.colorOverrides != null)
								draft.colorOverrides = java.util.Arrays.stream(draft.colorOverrides)
									.filter(rule -> ColorExpression.valid(ColorExpression.text(ModelOverrideFields.get(rule, "colors", com.google.gson.JsonElement.class))))
									.toArray(ModelOverride[]::new);
							draft.normalize(runtime.getPlugin());
						} catch (RuntimeException e) {
							draft = null;
							say("Override preview unavailable · " + e.getMessage());
						}
					}
				}

				boolean animateWind = windPreview && draft != null
					&& draft.windDisplacementMode != rs117.hd.scene.model_overrides.WindDisplacement.DISABLED
					&& ("Material".equals(centerShading) || "Wire".equals(centerShading))
					&& (centerPasses & OVERRIDE_PASS) != 0;
				long now = System.nanoTime();
				if (animateWind != previousWind || animateWind && now >= nextWindFrame)
					dirtyPasses |= centerPasses & OVERRIDE_PASS;
				previousWind = animateWind;

				int centerDirty = dirtyPasses & centerPasses;
				if (centerDirty == 0 && (dirtyPasses & DOCK_PASS) == 0) {
					Thread.sleep(8);
					continue;
				}
				int pass = centerDirty == 0 ? DOCK_PASS : centerDirty != centerPasses || centerPasses != (VANILLA_PASS | OVERRIDE_PASS)
					? centerDirty : centerPasses ^ lastCenterPass;
				boolean dock = pass == DOCK_PASS;
				boolean vanilla = pass == VANILLA_PASS;
				if (!dock)
					lastCenterPass = pass;
				String shading = dock ? "Material" : centerShading;
				var effective = vanilla ? rs117.hd.scene.model_overrides.ModelOverride.NONE : draft;
				BuiltModel shown = baseModel;
				if ("Normals".equals(shading)) {
					if (normals == null)
						normals = PreviewDiagnostics.normals(baseModel);
					shown = normals;
				} else if ("UV checker".equals(shading)) {
					if (vanilla || draft == null) {
						if (vanillaUv == null)
							vanillaUv = PreviewDiagnostics.uv(baseModel, rs117.hd.scene.model_overrides.ModelOverride.NONE);
						shown = vanillaUv;
					} else {
						if (overrideUv == null)
							overrideUv = PreviewDiagnostics.uv(baseModel, draft);
						shown = overrideUv;
					}
				}
				if (shown != baseModel) {
					var diagnostic = new rs117.hd.scene.model_overrides.ModelOverride();
					diagnostic.material = rs117.hd.scene.materials.Material.UNLIT;
					diagnostic.forceMaterialChanges = true;
					if (effective != null) {
						diagnostic.rotate = effective.rotate;
						diagnostic.heightOffset = effective.heightOffset;
					}
					effective = diagnostic;
				}
				if (animateWind && pass == OVERRIDE_PASS) {
					nextWindFrame = now + 33_333_333L;
					shown = WindPreview.deform(baseModel, draft, now * 1e-9,
						windPointer, windProjection, tileX * 128 + 64, modelBaseY, tileZ * 128 + 64, modelBottom);
				}
				if (shown != installedModel || orientation != 0) {
					setPreview(runtime, previewScene, renderedEntry, shown, tileX, tileZ, plane, modelBaseY, dock ? orientation : 0, shape);
					installedModel = shown;
				}
				int uuid = previewUuid(renderedEntry);
				runtime.setPreviewOverride(effective != null ? uuid : null, effective);
				runtime.setPreviewPass(dock, "Wire".equals(shading));
				var captured = renderedEntry;
				var plugin = runtime.getPlugin();
				int modelGroundY = modelBaseY;
				int previewX = tileX * 128 + 64, previewZ = tileZ * 128 + 64;
				var effectiveForPicking = effective;
				var sourceForPicking = baseModel;
				var modelForPicking = animateWind && pass == OVERRIDE_PASS ? shown : baseModel;
				float[][] renderedProjection = {null};
				boolean[] capturedFrame = {false};
				runtime.nextFrameTo(image -> {
					if (image == null)
						return;
					capturedFrame[0] = true;
					var picker = dock ? null : new PreviewFacePicker(sourceForPicking, modelForPicking, plugin.viewProjMatrix,
						previewX, modelGroundY + (effectiveForPicking == null ? 0 : effectiveForPicking.heightOffset),
						previewZ, effectiveForPicking == null ? 0 : effectiveForPicking.rotate);
					if (pass == OVERRIDE_PASS)
						renderedProjection[0] = picker.matrix();
					if (!dock && image instanceof BufferedImage)
						previewGrid.draw((BufferedImage) image, plugin.viewProjMatrix, previewX, previewZ, modelGroundY);
					frameUpdates.offer(pass, () -> {
						if (!previewActive || previewGeneration != generation || selectedEntity != captured || dock && !dockVisible)
							return;
						if (dock) {
							dockPreview.caption(selectedOverride == null ? captured.displayName : selectedOverride.override.description);
							dockPreview.frame(false, image);
							if (findingEntry == captured && foundLocation != null) {
								finishFinding(false);
								dockPreview.toast("Found " + captured.displayName);
							}
						} else {
							if (vanilla)
								vanillaFacePicker = picker;
							else
								overrideFacePicker = picker;
							centerPreview.modelFrame(vanilla, image, picker);
							signalInitialPreviewReady();
						}
					});
				});
				if (dock) {
					double focusY = modelBaseY + targetY + (effective == null ? 0 : effective.heightOffset);
					var camera = previewCamera(dockCamera.yaw, dockCamera.pitch, distance / dockCamera.zoom, focusY, previewX, previewZ);
					if (previewScene == scene)
						runtime.frameModelPreview(camera, (float) dockCamera.yaw, (float) dockCamera.pitch);
					else
						runtime.frame(camera,
						(float) dockCamera.yaw, (float) dockCamera.pitch);
				} else {
					runtime.frameModelPreview(previewCamera(y, p, distance / currentZoom, modelBaseY + targetY, previewX, previewZ), (float) y, (float) p);
				}
				if (renderedProjection[0] != null)
					windProjection = renderedProjection[0];
				if (capturedFrame[0])
					dirtyPasses &= ~pass;
				Thread.sleep(4);
			}
		} catch (IOException | RuntimeException e) {
			log.error("Model preview failed", e);
			say("Preview failed · " + e.getMessage());
			SwingUtilities.invokeLater(this::signalInitialPreviewReady);
		}
	}

	/** Keeps the latest image for each pane while the Swing event thread is busy. */
	private static final class FrameUpdates {
		private final java.util.Map<Integer, Runnable> latest = new java.util.LinkedHashMap<>();

		synchronized void offer(int pass, Runnable update) {
			boolean schedule = latest.isEmpty();
			latest.put(pass, update);
			if (schedule)
				SwingUtilities.invokeLater(this::publish);
		}

		synchronized void clear() { latest.clear(); }

		private void publish() {
			List<Runnable> updates;
			synchronized (this) {
				updates = List.copyOf(latest.values());
				latest.clear();
			}
			updates.forEach(Runnable::run);
		}
	}

	private static Path modelOverridesFile() throws IOException {
		var source = rs117.hd.utils.Props.getFile("rlhd.model-overrides-path",
			() -> rs117.hd.utils.ResourcePath.path(rs117.hd.scene.ModelOverrideManager.class, "model_overrides.json"));
		if (!source.isFileSystemResource())
			throw new IOException("model_overrides.json isn't a real file here (running from a packaged jar?) — pass -Drlhd.model-overrides-path=<file> to edit it");
		return source.toPath().toAbsolutePath();
	}

	private void selectInitialEntry() {
		String initial = System.getProperty("rlhd.editor-model");
		if (initial == null)
			return;
		for (var group : browserGroups) {
			for (var entry : group.variants) {
				if ((entry.category.name() + ":" + entry.id).equalsIgnoreCase(initial)) {
					category = entry.category;
					categories.get(category).setSelected(true);
					activeGroup = group;
					filter();
					list.ensureIndexIsVisible(list.getSelectedIndex());
					selectEntity(entry);
					return;
				}
			}
		}
	}

	private static java.util.Map<Integer, Integer> indexSceneObjects(BuiltScene scene) {
		var counts = new java.util.HashMap<Integer, Integer>();
		var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.runelite.api.TileObject, Boolean>());
		for (var level : scene.getTiles())
			for (var column : level)
				for (var tile : column) {
					if (tile == null)
						continue;
					for (var object : tile.getGameObjects())
						if (object != null && seen.add(object))
							counts.merge(object.getId(), 1, Integer::sum);
					for (var object : new net.runelite.api.TileObject[] { tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject() })
						if (object != null && seen.add(object))
							counts.merge(object.getId(), 1, Integer::sum);
				}
		return counts;
	}

	private static int previewGroundHeight(BuiltScene scene, int plane, int tileX, int tileZ) {
		int x = tileX + BuiltScene.SCENE_OFFSET;
		int z = tileZ + BuiltScene.SCENE_OFFSET;
		int[][] heights = scene.getTileHeights()[plane];
		return Math.min(Math.min(heights[x][z], heights[x + 1][z]),
			Math.min(heights[x][z + 1], heights[x + 1][z + 1]));
	}

	private static float[] previewCamera(double yaw, double pitch, double distance, double targetY, int x, int z) {
		return new float[] {
			(float) (x + Math.sin(yaw) * Math.cos(pitch) * distance),
			(float) (targetY - Math.sin(pitch) * distance),
			(float) (z - Math.cos(yaw) * Math.cos(pitch) * distance)
		};
	}

	private static int previewUuid(CacheModels.Entry entry) {
		int type = entry.category == CacheModels.Category.NPCs ? ModelHash.TYPE_NPC
			: entry.category == CacheModels.Category.Graphics ? ModelHash.TYPE_GRAPHICS_OBJECT : ModelHash.TYPE_OBJECT;
		return ModelHash.packUuid(type, entry.id);
	}

	private static void setPreview(HdRuntime runtime, BuiltScene scene, CacheModels.Entry entry, BuiltModel model,
		int tileX, int tileZ, int plane, int height, int orientation, int shape) {
		int type = entry.category == CacheModels.Category.NPCs ? ModelHash.TYPE_NPC : ModelHash.TYPE_OBJECT;
		long hash = entry.category == CacheModels.Category.Graphics ? -1 :
			ObjectPlacer.hash(plane, tileX, tileZ, type, true, entry.id, scene.getWorldViewId());
		var answers = new EntityModel(entry.id, model);
		Renderable renderable = entry.category == CacheModels.Category.NPCs ? Fake.of(NPC.class, answers)
			: entry.category == CacheModels.Category.Graphics ? Fake.of(GraphicsObject.class, answers) : model;
		var tile = new BuiltGameObject(scene, hash, shape, tileX * 128 + 64, tileZ * 128 + 64, height, plane,
			renderable, orientation, tileX, tileZ, tileX, tileZ);
		runtime.setPreviewModel(tile, renderable, model);
	}

	public static final class EntityModel {
		private final int id;
		private final Model model;
		EntityModel(int id, Model model) { this.id = id; this.model = model; }
		public int getId() { return id; }
		public Model getModel() { return model; }
	}

	private static double[] framing(Model model) {
		double radius = 1;
		double minY = 0, maxY = 0;
		for (int i = 0; i < model.getVerticesCount(); i++) {
			radius = Math.max(radius, Math.hypot(model.getVerticesX()[i], model.getVerticesZ()[i]));
			minY = Math.min(minY, model.getVerticesY()[i]);
			maxY = Math.max(maxY, model.getVerticesY()[i]);
		}
		return new double[] {Math.max(110, Math.max(radius * 3.1, (maxY - minY) * 1.55)), (minY + maxY) / 2, maxY};
	}

	private void say(String message) {
		SwingUtilities.invokeLater(() -> status.setText(message));
	}

	private static JLabel header(String name) {
		var title = EditorText.builder(name).font(WorkspaceStyle.SEMIBOLD).icon(WorkspaceStyle.icon("grip", 12))
			.border(BorderFactory.createEmptyBorder(0, 8, 0, 8)).build();
		title.setPreferredSize(new Dimension(0, 30));
		title.setOpaque(true);
		title.setBackground(WorkspaceStyle.HEADER);
		return title;
	}

	private static JPanel panel(JComponent header) {
		var panel = new JPanel(new BorderLayout());
		panel.setBackground(WorkspaceStyle.PANEL);
		panel.add(header, BorderLayout.NORTH);
		return panel;
	}
}
