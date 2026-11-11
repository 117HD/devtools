package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Trigger + popup for picking one color off a model's face-color palette (see §6.3). */
public final class SwatchDropdown extends JPanel {
	private static final int TRIGGER_HEIGHT = 30;
	private static final int PREVIEW_SWATCH_WIDTH = 10;
	private static final int PREVIEW_SWATCH_HEIGHT = 22;
	private static final int PREVIEW_COUNT = 5;
	private static final int POPUP_MIN_WIDTH = 280;
	private static final int GRID_MAX_HEIGHT = 300;
	private static final int TILE_MIN_WIDTH = 48;
	private static final int TILE_GAP = 4;
	private static final Color TRIGGER_TEXT = new Color(0xBD, 0xBD, 0xBD);
	private static final Color CAPTION_COLOR = new Color(0x7D, 0x7D, 0x7D);
	private static final Color HINT_COLOR = new Color(0x6F, 0x6F, 0x6F);
	private static final Color SWATCH_INSET_BORDER = new Color(255, 255, 255, 26);
	private static final Color FACE_COUNT_COLOR = new Color(0x8A, 0x8A, 0x8A);

	public static final class Swatch {
		public final Color color;
		public final int hue, saturation, lightness, faceCount;
		public final boolean clickable;

		public Swatch(Color color, int hue, int saturation, int lightness, int faceCount, boolean clickable) {
			this.color = color;
			this.hue = hue;
			this.saturation = saturation;
			this.lightness = lightness;
			this.faceCount = faceCount;
			this.clickable = clickable;
		}
	}

	public interface Provider {
		List<Swatch> allColors();
		List<Swatch> mostUsedColors();
	}

	private final Provider provider;
	private final Trigger trigger;
	private Consumer<Swatch> hoverListener;
	private Consumer<Swatch> selectListener;
	private PopupLayer.Handle openHandle;

	public static Builder builder(Provider provider) { return new Builder(provider); }

	public static final class Builder {
		private final Provider provider;
		private Consumer<Swatch> hover;
		private Consumer<Swatch> selection;

		private Builder(Provider provider) { this.provider = provider; }
		public Builder onHover(Consumer<Swatch> action) { hover = action; return this; }
		public Builder onSelect(Consumer<Swatch> action) { selection = action; return this; }

		public SwatchDropdown build() {
			var dropdown = new SwatchDropdown(provider);
			dropdown.onHover(hover);
			dropdown.onSelect(selection);
			return dropdown;
		}
	}

	public SwatchDropdown(Provider provider) {
		super(new BorderLayout());
		this.provider = provider;
		setOpaque(false);
		trigger = new Trigger();
		add(trigger, BorderLayout.CENTER);
	}

	public void onHover(Consumer<Swatch> listener) {
		this.hoverListener = listener;
	}

	public void onSelect(Consumer<Swatch> listener) {
		this.selectListener = listener;
	}

	private void fireHover(Swatch swatch) {
		if (hoverListener != null)
			hoverListener.accept(swatch);
	}

	private void fireSelect(Swatch swatch) {
		if (selectListener != null)
			selectListener.accept(swatch);
	}

	private static void copy(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
	}

	private void openPopup() {
		if (openHandle != null && !openHandle.isClosed()) {
			openHandle.close();
			return;
		}
		openHandle = PopupLayer.show(trigger, buildPopupContent(), POPUP_MIN_WIDTH);
		trigger.repaint();
	}

	private JPanel buildPopupContent() {
		JPanel root = new JPanel(new BorderLayout());
		root.setOpaque(false);

		JPanel header = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gap 8 0, fillx", "[grow][]", "[24!]"));
		header.setOpaque(false);
		header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JLabel caption = new JLabel("MODEL COLORS".toUpperCase());
		caption.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(10f));
		caption.setForeground(CAPTION_COLOR);
		header.add(caption, "aligny center");

		SegmentedControl<String> sort = SegmentedControl.builder(List.of("Hue", "Most used")).build();
		header.add(sort, "height 24!, aligny center");
		root.add(header, BorderLayout.NORTH);

		Grid grid = new Grid();
		grid.setItems(provider.allColors());
		sort.onChange(value -> grid.setItems("Hue".equals(value) ? provider.allColors() : provider.mostUsedColors()));

