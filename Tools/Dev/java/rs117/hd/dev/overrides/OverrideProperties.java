package rs117.hd.dev.overrides;

import rs117.hd.dev.editor.EditorScrollPane;
import rs117.hd.dev.editor.HdContextMenu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import net.runelite.api.Model;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.dev.editor.BeforeAfterSwatch;
import rs117.hd.dev.editor.CacheModels;
import rs117.hd.dev.editor.ColorPicker;
import rs117.hd.dev.editor.EditorButton;
import rs117.hd.dev.editor.EditorPanels;
import rs117.hd.dev.editor.EditorSearch;
import rs117.hd.dev.editor.EditorSelect;
import rs117.hd.dev.editor.EditorText;
import rs117.hd.dev.editor.NumberField;
import rs117.hd.dev.editor.PreviewDropdown;
import rs117.hd.dev.editor.PropertySection;
import rs117.hd.dev.editor.PropertyRow;
import rs117.hd.dev.editor.SearchableIdDropdown;
import rs117.hd.dev.editor.SegmentedControl;
import rs117.hd.dev.editor.SwatchDropdown;
import rs117.hd.dev.editor.ToggleList;
import rs117.hd.dev.editor.WorkspaceStyle;
import rs117.hd.dev.editor.UvTypeDropdown;
import rs117.hd.dev.editor.DashedAddButton;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.model_overrides.WindDisplacement;
import rs117.hd.utils.ColorUtils;

/** Right-side "Properties" panel for the Model Overrides editor tab: reads/writes every field of one {@link ModelOverride}. */
final class OverrideProperties extends JPanel {
	interface Context {
		void colorToast(String message);
		void highlightColors(ModelOverride.AhslPredicate rule, Integer hover);
		void setWindPreview(boolean enabled);
		List<Material> materials();
		int[] vanillaTexturePixels(int textureId);
		List<SearchableIdDropdown.Choice> npcChoices();
		List<SearchableIdDropdown.Choice> objectChoices();
		List<SearchableIdDropdown.Choice> spotanimChoices();
		String nearbyObject(int id);
	}

	private static final Color HEADER_BORDER = new Color(0x1A1A1A);

