package rs117.hd.dev.overrides;

import java.awt.*;
import java.util.function.IntConsumer;
import javax.swing.*;
import rs117.hd.dev.editor.EditorText;
import rs117.hd.dev.editor.WorkspaceStyle;

final class WindStrength extends JPanel {
	private static final String[] LEVELS = {
		"much weaker", "weaker", "slightly weaker", "mode default",
		"slightly stronger", "stronger", "much stronger"
	};
	private static final Color MUTED = new Color(0x6F6F6F);
	private final JLabel title = label("Strength", 12, new Color(0x9A9A9A), false);
	private final JLabel readout = label("", 11, new Color(0xCFCFCF), true);
	private final JTextArea hint = new JTextArea();
	private IntConsumer onChange = value -> {};
	private int value;
	private boolean faded;

	WindStrength() {
		setName("field:Strength");
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new BorderLayout(0, 4));
		setBorder(BorderFactory.createEmptyBorder(5, 0, 4, 0));
		var header = new JPanel(new BorderLayout(12, 0));
		header.setOpaque(false);
		var names = new JPanel(new BorderLayout());
		names.setOpaque(false);
		names.add(title, BorderLayout.NORTH);
		hint.setFont(WorkspaceStyle.UI.deriveFont(11f));
		hint.setForeground(MUTED);
		hint.setOpaque(false);
		hint.setEditable(false);
		hint.setFocusable(false);
		hint.setLineWrap(true);
		hint.setWrapStyleWord(true);
		hint.setRows(2);
		names.add(hint);
		header.add(names);
		readout.setVerticalAlignment(SwingConstants.TOP);
		header.add(readout, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);
		var track = new JPanel(new GridLayout(1, 7, 3, 0)) {
			@Override protected void paintComponent(Graphics graphics) {
				rounded(graphics, new Color(0x1A1A1A), getWidth(), getHeight(), 5);
				graphics.setColor(new Color(0x333333));
				graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 5, 5);
			}
		};
		track.setOpaque(false);
		track.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		for (int step = -3; step <= 3; step++)
			track.add(stepButton(step));
		add(track);
		var footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.add(label("Weaker", 10, MUTED, false), BorderLayout.WEST);
		footer.add(label("Stronger", 10, MUTED, false), BorderLayout.EAST);
		add(footer, BorderLayout.SOUTH);
		registerKeyboardAction(e -> choose(Math.max(-3, value - 1)), KeyStroke.getKeyStroke("LEFT"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		registerKeyboardAction(e -> choose(Math.min(3, value + 1)), KeyStroke.getKeyStroke("RIGHT"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		update(0, true, false);
	}

	void update(int value, boolean disabled, boolean changed) {
		this.value = value;
		faded = disabled;
		title.setForeground(new Color(changed ? 0xE6E6E6 : 0x9A9A9A));
		String level = value >= -3 && value <= 3 ? LEVELS[value + 3] : "custom";
		readout.setText(format(value) + " \u00b7 " + level);
		hint.setText(disabled ? "Pick a wind mode first" : "How much the model sways compared to the mode default");
		revalidate();
		repaint();
	}

	void onChange(IntConsumer action) {
		onChange = action;
	}

	private void choose(int step) {
		if (value != step)
			onChange.accept(step);
	}

	private JButton stepButton(int step) {
		var button = new JButton() {
			@Override protected void paintComponent(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				boolean selected = step == value;
				boolean lit = step >= Math.min(0, value) && step <= Math.max(0, value);
				if (selected || getModel().isRollover() || isFocusOwner())
					rounded(g, new Color(selected ? 0x2A3446 : 0x2C2C2C), getWidth(), getHeight(), 3);
				int barHeight = 6 + (step + 3) * 3;
				g.translate((getWidth() - 10) / 2, 28 - barHeight);
				rounded(g, new Color(selected ? 0x7AA5E8 : step == 0 ? 0x6A6A6A : lit ? 0x4772B3 : 0x3A3A3A), 10, barHeight, 3);
				g.translate(-(getWidth() - 10) / 2, barHeight - 28);
				g.setFont(WorkspaceStyle.MONO.deriveFont(10f));
				g.setColor(new Color(selected ? 0xFFFFFF : step == 0 ? 0xBDBDBD : 0x8A8A8A));
				String text = format(step);
				g.drawString(text, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2, 40);
				g.dispose();
			}
		};
		button.setPreferredSize(new Dimension(26, 44));
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setRolloverEnabled(true);
		button.setToolTipText(LEVELS[step + 3]);
		button.getAccessibleContext().setAccessibleName(format(step) + " " + LEVELS[step + 3]);
		button.addActionListener(e -> choose(step));
		return button;
	}

	@Override public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override public void paint(Graphics graphics) {
		var g = (Graphics2D) graphics.create();
		if (faded)
			g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
		super.paint(g);
		g.dispose();
	}

	private static void rounded(Graphics graphics, Color color, int width, int height, int radius) {
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.fillRoundRect(0, 0, width, height, radius, radius);
		g.dispose();
	}

	private static JLabel label(String text, int size, Color color, boolean mono) {
		return EditorText.builder(text).font((mono ? WorkspaceStyle.MONO : WorkspaceStyle.UI).deriveFont((float) size))
			.color(color).build();
	}

	private static String format(int step) {
		return step > 0 ? "+" + step : step < 0 ? "\u2212" + -(long) step : "0";
	}
}