		JScrollPane scroll = new EditorScrollPane(grid,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
			@Override
			public Dimension getPreferredSize() {
				Dimension preferred = super.getPreferredSize();
				return new Dimension(preferred.width, Math.min(preferred.height, GRID_MAX_HEIGHT));
			}
		};
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		root.add(scroll, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, WorkspaceStyle.BORDER),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		JLabel hint = new JLabel("Hover to highlight on the model · number = faces");
		hint.setFont(WorkspaceStyle.UI.deriveFont(10f));
		hint.setForeground(HINT_COLOR);
		footer.add(hint, BorderLayout.WEST);
		root.add(footer, BorderLayout.SOUTH);

		return root;
	}

	/** 30px tall trigger: most-used-color preview strip, color count, chevron. */
	private final class Trigger extends JComponent {
		private boolean hover;

		Trigger() {
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
				@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
				@Override public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event))
						openPopup();
				}
			});
		}

		private List<Swatch> previewSwatches() {
			List<Swatch> mostUsed = provider.mostUsedColors();
			return mostUsed.subList(0, Math.min(PREVIEW_COUNT, mostUsed.size()));
		}

		private String labelText() {
			return provider.allColors().size() + " colors on this model";
		}

		@Override
		public Dimension getPreferredSize() {
			int previewCount = previewSwatches().size();
			int stripWidth = previewCount * PREVIEW_SWATCH_WIDTH;
			FontMetrics metrics = getFontMetrics(WorkspaceStyle.UI.deriveFont(12f));
			int textWidth = metrics.stringWidth(labelText());
			int width = 8 + stripWidth + (previewCount > 0 ? 8 : 0) + textWidth + 10 + 16 + 8;
			return new Dimension(Math.max(width, 140), TRIGGER_HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int width = getWidth(), height = getHeight();
			boolean open = openHandle != null && !openHandle.isClosed();
			g.setColor(WorkspaceStyle.INPUT);
			g.fillRoundRect(0, 0, width - 1, height - 1, 4, 4);
			g.setColor(open || hover ? WorkspaceStyle.ACCENT : WorkspaceStyle.BORDER);
			g.drawRoundRect(0, 0, width - 1, height - 1, 4, 4);

			int x = 8;
			List<Swatch> preview = previewSwatches();
			if (!preview.isEmpty()) {
				int stripWidth = preview.size() * PREVIEW_SWATCH_WIDTH;
				int stripY = (height - PREVIEW_SWATCH_HEIGHT) / 2;
				Shape previousClip = g.getClip();
				g.clip(new RoundRectangle2D.Float(x, stripY, stripWidth, PREVIEW_SWATCH_HEIGHT, 3, 3));
				for (int i = 0; i < preview.size(); i++) {
					g.setColor(preview.get(i).color);
					g.fillRect(x + i * PREVIEW_SWATCH_WIDTH, stripY, PREVIEW_SWATCH_WIDTH, PREVIEW_SWATCH_HEIGHT);
				}
				g.setClip(previousClip);
				x += stripWidth + 8;
			}

			g.setFont(WorkspaceStyle.UI.deriveFont(12f));
			g.setColor(TRIGGER_TEXT);
			FontMetrics metrics = g.getFontMetrics();
			g.drawString(labelText(), x, (height - metrics.getHeight()) / 2 + metrics.getAscent());

			WorkspaceStyle.icon("chevron-down", 9).paintIcon(this, g, width - 17, (height - 9) / 2);
			g.dispose();
		}
	}

	/** Responsive wrap-grid of {@link Tile}s, scrolled by the enclosing {@link JScrollPane}. */
	private final class Grid extends JPanel implements Scrollable {
		Grid() {
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			setLayout(null);
		}

		void setItems(List<Swatch> items) {
			removeAll();
			for (Swatch swatch : items)
				add(new Tile(swatch));
			revalidate();
			repaint();
		}

		private int innerWidth() {
			Insets insets = getInsets();
			int width = getWidth();
			if (width <= 0)
				width = POPUP_MIN_WIDTH - 2 * 6;
			return Math.max(1, width - insets.left - insets.right);
		}

		private int columns() {
			return Math.max(1, innerWidth() / TILE_MIN_WIDTH);
		}

		private int tileHeight() {
			int height = 0;
			for (int i = 0; i < getComponentCount(); i++)
				height = Math.max(height, getComponent(i).getPreferredSize().height);
			return height;
		}

		@Override
		public void doLayout() {
			Insets insets = getInsets();
			int columns = columns();
			int tileWidth = Math.max(1, (innerWidth() - TILE_GAP * (columns - 1)) / columns);
			int tileHeight = tileHeight();
			int count = getComponentCount();
			for (int i = 0; i < count; i++) {
				int row = i / columns, col = i % columns;
				int x = insets.left + col * (tileWidth + TILE_GAP);
				int y = insets.top + row * (tileHeight + TILE_GAP);
				getComponent(i).setBounds(x, y, tileWidth, tileHeight);
			}
		}

		@Override
		public Dimension getPreferredSize() {
			Insets insets = getInsets();
			int width = getWidth();
			if (width <= 0)
				width = POPUP_MIN_WIDTH;
			int columns = columns();
			int count = getComponentCount();
			int rows = count == 0 ? 0 : (count + columns - 1) / columns;
			int height = insets.top + insets.bottom;
			if (rows > 0)
				height += rows * tileHeight() + (rows - 1) * TILE_GAP;
			return new Dimension(width, height);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 96;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	/** One color tile: 28px swatch, face count below, hover/click driven by the owning dropdown. */
	private final class Tile extends JComponent {
		private static final int SWATCH_SIZE = 28;
		private static final int PADDING = 3;

		private final Swatch swatch;
		private boolean hover;

		Tile(Swatch swatch) {
			this.swatch = swatch;
			setOpaque(false);
			setToolTipText(String.format(
				"h%d s%d l%d · %d faces", swatch.hue, swatch.saturation, swatch.lightness, swatch.faceCount));
			setCursor(swatch.clickable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			setFocusable(true);
			HdContextMenu.install(this, point -> {
				Point at = point == null ? new Point(0, getHeight()) : point;
				int packed = swatch.hue << 10 | swatch.saturation << 7 | swatch.lightness;
				var menu = new HdContextMenu("◐", swatch.color,
					String.format("h%d · s%d · l%d · %d faces", swatch.hue, swatch.saturation, swatch.lightness, swatch.faceCount));
				if (swatch.clickable && selectListener != null)
					menu.item("Use color for override", "plus", "", () -> {
						if (openHandle != null)
							openHandle.close();
						fireSelect(swatch);
					});
				menu.separator()
					.item("Copy HSL", "copy", "", () -> copy(swatch.hue + ", " + swatch.saturation + ", " + swatch.lightness))
					.item("Copy packed int", "copy", "", () -> copy(Integer.toString(packed)))
					.show(this, at.x, at.y);
			});
			addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent event) {
					hover = true;
					repaint();
					fireHover(swatch);
				}
				@Override public void mouseExited(MouseEvent event) {
					hover = false;
					repaint();
					fireHover(null);
				}
				@Override public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event) && swatch.clickable) {
						if (openHandle != null)
							openHandle.close();
						fireSelect(swatch);
					}
				}
			});
		}

		@Override
		public Dimension getPreferredSize() {
			FontMetrics metrics = getFontMetrics(WorkspaceStyle.MONO.deriveFont(9f));
			int height = PADDING + SWATCH_SIZE + 2 + metrics.getHeight() + PADDING;
			return new Dimension(SWATCH_SIZE + PADDING * 2, height);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			Composite previousComposite = g.getComposite();
			if (!swatch.clickable)
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));

			int width = getWidth(), height = getHeight();
			if (hover) {
				g.setColor(WorkspaceStyle.HEADER);
				g.fillRoundRect(0, 0, width, height, 4, 4);
			}

			int swatchX = (width - SWATCH_SIZE) / 2;
			int swatchY = PADDING;
			g.setColor(swatch.color);
			g.fillRoundRect(swatchX, swatchY, SWATCH_SIZE, SWATCH_SIZE, 3, 3);
			g.setColor(SWATCH_INSET_BORDER);
			g.drawRoundRect(swatchX, swatchY, SWATCH_SIZE - 1, SWATCH_SIZE - 1, 3, 3);

			g.setFont(WorkspaceStyle.MONO.deriveFont(9f));
			g.setColor(FACE_COUNT_COLOR);
			FontMetrics metrics = g.getFontMetrics();
			String text = String.valueOf(swatch.faceCount);
			int textX = (width - metrics.stringWidth(text)) / 2;
			int textY = swatchY + SWATCH_SIZE + 2 + metrics.getAscent();
			g.drawString(text, textX, textY);

			g.setComposite(previousComposite);
			g.dispose();
		}
	}
}