	private final Context context;
	private static final int MAX_VANILLA_PREVIEWS = 256;
	private final java.util.Map<Long, Image> vanillaTexturePreviews = new java.util.LinkedHashMap<Long, Image>(32, .75f, true) {
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Image> eldest) {
			return size() > MAX_VANILLA_PREVIEWS;
		}
	};
	private final MaterialSwatch swatch = new MaterialSwatch();
	private final JLabel titleLabel;
	private final JLabel targetLabel;
	private final JTextField filterField;
	private boolean filterActive;
	private final JPanel sectionsPanel;
	private final java.awt.CardLayout cards = new java.awt.CardLayout();
	private final JPanel pages = new JPanel(cards);
	private final JPanel emptyContent = new JPanel(new GridBagLayout());
	private Runnable createOverride;
	private final JScrollPane scroll;
	private final java.util.Map<PropertySection, Boolean> expansion = new java.util.HashMap<>();
	private final List<Section> sections = new ArrayList<>();

	private ModelOverride override;
	private ModelOverride baseline = new ModelOverride();
	private Model previewModel;
	private List<ModelColorPalette.Entry> modelColors = List.of();
	private final JPanel noMatches = new JPanel(new GridBagLayout());
	private final JLabel noMatchesLabel = new JLabel();
	private ModelOverride openColorRule;
	private ModelOverride expressionViewRule;
	private Integer hoveredColor;
	private Runnable stopColorPicking = () -> {};
	private boolean refreshing;

	private Runnable changeListener;
	private Runnable copyJsonListener;
	private Runnable pickColorListener;
	private ModelOverride pendingColorRule;
	private Runnable refreshColorRules;
	private Section colorRulesSection;

	OverrideProperties(Context context) {
		super(new BorderLayout());
		this.context = Objects.requireNonNull(context);
		setOpaque(true);
		setBackground(WorkspaceStyle.PANEL);

		titleLabel = EditorText.builder("").font(WorkspaceStyle.SEMIBOLD.deriveFont(13f)).color(new Color(0xEEEEEE)).build();
		targetLabel = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f)).color(new Color(0x7D7D7D)).build();

		var titles = new JPanel();
		titles.setOpaque(false);
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		targetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		titles.add(titleLabel);
		titles.add(targetLabel);

		var swatchWrap = EditorPanels.centered(swatch);

		var left = new JPanel(new BorderLayout(10, 0));
		left.setOpaque(false);
		left.add(swatchWrap, BorderLayout.WEST);
		var centeredTitles = new JPanel(new GridBagLayout());
		centeredTitles.setOpaque(false);
		var titleCell = new java.awt.GridBagConstraints();
		titleCell.weightx = 1;
		titleCell.fill = java.awt.GridBagConstraints.HORIZONTAL;
		centeredTitles.add(titles, titleCell);
		left.add(centeredTitles, BorderLayout.CENTER);

		var jsonButton = EditorButton.builder().style(WorkspaceStyle.Button.SECONDARY)
			.text("JSON").icon("copy", 12).tooltip("Copy override JSON")
			.onClick(() -> { if (copyJsonListener != null) copyJsonListener.run(); }).build();

		var header = new JPanel(new BorderLayout(10, 0));
		header.setBackground(new Color(0x262626));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, HEADER_BORDER),
			BorderFactory.createEmptyBorder(0, 12, 0, 12)));
		header.setPreferredSize(new Dimension(0, 48));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
		header.add(left, BorderLayout.CENTER);
		var jsonSlot = EditorPanels.centered(jsonButton);
		header.add(jsonSlot, BorderLayout.EAST);

		var search = EditorSearch.builder("Filter properties").background(new Color(0x1A1A1A))
			.fontSize(11f).gap(5).padding(7, 0).textInset(8).fieldHeight(26).onChange(this::setFilter).build();
		filterField = search.field();
		var filterWrap = new JPanel(new BorderLayout());
		filterWrap.setBackground(WorkspaceStyle.PANEL);
		filterWrap.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
		filterWrap.add(search);

		var top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(header, BorderLayout.NORTH);
		top.add(filterWrap, BorderLayout.SOUTH);

		sectionsPanel = EditorPanels.propertyStack();

		buildSections();
		for (Section section : sections) {
			section.view.setAlignmentX(Component.LEFT_ALIGNMENT);
			sectionsPanel.add(section.view);
		}

		scroll = new EditorScrollPane(sectionsPanel,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.MENU));
		scroll.getViewport().setBackground(WorkspaceStyle.INPUT);

		var editor = new JPanel(new BorderLayout());
		editor.add(top, BorderLayout.NORTH);
		editor.add(scroll);
		emptyContent.setBackground(WorkspaceStyle.INPUT);
		var emptyScroll = new EditorScrollPane(emptyContent, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		emptyScroll.setBorder(null);
		emptyScroll.getVerticalScrollBar().setUnitIncrement(16);
		pages.add(emptyScroll, "empty");
		pages.add(editor, "editor");
		add(pages);
		setEmptyEntity(null, 0);
	}

	void onCreate(Runnable action) { createOverride = action; }

	void setEmptyEntity(CacheModels.Entry entity, int matches) {
		emptyContent.removeAll();
		var column = new JPanel(new net.miginfocom.swing.MigLayout("insets 32 20, gap 0 14, wrap 1", "[grow,center]"));
		column.setOpaque(false);
		var icon = new JLabel(WorkspaceStyle.icon("override-cube", 20), javax.swing.SwingConstants.CENTER) {
			@Override protected void paintComponent(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(WorkspaceStyle.PANEL);
				g.fillOval(0, 0, 43, 43);
				g.setColor(new Color(0x333333));
				g.drawOval(0, 0, 43, 43);
				g.dispose();
				super.paintComponent(graphics);
			}
		};
		column.add(icon, "w 44!, h 44!");
		String heading = entity == null ? "Nothing loaded" : matches == 0
			? entity.displayName + " has no model overrides" : "No override selected";
		column.add(emptyText(heading, WorkspaceStyle.SEMIBOLD.deriveFont(14f), new Color(0xE6E6E6)), "growx");
		String kind = entity == null ? "" : entity.category == CacheModels.Category.NPCs ? "NPC"
			: entity.category == CacheModels.Category.Objects ? "object" : "graphic";
		String message = entity == null ? "Pick an NPC, object or graphic in the Browser to see or create its overrides."
			: matches == 0 ? "Create one to change how this " + kind
				+ " looks in 117 HD: material, UVs, colors and shadows. Targets are filled in from what is loaded."
			: entity.displayName + " has " + matches + (matches == 1 ? " override." : " overrides.")
				+ " Select one in the list to edit it, or create another.";
		column.add(emptyText(message, WorkspaceStyle.UI, WorkspaceStyle.DIM), "growx");
		if (entity != null) {
			var target = new JPanel(new BorderLayout(8, 6));
			target.setBackground(new Color(0x1A1A1A));
			target.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x333333), 1, true),
				BorderFactory.createEmptyBorder(10, 10, 10, 10)));
			target.add(EditorText.builder("WILL TARGET").font(WorkspaceStyle.SEMIBOLD.deriveFont(10f)).color(WorkspaceStyle.DIM).build(), BorderLayout.NORTH);
			boolean npc = entity.category == CacheModels.Category.NPCs;
			boolean graphic = entity.category == CacheModels.Category.Graphics;
			var label = new JLabel(npc ? "NPC ID" : graphic ? "Spotanim ID" : "Object ID",
				WorkspaceStyle.badge(npc ? "N" : graphic ? "G" : "O", new Color(npc ? 0xC25B5B : graphic ? 0x9A6BC2 : 0x5B8AC2)),
				javax.swing.SwingConstants.LEFT);
			label.setFont(WorkspaceStyle.UI);
			label.setForeground(WorkspaceStyle.DIM);
			target.add(label, BorderLayout.WEST);
			var value = EditorText.builder(entity.id + " · " + entity.displayName).font(WorkspaceStyle.MONO).build();
			value.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
			value.setToolTipText(value.getText());
			target.add(value);
			if (graphic)
				target.add(emptyText("You'll be asked whether to use it as a graphics object or a projectile.",
					WorkspaceStyle.UI.deriveFont(11f), WorkspaceStyle.DIM), BorderLayout.SOUTH);
			column.add(target, "growx");
			var createButton = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY)
				.text(matches == 0 ? "Create override" : "Create another override").icon("plus", 11)
				.onClick(() -> { if (createOverride != null) createOverride.run(); }).build();
			createButton.setFont(WorkspaceStyle.SEMIBOLD);
			createButton.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
			column.add(createButton, "h 30!");
			column.add(emptyText("Targets can be changed after creating.", WorkspaceStyle.UI.deriveFont(11f), new Color(0x6F6F6F)), "growx");
		}
		var cell = new java.awt.GridBagConstraints();
		cell.weightx = 1;
		cell.fill = java.awt.GridBagConstraints.HORIZONTAL;
		var centered = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, fillx", "[grow][0:340:340,fill][grow]"));
		centered.setOpaque(false);
		centered.add(column, "cell 1 0");
		emptyContent.add(centered, cell);
		emptyContent.revalidate();
		emptyContent.repaint();
	}

	private static JLabel emptyText(String text, Font font, Color color) {
		var label = new JLabel() {
			@Override protected void paintComponent(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				var layout = new java.awt.font.LineBreakMeasurer(attributedText().getIterator(), g.getFontRenderContext());
				float y = 0;
				g.setColor(getForeground());
				while (layout.getPosition() < text.length()) {
					var line = layout.nextLayout(Math.max(1, getWidth()));
					y += line.getAscent();
					line.draw(g, (getWidth() - line.getAdvance()) / 2, y);
					y += line.getDescent() + line.getLeading() + 4;
				}
				g.dispose();
			}

			private java.text.AttributedString attributedText() {
				var value = new java.text.AttributedString(text);
				value.addAttribute(java.awt.font.TextAttribute.FONT, font);
				return value;
			}

			@Override public Dimension getPreferredSize() {
				var metrics = getFontMetrics(font);
				int width = Math.min(300, metrics.stringWidth(text));
				var layout = new java.awt.font.LineBreakMeasurer(attributedText().getIterator(), metrics.getFontRenderContext());
				int lines = 0;
				while (layout.getPosition() < text.length()) {
					layout.nextLayout(Math.max(1, width));
					lines++;
				}
				return new Dimension(width, lines * (metrics.getHeight() + 4) - 4);
			}
		};
		label.setFont(font);
		label.setForeground(color);
		label.getAccessibleContext().setAccessibleName(text);
		return label;
	}

	// ----- public API -----

	void setOverride(ModelOverride override, String targetSummary, Model previewModel) {
		boolean selectionChanged = this.override != override;
		this.override = override;
		cards.show(pages, override == null ? "empty" : "editor");
		if (override == null) {
			openColorRule = expressionViewRule = pendingColorRule = null;
			hoveredColor = null;
			updateColorHighlight();
			return;
		}
		if (selectionChanged) {
			openColorRule = expressionViewRule = null;
			pendingColorRule = null;
			stopColorPicking.run();
		}
		if (selectionChanged)
			scroll.getVerticalScrollBar().setValue(0);
		updateModelColors(previewModel);
		this.baseline = new ModelOverride();
		this.pendingColorRule = null;

		titleLabel.setText(override.description == null ? "" : override.description);
		targetLabel.setText(targetSummary == null ? "" : targetSummary);
		swatch.setColor(materialColor(override.material));

		withRefreshGuard(() -> {
			for (Section section : sections)
				section.refresh.run();
		});
	}

	void setPreviewModel(Model model) {
		if (previewModel == model)
			return;
		updateModelColors(model);
		if (override != null)
			withRefreshGuard(() -> {
				for (Section section : sections)
					section.refresh.run();
			});
	}

	private void updateModelColors(Model model) {
		if (previewModel == model)
			return;
		previewModel = model;
		modelColors = model == null ? List.of() : ModelColorPalette.distinctColors(model);
	}

	void setFilter(String query) {
		String q = normalizeSearch(query);
		// Most fireChange() calls happen while editing a field with no active search filter; skip the
		// full section/field tree walk and panel revalidate when there's nothing to (un)filter.
		if (q.isEmpty() && !filterActive)
			return;
		filterActive = !q.isEmpty();
		boolean anyMatch = false;
		for (Section section : sections) {
			if (q.isEmpty()) {
				Boolean previous = expansion.remove(section.view);
				if (previous != null)
					section.view.setExpanded(previous);
				section.view.setVisible(true);
				filterFields(section.view.fields(), "", true);
			} else {
				expansion.putIfAbsent(section.view, section.view.isExpanded());
				boolean wholeSection = normalizeSearch(section.view.title()).contains(q);
				boolean fieldsMatch = filterFields(section.view.fields(), q, wholeSection);
				boolean match = wholeSection || fieldsMatch || section.labels.stream()
					.anyMatch(label -> normalizeSearch(label).contains(q));
				section.view.setVisible(match);
				anyMatch |= match;
				if (match)
					section.view.setExpanded(true);
			}
		}
		if (noMatches.getParent() == null) {
			noMatches.setOpaque(false);
			noMatches.setAlignmentX(LEFT_ALIGNMENT);
			noMatches.setPreferredSize(new Dimension(0, 144));
			var message = new JPanel(new BorderLayout(0, 8));
			message.setOpaque(false);
			noMatchesLabel.setForeground(new Color(0x9A9A9A));
			noMatchesLabel.setHorizontalAlignment(JLabel.CENTER);
			message.add(noMatchesLabel);
			message.add(EditorButton.builder().text("Clear filter").onClick(() -> filterField.setText("")).build(), BorderLayout.SOUTH);
			noMatches.add(message);
			sectionsPanel.add(noMatches);
		}
		noMatchesLabel.setText("No properties match “" + query + "”.");
		noMatches.setVisible(!q.isEmpty() && !anyMatch);
		sectionsPanel.revalidate();
		sectionsPanel.repaint();
	}

	private static String normalizeSearch(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static boolean filterFields(JComponent body, String query, boolean wholeSection) {
		boolean found = false;
		for (Component child : body.getComponents()) {
			if (child instanceof JPanel) {
				var panel = (JPanel) child;
				String key = panel.getName();
				if (panel instanceof rs117.hd.dev.editor.ToggleList) {
					found |= ((rs117.hd.dev.editor.ToggleList) panel).filter(query, wholeSection);
					continue;
				}
				if (key != null && key.startsWith("field:")) {
					boolean match = wholeSection || normalizeSearch(key.substring(6)).contains(query);
					panel.setVisible(match);
					found |= match;
				} else {
					found |= filterFields(panel, query, wholeSection);
				}
			}
		}
		return found;
	}

	void onChange(Runnable listener) { this.changeListener = listener; }

	void onCopyJson(Runnable listener) { this.copyJsonListener = listener; }

	void onPickColorRequested(Runnable listener) { this.pickColorListener = listener; }

	void onColorPickCancelled(Runnable listener) { stopColorPicking = listener; }

	void cancelColorPicking() {
		if (pendingColorRule == null)
			return;
		pendingColorRule = null;
		stopColorPicking.run();
		refreshColorRules.run();
	}

	private void chooseColor(ModelOverride rule) {
		pendingColorRule = rule;
		refreshColorRules.run();
		if (pickColorListener != null)
			pickColorListener.run();
	}

	void colorPicked(int ahsl) {
		var rule = pendingColorRule == null ? new ModelOverride() : pendingColorRule;
		String before = expressionText(rule);
		int hsl = ahsl & 65535;
		if (ModelOverrideFields.colorPredicate(rule, ruleColors(rule)).test(ahsl)) {
			context.colorToast("This rule already matches " + hslLabel(hsl));
			cancelColorPicking();
			return;
		}
		boolean newRule = before.isBlank();
		String exact = ColorExpression.exact(hsl);
		rule.colors(new JsonPrimitive(newRule ? exact :
			(ColorExpression.groups(before) == null ? "(" + before + ")" : before) + " || " + exact));
		if (newRule)
			rule.setHue(hsl >> 10).setSaturation(hsl >> 7 & 7).setLightness(hsl & 127);
		context.colorToast((newRule ? "New color override for " : "Added color ") + hslLabel(hsl));
		var current = override.colorOverrides;
		var rules = current == null ? new ArrayList<ModelOverride>() : new ArrayList<>(java.util.Arrays.asList(current));
		if (!rules.contains(rule)) {
			rules.add(rule);
			override.colorOverrides(rules.toArray(ModelOverride[]::new));
		}
		openColorRule = rule;
		pendingColorRule = null;
		stopColorPicking.run();
		onFieldEdited(refreshColorRules);
	}

	private static String hslLabel(int hsl) {
		return (hsl >> 10 & 63) + " · " + (hsl >> 7 & 7) + " · " + (hsl & 127);
	}

	private static String expressionText(ModelOverride rule) {
		return ColorExpression.text(ModelOverrideFields.get(rule, "colors", JsonElement.class));
	}

	private void updateColorHighlight() {
		context.highlightColors(openColorRule == null ? null : ModelOverrideFields.colorPredicate(openColorRule, ruleColors(openColorRule)), hoveredColor);
	}

	private static JsonArray ruleColors(ModelOverride rule) {
		var colors = ModelOverrideFields.get(rule, "colors", JsonElement.class);
		if (colors != null && colors.isJsonArray())
			return colors.getAsJsonArray().deepCopy();
		var values = new JsonArray();
		if (colors != null)
			values.add(colors);
		return values;
	}

	// ----- shared helpers -----

	private void withRefreshGuard(Runnable body) {
		boolean previous = refreshing;
		refreshing = true;
		try {
			body.run();
		} finally {
			refreshing = previous;
		}
	}

	private void onFieldEdited(Runnable refresh) {
		if (refreshing)
			return;
		refresh.run();
		fireChange();
	}

	private void fireChange() {
		if (override != null) {
			titleLabel.setText(override.description);
			swatch.setColor(materialColor(override.material));
		}
		setFilter(filterField.getText());
		if (changeListener != null)
			changeListener.run();
	}

	private static int count(boolean... flags) {
		int n = 0;
		for (boolean flag : flags)
			if (flag)
				n++;
		return n;
	}

	private static boolean arrayChanged(Object[] a, Object[] b) {
		int la = a == null ? -1 : a.length;
		int lb = b == null ? -1 : b.length;
		return la != lb;
	}

	private static boolean floatArrayChanged(float[] a, float[] b) {
		int la = a == null ? -1 : a.length;
		int lb = b == null ? -1 : b.length;
		return la != lb;
	}


	private static Color materialColor(Material material) {
		if (material == null || material.name == null)
			return WorkspaceStyle.BORDER;
		int hash = material.name.hashCode();
		float hue = (hash & 0xFFFF) / 65535f;
		return Color.getHSBColor(hue, 0.45f, 0.65f);
	}

	private static void copyFieldText(String value) {
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
			new java.awt.datatransfer.StringSelection(value), null);
	}

	static JPanel fieldRow(String label, JComponent control) {
		return new PropertyRow(label, control, PropertyRow.Layout.SIMPLE);
	}

	private static void setFieldChanged(JPanel row, boolean changed) {
		((PropertyRow) row).setChanged(changed);
	}

	private static JLabel infoLabel(String text) {
		return EditorText.builder(text).font(WorkspaceStyle.UI.deriveFont(11f)).color(WorkspaceStyle.DIM).build();
	}

	private static Component spacer() {
		return Box.createVerticalStrut(8);
	}

	private static void setTextIfChanged(JTextField field, String text) {
		if (!Objects.equals(field.getText(), text))
			field.setText(text);
	}

	private static void commitOnEdit(JTextField field, Consumer<String> commit) {
		field.addActionListener(e -> commit.accept(field.getText()));
		field.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent event) { commit.accept(field.getText()); }
		});
	}

	/** Small 14x14 rounded color chip used in the header. */
	private static final class MaterialSwatch extends JComponent {
		private Color color = WorkspaceStyle.BORDER;

		MaterialSwatch() {
			var size = new Dimension(14, 14);
			setPreferredSize(size);
			setMinimumSize(size);
			setMaximumSize(size);
			setOpaque(false);
		}

		void setColor(Color color) {
			this.color = color;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(color);
			g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 3, 3);
			g.setColor(WorkspaceStyle.BORDER);
			g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 3, 3);
			g.dispose();
		}
	}

	private static final class Section {
		final PropertySection view;
		final Runnable refresh;
		final List<String> labels;

		Section(PropertySection view, Runnable refresh, List<String> labels) {
			this.view = view;
			this.refresh = refresh;
			this.labels = labels;
		}
	}

	// ----- sections -----

	private void buildSections() {
		sections.add(buildGeneralSection());
		sections.add(buildTargetsSection());
		sections.add(buildMaterialSection());
		sections.add(buildUvsSection());
		sections.add(buildNormalsSection());
		sections.add(buildShadowsSection());
		sections.add(buildColorSection());
		sections.add(colorRulesSection);
		sections.add(buildMaterialSwapsSection());
		sections.add(buildTerrainSection());
		sections.add(buildWindSection());
	}

	private Section buildGeneralSection() {
		var content = EditorPanels.stack();

		JTextField descriptionField = EditorText.propertyField("");
		descriptionField.setFont(WorkspaceStyle.UI);
		descriptionField.setPreferredSize(new Dimension(descriptionField.getPreferredSize().width, 24));
		List<String> themeNames = new ArrayList<>();
		themeNames.add("(none)");
		for (SeasonalTheme theme : SeasonalTheme.values())
			if (theme != SeasonalTheme.AUTOMATIC)
				themeNames.add(theme.name());
		SegmentedControl<String> seasonalControl = SegmentedControl.builder(themeNames).values().build();
		SegmentedControl<Integer> rotateControl = SegmentedControl.builder(List.of(0, 90, 180, 270)).values().label(r -> r + "°").build();
		NumberField heightOffsetField = NumberField.builder().build();
		NumberField depthBiasField = NumberField.builder().value(-1.0).min(-1.0).build();

		var descriptionFieldRow = fieldRow("Description", descriptionField);
		content.add(descriptionFieldRow);
		HdContextMenu.install(descriptionField, point -> {
			Point at = point == null ? new Point(0, descriptionField.getHeight()) : point;
			String current = descriptionField.getText();
			var menu = new HdContextMenu("#", new Color(0x5A5A5A), "description · " + current);
			if (Objects.equals(current, baseline.description))
				menu.disabled("Reset to default", "reset", "Description is already the default");
			else
				menu.item("Reset to default", "reset", "", () -> {
					descriptionField.setText(baseline.description);
					descriptionField.postActionEvent();
				});
			menu.separator().item("Copy value", "copy", "", () -> copyFieldText(current))
				.item("Copy JSON key", "key", "", () -> copyFieldText("description"))
				.show(descriptionField, at.x, at.y);
		});
		var seasonalControlRow = fieldRow("Seasonal theme", seasonalControl);
		content.add(seasonalControlRow);
		var rotateControlRow = fieldRow("Rotate", rotateControl);
		content.add(rotateControlRow);
		var heightOffsetFieldRow = fieldRow("Height offset", heightOffsetField);
		content.add(heightOffsetFieldRow);
		var depthBiasFieldRow = fieldRow("Depth bias", depthBiasField);
		content.add(depthBiasFieldRow);

		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);

		PropertySection view = new PropertySection("General", content);
		Runnable[] self = new Runnable[1];

		commitOnEdit(descriptionField, text -> {
			if (override != null && !Objects.equals(text, override.description)) {
				override.description = text;
				onFieldEdited(self[0]);
			}
		});
		seasonalControl.onChange(value -> {
			override.seasonalTheme = "(none)".equals(value) ? null : SeasonalTheme.valueOf(value);
			onFieldEdited(self[0]);
		});
		rotateControl.onChange(value -> { override.rotate = value; onFieldEdited(self[0]); });
		heightOffsetField.onChange(value -> { override.heightOffset = value.intValue(); onFieldEdited(self[0]); });
		depthBiasField.onChange(value -> { override.depthBias = value.intValue(); onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			setTextIfChanged(descriptionField, override.description == null ? "" : override.description);
			seasonalControl.setSelected(override.seasonalTheme == null || override.seasonalTheme == SeasonalTheme.AUTOMATIC
				? "(none)" : override.seasonalTheme.name());
			rotateControl.setSelected(override.rotate);
			heightOffsetField.setValue((double) override.heightOffset);
			depthBiasField.setValue((double) override.depthBias);

			setFieldChanged(descriptionFieldRow, !Objects.equals(override.description, baseline.description));
			setFieldChanged(seasonalControlRow, override.seasonalTheme != baseline.seasonalTheme);
			setFieldChanged(rotateControlRow, override.rotate != baseline.rotate);
			setFieldChanged(heightOffsetFieldRow, override.heightOffset != baseline.heightOffset);
			setFieldChanged(depthBiasFieldRow, override.depthBias != baseline.depthBias);
			boolean hideAsWaterEffect = ModelOverrideFields.getBoolean(override, "hideAsWaterEffect");
			boolean baseHideAsWaterEffect = ModelOverrideFields.getBoolean(baseline, "hideAsWaterEffect");
			ToggleList.builder()
				.add("Hide model", override.hide, override.hide != baseline.hide,
					v -> { override.hide = v; onFieldEdited(self[0]); })
				.add("Hide as water effect", hideAsWaterEffect, hideAsWaterEffect != baseHideAsWaterEffect,
					v -> { override.hideAsWaterEffect(v); onFieldEdited(self[0]); })
				.add("No priority sorting", override.disablePrioritySorting,
					override.disablePrioritySorting != baseline.disablePrioritySorting,
					v -> { override.disablePrioritySorting = v; onFieldEdited(self[0]); })
				.add("No detail culling", override.disableDetailCulling,
					override.disableDetailCulling != baseline.disableDetailCulling,
					v -> { override.disableDetailCulling = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				!Objects.equals(override.description, baseline.description),
				override.seasonalTheme != baseline.seasonalTheme,
				override.rotate != baseline.rotate,
				override.heightOffset != baseline.heightOffset,
				override.depthBias != baseline.depthBias,
				override.hide != baseline.hide,
				hideAsWaterEffect != baseHideAsWaterEffect,
				override.disablePrioritySorting != baseline.disablePrioritySorting,
				override.disableDetailCulling != baseline.disableDetailCulling
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.description = baseline.description;
			override.seasonalTheme = baseline.seasonalTheme;
			override.rotate = baseline.rotate;
			override.heightOffset = baseline.heightOffset;
			override.depthBias = baseline.depthBias;
			override.hide = baseline.hide;
			override.hideAsWaterEffect(ModelOverrideFields.getBoolean(baseline, "hideAsWaterEffect"));
			override.disablePrioritySorting = baseline.disablePrioritySorting;
			override.disableDetailCulling = baseline.disableDetailCulling;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"Description", "Seasonal theme", "Rotate", "Height offset", "Depth bias",
			"Hide", "Hide as water effect", "Disable priority sorting", "Disable detail culling"));
	}

	private Section buildTargetsSection() {
		var content = EditorPanels.stack();

		SearchableIdDropdown npcDropdown = SearchableIdDropdown.builder(context::npcChoices)
			.title("NPC IDs").build();
		SearchableIdDropdown objectDropdown = SearchableIdDropdown.builder(context::objectChoices)
			.title("Object IDs").nearby(context::nearbyObject).build();
		SearchableIdDropdown projectileDropdown = SearchableIdDropdown.builder(context::spotanimChoices)
			.title("Projectile IDs").build();
		SearchableIdDropdown graphicsObjectDropdown = SearchableIdDropdown.builder(context::spotanimChoices)
			.title("Graphics object IDs").build();

		Runnable[] self = new Runnable[1];
		var areasList = new AreaListField("Areas", false, areas -> { override.areas = areas; onFieldEdited(self[0]); });
		var hiddenAreasList = new AreaListField("Hide in areas", true, areas -> { override.hideInAreas = areas; onFieldEdited(self[0]); });

		content.add(new PropertyRow("NPC IDs", npcDropdown, PropertyRow.Layout.WIDE));
		content.add(new PropertyRow("Object IDs", objectDropdown, PropertyRow.Layout.WIDE));
		content.add(new PropertyRow("Projectile IDs", projectileDropdown, PropertyRow.Layout.WIDE));
		content.add(new PropertyRow("Graphics object IDs", graphicsObjectDropdown, PropertyRow.Layout.WIDE));
		content.add(areasList);
		content.add(hiddenAreasList);

		PropertySection view = new PropertySection("Targets", content);

		npcDropdown.onChange(ids -> { override.npcIds = new LinkedHashSet<>(ids); onFieldEdited(self[0]); });
		objectDropdown.onChange(ids -> { override.objectIds = new LinkedHashSet<>(ids); onFieldEdited(self[0]); });
		projectileDropdown.onChange(ids -> { override.projectileIds = new LinkedHashSet<>(ids); onFieldEdited(self[0]); });
		graphicsObjectDropdown.onChange(ids -> { override.graphicsObjectIds = new LinkedHashSet<>(ids); onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			npcDropdown.setSelected(override.npcIds == null ? Collections.emptySet() : override.npcIds);
			objectDropdown.setSelected(override.objectIds == null ? Collections.emptySet() : override.objectIds);
			projectileDropdown.setSelected(override.projectileIds == null ? Collections.emptySet() : override.projectileIds);
			graphicsObjectDropdown.setSelected(override.graphicsObjectIds == null ? Collections.emptySet() : override.graphicsObjectIds);
			areasList.setAreas(override.areas);
			hiddenAreasList.setAreas(override.hideInAreas);

			view.setChangedCount(count(
				!Objects.equals(override.npcIds, baseline.npcIds),
				!Objects.equals(override.objectIds, baseline.objectIds),
				!Objects.equals(override.projectileIds, baseline.projectileIds),
				!Objects.equals(override.graphicsObjectIds, baseline.graphicsObjectIds),
				arrayChanged(override.areas, baseline.areas),
				arrayChanged(override.hideInAreas, baseline.hideInAreas)
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.npcIds = baseline.npcIds;
			override.objectIds = baseline.objectIds;
			override.projectileIds = baseline.projectileIds;
			override.graphicsObjectIds = baseline.graphicsObjectIds;
			override.areas = baseline.areas;
			override.hideInAreas = baseline.hideInAreas;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"NPC IDs", "Object IDs", "Projectile IDs", "Graphics object IDs", "Areas", "Hide in areas"));
	}

	private Section buildMaterialSection() {
		var content = EditorPanels.stack();

		var picker = materialPicker(false, null);
		var materialDropdown = picker.build();
		var textureMaterialDropdown = picker.build();

		content.add(fieldRow("Material", materialDropdown));
		content.add(fieldRow("Texture material", textureMaterialDropdown));

		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);

		PropertySection view = new PropertySection("Material", content);
		Runnable[] self = new Runnable[1];

		materialDropdown.onChange(material -> { override.material = material; onFieldEdited(self[0]); });
		textureMaterialDropdown.onChange(material -> { override.textureMaterial = material; onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			materialDropdown.setValue(override.material);
			textureMaterialDropdown.setValue(override.textureMaterial);

			ToggleList.builder()
				.add("Force material changes", override.forceMaterialChanges,
					override.forceMaterialChanges != baseline.forceMaterialChanges,
					v -> { override.forceMaterialChanges = v; onFieldEdited(self[0]); })
				.add("Undo vanilla shading", override.undoVanillaShading,
					override.undoVanillaShading != baseline.undoVanillaShading,
					v -> { override.undoVanillaShading = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				!Objects.equals(override.material, baseline.material),
				!Objects.equals(override.textureMaterial, baseline.textureMaterial),
				override.forceMaterialChanges != baseline.forceMaterialChanges,
				override.undoVanillaShading != baseline.undoVanillaShading
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.material = baseline.material;
			override.textureMaterial = baseline.textureMaterial;
			override.forceMaterialChanges = baseline.forceMaterialChanges;
			override.undoVanillaShading = baseline.undoVanillaShading;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of("Material", "Texture material", "Force material changes", "Undo vanilla shading"));
	}

	private Section buildUvsSection() {
		var content = EditorPanels.stack();

		var uvTypeCombo = new UvTypeDropdown(UvType.VANILLA, false);
		NumberField uvScaleField = NumberField.builder().value(1.0).step(0.05).min(0.01).build();
		NumberField uvOrientationField = NumberField.builder().step(64).min(0.0).max(2047.0).unit("jau").build();
		NumberField uvOrientationXField = NumberField.builder().step(64).min(0.0).max(2047.0).unit("jau").build();
		NumberField uvOrientationYField = NumberField.builder().step(64).min(0.0).max(2047.0).unit("jau").build();
		NumberField uvOrientationZField = NumberField.builder().step(64).min(0.0).max(2047.0).unit("jau").build();
		JLabel uvsLabel = infoLabel("No custom UVs");

		content.add(fieldRow("UV type", uvTypeCombo));
		content.add(fieldRow("UV scale", uvScaleField));
		content.add(fieldRow("UV orientation", uvOrientationField));
		content.add(fieldRow("UV orientation X", uvOrientationXField));
		content.add(fieldRow("UV orientation Y", uvOrientationYField));
		content.add(fieldRow("UV orientation Z", uvOrientationZField));

		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);
		content.add(fieldRow("Custom UVs", uvsLabel));

		PropertySection view = new PropertySection("UVs", content);
		Runnable[] self = new Runnable[1];

		uvTypeCombo.onChange(value -> {
			override.uvType = value;
			onFieldEdited(self[0]);
		});
		uvScaleField.onChange(v -> { override.uvScale = v.floatValue(); onFieldEdited(self[0]); });
		uvOrientationField.onChange(v -> { override.uvOrientation = v.intValue(); onFieldEdited(self[0]); });
		uvOrientationXField.onChange(v -> { override.uvOrientationX = v.intValue(); onFieldEdited(self[0]); });
		uvOrientationYField.onChange(v -> { override.uvOrientationY = v.intValue(); onFieldEdited(self[0]); });
		uvOrientationZField.onChange(v -> { override.uvOrientationZ = v.intValue(); onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			uvTypeCombo.setValue(override.uvType);
			uvScaleField.setValue((double) override.uvScale);
			uvOrientationField.setValue((double) override.uvOrientation);
			uvOrientationXField.setValue((double) override.uvOrientationX);
			uvOrientationYField.setValue((double) override.uvOrientationY);
			uvOrientationZField.setValue((double) override.uvOrientationZ);
			uvsLabel.setText(override.uvs == null ? "No custom UVs" : "Custom UVs set (" + override.uvs.length + ")");

			ToggleList.builder()
				.add("Retain vanilla UVs", override.retainVanillaUvs,
					override.retainVanillaUvs != baseline.retainVanillaUvs,
					v -> { override.retainVanillaUvs = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				override.uvType != baseline.uvType,
				override.uvScale != baseline.uvScale,
				override.uvOrientation != baseline.uvOrientation,
				override.uvOrientationX != baseline.uvOrientationX,
				override.uvOrientationY != baseline.uvOrientationY,
				override.uvOrientationZ != baseline.uvOrientationZ,
				override.retainVanillaUvs != baseline.retainVanillaUvs,
				floatArrayChanged(override.uvs, baseline.uvs)
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.uvType = baseline.uvType;
			override.uvScale = baseline.uvScale;
			override.uvOrientation = baseline.uvOrientation;
			override.uvOrientationX = baseline.uvOrientationX;
			override.uvOrientationY = baseline.uvOrientationY;
			override.uvOrientationZ = baseline.uvOrientationZ;
			override.retainVanillaUvs = baseline.retainVanillaUvs;
			override.uvs = baseline.uvs;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"UV type", "UV scale", "UV orientation", "UV orientation X", "UV orientation Y", "UV orientation Z",
			"Retain vanilla UVs", "Custom UVs"));
	}

	private Section buildNormalsSection() {
		var content = EditorPanels.stack();
		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);

		PropertySection view = new PropertySection("Normals & faces", content);
		Runnable[] self = new Runnable[1];

		Runnable refresh = () -> withRefreshGuard(() -> {
			ToggleList.builder()
				.add("Flat normals", override.flatNormals,
					override.flatNormals != baseline.flatNormals,
					v -> { override.flatNormals = v; onFieldEdited(self[0]); })
				.add("Upwards normals", override.upwardsNormals,
					override.upwardsNormals != baseline.upwardsNormals,
					v -> { override.upwardsNormals = v; onFieldEdited(self[0]); })
				.add("Double sided faces", override.doubleSidedFaces,
					override.doubleSidedFaces != baseline.doubleSidedFaces,
					v -> { override.doubleSidedFaces = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				override.flatNormals != baseline.flatNormals,
				override.upwardsNormals != baseline.upwardsNormals,
				override.doubleSidedFaces != baseline.doubleSidedFaces
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.flatNormals = baseline.flatNormals;
			override.upwardsNormals = baseline.upwardsNormals;
			override.doubleSidedFaces = baseline.doubleSidedFaces;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of("Flat normals", "Upwards normals", "Double sided faces"));
	}

	private Section buildShadowsSection() {
		var content = EditorPanels.stack();
		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);
		NumberField thresholdField = NumberField.builder().step(0.05).min(0.0).max(1.0).build();
		content.add(fieldRow("Shadow opacity threshold", thresholdField));

		PropertySection view = new PropertySection("Shadows", content);
		Runnable[] self = new Runnable[1];
		thresholdField.onChange(v -> { override.shadowOpacityThreshold = v.floatValue(); onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			thresholdField.setValue((double) override.shadowOpacityThreshold);
			ToggleList.builder()
				.add("Cast shadows", override.castShadows,
					override.castShadows != baseline.castShadows,
					v -> { override.castShadows = v; onFieldEdited(self[0]); })
				.add("Receive shadows", override.receiveShadows,
					override.receiveShadows != baseline.receiveShadows,
					v -> { override.receiveShadows = v; onFieldEdited(self[0]); })
				.add("Hide vanilla shadows", override.hideVanillaShadows,
					override.hideVanillaShadows != baseline.hideVanillaShadows,
					v -> { override.hideVanillaShadows = v; onFieldEdited(self[0]); })
				.add("Retain vanilla shadows in PVM", override.retainVanillaShadowsInPvm,
					override.retainVanillaShadowsInPvm != baseline.retainVanillaShadowsInPvm,
					v -> { override.retainVanillaShadowsInPvm = v; onFieldEdited(self[0]); })
				.add("Hide HD shadows in PVM", override.hideHdShadowsInPvm,
					override.hideHdShadowsInPvm != baseline.hideHdShadowsInPvm,
					v -> { override.hideHdShadowsInPvm = v; onFieldEdited(self[0]); })
				.add("Shadow normal bias", override.shadowNormalBias,
					override.shadowNormalBias != baseline.shadowNormalBias,
					v -> { override.shadowNormalBias = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				override.castShadows != baseline.castShadows,
				override.receiveShadows != baseline.receiveShadows,
				override.hideVanillaShadows != baseline.hideVanillaShadows,
				override.retainVanillaShadowsInPvm != baseline.retainVanillaShadowsInPvm,
				override.hideHdShadowsInPvm != baseline.hideHdShadowsInPvm,
				override.shadowNormalBias != baseline.shadowNormalBias,
				override.shadowOpacityThreshold != baseline.shadowOpacityThreshold
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.castShadows = baseline.castShadows;
			override.receiveShadows = baseline.receiveShadows;
			override.hideVanillaShadows = baseline.hideVanillaShadows;
			override.retainVanillaShadowsInPvm = baseline.retainVanillaShadowsInPvm;
			override.hideHdShadowsInPvm = baseline.hideHdShadowsInPvm;
			override.shadowNormalBias = baseline.shadowNormalBias;
			override.shadowOpacityThreshold = baseline.shadowOpacityThreshold;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"Cast shadows", "Receive shadows", "Hide vanilla shadows", "Retain vanilla shadows in PVM",
			"Hide HD shadows in PVM", "Shadow normal bias", "Shadow opacity threshold"));
	}

	private Section buildTerrainSection() {
		var content = EditorPanels.stack();
		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);
		NumberField thresholdField = NumberField.builder().value(0.125).step(0.005).min(0.0).build();
		content.add(fieldRow("Vertex snap threshold", thresholdField));

		PropertySection view = new PropertySection("Terrain", content);
		view.setExpanded(false);
		Runnable[] self = new Runnable[1];
		thresholdField.onChange(v -> { override.terrainVertexSnapThreshold = v.floatValue(); onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			thresholdField.setValue((double) override.terrainVertexSnapThreshold);
			ToggleList.builder()
				.add("Terrain vertex snap", override.terrainVertexSnap,
					override.terrainVertexSnap != baseline.terrainVertexSnap,
					v -> { override.terrainVertexSnap = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				override.terrainVertexSnap != baseline.terrainVertexSnap,
				override.terrainVertexSnapThreshold != baseline.terrainVertexSnapThreshold
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.terrainVertexSnap = baseline.terrainVertexSnap;
			override.terrainVertexSnapThreshold = baseline.terrainVertexSnapThreshold;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of("Terrain vertex snap", "Vertex snap threshold"));
	}

	private javax.swing.JToggleButton windPreviewButton;

	void setWindPreviewSelected(boolean enabled) {
		if (windPreviewButton != null)
			windPreviewButton.setSelected(enabled);
	}

	private Section buildWindSection() {
		var content = EditorPanels.stack();
		var modeControl = new WindModeDropdown();
		var modeRow = fieldRow("Mode", modeControl);
		var modifierField = new WindStrength();
		windPreviewButton = EditorButton.builder().style(WorkspaceStyle.Button.TRIGGER).text("Preview wind")
			.tooltip("Animate the override preview. Move across the grid to simulate a player in Object sway mode.")
			.onToggle(context::setWindPreview).buildToggle(false);
		content.add(fieldRow("Viewport", windPreviewButton));
		content.add(modeRow);
		content.add(modifierField);
		var toggles = ToggleList.builder().build();
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(toggles);

		PropertySection view = new PropertySection("Wind", content);
		view.setExpanded(false);
		Runnable[] self = new Runnable[1];
		modeControl.onChange(v -> { override.windDisplacementMode = v; onFieldEdited(self[0]); });
		modifierField.onChange(v -> { override.windDisplacementModifier = v; onFieldEdited(self[0]); });

		Runnable refresh = () -> withRefreshGuard(() -> {
			modeControl.setSelected(override.windDisplacementMode == null ? WindDisplacement.DISABLED : override.windDisplacementMode);
			boolean disabled = modeControl.getSelected() == WindDisplacement.DISABLED;
			setFieldChanged(modeRow, override.windDisplacementMode != baseline.windDisplacementMode);
			modifierField.update(override.windDisplacementModifier, disabled,
				override.windDisplacementModifier != baseline.windDisplacementModifier);
			view.setInactiveNote(disabled ? "Wind is disabled" : null);
			ToggleList.builder()
				.add("Invert strength (sway more at the base)", override.invertDisplacementStrength,
					override.invertDisplacementStrength != baseline.invertDisplacementStrength,
					v -> { override.invertDisplacementStrength = v; onFieldEdited(self[0]); })
				.update(toggles);

			view.setChangedCount(count(
				override.windDisplacementMode != baseline.windDisplacementMode,
				override.windDisplacementModifier != baseline.windDisplacementModifier,
				override.invertDisplacementStrength != baseline.invertDisplacementStrength
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.windDisplacementMode = baseline.windDisplacementMode;
			override.windDisplacementModifier = baseline.windDisplacementModifier;
			override.invertDisplacementStrength = baseline.invertDisplacementStrength;
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"Mode", "Strength", "Invert strength (sway more at the base)"));
	}

	private Section buildColorSection() {
		var content = EditorPanels.stack();

		var paletteHolder = new JPanel();
		paletteHolder.setLayout(new BoxLayout(paletteHolder, BoxLayout.Y_AXIS));
		paletteHolder.setOpaque(false);
		paletteHolder.setAlignmentX(Component.LEFT_ALIGNMENT);

		var rulesHolder = new JPanel();
		rulesHolder.setLayout(new BoxLayout(rulesHolder, BoxLayout.Y_AXIS));
		rulesHolder.setOpaque(false);
		rulesHolder.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton newRuleButton = DashedAddButton.create("Add color override");
		newRuleButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		newRuleButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		newRuleButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		newRuleButton.setBorderPainted(true);
		newRuleButton.setBorder(BorderFactory.createDashedBorder(new Color(0x444444), 3, 3));

		SegmentedControl<TzHaarRecolorType> tzControl = SegmentedControl.builder(List.of(TzHaarRecolorType.values()))
			.values().label(Enum::name).selected(TzHaarRecolorType.NONE).build();
		SegmentedControl<InheritTileColorType> inheritControl = SegmentedControl.builder(List.of(InheritTileColorType.values()))
			.values().label(Enum::name).selected(InheritTileColorType.NONE).build();

		var mode = SegmentedControl.builder(List.of("Set", "Shift", "Min", "Max")).build();
		var sliders = new JPanel(new GridLayout(4, 1, 0, 2));
		sliders.setOpaque(false);
		var sample = new BeforeAfterSwatch(36, BeforeAfterSwatch.Split.HORIZONTAL, Color.GRAY, Color.GRAY);
		sample.setToolTipText("Model color before and after");
		var sliderPanel = new JPanel(new BorderLayout(8, 0));
		sliderPanel.setOpaque(false);
		var picker = EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("eyedropper", 12)
			.tooltip("Pick the whole-model color").build();
		var swatchColumn = EditorPanels.stack();
		swatchColumn.add(sample);
		swatchColumn.add(picker);
		var swatchSlot = EditorPanels.centered(swatchColumn);
		sliderPanel.add(swatchSlot, BorderLayout.WEST);
		sliderPanel.add(sliders);
		String[] channels = {"Hue", "Saturation", "Lightness", "Alpha"};
		int[] limits = {63, 7, 127, 255};
		int[] bits = {10, 7, 0, 16};
		int[] source = {0xFF0040};
		java.util.function.IntSupplier output = () -> {
			int alpha = ModelOverrideFields.getInt(override, "setAlpha");
			if (alpha < 0)
				alpha = override.modifyAlpha(source[0] >>> 16);
			return alpha << 16 | ruleOutput(override, source[0]);
		};
		Runnable updateSamples = () -> {
			sample.setColors(sampleColor(source[0]), sampleColor(output.getAsInt()));
			sliders.repaint();
		};
		var controls = new ArrayList<javax.swing.JSlider>();
		for (int i = 0; i < channels.length; i++) {
			String name = channels[i];
			int max = limits[i], shift = bits[i], mask = max << shift;
			var slider = new javax.swing.JSlider(0, max);
			controls.add(slider);
			var row = gradientSlider(slider, name, position -> {
				int packed = override == null ? source[0] : output.getAsInt();
				int value = Math.round(position * max);
				if (override != null && "Shift".equals(mode.getSelected())) {
					int lower = ModelOverrideFields.getInt(override, "min" + name);
					int upper = ModelOverrideFields.getInt(override, "max" + name);
					value = Math.min(Math.max((source[0] >> shift & max) + 2 * value - max, lower), upper);
					value = Math.max(0, Math.min(max, value));
				}
				if (shift == 16)
					return sampleColor(value << 16 | packed & 0xFFFF);
				if (shift == 10)
					packed = 4 << 7 | 64;
				else if (shift == 7)
					packed = packed & 0xFC00 | 64;
				return sampleColor(0xFF0000 | packed & 0xFFFF & ~mask | value << shift);
			});
			sliders.add(row);
		}
		Runnable refreshSliders = () -> withRefreshGuard(() -> {
			String operation = mode.getSelected();
			for (int i = 0; i < channels.length; i++) {
				var slider = controls.get(i);
				int min = "Shift".equals(operation) ? -limits[i] : "Set".equals(operation) ? -1 : 0;
				int value = ModelOverrideFields.getInt(override, operation.toLowerCase(Locale.ROOT) + channels[i]);
				slider.putClientProperty("unsetAllowed", "Set".equals(operation));
				slider.setModel(new javax.swing.DefaultBoundedRangeModel(
					Math.max(min, Math.min(limits[i], value)), 0, min, limits[i]));
			}
			updateSamples.run();
		});
		Runnable[] self = new Runnable[1];
		ColorPicker.builder(picker, () -> sampleColor(output.getAsInt()))
			.palette("MODEL COLORS", () -> pickerPalette(modelColors)).onApply(color -> {
				if (override == null)
					return;
				setPickedColor(override, color);
				mode.setSelected("Set");
				onFieldEdited(self[0]);
			}).bind();
		mode.onChange(ignored -> refreshSliders.run());
		content.add(paletteHolder);
		content.add(spacer());
		content.add(infoLabel("Whole model"));
		content.add(PropertyRow.compact("Change", mode));
		content.add(PropertyRow.compact("", sliderPanel));
		content.add(spacer());
		content.add(fieldRow("TzHaar recolor", tzControl));
		content.add(fieldRow("Inherit tile color", inheritControl));
		content.add(spacer());
		PropertySection view = new PropertySection("Color", content);

		var rulesContent = new JPanel();
		rulesContent.setOpaque(false);
		rulesContent.setLayout(new BoxLayout(rulesContent, BoxLayout.Y_AXIS));
		rulesContent.add(rulesHolder);
		rulesContent.add(newRuleButton);
		var rulesSection = new PropertySection("Color overrides", rulesContent);

		Runnable refreshRules = () -> {
			rulesHolder.removeAll();
			if (pendingColorRule != null) {
				var choose = new JPanel(new BorderLayout(4, 8));
				choose.setBackground(new Color(0x1C2330));
				choose.setAlignmentX(LEFT_ALIGNMENT);
				choose.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.ACCENT, 1, true),
					BorderFactory.createEmptyBorder(10, 10, 10, 10)));
				var heading = new JPanel(new BorderLayout());
				heading.setOpaque(false);
				int ruleIndex = override.colorOverrides == null ? -1 : java.util.Arrays.asList(override.colorOverrides).indexOf(pendingColorRule);
				heading.add(EditorText.builder(ruleIndex < 0 ? "New color override · choose a color" : "Add a color to Rule " + (ruleIndex + 1))
					.font(WorkspaceStyle.SEMIBOLD).icon(WorkspaceStyle.icon("eyedropper", 12)).build());
				heading.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).text("Cancel").onClick(this::cancelColorPicking).build(), BorderLayout.EAST);
				choose.add(heading, BorderLayout.NORTH);
				var hint = new javax.swing.JTextArea("Choose from the model colors, or click a face on the model in the viewer.");
				hint.setFont(WorkspaceStyle.UI.deriveFont(11f));
				hint.setForeground(new Color(0x8A9AB3));
				hint.setOpaque(false);
				hint.setEditable(false);
				hint.setFocusable(false);
				hint.setLineWrap(true);
				hint.setWrapStyleWord(true);
				hint.setRows(2);
				choose.add(hint);
				if (previewModel != null)
					choose.add(buildModelColorPalette(), BorderLayout.SOUTH);
				rulesHolder.add(choose);
				rulesHolder.add(Box.createVerticalStrut(6));
			}
			ModelOverride[] rules = override.colorOverrides;
			if (rules != null) {
				for (int i = 0; i < rules.length; i++) {
					JPanel card = buildRuleCard(rules[i], i);
					card.setAlignmentX(Component.LEFT_ALIGNMENT);
					rulesHolder.add(card);
					rulesHolder.add(Box.createVerticalStrut(4));
				}
			}
			rulesSection.setChangedCount(rules == null ? 0 : rules.length);
			hoveredColor = null;
			updateColorHighlight();
			rulesHolder.revalidate();
			rulesHolder.repaint();
		};
		refreshColorRules = refreshRules;
		rulesSection.onReset(() -> {
			override.colorOverrides(baseline.colorOverrides);
			pendingColorRule = null;
			refreshRules.run();
			fireChange();
		});
		colorRulesSection = new Section(rulesSection, refreshRules, List.of("Color overrides", "colorOverrides"));

		newRuleButton.addActionListener(event -> chooseColor(new ModelOverride()));

		tzControl.onChange(v -> { override.tzHaarRecolorType = v; onFieldEdited(self[0]); });
		inheritControl.onChange(v -> { override.inheritTileColorType = v; onFieldEdited(self[0]); });

		List<List<java.util.function.ObjIntConsumer<ModelOverride>>> setters = List.of(
			List.of(ModelOverride::setHue, ModelOverride::setSaturation, ModelOverride::setLightness, ModelOverride::setAlpha),
			List.of(ModelOverride::shiftHue, ModelOverride::shiftSaturation, ModelOverride::shiftLightness, ModelOverride::shiftAlpha),
			List.of(ModelOverride::minHue, ModelOverride::minSaturation, ModelOverride::minLightness, ModelOverride::minAlpha),
			List.of(ModelOverride::maxHue, ModelOverride::maxSaturation, ModelOverride::maxLightness, ModelOverride::maxAlpha));
		var operations = List.of("Set", "Shift", "Min", "Max");
		for (int i = 0; i < controls.size(); i++) {
			int channel = i;
			var slider = controls.get(i);
			slider.addChangeListener(e -> {
				if (refreshing || slider.getValueIsAdjusting() || override == null)
					return;
				setters.get(operations.indexOf(mode.getSelected())).get(channel).accept(override, slider.getValue());
				onFieldEdited(self[0]);
			});
		}

		Runnable refresh = () -> withRefreshGuard(() -> {
			tzControl.setSelected(override.tzHaarRecolorType == null ? TzHaarRecolorType.NONE : override.tzHaarRecolorType);
			inheritControl.setSelected(override.inheritTileColorType == null ? InheritTileColorType.NONE : override.inheritTileColorType);

			source[0] = previewModel == null ? 0xFF0040 : modelColors.stream()
				.max(Comparator.comparingInt(entry -> entry.faceCount)).map(entry -> entry.ahsl).orElse(0xFF0040);
			refreshSliders.run();

			paletteHolder.removeAll();
			if (previewModel != null) {
				paletteHolder.add(buildModelColorPalette());
			} else {
				JLabel none = infoLabel("No preview model loaded");
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				paletteHolder.add(none);
			}
			paletteHolder.revalidate();
			paletteHolder.repaint();

			view.setChangedCount(count(
				override.tzHaarRecolorType != baseline.tzHaarRecolorType,
				override.inheritTileColorType != baseline.inheritTileColorType,
				(ModelOverrideFields.getInt(override, "setHue") != ModelOverrideFields.getInt(baseline, "setHue") ||
				ModelOverrideFields.getInt(override, "shiftHue") != ModelOverrideFields.getInt(baseline, "shiftHue") ||
				ModelOverrideFields.getInt(override, "minHue") != ModelOverrideFields.getInt(baseline, "minHue") ||
				ModelOverrideFields.getInt(override, "maxHue") != ModelOverrideFields.getInt(baseline, "maxHue") ||
				ModelOverrideFields.getInt(override, "setSaturation") != ModelOverrideFields.getInt(baseline, "setSaturation") ||
				ModelOverrideFields.getInt(override, "shiftSaturation") != ModelOverrideFields.getInt(baseline, "shiftSaturation") ||
				ModelOverrideFields.getInt(override, "minSaturation") != ModelOverrideFields.getInt(baseline, "minSaturation") ||
				ModelOverrideFields.getInt(override, "maxSaturation") != ModelOverrideFields.getInt(baseline, "maxSaturation") ||
				ModelOverrideFields.getInt(override, "setLightness") != ModelOverrideFields.getInt(baseline, "setLightness") ||
				ModelOverrideFields.getInt(override, "shiftLightness") != ModelOverrideFields.getInt(baseline, "shiftLightness") ||
				ModelOverrideFields.getInt(override, "minLightness") != ModelOverrideFields.getInt(baseline, "minLightness") ||
				ModelOverrideFields.getInt(override, "maxLightness") != ModelOverrideFields.getInt(baseline, "maxLightness") ||
				ModelOverrideFields.getInt(override, "setAlpha") != ModelOverrideFields.getInt(baseline, "setAlpha") ||
				ModelOverrideFields.getInt(override, "shiftAlpha") != ModelOverrideFields.getInt(baseline, "shiftAlpha") ||
				ModelOverrideFields.getInt(override, "minAlpha") != ModelOverrideFields.getInt(baseline, "minAlpha") ||
				ModelOverrideFields.getInt(override, "maxAlpha") != ModelOverrideFields.getInt(baseline, "maxAlpha"))
			));
		});
		self[0] = refresh;

		view.onReset(() -> {
			override.tzHaarRecolorType = baseline.tzHaarRecolorType;
			override.inheritTileColorType = baseline.inheritTileColorType;
			override.setHue(ModelOverrideFields.getInt(baseline, "setHue"));
			override.shiftHue(ModelOverrideFields.getInt(baseline, "shiftHue"));
			override.minHue(ModelOverrideFields.getInt(baseline, "minHue"));
			override.maxHue(ModelOverrideFields.getInt(baseline, "maxHue"));
			override.setSaturation(ModelOverrideFields.getInt(baseline, "setSaturation"));
			override.shiftSaturation(ModelOverrideFields.getInt(baseline, "shiftSaturation"));
			override.minSaturation(ModelOverrideFields.getInt(baseline, "minSaturation"));
			override.maxSaturation(ModelOverrideFields.getInt(baseline, "maxSaturation"));
			override.setLightness(ModelOverrideFields.getInt(baseline, "setLightness"));
			override.shiftLightness(ModelOverrideFields.getInt(baseline, "shiftLightness"));
			override.minLightness(ModelOverrideFields.getInt(baseline, "minLightness"));
			override.maxLightness(ModelOverrideFields.getInt(baseline, "maxLightness"));
			override.setAlpha(ModelOverrideFields.getInt(baseline, "setAlpha"));
			override.shiftAlpha(ModelOverrideFields.getInt(baseline, "shiftAlpha"));
			override.minAlpha(ModelOverrideFields.getInt(baseline, "minAlpha"));
			override.maxAlpha(ModelOverrideFields.getInt(baseline, "maxAlpha"));
			self[0].run();
			fireChange();
		});

		return new Section(view, self[0], List.of(
			"TzHaar recolor", "Inherit tile color", "Hue", "Saturation", "Lightness", "Alpha",
			"Set", "Shift", "Min", "Max", "Color rules"));
	}

	private Section buildMaterialSwapsSection() {
		var content = new JPanel();
		content.setOpaque(false);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		var view = new PropertySection("Material overrides", content);
		var add = DashedAddButton.create("Add material swap");
		add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			content.removeAll();
			var swaps = override.materialOverrides;
			var sources = vanillaSwapSources();
			if (swaps != null) {
				swaps.forEach((source, rule) -> {
					if (rule == null)
						return;
					var row = new JPanel(new net.miginfocom.swing.MigLayout("insets 6, gap 4, fillx, wrap 4", "[][grow,fill][][grow,fill]"));
					row.setBackground(new Color(0x1A1A1A));
					row.setBorder(BorderFactory.createLineBorder(new Color(0x333333), 1, true));
					row.setAlignmentX(Component.LEFT_ALIGNMENT);
					row.add(infoLabel("When"));
					var when = PreviewDropdown.builder(new PreviewDropdown.Provider<Material>() {
						@Override public List<Material> items() {
							return sources.keySet().stream()
								.filter(candidate -> candidate == source || !swaps.containsKey(candidate))
								.collect(java.util.stream.Collectors.toList());
						}

						@Override public String label(Material material) { return material.name; }

						@Override public Image preview(Material material, int size) {
							Integer textureId = sources.get(material);
							return textureId == null ? null : vanillaTexturePreview(textureId, size);
						}
					}).search("Search vanilla materials").compact(true).caption("When the face material is").build();
					when.setValue(source);
					when.onChange(value -> {
						if (value != null && value != source && !swaps.containsKey(value)) {
							swaps.remove(source);
							swaps.put(value, rule);
						}
						onFieldEdited(refresh[0]);
					});
					row.add(when, "w 0:80:120");
					row.add(infoLabel("→"));
					row.add(swapMaterial(rule.textureMaterial != Material.NONE ? rule.textureMaterial : rule.material,
						"Replace it with", value -> {
							rule.textureMaterial = value;
							rule.material = Material.NONE;
							fireChange();
						}), "w 0:80:120");
					var swapUv = new UvTypeDropdown(rule.uvType, true);
					swapUv.onChange(value -> { rule.uvType = value; fireChange(); });
					row.add(swapUv, "span 3, growx");
					row.add(EditorButton.builder().icon("close", 10).tooltip("Remove material swap").size(22, 22)
						.onClick(() -> {
							swaps.remove(source);
							if (swaps.isEmpty())
								override.materialOverrides = null;
							onFieldEdited(refresh[0]);
						}).build());
					content.add(row);
				});
			}
			boolean canAdd = sources.keySet().stream().anyMatch(source -> swaps == null || !swaps.containsKey(source));
			add.setEnabled(canAdd);
			add.setToolTipText(canAdd ? null : sources.isEmpty()
				? "The preview model has no mapped vanilla textures"
				: "All vanilla materials already have a swap");
			if (previewModel != null && sources.isEmpty()) {
				var empty = infoLabel("No vanilla textures");
				empty.setAlignmentX(Component.LEFT_ALIGNMENT);
				content.add(empty);
			} else {
				content.add(add);
			}
			view.setChangedCount(swaps == null || swaps.isEmpty() ? 0 : 1);
			content.revalidate();
			content.repaint();
		};
		add.setAlignmentX(Component.LEFT_ALIGNMENT);
		add.addActionListener(e -> {
			var swaps = override.materialOverrides == null ? new java.util.LinkedHashMap<Material, ModelOverride>()
				: new java.util.LinkedHashMap<>(override.materialOverrides);
			var available = context.materials();
			var source = vanillaSwapSources().keySet().stream().filter(value -> !swaps.containsKey(value))
				.findFirst().orElse(null);
			if (source == null)
				return;
			var rule = new ModelOverride();
			rule.textureMaterial = available.stream().filter(value -> value != null && "HD_MARBLE".equals(value.name))
				.findFirst().orElse(source);
			swaps.put(source, rule);
			override.materialOverrides = swaps;
			onFieldEdited(refresh[0]);
		});
		view.onReset(() -> { override.materialOverrides = null; onFieldEdited(refresh[0]); });
		return new Section(view, refresh[0], List.of("Material overrides", "Material swaps"));
	}

	private java.util.Map<Material, Integer> vanillaSwapSources() {
		var mapping = rs117.hd.scene.MaterialManager.VANILLA_TEXTURE_MAPPING;
		var textures = previewModel == null ? null : previewModel.getFaceTextures();
		if (mapping == null || textures == null)
			return java.util.Map.of();

		var sources = new java.util.LinkedHashMap<Material, Integer>();
		for (short texture : textures) {
			if (texture >= 0 && texture < mapping.length) {
				var material = mapping[texture];
				if (material != null && material != Material.NONE)
					sources.putIfAbsent(material, (int) texture);
			}
		}
		return sources;
	}

	private Image vanillaTexturePreview(int textureId, int size) {
		long key = (long) textureId << 32 | size;
		if (!vanillaTexturePreviews.containsKey(key)) {
			int nativeSize = rs117.hd.dev.sceneloader.standalone.Textures.SIZE;
			Image image;
			if (size == nativeSize) {
				image = MaterialThumbnails.vanillaPreview(context.vanillaTexturePixels(textureId));
			} else {
				var original = vanillaTexturePreview(textureId, nativeSize);
				image = original == null ? null : MaterialThumbnails.scale(original, size);
			}
			vanillaTexturePreviews.put(key, image);
		}
		return vanillaTexturePreviews.get(key);
	}

	private Image materialPreview(Material material, int size, JComponent observer) {
		if (material.vanillaTextureIndex >= 0) {
			Image image = vanillaTexturePreview(material.vanillaTextureIndex, size);
			if (image != null)
				return image;
		}
		return MaterialThumbnails.preview(material, size, observer);
	}

	private PreviewDropdown.Builder<Material> materialPicker(boolean compact, String caption) {
		return PreviewDropdown.builder(new PreviewDropdown.Provider<Material>() {
			@Override public List<Material> items() { return context.materials(); }
			@Override public String label(Material material) { return material.name; }
			@Override public Image preview(Material material, int size) { return preview(material, size, OverrideProperties.this); }
			@Override public Image preview(Material material, int size, JComponent observer) {
				return materialPreview(material, size, observer);
			}
		}).search("Search materials").filterItems(MaterialThumbnails::withoutAuxiliaryMaps)
			.compact(compact).caption(caption);
	}

	private PreviewDropdown<Material> swapMaterial(Material initial, String caption, Consumer<Material> change) {
		var dropdown = materialPicker(true, caption).build();
		dropdown.setValue(initial);
		dropdown.onChange(change);
		return dropdown;
	}

	private JPanel buildRuleCard(ModelOverride rule, int index) {
		var card = new JPanel(new BorderLayout(0, 8));
		card.setBackground(new Color(0x1A1A1A));
		boolean expanded = openColorRule == rule;
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(expanded || pendingColorRule == rule ? WorkspaceStyle.ACCENT : new Color(0x333333), 1, true),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		var header = new JPanel(new BorderLayout(6, 0));
		header.setPreferredSize(new Dimension(0, 34));
		header.setOpaque(false);
		var colors = ruleColors(rule);
		var heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
		heading.setOpaque(false);
		var title = EditorButton.builder().style(WorkspaceStyle.Button.TAB).text("Rule " + (index + 1))
			.icon(expanded ? "chevron-down" : "chevron-right", 9).onClick(() -> {
				openColorRule = expanded ? null : rule;
				refreshColorRules.run();
			}).build();
		title.setFont(WorkspaceStyle.SEMIBOLD);
		heading.add(title);
		var matches = new LinkedHashSet<Integer>();
		for (var color : colors)
			if (color.isJsonPrimitive() && color.getAsJsonPrimitive().isNumber())
				matches.add(color.getAsInt() & 0xFFFF);
		var predicate = ModelOverrideFields.colorPredicate(rule, colors);
		var matchedColors = previewModel == null ? List.<ModelColorPalette.Entry>of()
			: modelColors.stream()
				.filter(entry -> predicate.test(entry.ahsl)).collect(java.util.stream.Collectors.toList());
		int source = matchedColors.stream().max(Comparator.comparingInt(entry -> entry.faceCount))
			.map(entry -> entry.ahsl & 0xFFFF).orElseGet(() -> matches.isEmpty() ? -1 : matches.iterator().next());
		for (int rgb : matchedColors.stream().mapToInt(entry -> entry.ahsl & 65535).distinct().limit(4).toArray())
			heading.add(ruleSample(rule, rgb));
		String expressionText = expressionText(rule);
		var summary = EditorText.builder("").font(ColorExpressionPanel.CODE.deriveFont(10f)).build();
		var palette = modelColors;
		java.util.function.Function<String, String> evaluate = expression -> {
			boolean valid = ColorExpression.valid(expression);
			var test = valid ? ModelOverrideFields.colorPredicate(rule, new JsonPrimitive(expression)) : null;
			var hits = palette.stream().filter(entry -> test != null && test.test(entry.ahsl)).collect(java.util.stream.Collectors.toList());
			int faces = hits.stream().mapToInt(entry -> entry.faceCount).sum();
			String caption = (valid ? expression : "invalid expression") + " · " + faces + " faces";
			summary.setText(caption);
			summary.setToolTipText(caption);
			summary.setForeground(new Color(valid ? 0x9FB8E0 : 0xE8A0A0));
			return hits.isEmpty() ? "Valid, but no colors on this model match"
				: "Matches " + hits.size() + " of " + palette.size() + " model colors · " + faces + " faces";
		};
		evaluate.apply(expressionText);
		header.add(heading, BorderLayout.WEST);
		header.add(summary);

		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		actions.setOpaque(false);
		actions.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("eyedropper", 12).tooltip("Add a color").onClick(() -> chooseColor(rule)).build());
		actions.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("trash", 12).tooltip("Delete rule").onClick(() -> {
			var rules = new ArrayList<>(java.util.Arrays.asList(override.colorOverrides));
			rules.remove(rule);
			override.colorOverrides(rules.isEmpty() ? null : rules.toArray(ModelOverride[]::new));
			if (openColorRule == rule)
				openColorRule = null;
			if (pendingColorRule == rule)
				cancelColorPicking();
			onFieldEdited(refreshColorRules);
		}).build());
		header.add(actions, BorderLayout.EAST);
		if (expanded)
			header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2A2A2A)));
		card.add(header, BorderLayout.NORTH);
		if (!expanded)
			return card;

		var body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		var changeMode = SegmentedControl.builder(List.of("Set color", "Shift")).build();
		boolean shifted = ModelOverrideFields.getInt(rule, "shiftHue") != 0
			|| ModelOverrideFields.getInt(rule, "shiftSaturation") != 0
			|| ModelOverrideFields.getInt(rule, "shiftLightness") != 0;
		boolean hasAbsoluteColor = ModelOverrideFields.getInt(rule, "setHue") >= 0
			&& ModelOverrideFields.getInt(rule, "setSaturation") >= 0
			&& ModelOverrideFields.getInt(rule, "setLightness") >= 0;
		changeMode.setSelected(shifted && !hasAbsoluteColor ? "Shift" : "Set color");
		var fields = new JPanel(new java.awt.CardLayout());
		fields.setOpaque(false);
		var set = new JPanel(new GridLayout(3, 1, 0, 0));
		var sliderPanel = new JPanel(new BorderLayout(8, 0));
		sliderPanel.setOpaque(false);
		sliderPanel.add(fields);
		var result = EditorButton.builder().style(WorkspaceStyle.Button.TAB).size(40, 40).tooltip("Pick the replacement color").build();
		var preview = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 4, wrap 3", "[]"));
		preview.setOpaque(false);
		Runnable updateSamples = () -> {
			int color = ruleOutput(rule, source < 0 ? 0 : source);
			result.setIcon(colorIcon(color, 36, 36));
			preview.removeAll();
			var test = ModelOverrideFields.colorPredicate(rule, ruleColors(rule));
			var inputs = palette.stream().filter(entry -> test.test(entry.ahsl))
				.mapToInt(entry -> entry.ahsl & 65535).distinct().limit(10).toArray();
			for (int input : inputs) {
				var pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
				pair.setOpaque(false);
				pair.add(colorSample(input, 18, 18));
				pair.add(infoLabel("→"));
				pair.add(colorSample(ruleOutput(rule, input), 18, 18));
				preview.add(pair);
			}
			if (inputs.length == 0)
				preview.add(infoLabel("No colors on this model match yet."), "span");
			preview.revalidate();
			preview.repaint();
		};
		updateSamples.run();
		var resultSlot = EditorPanels.centered(result);
		sliderPanel.add(resultSlot, BorderLayout.WEST);
		var shift = new JPanel(new GridLayout(3, 1, 0, 2));
		set.setOpaque(false);
		shift.setOpaque(false);
		var hueSlider = addColorChannel(set, shift, rule, "Hue", 63, rule::setHue, rule::shiftHue, source, updateSamples);
		var saturationSlider = addColorChannel(set, shift, rule, "Saturation", 7, rule::setSaturation, rule::shiftSaturation, source, updateSamples);
		var lightnessSlider = addColorChannel(set, shift, rule, "Lightness", 127, rule::setLightness, rule::shiftLightness, source, updateSamples);
		fields.add(set, "Set color");
		fields.add(shift, "Shift");
		ColorPicker.builder(result, () -> sampleColor(0xFF0000 | ruleOutput(rule, Math.max(0, source))))
			.palette("MODEL COLORS", () -> pickerPalette(palette)).onApply(color -> {
				setPickedColor(rule, color);
				changeMode.setSelected("Set color");
				((java.awt.CardLayout) fields.getLayout()).show(fields, "Set color");
				int packed = ruleOutput(rule, Math.max(0, source));
				withRefreshGuard(() -> {
					hueSlider.setValue(packed >> 10 & 63);
					saturationSlider.setValue(packed >> 7 & 7);
					lightnessSlider.setValue(packed & 127);
				});
				updateSamples.run();
				fireChange();
			}).bind();
		var fieldLayout = (java.awt.CardLayout) fields.getLayout();
		fieldLayout.show(fields, changeMode.getSelected());
		changeMode.onChange(mode -> fieldLayout.show(fields, mode));
		body.add(new ColorExpressionPanel(expressionText, expressionViewRule == rule, text -> {
			rule.colors(new JsonPrimitive(text));
			updateColorHighlight();
			updateSamples.run();
			fireChange();
		}, evaluate, expression -> expressionViewRule = expression ? rule : null, () -> chooseColor(rule), context::colorToast));
		body.add(Box.createVerticalStrut(8));

		body.add(PropertyRow.compact("Change", changeMode));
		body.add(PropertyRow.compact("", sliderPanel));
		body.add(PropertyRow.compact("Material", swapMaterial(rule.material, "Material for rule " + (index + 1),
			material -> { rule.material = material; fireChange(); })));
		body.add(new PropertyRow("Preview", preview, PropertyRow.Layout.WIDE));
		card.add(body);
		return card;
	}

	private static JLabel ruleSample(ModelOverride rule, int source) {
		var image = new java.awt.image.BufferedImage(22, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		var g = image.createGraphics();
		g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, 22, 14, 3, 3));
		colorIcon(source, 11, 14).paintIcon(null, g, 0, 0);
		colorIcon(ruleOutput(rule, source), 11, 14).paintIcon(null, g, 11, 0);
		g.dispose();
		return new JLabel(new javax.swing.ImageIcon(image));
	}


	private static int ruleOutput(ModelOverride rule, int source) {
		int hue = ModelOverrideFields.getInt(rule, "setHue");
		int saturation = ModelOverrideFields.getInt(rule, "setSaturation");
		int lightness = ModelOverrideFields.getInt(rule, "setLightness");
		int shifted = rule.modifyColor(source);
		return (hue < 0 ? shifted & 0xFC00 : hue << 10)
			| (saturation < 0 ? shifted & 0x380 : saturation << 7)
			| (lightness < 0 ? shifted & 0x7F : lightness);
	}

	private static JLabel colorSample(int hsl, int width, int height) {
		return new JLabel(colorIcon(hsl, width, height));
	}

	private static javax.swing.Icon colorIcon(int hsl, int width, int height) {
		float[] rgb = ColorUtils.packedHslToSrgb(hsl & 0xFFFF);
		var sample = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		var graphics = sample.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(new Color(rgb[0], rgb[1], rgb[2]));
		graphics.fillRoundRect(0, 0, width, height, 7, 7);
		graphics.dispose();
		return new javax.swing.ImageIcon(sample);
	}

	private javax.swing.JSlider addColorChannel(JPanel set, JPanel shift, ModelOverride rule, String name, int max,
		java.util.function.IntConsumer setValue, java.util.function.IntConsumer shiftValue, int source, Runnable updateSamples) {
		int bits = name.equals("Hue") ? 10 : name.equals("Saturation") ? 7 : 0;
		var absolute = new javax.swing.JSlider(0, max, (ruleOutput(rule, Math.max(0, source)) >> bits) & max);
		int initialShift = ModelOverrideFields.getInt(rule, "shift" + name);
		var offset = NumberField.builder().min((double) -max).max((double) max)
			.value((double) Math.max(-max, Math.min(max, initialShift))).build();
		boolean[] syncing = {false};
		set.add(colorSlider(absolute, name, bits, max, source, rule, false, value -> {
			if (syncing[0] || refreshing)
				return;
			setValue.accept(value);
			updateSamples.run();
			set.repaint();
			fireChange();
		}));
		shift.add(new PropertyRow(name.substring(0, 1) + "+", offset, PropertyRow.Layout.SIMPLE));
		offset.onChange(value -> {
			shiftValue.accept(value.intValue());
			setValue.accept(-1);
			syncing[0] = true;
			try {
				absolute.setValue((ruleOutput(rule, Math.max(0, source)) >> bits) & max);
			} finally {
				syncing[0] = false;
			}
			updateSamples.run();
			shift.repaint();
			fireChange();
		});
		return absolute;
	}

	private JPanel colorSlider(javax.swing.JSlider slider, String name, int bits, int max, int source,
		ModelOverride rule, boolean signed, java.util.function.IntConsumer commit) {
		var row = gradientSlider(slider, name, position -> {
			int packed = ruleOutput(rule, Math.max(0, source));
			int channel = Math.round(position * max);
			if (signed) {
				int original = (Math.max(0, source) >> bits) & max;
				int offset = Math.round(position * 2 * max) - max;
				int min = ModelOverrideFields.getInt(rule, "min" + name);
				int upper = ModelOverrideFields.getInt(rule, "max" + name);
				channel = Math.max(min, Math.min(upper, original + offset));
			}
			int base = bits == 10 ? 4 << 7 | 64 : bits == 7 ? (packed & 0xFC00) | 64 : packed & ~127;
			return sampleColor(0xFF0000 | (base & ~(max << bits)) | channel << bits);
		});
		slider.addChangeListener(e -> {
			if (!slider.getValueIsAdjusting())
				commit.accept(slider.getValue());
		});
		slider.getAccessibleContext().setAccessibleName(name + (signed ? " shift" : ""));
		return row;
	}

	private static List<Color> pickerPalette(List<ModelColorPalette.Entry> palette) {
		return palette.stream().sorted(Comparator.comparingInt((ModelColorPalette.Entry entry) -> entry.faceCount).reversed())
			.map(entry -> sampleColor(0xFF0000 | (entry.ahsl & 0xFFFF))).distinct().limit(20)
			.collect(java.util.stream.Collectors.toList());
	}

	private static void setPickedColor(ModelOverride target, Color color) {
		int packed = ColorUtils.hsl(color.getRGB() & 0xffffff);
		target.setHue(packed >> 10 & 63);
		target.setSaturation(packed >> 7 & 7);
		target.setLightness(packed & 127);
	}

	private static Color sampleColor(int ahsl) {
		float[] rgb = ColorUtils.packedHslToSrgb(ahsl & 0xFFFF);
		return new Color(rgb[0], rgb[1], rgb[2], (ahsl >>> 16 & 255) / 255f);
	}

	private static JPanel gradientSlider(javax.swing.JSlider slider, String name,
		java.util.function.Function<Float, Color> colorAt) {
		slider.setOpaque(false);
		slider.setPreferredSize(new Dimension(100, 19));
		slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
			@Override protected Dimension getThumbSize() { return new Dimension(8, 16); }

			@Override public void paintTrack(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int steps = 32;
				float[] positions = new float[steps + 1];
				Color[] colors = new Color[steps + 1];
				for (int i = 0; i <= steps; i++) {
					positions[i] = i / (float) steps;
					colors[i] = colorAt.apply(positions[i]);
				}
				if (trackRect.width > 0) {
					g.setPaint(new java.awt.LinearGradientPaint(trackRect.x, 0, trackRect.x + trackRect.width, 0, positions, colors));
					g.fillRoundRect(trackRect.x, trackRect.y + (trackRect.height - 10) / 2, trackRect.width, 10, 10, 10);
				}
				g.dispose();
			}

			@Override public void paintThumb(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(0xF2F2F2));
				g.fillRoundRect(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height, 5, 5);
				g.dispose();
			}
		});
		var valueLabel = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(11f)).build();
		valueLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		valueLabel.setPreferredSize(new Dimension(30, 18));
		Runnable updateLabel = () -> {
			int value = slider.getValue();
			if (value == -1 && Boolean.TRUE.equals(slider.getClientProperty("unsetAllowed")))
				valueLabel.setText("—");
			else
				valueLabel.setText(slider.getMinimum() < 0 && !Boolean.TRUE.equals(slider.getClientProperty("unsetAllowed"))
					&& value > 0 ? "+" + value : Integer.toString(value));
		};
		updateLabel.run();
		slider.addChangeListener(e -> updateLabel.run());
		slider.addPropertyChangeListener("model", e -> updateLabel.run());
		slider.getAccessibleContext().setAccessibleName(name);
		var channel = new JPanel(new BorderLayout(5, 0));
		channel.setOpaque(false);
		channel.add(infoLabel(name.substring(0, 1)), BorderLayout.WEST);
		channel.add(slider);
		channel.add(valueLabel, BorderLayout.EAST);
		return channel;
	}

	private SwatchDropdown buildModelColorPalette() {
		List<ModelColorPalette.Entry> entries = modelColors;
		List<SwatchDropdown.Swatch> swatches = new ArrayList<>();
		var rules = override == null || override.colorOverrides == null ? new ModelOverride[0] : override.colorOverrides;
		var unavailable = pendingColorRule == null
			? java.util.Arrays.stream(rules).map(rule -> ModelOverrideFields.colorPredicate(rule, ruleColors(rule)))
				.collect(java.util.stream.Collectors.toList())
			: List.of(ModelOverrideFields.colorPredicate(pendingColorRule, ruleColors(pendingColorRule)));
		for (ModelColorPalette.Entry entry : entries)
			swatches.add(new SwatchDropdown.Swatch(entry.color, entry.hue, entry.saturation, entry.lightness, entry.faceCount, unavailable.stream().noneMatch(test -> test.test(entry.ahsl))));
		List<SwatchDropdown.Swatch> byHue = new ArrayList<>(swatches);
		byHue.sort(Comparator.comparingInt((SwatchDropdown.Swatch s) -> s.hue).thenComparingInt(s -> s.lightness));
		List<SwatchDropdown.Swatch> byUsage = new ArrayList<>(swatches);
		byUsage.sort((a, b) -> Integer.compare(b.faceCount, a.faceCount));
		return SwatchDropdown.builder(new SwatchDropdown.Provider() {
			@Override public List<SwatchDropdown.Swatch> allColors() { return byHue; }
			@Override public List<SwatchDropdown.Swatch> mostUsedColors() { return byUsage; }
		}).onHover(swatch -> {
			hoveredColor = swatch == null ? null : swatch.hue << 10 | swatch.saturation << 7 | swatch.lightness;
			updateColorHighlight();
		}).onSelect(swatch -> colorPicked(swatch.hue << 10 | swatch.saturation << 7 | swatch.lightness)).build();
	}
}
