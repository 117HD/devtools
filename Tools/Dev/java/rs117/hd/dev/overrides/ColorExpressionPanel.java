package rs117.hd.dev.overrides;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.font.TextAttribute;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rs117.hd.dev.editor.*;

final class ColorExpressionPanel extends JPanel {
	static final Font CODE = WorkspaceStyle.MONO.deriveFont(Map.of(TextAttribute.LIGATURES, 0));
	private static final Font OPERATOR = WorkspaceStyle.MONO_SEMIBOLD.deriveFont(Map.of(TextAttribute.LIGATURES, 0));
	private static final List<String> OPERATORS = List.of("==", "!=", "<", "<=", ">", ">=");
	private static final List<String> WORDS = List.of("equals", "is not", "less than", "at most", "more than", "at least");
	private final JPanel fields = builderStack();
	private final JPanel pickRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
	private final JLabel status = EditorText.builder("").font(WorkspaceStyle.UI.deriveFont(11f)).build();
	private final JLabel output = EditorText.builder("").font(CODE).color(new Color(0xCFE0FF)).build();
	private final JTextField input = new JTextField();
	private final EditorButtonGroup<String> tabs;
	private final JToggleButton builder;
	private final Consumer<String> change;
	private final Function<String, String> evaluate;
	private final Consumer<Boolean> viewChanged;
	private String text;
	private boolean syncing;

