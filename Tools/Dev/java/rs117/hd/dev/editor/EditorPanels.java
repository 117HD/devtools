package rs117.hd.dev.editor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.GridBagLayout;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.Scrollable;

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

	public static JPanel propertyStack() {
		var panel = new PropertyStack();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setBackground(WorkspaceStyle.INPUT);
		return panel;
	}

	private static final class PropertyStack extends JPanel implements Scrollable {
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
		@Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(16, visible.height - 16);
		}
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
}
