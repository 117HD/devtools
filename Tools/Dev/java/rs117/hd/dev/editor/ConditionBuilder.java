package rs117.hd.dev.editor;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.Pattern;
import javax.swing.*;
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

	private static final Pattern CLAUSE = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*(==|!=|<=|>=|<|>)\\s*(-?\\d+)");
	private final String field, kind;
	private final Map<String, Gameval> gamevals;
	private final Consumer<String> save;
	private final Runnable onTest;
	private final Map<String, Integer> overrides = new HashMap<>();
	private final JPanel body = EditorPanels.stack();
	private final JLabel status = EditorText.builder("").build();
	private final JTextArea output = new JTextArea();
	private final JPanel tester = EditorPanels.stack();
	private final JLabel result = EditorText.builder("").build();
	private String expression;
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
		this.gamevals = gamevals;
		this.save = save;
		this.onTest = onTest;
		setBackground(new Color(0x1A1A1A));
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x333333)),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		output.setEditable(false);
		output.setRows(2);
		output.setLineWrap(true);
		output.setWrapStyleWord(true);
		output.setFont(WorkspaceStyle.MONO.deriveFont(10f));
		output.setBackground(WorkspaceStyle.GAP);
		output.setForeground(new Color(0xCFE0FF));
		read(expression);
		expressionMode = groups == null;
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
		var builder = EditorButton.builder().text("Builder").enabled(groups != null)
			.style(expressionMode ? WorkspaceStyle.Button.TAB : WorkspaceStyle.Button.SECONDARY)
			.onClick(() -> { expressionMode = false; rebuild(); }).build();
		if (groups == null)
			builder.setToolTipText("Use comparisons joined by && or || to enable Builder");
		var modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		modes.setOpaque(false);
		modes.add(builder);
		modes.add(EditorButton.builder().text("Expression")
			.style(expressionMode ? WorkspaceStyle.Button.SECONDARY : WorkspaceStyle.Button.TAB)
			.onClick(() -> { expressionMode = true; rebuild(); }).build());
		header.add(modes);
		header.add(EditorText.builder(kind + " gamevals").font(WorkspaceStyle.MONO.deriveFont(10f))
			.color(WorkspaceStyle.DIM).build(), BorderLayout.EAST);
		body.add(header);
		if (expressionMode) {
			var fieldInput = new JTextField(expression);
			fieldInput.setFont(WorkspaceStyle.MONO.deriveFont(12f));
			var normalBorder = fieldInput.getBorder();
			EditorSearch.onChange(fieldInput, value -> {
				read(value);
				builder.setEnabled(groups != null);
				fieldInput.setBorder(error == null ? normalBorder : BorderFactory.createLineBorder(new Color(0xA04848)));
				if (error == null)
					commit(value);
				refreshStatus();
				onTest.run();
			});
			body.add(fieldInput);
		} else {
			for (int i = 0; i < groups.size(); i++) {
				if (i > 0)
					body.add(EditorText.builder("OR").color(new Color(0xE8A33D)).alignment(SwingConstants.CENTER).build());
				body.add(group(groups.get(i)));
			}
			body.add(DashedAddButton.create(groups.isEmpty() ? "Add condition" : "Or group", () -> {
				String preferred = kind.equals("varbit") ? "MYQ6" : "QUEST_POINTS";
				var first = gamevals.getOrDefault(preferred, gamevals.values().stream().findFirst().orElse(null));
				String name = first == null ? "GAMEVAL" : first.name;
				groups.add(new ArrayList<>(List.of(new Clause(name, Comparison.EQ, 0))));
				writeGroups(true);
			}));
		}
		body.add(status);
		body.add(output);
		body.add(tester);
		testNames = Set.of();
		refreshStatus();
		align(body);
		revalidate();
		repaint();
	}

	private JPanel group(List<Clause> clauses) {
		var panel = EditorPanels.stack();
		panel.setOpaque(true);
		panel.setBackground(new Color(0x1F1F1F));
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x333333)),
			BorderFactory.createEmptyBorder(5, 5, 5, 5)));
		for (int index = 0; index < clauses.size(); index++) {
			int row = index;
			var clause = clauses.get(row);
			var variable = gamevals.get(clause.name);
			panel.add(EditorText.builder(index == 0 ? "where" : "and").color(WorkspaceStyle.DIM).build());
			var gameval = EditorButton.builder().style(WorkspaceStyle.Button.TRIGGER)
				.text(clause.name + (variable == null ? "" : "   #" + variable.id))
				.icon("chevron-down", 10).build();
			gameval.setFont(WorkspaceStyle.MONO.deriveFont(11f));
			if (variable == null)
				gameval.setForeground(new Color(0xE06060));
			gameval.addActionListener(e -> chooseGameval(gameval, pick -> {
				var current = clauses.get(row);
				clauses.set(row, new Clause(pick.name, current.comparison, current.value));
				writeGroups(true);
			}));
			panel.add(gameval);

			var controls = new JPanel(new BorderLayout(4, 0));
			controls.setOpaque(false);
			var comparison = EditorButton.builder().text(comparisonText(clause.comparison))
				.style(WorkspaceStyle.Button.TRIGGER).icon("chevron-down", 10).build();
			comparison.addActionListener(e -> chooseComparison(comparison, clauses.get(row), operator -> {
				var current = clauses.get(row);
				clauses.set(row, new Clause(current.name, operator, current.value));
				writeGroups(true);
			}));
			controls.add(comparison);
			var value = new JTextField(Integer.toString(clause.value));
			value.setFont(WorkspaceStyle.MONO.deriveFont(11f));
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
			var right = new JPanel(new BorderLayout(4, 0));
			right.setOpaque(false);
			right.add(value);
			right.add(EditorButton.builder().icon("close", 10).tooltip("Remove condition").size(22, 22).onClick(() -> {
				clauses.remove(row);
				if (clauses.isEmpty())
					groups.remove(clauses);
				writeGroups(true);
			}).build(), BorderLayout.EAST);
			controls.add(right, BorderLayout.EAST);
			panel.add(controls);
		}
		var actions = new JPanel(new BorderLayout(4, 0));
		actions.setOpaque(false);
		actions.add(DashedAddButton.create("and", () -> {
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
		align(panel);
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
		Consumer<String> filter = query -> {
			String term = query.strip().toLowerCase(Locale.ROOT);
			items.clear();
			gamevals.values().stream().filter(v -> v.name.toLowerCase(Locale.ROOT).contains(term)
				|| Integer.toString(v.id).contains(term)).forEach(items::addElement);
		};
		var search = EditorSearch.builder("Search " + kind + "s").onChange(filter).build();
		filter.accept("");
		var panel = new JPanel(new BorderLayout());
		panel.add(search, BorderLayout.NORTH);
		panel.add(new EditorScrollPane(list));
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

	private static void align(JPanel panel) {
		for (Component child : panel.getComponents())
			((JComponent) child).setAlignmentX(LEFT_ALIGNMENT);
	}

	private static String comparisonText(Comparison comparison) {
		return comparison.symbol + "    " + comparison.label;
	}

	private static void chooseComparison(JButton trigger, Clause clause, Consumer<Comparison> select) {
		var content = EditorPanels.stack();
		var popup = new PopupLayer.Handle[1];
		for (var comparison : Comparison.values()) {
			var button = EditorButton.builder().text(comparisonText(comparison) + "    x " + comparison.symbol + " " + clause.value)
				.style(comparison == clause.comparison ? WorkspaceStyle.Button.PRIMARY : WorkspaceStyle.Button.TAB)
				.onClick(() -> PopupLayer.select(popup[0], () -> select.accept(comparison))).build();
			button.setFont(WorkspaceStyle.MONO.deriveFont(11f));
			button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			content.add(button);
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
		save.accept(text);
	}

	private void refreshStatus() {
		var names = parsed == null ? Set.<String>of() : new TreeSet<>(parsed.variables);
		var unknown = names.stream().filter(name -> !gamevals.containsKey(name)).findFirst();
		status.setForeground(error != null ? new Color(0xE06060) : unknown.isPresent() ? new Color(0xE8A33D)
			: parsed == null ? WorkspaceStyle.DIM : new Color(0x6CB348));
		int count = groups == null ? 0 : groups.stream().mapToInt(List::size).sum();
		String valid = groups == null ? "Valid expression" : count + (count == 1 ? " condition" : " conditions");
		if (groups != null && groups.size() > 1)
			valid += " · " + groups.size() + " OR groups";
		String message = error != null ? error : unknown.isPresent() ? "Unknown " + kind + " gameval · " + unknown.get()
			: parsed == null ? "No condition · always eligible" : valid + " · " + names.size() + " " + kind + (names.size() == 1 ? "" : "s");
		status.setText(message);
		status.setToolTipText(message);
		status.setIcon(EnvironmentsScreen.statusDot(status.getForeground()));
		output.setText(expression.isBlank() ? field + " not set · always" : "\"" + field + "\": " + EnvironmentDocument.JSON.toJson(expression));
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
		tester.add(ToggleList.builder().add("Test in scene", testing, false, on -> {
			testing = on;
			if (!on)
				overrides.clear();
			rebuildTester();
			onTest.run();
		}).build());
		if (!testing)
			return;
		for (String name : testNames) {
			var variable = gamevals.get(name);
			if (variable != null)
				tester.add(testControl(variable));
		}
		var footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.add(result);
		footer.add(EditorButton.builder().text("Use scene values").onClick(() -> {
			overrides.clear();
			rebuildTester();
			onTest.run();
		}).build(), BorderLayout.EAST);
		tester.add(footer);
		align(tester);
		updateResult();
		tester.revalidate();
		tester.repaint();
	}

	private JPanel testControl(Gameval variable) {
		int current = overrides.getOrDefault(variable.name, variable.scene);
		var slider = new JSlider(variable.min, variable.max, current);
		slider.setOpaque(false);
		slider.setForeground(WorkspaceStyle.ACCENT);
		var input = new JSpinner(new SpinnerNumberModel(current, variable.min, variable.max, 1));
		input.setFont(WorkspaceStyle.MONO.deriveFont(11f));
		var label = EditorText.builder("").font(WorkspaceStyle.MONO.deriveFont(10f)).build();
		IntConsumer show = value -> {
			label.setText(variable.name + " · scene " + variable.scene + (value == variable.scene ? "" : " · testing " + value));
			label.setForeground(value == variable.scene ? WorkspaceStyle.DIM : new Color(0xE8A33D));
		};
		show.accept(current);
		input.addChangeListener(e -> {
			int value = (Integer) input.getValue();
			overrides.put(variable.name, value);
			slider.setValue(value);
			show.accept(value);
			updateResult();
			onTest.run();
		});
		slider.addChangeListener(e -> input.setValue(slider.getValue()));
		var row = EditorPanels.stack();
		var header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.add(label);
		header.add(input, BorderLayout.EAST);
		row.add(header);
		row.add(slider);
		align(row);
		return row;
	}

	private void updateResult() {
		if (testing) {
			boolean matches = matches();
			result.setText(matches ? "TRUE · environment active" : "FALSE · not active");
			result.setForeground(new Color(matches ? 0x9FCF86 : 0xE8A0A0));
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
