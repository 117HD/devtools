package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

public final class ColorPicker extends JPanel {
	private final float[] hsv = new float[3];
	private final JTextField[] inputs = new JTextField[4];
	private final Consumer<Color> apply;
	private final Timer debounce;
	private Color committed;
	private boolean syncing;
	private final JPanel sample = new JPanel();
	private final Gradient saturation = new Gradient(false);
	private final Gradient hue = new Gradient(true);

	private ColorPicker(String paletteLabel, List<Color> palette, Color initial, Consumer<Color> apply, boolean manual) {
		super(new BorderLayout(0, 8));
		this.apply = apply;
		committed = new Color(initial.getRGB());
		debounce = manual ? null : new Timer(150, event -> commit());
		if (debounce != null)
			debounce.setRepeats(false);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		var controls = new JPanel(new BorderLayout(0, 8));
		controls.setOpaque(false);
		controls.add(saturation);
		hue.setPreferredSize(new Dimension(0, 16));
		controls.add(hue, BorderLayout.SOUTH);
		add(controls);

		var footer = EditorPanels.stack();
		var fields = new JPanel(new BorderLayout(5, 0));
		fields.setOpaque(false);
		sample.setPreferredSize(new Dimension(34, 0));
		sample.setBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true));
		fields.add(sample, BorderLayout.WEST);
		var values = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 4, fillx", "[grow,fill][38!][38!][38!]"));
		values.setOpaque(false);
		String[] labels = {"HEX", "R", "G", "B"};
		for (int i = 0; i < inputs.length; i++) {
			var column = new JPanel(new BorderLayout(0, 2));
			column.setOpaque(false);
			var field = inputs[i] = new JTextField();
			field.setFont(WorkspaceStyle.MONO.deriveFont(11f));
			field.setForeground(WorkspaceStyle.TEXT);
			field.setCaretColor(WorkspaceStyle.TEXT);
			field.setBackground(WorkspaceStyle.INPUT);
			field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1, true),
				BorderFactory.createEmptyBorder(3, 3, 3, 3)));
			field.getAccessibleContext().setAccessibleName(labels[i]);
			var caption = EditorText.builder(labels[i]).color(WorkspaceStyle.DIM).font(WorkspaceStyle.UI.deriveFont(10f)).build();
			caption.setLabelFor(field);
			column.add(caption, BorderLayout.NORTH);
			column.add(field);
			int channel = i;
			field.addActionListener(event -> {
				if (readInput(channel, true).isPresent() && debounce != null)
					commit();
			});
			EditorSearch.onChange(field, text -> {
				if (!syncing && debounce != null) {
					debounce.stop();
					if (parseInput(channel).isPresent())
						debounce.restart();
				}
			});
			field.addFocusListener(new java.awt.event.FocusAdapter() {
				@Override public void focusLost(java.awt.event.FocusEvent event) {
					if (!event.isTemporary())
						readInput(channel, false);
				}
			});
			values.add(column);
		}
		fields.add(values);
		footer.add(fields);
		if (!palette.isEmpty()) {
			footer.add(Box.createVerticalStrut(10));
			footer.add(EditorText.builder(paletteLabel).color(WorkspaceStyle.DIM).font(WorkspaceStyle.SEMIBOLD.deriveFont(10f)).build());
			var chips = new JPanel(new GridLayout(0, 10, 3, 3));
			chips.setOpaque(false);
			for (Color color : palette) {
				var chip = EditorButton.builder().style(WorkspaceStyle.Button.TAB).size(19, 19)
					.icon(swatch(color, 19)).tooltip(hex(color)).onClick(() -> setColor(color)).build();
				chips.add(chip);
			}
			footer.add(chips);
		}
		if (manual) {
			footer.add(Box.createVerticalStrut(6));
			footer.add(EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY).text("Apply")
				.onClick(() -> apply.accept(currentInput())).build());
		}
		add(footer, BorderLayout.SOUTH);
		setColor(new Color(initial.getRGB()));
	}

	private Color currentInput() {
		var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		for (int i = 0; i < inputs.length; i++)
			if (focus == inputs[i])
				return parseInput(i).orElseGet(this::color);
		return color();
	}

	private void commit() {
		debounce.stop();
		Color next = currentInput();
		if (!next.equals(committed)) {
			committed = next;
			if (!next.equals(color()))
				setColor(next);
			apply.accept(next);
		}
	}

	public static Builder builder(AbstractButton trigger, java.util.function.Supplier<Color> initial) {
		return new Builder(trigger, initial);
	}

	public static final class Builder {
		private final AbstractButton trigger;
		private final java.util.function.Supplier<Color> initial;
		private String paletteLabel = "";
		private java.util.function.Supplier<List<Color>> palette = List::of;
		private Consumer<Color> action;
		private boolean manual;

		private Builder(AbstractButton trigger, java.util.function.Supplier<Color> initial) {
			this.trigger = trigger;
			this.initial = initial;
		}

		public Builder palette(String label, java.util.function.Supplier<List<Color>> colors) {
			paletteLabel = label;
			palette = colors;
			return this;
		}

		public Builder showApplyButton(boolean enabled) { manual = enabled; return this; }
		public Builder onApply(Consumer<Color> action) { this.action = action; return this; }

		public void bind() {
			Consumer<Color> callback = java.util.Objects.requireNonNull(action, "Provide a color apply action");
			boolean explicitApply = manual;
			String label = paletteLabel;
			var colors = palette;
			PopupLayer.Handle[] popup = {null};
			trigger.addHierarchyListener(event -> {
				if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
					&& !trigger.isShowing() && popup[0] != null)
					popup[0].close();
			});
			trigger.addActionListener(event -> {
				if (popup[0] != null) {
					popup[0].close();
					return;
				}
				var picker = new ColorPicker(label, colors.get().stream().distinct().limit(20)
					.collect(java.util.stream.Collectors.toList()), initial.get(), color -> {
						if (explicitApply)
							PopupLayer.select(popup[0], () -> callback.accept(color));
						else
							callback.accept(color);
					}, explicitApply);
				var scroll = new EditorScrollPane(picker, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
					ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
				scroll.setBorder(null);
				scroll.setOpaque(false);
				scroll.getViewport().setOpaque(false);
				popup[0] = PopupLayer.show(trigger, scroll, 248, 0, Integer.MAX_VALUE);
				popup[0].onClose(() -> {
					popup[0] = null;
					if (picker.debounce != null && picker.debounce.isRunning()) {
						if (trigger.isShowing())
							picker.commit();
						else
							picker.debounce.stop();
					}
				});
			});
		}
	}

	public static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xffffff); }

	public static JButton propertyButton(String tooltip) {
		var button = EditorButton.builder().style(WorkspaceStyle.Button.TAB).tooltip(tooltip).build();
		button.setBorder(BorderFactory.createEmptyBorder());
		button.setHorizontalAlignment(SwingConstants.LEFT);
		button.setIconTextGap(4);
		return button;
	}

	public static void setPropertyColor(JButton button, Color color, boolean explicit) {
		button.setText(explicit ? hex(color) : "Default");
		button.setFont(explicit ? WorkspaceStyle.MONO_SEMIBOLD : WorkspaceStyle.UI);
		button.setIcon(explicit ? swatch(color, 24) : defaultSwatch(color));
	}

	private static Icon defaultSwatch(Color color) {
		return new Icon() {
			private static final int SIZE = 24;
			@Override public int getIconWidth() { return SIZE; }
			@Override public int getIconHeight() { return SIZE; }
			@Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
				var g = graphics.create(x, y, SIZE, SIZE);
				g.setColor(color == null ? WorkspaceStyle.PANEL : color);
				g.fillRect(0, 0, SIZE, SIZE);
				g.setColor(new Color(0x707070));
				for (int stripe = 0; stripe < 2 * SIZE; stripe += 4)
					g.drawLine(stripe - SIZE, SIZE, stripe, 0);
				g.dispose();
			}
		};
	}

	public static Icon swatch(Color color, int size) {
		return new Icon() {
			@Override public int getIconWidth() { return size; }
			@Override public int getIconHeight() { return size; }
			@Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(color);
				g.fillRoundRect(x, y, size, size, 7, 7);
				g.setColor(new Color(255, 255, 255, 28));
				g.drawRoundRect(x, y, size - 1, size - 1, 7, 7);
				g.dispose();
			}
		};
	}

	private Color color() { return Color.getHSBColor(hsv[0], hsv[1], hsv[2]); }

	private void setColor(Color color) {
		Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsv);
		refresh();
	}

	private void refresh() {
		Color color = color();
		sample.setBackground(color);
		syncing = true;
		inputs[0].setText(hex(color));
		inputs[1].setText(Integer.toString(color.getRed()));
		inputs[2].setText(Integer.toString(color.getGreen()));
		inputs[3].setText(Integer.toString(color.getBlue()));
		syncing = false;
		if (debounce != null && !color.equals(committed))
			debounce.restart();
		saturation.repaint();
		hue.repaint();
	}

	private java.util.Optional<Color> parseInput(int channel) {
		String text = inputs[channel].getText().strip();
		Color current = color();
		try {
			Color next;
			if (channel == 0) {
				if (!text.matches("#?[0-9a-fA-F]{6}"))
					throw new IllegalArgumentException();
				next = new Color(Integer.parseInt(text.replace("#", ""), 16));
			} else {
				int[] rgb = {current.getRed(), current.getGreen(), current.getBlue()};
				rgb[channel - 1] = Integer.parseInt(text);
				next = new Color(rgb[0], rgb[1], rgb[2]);
			}
			return java.util.Optional.of(next);
		} catch (IllegalArgumentException invalid) {
			return java.util.Optional.empty();
		}
	}

	private java.util.Optional<Color> readInput(int channel, boolean report) {
		var result = parseInput(channel);
		if (result.isPresent())
			setColor(result.get());
		else if (report) {
			Toolkit.getDefaultToolkit().beep();
			inputs[channel].selectAll();
		} else
			refresh();
		return result;
	}

	private final class Gradient extends JComponent {
		private final boolean hueOnly;

		Gradient(boolean hueOnly) {
			this.hueOnly = hueOnly;
			setPreferredSize(new Dimension(226, 140));
			setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
			var mouse = new MouseAdapter() {
				@Override public void mousePressed(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event))
						move(event);
				}
				@Override public void mouseDragged(MouseEvent event) {
					if ((event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0)
						move(event);
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private void move(MouseEvent event) {
			float x = Math.max(0, Math.min(1, (event.getX() - (hueOnly ? 3 : 6)) / (float) Math.max(1, getWidth() - (hueOnly ? 7 : 12))));
			if (hueOnly) {
				hsv[0] = x;
			} else {
				hsv[1] = x;
				hsv[2] = 1 - Math.max(0, Math.min(1, (event.getY() - 6) / (float) Math.max(1, getHeight() - 12)));
			}
			refresh();
		}

		@Override protected void paintComponent(Graphics graphics) {
			int w = getWidth(), h = getHeight();
			if (w < 2 || h < 2)
				return;
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (hueOnly) {
				g.setPaint(new LinearGradientPaint(0, 0, w - 1, 0,
					new float[]{0, 1f/6, 2f/6, 3f/6, 4f/6, 5f/6, 1},
					new Color[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}));
				g.fillRoundRect(0, 3, w, h - 6, 10, 10);
				int x = Math.round(hsv[0] * (w - 7));
				g.setColor(Color.WHITE);
				g.fillRoundRect(x, 0, 7, h, 5, 5);
				g.setColor(WorkspaceStyle.BORDER);
				g.drawRoundRect(x, 0, 6, h - 1, 5, 5);
			} else {
				var plane = new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 8, 8);
				g.setPaint(new GradientPaint(0, 0, Color.WHITE, w - 1, 0, Color.getHSBColor(hsv[0], 1, 1)));
				g.fill(plane);
				g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0), 0, h - 1, Color.BLACK));
				g.fill(plane);
				int x = 6 + Math.round(hsv[1] * (w - 12));
				int y = 6 + Math.round((1 - hsv[2]) * (h - 12));
				g.setColor(Color.BLACK);
				g.drawOval(x - 6, y - 6, 12, 12);
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(2));
				g.drawOval(x - 5, y - 5, 10, 10);
			}
			g.dispose();
		}
	}
}
