package rs117.hd.dev.editor;

import java.awt.Color;
import java.awt.Dimension;
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

	public PropertyRow(String name, JComponent control, Layout layout) {
		super(null);
		this.control = control;
		this.layout = layout;
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
	}

	@Override public void doLayout() {
		int available = Math.max(0, getWidth() - GAP);
		int labelMinimum = layout == Layout.SIMPLE ? 70 : 90;
		int desiredControlWidth = layout == Layout.SIMPLE ? CONTROL_WIDTH : Math.round(available * 2.2f / 3.2f);
		int controlWidth = Math.min(desiredControlWidth, Math.max(0, available - labelMinimum));
		int labelWidth = available - controlWidth;
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
