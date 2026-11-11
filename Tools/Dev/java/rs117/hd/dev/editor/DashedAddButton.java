package rs117.hd.dev.editor;

import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JButton;

public final class DashedAddButton {
	private DashedAddButton() {}

	public static JButton create(String label, Runnable action) {
		var button = create(label);
		button.addActionListener(e -> action.run());
		return button;
	}

	public static JButton create(String label) {
		var button = EditorButton.builder().style(WorkspaceStyle.Button.DASHED_ADD)
			.icon("plus", 10).text(label).build();
		var padding = BorderFactory.createEmptyBorder(3, 9, 3, 9);
		var border = BorderFactory.createCompoundBorder(
			BorderFactory.createDashedBorder(new Color(0x4A4A4A), 3, 3), padding);
		button.setBorderPainted(true);
		button.setBorder(border);
		return button;
	}
}
