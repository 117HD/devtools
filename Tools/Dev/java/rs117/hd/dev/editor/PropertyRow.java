package rs117.hd.dev.editor;

import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class PropertyRow extends JPanel {
	public enum Layout { SIMPLE, WIDE }

	private static final int GAP = 12;
	private static final int VERTICAL_PADDING = 4;
	private static final int CONTROL_WIDTH = 210;

	private final JLabel label;
	private final JComponent control;
	private final Layout layout;
	private final JButton reset;

	public PropertyRow(String name, JComponent control, Layout layout) {
		this(name, control, layout, null);
	}

	public PropertyRow(String name, JComponent control, Layout layout, Runnable onReset) {
		super(null);
		this.control = control;
		this.layout = layout;
		reset = onReset == null ? null : resetButton(name, onReset);
		setName("field:" + name);
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		label = EditorText.builder(name).font(WorkspaceStyle.UI.deriveFont(12f)).build();
		label.setToolTipText(name);
		label.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 0, 0, 0));
		if (control instanceof NumberField)
			((NumberField) control).attachScrub(label);
		setChanged(false);
		add(label);
		add(control);
		if (reset != null)
			add(reset);
	}

	static JButton resetButton(String name, Runnable action) {
		return EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("close", 10).size(18, 18)
			.tooltip("Reset " + name).onClick(action).build();
	}

	/** A compact fixed-label row for inline cards and nested forms. */
	public static JPanel compact(String name, JComponent control) {
		var row = new JPanel(new java.awt.BorderLayout(8, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 2, 3, 2));
		var label = EditorText.builder(name).font(WorkspaceStyle.UI.deriveFont(11f)).color(WorkspaceStyle.DIM).build();
		label.setPreferredSize(new Dimension(64, 20));
		row.add(label, java.awt.BorderLayout.WEST);
		row.add(control);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	public void setChanged(boolean changed) {
		label.setForeground(new Color(changed ? 0xE6E6E6 : 0x9A9A9A));
		if (reset != null)
			reset.setVisible(changed);
	}

	@Override public void doLayout() {
		int available = Math.max(0, getWidth() - GAP);
		int labelMinimum = layout == Layout.WIDE ? 90 : 70;
		int desiredControlWidth = layout == Layout.WIDE ? Math.round(available * 2.2f / 3.2f) : CONTROL_WIDTH;
		int controlWidth = Math.min(desiredControlWidth, Math.max(0, available - labelMinimum));
		int labelWidth = available - controlWidth;
		if (reset != null && reset.isVisible()) {
			var size = reset.getPreferredSize();
			reset.setBounds(getWidth() - controlWidth - size.width - 4,
				Math.max(VERTICAL_PADDING, (getHeight() - size.height) / 2), size.width, size.height);
			labelWidth = Math.max(0, labelWidth - size.width - 4);
		}
		label.setBounds(0, VERTICAL_PADDING, labelWidth, label.getPreferredSize().height);
		control.setBounds(getWidth() - controlWidth, VERTICAL_PADDING, controlWidth, control.getPreferredSize().height);
	}

	@Override public Dimension getPreferredSize() {
		int height = Math.max(label.getPreferredSize().height, control.getPreferredSize().height);
		return new Dimension(70 + GAP + CONTROL_WIDTH, height + 2 * VERTICAL_PADDING);
	}

	@Override public Dimension getMinimumSize() {
		return new Dimension(0, getPreferredSize().height);
	}

	@Override public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
