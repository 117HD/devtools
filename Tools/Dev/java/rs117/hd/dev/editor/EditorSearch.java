package rs117.hd.dev.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A search box with the shared icon, placeholder, and editor typography. */
public final class EditorSearch extends JPanel {
	private final JTextField field;

	private EditorSearch(Builder builder) {
		super(new BorderLayout(builder.gap, 0));
		setBackground(builder.background);
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(builder.outline, 1, true),
			BorderFactory.createEmptyBorder(0, builder.left, 0, builder.right)));
		if (builder.height > 0)
			setPreferredSize(new Dimension(0, builder.height));
		field = WorkspaceStyle.search(builder.placeholder);
		field.setOpaque(false);
		field.setForeground(builder.foreground);
		field.setCaretColor(WorkspaceStyle.TEXT);
		field.setFont(WorkspaceStyle.UI.deriveFont(builder.fontSize));
		field.setBorder(BorderFactory.createEmptyBorder(0, builder.textInset, 0, builder.textInset));
		if (builder.fieldHeight > 0)
			field.setPreferredSize(new Dimension(10, builder.fieldHeight));
		field.getAccessibleContext().setAccessibleName(builder.placeholder);
		add(new JLabel(WorkspaceStyle.icon("search", 12)), BorderLayout.WEST);
		add(field);
	}

	public JTextField field() { return field; }

	public static Builder builder(String placeholder) { return new Builder(placeholder); }

	public static void onChange(JTextField field, Consumer<String> listener) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent event) { listener.accept(field.getText()); }
			@Override public void removeUpdate(DocumentEvent event) { listener.accept(field.getText()); }
			@Override public void changedUpdate(DocumentEvent event) { listener.accept(field.getText()); }
		});
	}

	public static final class Builder {
		private final String placeholder;
		private Color background = WorkspaceStyle.INPUT;
		private Color outline = WorkspaceStyle.BORDER;
		private Color foreground = WorkspaceStyle.TEXT;
		private int gap = 6, left = 6, right = 6, height, fieldHeight, textInset;
		private float fontSize = 12f;
		private Consumer<String> listener;

		private Builder(String placeholder) { this.placeholder = placeholder; }

		public Builder background(Color value) { background = value; return this; }
		public Builder outline(Color value) { outline = value; return this; }
		public Builder foreground(Color value) { foreground = value; return this; }
		public Builder gap(int value) { gap = value; return this; }
		public Builder padding(int start, int end) { left = start; right = end; return this; }
		public Builder height(int value) { height = value; return this; }
		public Builder fieldHeight(int value) { fieldHeight = value; return this; }
		public Builder textInset(int value) { textInset = value; return this; }
		public Builder fontSize(float value) { fontSize = value; return this; }
		public Builder onChange(Consumer<String> action) { listener = action; return this; }

		public EditorSearch build() {
			var search = new EditorSearch(this);
			if (listener != null)
				EditorSearch.onChange(search.field, listener);
			return search;
		}
	}
}