	ColorExpressionPanel(String text, boolean expressionView, Consumer<String> change,
		Function<String, String> evaluate, Consumer<Boolean> viewChanged, Runnable pick, Consumer<String> toast) {
		this.text = text;
		this.change = change;
		this.evaluate = evaluate;
		this.viewChanged = viewChanged;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		setOpaque(false);
		var bar = new JPanel(new BorderLayout(4, 0));
		bar.setOpaque(false);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		bar.add(EditorText.builder("Match faces where").font(WorkspaceStyle.UI.deriveFont(11f)).color(WorkspaceStyle.DIM).build());
		tabs = EditorButtonGroup.builder(List.of("Builder", "Expression")).onChange(mode -> {
			if ("Builder".equals(mode))
				showBuilder();
			else
				showExpression();
		}).build();
		builder = tabs.button("Builder");
		bar.add(tabs, BorderLayout.EAST);
		add(bar);
		add(Box.createVerticalStrut(8));
		add(fields);
		input.setFont(CODE.deriveFont(12f));
		input.setBackground(new Color(0x141414));
		input.setForeground(WorkspaceStyle.TEXT);
		input.setCaretColor(WorkspaceStyle.TEXT);
		input.setToolTipText("h == 63 && s > 0");
		input.setText(text);
		input.setAlignmentX(LEFT_ALIGNMENT);
		input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		input.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { typed(); }
			public void removeUpdate(DocumentEvent e) { typed(); }
			public void changedUpdate(DocumentEvent e) { typed(); }
		});
		var pickButton = EditorButton.builder().text("Pick on model").icon("eyedropper", 12).onClick(pick).build();
		pickButton.setAlignmentX(LEFT_ALIGNMENT);
		pickRow.setOpaque(false);
		pickRow.setAlignmentX(LEFT_ALIGNMENT);
		pickRow.add(pickButton);
		pickRow.add(EditorText.builder("adds an exact h/s/l group").font(WorkspaceStyle.UI.deriveFont(10f))
			.color(WorkspaceStyle.DIM).build());
		add(Box.createVerticalStrut(6));
		add(pickRow);
		add(Box.createVerticalStrut(7));
		add(status);
		add(Box.createVerticalStrut(7));
		var result = new JPanel(new BorderLayout(5, 0));
		result.setAlignmentX(LEFT_ALIGNMENT);
		result.setBackground(new Color(0x141414));
		result.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x333333), 1, true),
			BorderFactory.createEmptyBorder(4, 8, 4, 5)));
		result.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		result.add(output);
		result.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("copy", 12).size(22, 22)
			.tooltip("Copy colors expression").onClick(() -> {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(output.getText()), null);
				toast.accept("Copied colors expression");
			}).build(), BorderLayout.EAST);
		add(result);
		add(Box.createVerticalStrut(6));
		add(EditorText.builder("<html>h hue 0–63 · s saturation 0–7 · l lightness 0–127<br>&amp;&amp; = and · || = or</html>")
			.font(CODE.deriveFont(10f)).color(WorkspaceStyle.DIM).build());
		if (expressionView || ColorExpression.groups(text) == null)
			showExpression();
		else
			showBuilder();
		updateStatus();
	}

	private void typed() {
		if (syncing)
			return;
		text = input.getText();
		change.accept(text);
		updateStatus();
	}

	private void updateStatus() {
		boolean valid = ColorExpression.valid(text);
		builder.setEnabled(ColorExpression.groups(text) != null && valid);
		builder.setToolTipText(builder.isEnabled() ? null : valid ? "This expression uses syntax outside the builder" : "Fix the expression to use the builder");
		String message = valid ? evaluate.apply(text) : text.isBlank() ? "Empty · add at least one condition"
			: "Can't read this expression · check the comparisons";
		if (!valid)
			evaluate.apply(text);
		status.setText(message);
		boolean matches = valid && message.startsWith("Matches");
		status.setForeground(new Color(!valid ? 0xE8A0A0 : matches ? 0x9A9A9A : 0xE8C27A));
		status.setIcon(WorkspaceStyle.badge("", new Color(!valid ? 0xE06060 : matches ? 0x6CB348 : 0xE8A33D)));
		status.setToolTipText(message);
		output.setText("\"colors\": " + new com.google.gson.JsonPrimitive(text));
		output.setToolTipText(output.getText());
		input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(valid ? WorkspaceStyle.BORDER : new Color(0xA04848)),
			BorderFactory.createEmptyBorder(4, 7, 4, 7)));
	}

	private void showExpression() {
		viewChanged.accept(true);
		fields.removeAll();
		removeOrButton();
		fields.add(input);
		tabs.setSelected("Expression");
		fields.revalidate();
		fields.repaint();
	}

	private void showBuilder() {
		var groups = ColorExpression.groups(text);
		if (groups == null)
			return;
		viewChanged.accept(false);
		builder.setSelected(true);
		fields.removeAll();
		for (var conditions : groups) {
			if (fields.getComponentCount() > 0)
				fields.add(EditorText.builder("OR").font(WorkspaceStyle.SEMIBOLD.deriveFont(10f)).color(WorkspaceStyle.DIM).build());
			var box = builderStack();
			box.setBackground(new Color(0x1F1F1F));
			box.setOpaque(true);
			box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true),
				BorderFactory.createEmptyBorder(6, 7, 6, 7)));
			for (int i = 0; i < conditions.size(); i++)
				box.add(conditionRow(groups, conditions, i), "growx, wmin 0");
			var footer = new JPanel(new BorderLayout());
			footer.setOpaque(false);
			footer.setBorder(BorderFactory.createEmptyBorder(4, 43, 0, 0));
			footer.add(DashedAddButton.create("and", () -> {
				conditions.add(new ColorExpression.Condition("s", ">", 0));
				apply(groups, true);
			}), BorderLayout.WEST);
			if (groups.size() > 1)
				footer.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).text("Remove group").onClick(() -> {
					groups.remove(conditions);
					apply(groups, true);
				}).build(), BorderLayout.EAST);
			box.add(footer);
			fields.add(box, "growx, wmin 0");
		}
		removeOrButton();
		pickRow.add(DashedAddButton.create("Or group", () -> {
			groups.add(new java.util.ArrayList<>(List.of(new ColorExpression.Condition("h", "==", 0))));
			apply(groups, true);
		}), 0);
		fields.revalidate();
		fields.repaint();
	}

	private void removeOrButton() {
		if (pickRow.getComponentCount() == 3)
			pickRow.remove(0);
	}

	private JPanel conditionRow(List<List<ColorExpression.Condition>> groups, List<ColorExpression.Condition> conditions, int row) {
		var controls = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 4, fillx, novisualpadding", "[32!,right][76!,fill][34:88:88,fill][48:60:,grow,fill][22!,fill]", "[22!,center]"));
		controls.setOpaque(false);
		controls.setAlignmentX(LEFT_ALIGNMENT);
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		controls.add(EditorText.builder(row == 0 ? "where" : "and").font(WorkspaceStyle.UI.deriveFont(10f)).build());
		var condition = conditions.get(row);
		var channel = SegmentedControl.builder(List.of("h", "s", "l")).values().label(v -> v.toUpperCase(java.util.Locale.ROOT))
			.marker(v -> new Color("h".equals(v) ? 0xD68957 : "s".equals(v) ? 0x78A477 : 0xB9B9B9))
			.selected(condition.channel).onChange(v -> {
				var current = conditions.get(row);
				conditions.set(row, new ColorExpression.Condition(v, current.operator, current.value));
				apply(groups, true);
			}).build();
		controls.add(channel, "h 22!");
		controls.add(new ColorOperatorDropdown(condition, op -> {
			var current = conditions.get(row);
			conditions.set(row, new ColorExpression.Condition(current.channel, op, current.value));
			apply(groups, true);
		}), "wmin 0");
		var value = NumberField.builder().value((double) condition.value).min((double) Integer.MIN_VALUE)
			.max((double) Integer.MAX_VALUE).onChange(v -> {
				var current = conditions.get(row);
				conditions.set(row, new ColorExpression.Condition(current.channel, current.operator, v.intValue()));
				apply(groups, false);
			}).build();
		value.setToolTipText("Range 0–" + (condition.channel.equals("h") ? 63 : condition.channel.equals("s") ? 7 : 127));
		controls.add(value, "h 22!, wmin 0");
		if (groups.size() > 1 || conditions.size() > 1)
			controls.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("close", 11).size(22, 22)
				.tooltip("Remove condition").onClick(() -> {
					conditions.remove(row);
					if (conditions.isEmpty()) groups.remove(conditions);
					apply(groups, true);
				}).build());
		return controls;
	}

	private void apply(List<List<ColorExpression.Condition>> groups, boolean rebuild) {
		text = ColorExpression.format(groups);
		syncing = true;
		try {
			input.setText(text);
		} finally {
			syncing = false;
		}
		change.accept(text);
		updateStatus();
		if (rebuild)
			showBuilder();
	}

	private static JPanel builderStack() {
		var panel = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gapy 4, fillx, wrap 1", "[0,grow,fill]"));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setOpaque(false);
		return panel;
	}

	private static final class ColorOperatorDropdown extends JButton {
		private PopupLayer.Handle popup;

		ColorOperatorDropdown(ColorExpression.Condition condition, Consumer<String> onChange) {
			WorkspaceStyle.button(this, WorkspaceStyle.Button.TRIGGER);
			setLayout(new BorderLayout(4, 0));
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true),
				BorderFactory.createEmptyBorder(0, 5, 0, 5)));
			setBorderPainted(true);
			add(parts(condition.operator, WORDS.get(OPERATORS.indexOf(condition.operator)), null));
			add(new JLabel(WorkspaceStyle.icon("chevron-down", 8)), BorderLayout.EAST);
			getAccessibleContext().setAccessibleName("Comparison " + condition.operator);
			addActionListener(e -> {
				if (popup != null) {
					popup.close();
					return;
				}
				var menu = EditorPanels.stack();
				menu.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
				var options = new java.util.ArrayList<JButton>();
				for (int i = 0; i < OPERATORS.size(); i++) {
					String op = OPERATORS.get(i);
					var option = EditorButton.builder().style(WorkspaceStyle.Button.SEGMENT)
						.onClick(() -> {
							if (popup != null)
								popup.close();
							onChange.accept(op);
						}).build();
					option.setLayout(new BorderLayout());
					option.add(parts(op, WORDS.get(i), condition.channel + " " + op + " " + condition.value));
					option.getAccessibleContext().setAccessibleName(op + " " + WORDS.get(i));
					option.setSelected(op.equals(condition.operator));
					option.setHorizontalAlignment(LEFT);
					option.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
					option.setAlignmentX(LEFT_ALIGNMENT);
					options.add(option);
					menu.add(option);
				}
				for (int i = 0; i < options.size(); i++) {
					int index = i;
					var option = options.get(i);
					option.registerKeyboardAction(ev -> options.get((index + 1) % options.size()).requestFocusInWindow(),
						KeyStroke.getKeyStroke("DOWN"), WHEN_FOCUSED);
					option.registerKeyboardAction(ev -> options.get((index + options.size() - 1) % options.size()).requestFocusInWindow(),
						KeyStroke.getKeyStroke("UP"), WHEN_FOCUSED);
					option.registerKeyboardAction(ev -> option.doClick(), KeyStroke.getKeyStroke("ENTER"), WHEN_FOCUSED);
				}
				popup = PopupLayer.show(this, menu, 160);
				setSelected(true);
				popup.onClose(() -> { popup = null; setSelected(false); });
				options.get(OPERATORS.indexOf(condition.operator)).requestFocusInWindow();
			});
		}
		private static JPanel parts(String operator, String word, String example) {
			var panel = new JPanel(new BorderLayout(5, 0));
			panel.setOpaque(false);
			var symbol = EditorText.builder(operator).font(OPERATOR).color(new Color(0xCFE0FF)).build();
			symbol.setPreferredSize(new Dimension(16, symbol.getPreferredSize().height));
			panel.add(symbol, BorderLayout.WEST);
			panel.add(EditorText.builder(word).font(WorkspaceStyle.UI.deriveFont(10f)).build());
			if (example != null)
				panel.add(EditorText.builder(example).font(CODE.deriveFont(9f)).color(WorkspaceStyle.DIM).build(), BorderLayout.EAST);
			return panel;
		}

	}
}
