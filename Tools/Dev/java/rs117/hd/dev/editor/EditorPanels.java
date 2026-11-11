package rs117.hd.dev.editor;

import java.awt.Component;
import java.awt.GridBagLayout;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/** Layout containers for the editor's common transparent groups. */
public final class EditorPanels {
	private EditorPanels() {}

	public static JPanel centered(Component content) {
		var panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		panel.add(content);
		return panel;
	}

	public static JPanel stack() {
		var panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setOpaque(false);
		return panel;
	}
}
