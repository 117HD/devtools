package rs117.hd.dev.editor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JPanel;

public final class SwatchStrip extends JPanel {
	public SwatchStrip(List<Color[]> beforeAfterPairs, int maxVisible) {
		super(new FlowLayout(FlowLayout.LEFT, 2, 0));
		setOpaque(false);
		int count = Math.min(beforeAfterPairs.size(), maxVisible);
		for (int i = 0; i < count; i++) {
			Color[] pair = beforeAfterPairs.get(i);
			var swatch = new BeforeAfterSwatch(14, BeforeAfterSwatch.Split.HORIZONTAL, pair[0], pair[1]);
			var size = new Dimension(22, 14);
			swatch.setPreferredSize(size);
			swatch.setMinimumSize(size);
			swatch.setMaximumSize(size);
			add(swatch);
		}
	}
}
