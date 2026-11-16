package rs117.hd.dev.editor;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import net.miginfocom.swing.MigLayout;
import rs117.hd.utils.ExpressionParser;

final class ConditionBuilder extends JPanel {
	@lombok.RequiredArgsConstructor
	static final class Gameval {
		final String name;
		final int id, min, max, scene;
		String range() { return min + "–" + max; }
	}
	@lombok.RequiredArgsConstructor
	private static final class Clause {
		final String name;
		final Comparison comparison;
		final int value;
		@Override public String toString() { return name + " " + comparison.symbol + " " + value; }
	}
	private enum Comparison {
		EQ("==", "equals"), NE("!=", "is not"), LT("<", "less than"),
		LE("<=", "at most"), GT(">", "more than"), GE(">=", "at least");

		final String symbol, label;
		Comparison(String symbol, String label) { this.symbol = symbol; this.label = label; }

		static Comparison of(String symbol) {
			return Arrays.stream(values()).filter(it -> it.symbol.equals(symbol)).findFirst().orElseThrow();
		}
	}

	private static final int GAMEVAL_RESULT_LIMIT = 120;
	private static final Pattern CLAUSE = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*(==|!=|<=|>=|<|>)\\s*(-?\\d+)");
	private final String field, kind;
	private final Map<String, Gameval> gamevals;
	private final Consumer<String> save;
	private final Runnable onTest;
	private final Map<String, Integer> overrides = new HashMap<>();
	private final JPanel body = column(7);
	private final JLabel status = EditorText.builder("").font(WorkspaceStyle.UI.deriveFont(11f)).build();
	private final JLabel output = EditorText.builder("").font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(10f))
		.color(new Color(0xCFE0FF)).border(BorderFactory.createEmptyBorder(5, 8, 5, 8)).build();
	private final JPanel tester = column(5);
	private final JLabel result = EditorText.builder("").font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(11f))
		.border(BorderFactory.createEmptyBorder(3, 7, 3, 7)).build();
	private final Rounded resultBadge = new Rounded(new BorderLayout(), new Color(0x24331F), null);
	private String expression, expressionDraft;
	private List<List<Clause>> groups;
	private ExpressionParser.Expression parsed;
	private boolean expressionMode, testing;
	private String error;
	private Set<String> testNames = Set.of();

	ConditionBuilder(String field, String expression, Map<String, Gameval> gamevals,
		Consumer<String> save, Runnable onTest) {
		super(new BorderLayout());
		this.field = field;
		this.kind = field.startsWith("varbit") ? "varbit" : "varp";
		this.expression = expression;
		expressionDraft = expression;
		this.gamevals = gamevals;
		this.save = save;
		this.onTest = onTest;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 9));
		resultBadge.add(result);
		tester.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x303030)),
			BorderFactory.createEmptyBorder(5, 0, 0, 0)));
		HdContextMenu.install(output, point -> new HdContextMenu("#", WorkspaceStyle.DIM, "Condition")
			.item("Copy expression", "copy", "", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new java.awt.datatransfer.StringSelection(output.getText()), null))
			.show(output, point == null ? 0 : point.x, point == null ? output.getHeight() : point.y));
		read(expression);
		add(body);
		rebuild();
	}

	private void read(String text) {
		error = null;
		var previous = parsed;
		parsed = null;
		if (!text.isBlank()) {
			try {
				parsed = ExpressionParser.asExpression(ExpressionParser.parseExpression(text));
			} catch (IllegalArgumentException e) {
				parsed = previous;
				error = "Can't read expression · check the name, operator and value";
			}
		}
		groups = error == null ? parseGroups(text) : null;
	}

	private static List<List<Clause>> parseGroups(String text) {
		var result = new ArrayList<List<Clause>>();
		if (text.isBlank())
			return result;
		for (String group : text.split("\\|\\|", -1)) {
			var clauses = new ArrayList<Clause>();
			for (String part : group.split("&&", -1)) {
				var matcher = CLAUSE.matcher(part.strip());
				if (!matcher.matches())
					return null;
				try {
					clauses.add(new Clause(matcher.group(1), Comparison.of(matcher.group(2)), Integer.parseInt(matcher.group(3))));
				} catch (NumberFormatException e) {
					return null;
				}
			}
			result.add(clauses);
		}
		return result;
	}

	private void rebuild() {
		body.removeAll();
		var header = new JPanel(new BorderLayout(5, 0));
		header.setOpaque(false);
		var modes = EditorButtonGroup.builder(List.of("Builder", "Expression")).track()
			.selected(expressionMode ? "Expression" : "Builder").onChange(mode -> {
				boolean next = mode.equals("Expression");
				if (next != expressionMode) {
					expressionMode = next;
					rebuild();
				}
			}).build();
		for (String mode : List.of("Builder", "Expression")) {
			var button = modes.button(mode);
			button.setFont(WorkspaceStyle.UI.deriveFont(11f));
			button.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			button.setPreferredSize(new Dimension(button.getPreferredSize().width, 18));
		}
		header.add(modes, BorderLayout.WEST);
		header.add(EditorText.builder(kind + " gamevals").font(WorkspaceStyle.MONO.deriveFont(10f))
			.color(WorkspaceStyle.DIM).build(), BorderLayout.EAST);
		body.add(header, "wmin 0");
		if (expressionMode) {
			body.add(expressionInput(), "wmin 0");
		} else if (groups == null) {
			var custom = new Rounded(new MigLayout("insets 6, gap 0 4, wrap 1, fillx", "[0:pref,grow,fill]"),
				WorkspaceStyle.INPUT, new Color(0x333333));
			custom.add(EditorText.builder("Custom expression").font(WorkspaceStyle.SEMIBOLD.deriveFont(11f)).build());
			var input = expressionInput();
			input.addActionListener(e -> {
				if (groups != null)
					rebuild();
			});
			custom.add(input, "wmin 0");
			body.add(custom, "wmin 0");
		} else {
			for (int i = 0; i < groups.size(); i++) {
				if (i > 0)
					body.add(EditorText.builder("OR").font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(10f))
						.color(new Color(0xE8A33D)).alignment(SwingConstants.CENTER).build());
				body.add(group(groups.get(i)), "wmin 0");
			}
			body.add(addButton(groups.isEmpty() ? "Add condition" : "Or group", () -> {
				String preferred = kind.equals("varbit") ? "MYQ6" : "QUEST_POINTS";
				var first = gamevals.getOrDefault(preferred, gamevals.values().stream().findFirst().orElse(null));
				String name = first == null ? "GAMEVAL" : first.name;
				groups.add(new ArrayList<>(List.of(new Clause(name, Comparison.EQ, 0))));
				writeGroups(true);
			}), "growx 0, alignx left");
		}
		body.add(status, "wmin 0");
		var outputBox = new Rounded(new BorderLayout(), WorkspaceStyle.GAP, new Color(0x303030));
		outputBox.add(output);
		body.add(outputBox, "wmin 0");
		body.add(tester, "wmin 0");
		testNames = Set.of();
		refreshStatus();
		revalidate();
		repaint();
	}

	private JTextField expressionInput() {
		var input = EditorText.propertyField(expressionDraft);
		input.setBackground(WorkspaceStyle.GAP);
		var normalBorder = input.getBorder();
		var invalidBorder = BorderFactory.createLineBorder(new Color(0xA04848));
		input.setBorder(error == null ? normalBorder : invalidBorder);
		EditorSearch.onChange(input, value -> {
			expressionDraft = value;
			read(value);
			input.setBorder(error == null ? normalBorder : invalidBorder);
			if (error == null)
				commit(value);
			refreshStatus();
			onTest.run();
		});
		return input;
	}

	private JPanel group(List<Clause> clauses) {
		var panel = new Rounded(new MigLayout("insets 6, gap 0 4, wrap 1, fillx", "[0:pref,grow,fill]"),
			WorkspaceStyle.INPUT, new Color(0x333333));
		for (int index = 0; index < clauses.size(); index++) {
			int row = index;
			var clause = clauses.get(row);
			var variable = gamevals.get(clause.name);
			panel.add(EditorText.builder(index == 0 ? "where" : "and").font(WorkspaceStyle.MONO.deriveFont(10f))
				.color(WorkspaceStyle.DIM).build());
			var name = EditorText.builder(clause.name).font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(11f))
				.color(variable == null ? new Color(0xE06060) : WorkspaceStyle.TEXT).build();
			var id = variable == null ? null : EditorText.builder("#" + variable.id)
				.font(WorkspaceStyle.MONO.deriveFont(9f)).color(WorkspaceStyle.DIM).build();
			var gameval = trigger(clause.name, name, id);
			gameval.addActionListener(e -> chooseGameval(gameval, pick -> {
				var current = clauses.get(row);
				clauses.set(row, new Clause(pick.name, current.comparison, current.value));
				writeGroups(true);
			}));
			panel.add(gameval);

			var controls = new JPanel(new MigLayout("insets 0, gap 4 0, fillx", "[0:pref,grow,fill][64!][20!]"));
			controls.setOpaque(false);
			var comparison = trigger(comparisonText(clause.comparison), comparisonContent(clause.comparison, false), null);
			comparison.addActionListener(e -> chooseComparison(comparison, clauses.get(row), operator -> {
				var current = clauses.get(row);
				clauses.set(row, new Clause(current.name, operator, current.value));
				writeGroups(true);
			}));
			controls.add(comparison);
			var value = EditorText.propertyField(Integer.toString(clause.value));
			value.setBackground(WorkspaceStyle.GAP);
			value.setHorizontalAlignment(JTextField.RIGHT);
			value.setPreferredSize(new Dimension(64, 24));
			value.setToolTipText(variable == null ? "Integer value" : variable.range());
			var border = value.getBorder();
			EditorSearch.onChange(value, text -> {
				try {
					int number = Integer.parseInt(text.strip());
					var current = clauses.get(row);
					clauses.set(row, new Clause(current.name, current.comparison, number));
					value.setBorder(border);
					writeGroups(false);
				} catch (NumberFormatException e) {
					value.setBorder(BorderFactory.createLineBorder(new Color(0xA04848)));
				}
			});
			controls.add(value);
			controls.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("close", 8)
				.tooltip("Remove condition").size(20, 24).onClick(() -> {
					clauses.remove(row);
					if (clauses.isEmpty())
						groups.remove(clauses);
					writeGroups(true);
				}).build());
			panel.add(controls);
		}
		var actions = new JPanel(new BorderLayout(4, 0));
		actions.setOpaque(false);
		actions.add(addButton("and", () -> {
			var first = clauses.get(0);
			var variable = gamevals.get(first.name);
			clauses.add(new Clause(first.name, Comparison.LT, variable == null ? 0 : variable.max));
			writeGroups(true);
		}), BorderLayout.WEST);
		if (groups.size() > 1)
			actions.add(EditorButton.builder().text("Remove group").onClick(() -> {
				groups.remove(clauses);
				writeGroups(true);
			}).build(), BorderLayout.EAST);
		panel.add(actions);
		return panel;
	}

	private void chooseGameval(JButton trigger, Consumer<Gameval> select) {
		var items = new DefaultListModel<Gameval>();
		var list = new JList<>(items);
		list.setFixedCellHeight(27);
		list.setCellRenderer((source, item, index, selected, focused) -> {
			var label = EditorText.builder(item.name + "    #" + item.id + " · " + item.range())
				.font(WorkspaceStyle.MONO.deriveFont(10f)).build();
			label.setOpaque(true);
			label.setBackground(selected ? new Color(0x334D73) : WorkspaceStyle.BAR);
			return label;
		});
		var results = new JPanel(new CardLayout());
		var cards = (CardLayout) results.getLayout();
		var hint = EditorText.builder("Type a name or ID to search").color(WorkspaceStyle.DIM)
			.border(BorderFactory.createEmptyBorder(8, 10, 8, 10)).build();
		var empty = new JPanel(new BorderLayout());
		empty.setBackground(WorkspaceStyle.BAR);
		empty.add(hint, BorderLayout.NORTH);
		results.add(empty, "empty");
		results.add(new EditorScrollPane(list), "matches");
		Consumer<String> filter = query -> {
			String term = query.strip().toLowerCase(Locale.ROOT);
			items.clear();
			findGamevals(gamevals.values(), term).forEach(items::addElement);
			hint.setText(term.isEmpty() ? "No " + kind + "s available" : "No matching " + kind + "s");
			cards.show(results, items.isEmpty() ? "empty" : "matches");
		};
		var search = EditorSearch.builder("Search " + kind + "s").onChange(filter).build();
		filter.accept("");
		var panel = new JPanel(new BorderLayout());
		panel.add(search, BorderLayout.NORTH);
		panel.add(results);
		panel.setPreferredSize(new Dimension(300, 320));
		var popup = PopupLayer.show(trigger, panel, 300, 340, 260);
		list.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override public void mouseClicked(java.awt.event.MouseEvent e) {
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint()))
					PopupLayer.select(popup, () -> select.accept(items.get(index)));
			}
		});
		search.field().addActionListener(e -> {
			if (!items.isEmpty())
				PopupLayer.select(popup, () -> select.accept(items.get(0)));
		});
		SwingUtilities.invokeLater(() -> search.field().requestFocusInWindow());
	}

	private static List<Gameval> findGamevals(Collection<Gameval> gamevals, String term) {
		if (term.isEmpty())
			return gamevals.stream().limit(GAMEVAL_RESULT_LIMIT).collect(java.util.stream.Collectors.toList());
		boolean matchId = term.chars().allMatch(Character::isDigit);
		return gamevals.stream()
			.filter(value -> value.name.toLowerCase(Locale.ROOT).contains(term)
				|| matchId && Integer.toString(value.id).contains(term))
			.limit(GAMEVAL_RESULT_LIMIT)
			.collect(java.util.stream.Collectors.toList());
	}

	private static JPanel column(int gap) {
		var panel = new JPanel(new MigLayout("insets 0, wrap 1, fillx, hidemode 3, gap 0 " + gap, "[0:pref,grow,fill]"));
		panel.setOpaque(false);
		return panel;
	}

	private static JButton addButton(String text, Runnable action) {
		var button = DashedAddButton.create(text, action);
		button.setFont(WorkspaceStyle.UI.deriveFont(10f));
		button.setPreferredSize(new Dimension(button.getPreferredSize().width, 21));
		return button;
	}

	private static JButton trigger(String label, JComponent content, JComponent detail) {
		var button = EditorButton.builder().style(WorkspaceStyle.Button.FIELD_TRIGGER).tooltip(label).build();
		button.setLayout(new BorderLayout(5, 0));
		button.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		button.add(content);
		var trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		trailing.setOpaque(false);
		if (detail != null)
			trailing.add(detail);
		trailing.add(EditorText.builder("").icon(WorkspaceStyle.icon("chevron-down", 8)).build());
		button.add(EditorPanels.centered(trailing), BorderLayout.EAST);
		return button;
	}

	private static JComponent comparisonContent(Comparison comparison, boolean selected) {
		var content = new JPanel(new BorderLayout(6, 0));
		content.setOpaque(false);
		var chip = new Rounded(new BorderLayout(), selected ? WorkspaceStyle.ACCENT : new Color(0x2A3B55), null);
		var symbol = EditorText.builder(comparison.symbol).font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(10f))
			.color(new Color(0xCFE0FF)).alignment(SwingConstants.CENTER).build();
		chip.setPreferredSize(new Dimension(27, 15));
		chip.add(symbol);
		content.add(EditorPanels.centered(chip), BorderLayout.WEST);
		content.add(EditorText.builder(comparison.label).font(WorkspaceStyle.UI.deriveFont(11f)).build());
		return content;
	}

	@Override public Dimension getMinimumSize() {
		return new Dimension(0, super.getMinimumSize().height);
	}

	@Override public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override protected void paintComponent(Graphics graphics) {
		Rounded.paintCard(graphics, getWidth(), getHeight(), new Color(0x1A1A1A), new Color(0x333333));
	}

	private static final class Rounded extends JPanel {
		private Color fill;
		private final Color outline;

		Rounded(LayoutManager layout, Color fill, Color outline) {
			super(layout);
			setOpaque(false);
			this.fill = fill;
			this.outline = outline;
		}

		void setFill(Color color) { fill = color; repaint(); }

		@Override protected void paintComponent(Graphics graphics) {
			paintCard(graphics, getWidth(), getHeight(), fill, outline);
		}

		static void paintCard(Graphics graphics, int width, int height, Color fill, Color outline) {
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(fill);
			g.fillRoundRect(0, 0, width, height, 8, 8);
			if (outline != null) {
				g.setColor(outline);
				g.drawRoundRect(0, 0, width - 1, height - 1, 8, 8);
			}
			g.dispose();
		}
	}
	private static String comparisonText(Comparison comparison) {
		return comparison.symbol + "    " + comparison.label;
	}

	private static void chooseComparison(JButton trigger, Clause clause, Consumer<Comparison> select) {
		var content = new Rounded(new MigLayout("insets 4, wrap 1, fillx, gap 0 2", "[grow,fill]"),
			WorkspaceStyle.BAR, WorkspaceStyle.BORDER);
		var popup = new PopupLayer.Handle[1];
		for (var comparison : Comparison.values()) {
			boolean selected = comparison == clause.comparison;
			var button = EditorButton.builder().style(WorkspaceStyle.Button.TAB).tooltip(comparisonText(comparison))
				.onClick(() -> PopupLayer.select(popup[0], () -> select.accept(comparison))).build();
			button.setLayout(new BorderLayout(8, 0));
			button.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));
			button.add(comparisonContent(comparison, selected));
			var example = EditorText.builder("x " + comparison.symbol + " " + clause.value)
				.font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();
			button.add(example, BorderLayout.EAST);
			var row = new Rounded(new BorderLayout(), selected ? new Color(0x334D73) : WorkspaceStyle.BAR, null);
			row.add(button);
			content.add(row, "h 25!");
		}
		popup[0] = PopupLayer.show(trigger, content, 220, 200, 200);
	}

	private void writeGroups(boolean rebuild) {
		String text = groups.stream().map(group -> group.stream().map(Clause::toString).collect(java.util.stream.Collectors.joining(" && ")))
			.collect(java.util.stream.Collectors.joining(" || "));
		// Keep row listeners attached to their original group during inline value changes.
		var rows = groups;
		read(text);
		groups = rows;
		commit(text);
		if (rebuild)
			rebuild();
		else
			refreshStatus();
		onTest.run();
	}

	private void commit(String text) {
		expression = text;
		expressionDraft = text;
		save.accept(text);
	}

	private void refreshStatus() {
		var names = parsed == null ? Set.<String>of() : new TreeSet<>(parsed.variables);
		var unknown = names.stream().filter(name -> !gamevals.containsKey(name)).findFirst();
		Color indicator = error != null ? new Color(0xE06060) : unknown.isPresent() ? new Color(0xE8A33D)
			: parsed == null ? WorkspaceStyle.DIM : new Color(0x6CB348);
		status.setForeground(error != null || unknown.isPresent() ? indicator : WorkspaceStyle.DIM);
		int count = groups == null ? 0 : groups.stream().mapToInt(List::size).sum();
		String valid = groups == null ? "Valid expression" : count + (count == 1 ? " condition" : " conditions");
		if (groups != null && groups.size() > 1)
			valid += " · " + groups.size() + " OR groups";
		String message = error != null ? error : unknown.isPresent() ? "Unknown " + kind + " gameval · " + unknown.get()
			: parsed == null ? "No condition · always eligible" : valid + " · " + names.size() + " " + kind + (names.size() == 1 ? "" : "s");
		status.setText(message);
		status.setToolTipText(message);
		status.setIcon(EnvironmentsScreen.statusDot(indicator));
		output.setText(expression.isBlank() ? field + " not set · always" : "\"" + field + "\": " + EnvironmentDocument.JSON.toJson(expression));
		output.setToolTipText(output.getText());
		if (!names.equals(testNames) || !tester.isVisible()) {
			testNames = names;
			overrides.keySet().retainAll(names);
			if (names.isEmpty() && error == null)
				testing = false;
			rebuildTester();
		}
		updateResult();
	}

	private void rebuildTester() {
		tester.removeAll();
		tester.setVisible(!testNames.isEmpty());
		if (testNames.isEmpty())
			return;
		var title = new JPanel(new BorderLayout());
		title.setOpaque(false);
		title.add(EditorText.builder("Test in scene").font(WorkspaceStyle.MEDIUM.deriveFont(11f)).build());
		title.add(EditorPanels.centered(ToggleSwitch.builder().selected(testing).onChange(on -> {
			testing = on;
			if (!on)
				overrides.clear();
			rebuildTester();
			onTest.run();
		}).build()), BorderLayout.EAST);
		tester.add(title, "h 20!");
		if (testing) {
			for (String name : testNames) {
				var variable = gamevals.get(name);
				if (variable != null)
					tester.add(testControl(variable));
			}
			var footer = new JPanel(new MigLayout("insets 0, gap 4 0, fillx", "[0:pref,shrink,fill][grow][pref!]"));
			footer.setOpaque(false);
			footer.add(resultBadge, "wmin 0");
			var useScene = EditorButton.builder().style(WorkspaceStyle.Button.TAB).text("Use scene values").onClick(() -> {
				overrides.clear();
				rebuildTester();
				onTest.run();
			}).build();
			useScene.setFont(WorkspaceStyle.UI.deriveFont(11f));
			useScene.setForeground(new Color(0x7EA9EB));
			useScene.setBorder(BorderFactory.createEmptyBorder());
			footer.add(useScene, "skip 1");
			tester.add(footer);
			updateResult();
		}
		tester.revalidate();
		tester.repaint();
	}

	private JPanel testControl(Gameval variable) {
		int current = overrides.getOrDefault(variable.name, variable.scene);
		var slider = new JSlider(variable.min, variable.max, current);
		slider.setOpaque(false);
		slider.setUI(new SceneSliderUI(slider));
		slider.setPreferredSize(new Dimension(0, 16));
		slider.getAccessibleContext().setAccessibleName(variable.name + " test value");
		var input = EditorText.propertyField(Integer.toString(current));
		input.setHorizontalAlignment(JTextField.RIGHT);
		input.setBackground(WorkspaceStyle.GAP);
		input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER),
			BorderFactory.createEmptyBorder(2, 4, 2, 4)));
		input.getAccessibleContext().setAccessibleName(variable.name + " test value");
		var name = EditorText.builder(variable.name).font(WorkspaceStyle.MONO_SEMIBOLD.deriveFont(11f))
			.tooltip(variable.name).build();
		var valueLabel = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f)).color(WorkspaceStyle.DIM).build();
		IntConsumer show = value -> {
			valueLabel.setText("scene " + variable.scene + (value == variable.scene ? "" : " · testing " + value));
			valueLabel.setForeground(value == variable.scene ? WorkspaceStyle.DIM : new Color(0xE8A33D));
		};
		IntConsumer apply = value -> {
			overrides.put(variable.name, value);
			show.accept(value);
			updateResult();
			onTest.run();
		};
		show.accept(current);
		var syncing = new boolean[1];
		var normalBorder = input.getBorder();
		EditorSearch.onChange(input, text -> {
			if (syncing[0])
				return;
			try {
				int value = Integer.parseInt(text.strip());
				if (value < variable.min || value > variable.max)
					throw new NumberFormatException();
				input.setBorder(normalBorder);
				syncing[0] = true;
				slider.setValue(value);
				syncing[0] = false;
				apply.accept(value);
			} catch (NumberFormatException e) {
				input.setBorder(BorderFactory.createLineBorder(new Color(0xA04848)));
			}
		});
		slider.addChangeListener(e -> {
			if (syncing[0])
				return;
			int value = slider.getValue();
			syncing[0] = true;
			input.setText(Integer.toString(value));
			input.setBorder(normalBorder);
			syncing[0] = false;
			apply.accept(value);
		});
		var row = column(3);
		var header = new JPanel(new MigLayout("insets 0, gap 5 0, fillx", "[0:pref,shrink][0:pref,grow,fill][52!]"));
		header.setOpaque(false);
		header.add(name);
		header.add(valueLabel);
		header.add(input);
		row.add(header, "h 22!");
		row.add(slider);
		return row;
	}

	private static final class SceneSliderUI extends BasicSliderUI {
		SceneSliderUI(JSlider slider) { super(slider); }

		@Override protected Dimension getThumbSize() { return new Dimension(14, 14); }

		@Override public void paintTrack(Graphics graphics) {
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int y = trackRect.y + (trackRect.height - 7) / 2;
			g.setColor(new Color(0xDDDDDD));
			g.fillRoundRect(trackRect.x, y, trackRect.width, 7, 7, 7);
			g.setColor(WorkspaceStyle.ACCENT);
			int progress = Math.max(0, thumbRect.x + thumbRect.width / 2 - trackRect.x);
			g.fillRoundRect(trackRect.x, y, Math.min(trackRect.width, progress), 7, 7, 7);
			g.dispose();
		}

		@Override public void paintThumb(Graphics graphics) {
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(WorkspaceStyle.ACCENT);
			g.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
			g.dispose();
		}
	}

	private void updateResult() {
		if (testing) {
			boolean matches = matches();
			result.setText(matches ? "TRUE · environment active" : "FALSE · not active");
			result.setToolTipText(result.getText());
			result.setForeground(new Color(matches ? 0x9FCF86 : 0xE8A0A0));
			resultBadge.setFill(new Color(matches ? 0x253822 : 0x3B2727));
		}
	}
	Map<Integer, Integer> testValues() {
		if (!testing)
			return Map.of();
		var values = new HashMap<Integer, Integer>();
		overrides.forEach((name, value) -> {
			var variable = gamevals.get(name);
			if (variable != null)
				values.put(variable.id, value);
		});
		return Map.copyOf(values);
	}

	boolean isTesting() { return testing; }

	boolean matches() {
		if (parsed == null)
			return expression.isBlank();
		try {
			return parsed.toPredicate().test(name -> {
				var variable = gamevals.get(name);
				return variable == null ? null : overrides.getOrDefault(name, variable.scene);
			});
		} catch (RuntimeException e) {
			return false;
		}
	}

	void endTest() {
		if (!testing)
			return;
		testing = false;
		overrides.clear();
		rebuildTester();
	}
}
