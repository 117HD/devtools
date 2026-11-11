package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Generic thumbnail-preview dropdown: a compact trigger that opens a searchable grid of tiles via {@link PopupLayer}. */
public final class PreviewDropdown<T> extends JPanel {
	public interface Provider<T> {
		List<T> items();
		String label(T item);

		/** String to match against when filtering. Defaults to {@link #label(Object)}; override for e.g. gameval-name search. */
		default String searchKey(T item) { return label(item); }

		/** Returns a preview image sized for {@code size}px, or null to draw the checker "none" fallback. */
		Image preview(T item, int size);
		default Image preview(T item, int size, JComponent observer) { return preview(item, size); }
	}

	public static <T> Builder<T> builder(Provider<T> provider) {
		return new Builder<>(provider);
	}

	public static final class Builder<T> {
		private final Provider<T> provider;
		private UnaryOperator<List<T>> filter = UnaryOperator.identity();
		private String searchPlaceholder;
		private boolean compact;
		private String caption;
		private String emptyText = "";

		private Builder(Provider<T> provider) { this.provider = provider; }

		/** Filters the available options each time the popup opens. Existing values stay visible. */
		public Builder<T> filterItems(UnaryOperator<List<T>> filter) {
			this.filter = Objects.requireNonNull(filter);
			return this;
		}

		public Builder<T> search(String placeholder) {
			searchPlaceholder = placeholder;
			return this;
		}

		public Builder<T> compact(boolean compact) { this.compact = compact; return this; }
		public Builder<T> caption(String caption) { this.caption = caption; return this; }

		public Builder<T> emptyText(String text) { emptyText = Objects.requireNonNull(text); return this; }

		public PreviewDropdown<T> build() {
			var dropdown = new PreviewDropdown<>(provider, filter);
			dropdown.compact = compact;
			dropdown.caption = caption;
			dropdown.emptyText = emptyText;
			if (searchPlaceholder != null) {
				dropdown.setSearchable(true);
				dropdown.setSearchPlaceholder(searchPlaceholder);
			}
			return dropdown;
		}
	}

	private static final int TRIGGER_HEIGHT = 30;
	private static final int THUMB_SIZE = 24;
	private static final int THUMB_RADIUS = 3;
	private static final int TRIGGER_RADIUS = 4;
	private static final int PAD_LEFT = 3;
	private static final int PAD_RIGHT = 6;
	private static final int GAP_THUMB_TEXT = 6;
	private static final int GAP_TEXT_CHEVRON = 6;
	private static final int CHEVRON_SIZE = 10;
	private static final int MAX_TRIGGER_WIDTH = 260;

	private static final int POPUP_WIDTH = 300;
	private static final int GRID_PADDING = 6;
	private static final int GRID_GAP = 6;
	private static final int MIN_TILE = 80;
	private static final int TILE_PADDING = 4;
	private static final int TILE_RADIUS = 4;
	private static final int TILE_LABEL_GAP = 4;
	private static final int TILE_LABEL_HEIGHT = 12;
	private static final int GRID_MAX_HEIGHT = 292;

	private static final Color HOVER_BORDER = new Color(0x555555);
	private static final Color THUMB_INSET_BORDER = new Color(255, 255, 255, 20);
	private static final Color CHECKER_LIGHT = new Color(0x3A3A3A);
	private static final Color CHECKER_DARK = new Color(0x2A2A2A);
	private static final Color TILE_SELECTED_BG = new Color(0x334D73);
	private static final Color TILE_HOVER_BG = new Color(0x333333);
	private static final Color LABEL_COLOR = new Color(0xDDDDDD);
	private static final Color SEARCH_BORDER = new Color(0x1E1E1E);

	private final Provider<T> provider;
	private final UnaryOperator<List<T>> filter;
	private final Map<Integer, Map<T, Image>> previewCache = new HashMap<>();
	private final List<Consumer<T>> listeners = new ArrayList<>();

	private T value;
	private boolean compact;
	private String caption;
	private String emptyText = "";
	private boolean searchable;
	private String searchPlaceholder = "Search";
	private boolean hover;
	private boolean open;
	private PopupLayer.Handle popupHandle;

	public PreviewDropdown(Provider<T> provider) {
		this(provider, UnaryOperator.identity());
	}

	private PreviewDropdown(Provider<T> provider, UnaryOperator<List<T>> filter) {
		this.provider = provider;
		this.filter = filter;
		setOpaque(false);
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
			@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
			@Override public void mouseClicked(MouseEvent event) { togglePopup(); }
		});
	}

	public void setSearchable(boolean searchable) {
		this.searchable = searchable;
	}

	public void setSearchPlaceholder(String placeholder) {
		this.searchPlaceholder = placeholder;
	}

	public void setValue(T value) {
		this.value = value;
		setToolTipText(value == null ? null : provider.label(value));
		repaint();
	}

	public T getValue() {
		return value;
	}

	public void onChange(Consumer<T> listener) {
		listeners.add(listener);
	}

	// ----- trigger -----

	@Override
	public Dimension getPreferredSize() {
		FontMetrics metrics = getFontMetrics(WorkspaceStyle.MONO.deriveFont(11f));
		String label = value != null ? provider.label(value) : emptyText;
		int textWidth = metrics != null ? metrics.stringWidth(label) : 0;
		int width = PAD_LEFT + THUMB_SIZE + GAP_THUMB_TEXT + textWidth + GAP_TEXT_CHEVRON + CHEVRON_SIZE + PAD_RIGHT;
		return new Dimension(Math.min(MAX_TRIGGER_WIDTH, width), compact ? 26 : TRIGGER_HEIGHT);
	}

	@Override
	public Dimension getMinimumSize() {
		return new Dimension(80, compact ? 26 : TRIGGER_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(MAX_TRIGGER_WIDTH, compact ? 26 : TRIGGER_HEIGHT);
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int width = getWidth();
		int height = getHeight();
		g.setColor(WorkspaceStyle.INPUT);
		g.fillRoundRect(0, 0, width - 1, height - 1, TRIGGER_RADIUS, TRIGGER_RADIUS);
		g.setColor(open ? WorkspaceStyle.ACCENT : hover ? HOVER_BORDER : WorkspaceStyle.BORDER);
		g.drawRoundRect(0, 0, width - 1, height - 1, TRIGGER_RADIUS, TRIGGER_RADIUS);

		int x = PAD_LEFT;
		int thumbSize = compact ? 18 : THUMB_SIZE;
		int y = (height - thumbSize) / 2;
		paintThumbnail(g, value, thumbSize, x, y, this);
		x += thumbSize + GAP_THUMB_TEXT;

		int chevronX = width - PAD_RIGHT - CHEVRON_SIZE;
		int chevronY = (height - CHEVRON_SIZE) / 2;
		int textMaxWidth = Math.max(0, chevronX - GAP_TEXT_CHEVRON - x);

		String label = value != null ? provider.label(value) : emptyText;
		g.setFont(WorkspaceStyle.MONO.deriveFont(11f));
		g.setColor(LABEL_COLOR);
		FontMetrics metrics = g.getFontMetrics();
		String shown = ellipsize(metrics, label, textMaxWidth);
		g.drawString(shown, x, (height - metrics.getHeight()) / 2 + metrics.getAscent());

		WorkspaceStyle.icon("chevron-down", CHEVRON_SIZE).paintIcon(this, g, chevronX, chevronY);
		g.dispose();
	}

	private void togglePopup() { togglePopup(this); }

	public void togglePopup(JComponent trigger) {
		if (popupHandle != null && !popupHandle.isClosed()) {
			popupHandle.close();
			return;
		}
		JComponent content = buildPopupContent();
		popupHandle = PopupLayer.show(trigger, content, POPUP_WIDTH);
		open = true;
		if (searchable && content.getClientProperty("searchField") instanceof JTextField)
			((JTextField) content.getClientProperty("searchField")).requestFocusInWindow();
		repaint();
		popupHandle.onClose(() -> {
			popupHandle = null;
			open = false;
			previewCache.clear();
			repaint();
		});
	}

	// ----- popup content -----

	private JComponent buildPopupContent() {
		JPanel content = new JPanel(new BorderLayout());
		content.setOpaque(false);

		List<T> allItems = filter.apply(new ArrayList<>(provider.items()));
		List<Tile> tiles = new ArrayList<>();

		int contentWidth = POPUP_WIDTH - 2 * GRID_PADDING - 12;
		int columns = Math.max(1, (contentWidth + GRID_GAP) / (MIN_TILE + GRID_GAP));
		int tileWidth = Math.max(MIN_TILE, (contentWidth - (columns - 1) * GRID_GAP) / columns);
		int thumbSize = Math.max(16, tileWidth - 2 * TILE_PADDING);
		int tileHeight = 2 * TILE_PADDING + thumbSize + TILE_LABEL_GAP + TILE_LABEL_HEIGHT;

		JPanel grid = new JPanel(new GridLayout(0, columns, GRID_GAP, GRID_GAP));
		grid.setOpaque(false);

		for (T item : allItems) {
			Tile tile = new Tile(item, tileWidth, tileHeight, thumbSize, () -> {
				setValue(item);
				for (Consumer<T> listener : listeners)
					listener.accept(item);
				if (popupHandle != null)
					popupHandle.close();
			});
			tiles.add(tile);
			grid.add(tile);
		}

		JPanel gridWrapper = new JPanel(new BorderLayout());
		gridWrapper.setOpaque(false);
		gridWrapper.setBorder(BorderFactory.createEmptyBorder(GRID_PADDING, GRID_PADDING, GRID_PADDING, GRID_PADDING));
		gridWrapper.add(grid, BorderLayout.NORTH);

		JScrollPane scroll = new EditorScrollPane(
			gridWrapper, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(true);
		scroll.getViewport().setBackground(WorkspaceStyle.PANEL);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setPreferredSize(new Dimension(POPUP_WIDTH, popupHeight(gridWrapper, tileHeight)));

		content.add(scroll, BorderLayout.CENTER);

		if (searchable) {
			var field = EditorSearch.builder(searchPlaceholder).background(new Color(0x1A1A1A))
				.foreground(LABEL_COLOR).padding(7, 7).fieldHeight(24).build();
			JTextField search = field.field();
			content.putClientProperty("searchField", search);

			JPanel searchRow = new JPanel(new BorderLayout());
			searchRow.setOpaque(true);
			searchRow.setBackground(WorkspaceStyle.MENU);
			searchRow.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, SEARCH_BORDER), BorderFactory.createEmptyBorder(6, 6, 6, 6)));
			searchRow.add(field);
			if (caption != null) {
				var label = EditorText.builder(caption.toUpperCase(java.util.Locale.ROOT))
					.font(WorkspaceStyle.UI.deriveFont(10f)).color(WorkspaceStyle.DIM)
					.border(BorderFactory.createEmptyBorder(0, 0, 5, 0)).build();
				searchRow.add(label, BorderLayout.NORTH);
			}
			content.add(searchRow, BorderLayout.NORTH);

			search.getDocument().addDocumentListener(new DocumentListener() {
				@Override public void insertUpdate(DocumentEvent event) { filter(); }
				@Override public void removeUpdate(DocumentEvent event) { filter(); }
				@Override public void changedUpdate(DocumentEvent event) { filter(); }

				private void filter() {
					String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
					grid.removeAll();
					for (Tile tile : tiles) {
						if (query.isEmpty() || tile.searchKey.contains(query))
							grid.add(tile);
					}
					if (grid.getComponentCount() == 0)
						grid.add(EditorText.builder("No materials match.").color(WorkspaceStyle.DIM).build());
					scroll.getViewport().setViewPosition(new Point());
					grid.revalidate();
					scroll.getViewport().repaint();
					scroll.setPreferredSize(new Dimension(POPUP_WIDTH, popupHeight(gridWrapper, tileHeight)));
					content.revalidate();
				}
			});
		}

		return content;
	}

	private int popupHeight(JPanel gridWrapper, int tileHeight) {
		int wrapperHeight = gridWrapper.getPreferredSize().height;
		return Math.min(GRID_MAX_HEIGHT, Math.max(tileHeight + 2 * GRID_PADDING, wrapperHeight));
	}

	// ----- shared painting helpers -----

	private void paintThumbnail(Graphics2D g, T item, int size, int x, int y, JComponent observer) {
		Image image = item != null ? getPreview(item, size, observer) : null;
		Graphics2D clipped = (Graphics2D) g.create();
		clipped.clip(new RoundRectangle2D.Float(x, y, size, size, THUMB_RADIUS, THUMB_RADIUS));
		if (image != null)
			clipped.drawImage(image, x, y, size, size, this);
		else
			paintChecker(clipped, x, y, size);
		clipped.dispose();
		g.setColor(THUMB_INSET_BORDER);
		g.drawRoundRect(x, y, size - 1, size - 1, THUMB_RADIUS, THUMB_RADIUS);
	}

	private static void paintChecker(Graphics2D g, int x, int y, int size) {
		int cell = Math.max(2, Math.round(size * 8f / 64f));
		for (int ry = 0, row = 0; ry < size; ry += cell, row++) {
			for (int rx = 0, col = 0; rx < size; rx += cell, col++) {
				g.setColor((row + col) % 2 == 0 ? CHECKER_LIGHT : CHECKER_DARK);
				int w = Math.min(cell, size - rx);
				int h = Math.min(cell, size - ry);
				g.fillRect(x + rx, y + ry, w, h);
			}
		}
	}

	private Image getPreview(T item, int size, JComponent observer) {
		Map<T, Image> cache = previewCache.computeIfAbsent(size, key -> new java.util.LinkedHashMap<T, Image>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<T, Image> eldest) { return size() > 48; }
		});
		if (cache.containsKey(item))
			return cache.get(item);
		Image image = provider.preview(item, size, observer);
		if (image != null)
			cache.put(item, image);
		return image;
	}

	private static String ellipsize(FontMetrics metrics, String text, int maxWidth) {
		if (text == null)
			return "";
		if (maxWidth <= 0)
			return "";
		if (metrics.stringWidth(text) <= maxWidth)
			return text;
		String ellipsis = "...";
		int ellipsisWidth = metrics.stringWidth(ellipsis);
		if (ellipsisWidth > maxWidth)
			return "";
		int length = text.length();
		while (length > 0 && metrics.stringWidth(text.substring(0, length)) + ellipsisWidth > maxWidth)
			length--;
		return text.substring(0, length) + ellipsis;
	}

	/** One tile in the popup grid: thumbnail + name, with hover/selected states. */
	private final class Tile extends JComponent {
		private final T item;
		private final int thumbSize;
		private final String searchKey;
		private boolean hoverState;

		Tile(T item, int width, int height, int thumbSize, Runnable onSelect) {
			this.item = item;
			this.thumbSize = thumbSize;
			this.searchKey = searchable ? provider.searchKey(item).toLowerCase(java.util.Locale.ROOT) : "";
			setPreferredSize(new Dimension(width, height));
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText(provider.label(item));
			addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent event) { hoverState = true; repaint(); }
				@Override public void mouseExited(MouseEvent event) { hoverState = false; repaint(); }
				@Override public void mouseClicked(MouseEvent event) { onSelect.run(); }
			});
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			boolean selected = Objects.equals(item, value);
			int width = getWidth();
			int height = getHeight();
			if (selected) {
				g.setColor(TILE_SELECTED_BG);
				g.fillRoundRect(0, 0, width - 1, height - 1, TILE_RADIUS, TILE_RADIUS);
				g.setColor(WorkspaceStyle.ACCENT);
				g.drawRoundRect(0, 0, width - 1, height - 1, TILE_RADIUS, TILE_RADIUS);
			} else if (hoverState) {
				g.setColor(TILE_HOVER_BG);
				g.fillRoundRect(0, 0, width - 1, height - 1, TILE_RADIUS, TILE_RADIUS);
			}

			int thumbX = (width - thumbSize) / 2;
			int thumbY = TILE_PADDING;
			paintThumbnail(g, item, thumbSize, thumbX, thumbY, this);

			String label = provider.label(item);
			g.setFont(WorkspaceStyle.MONO.deriveFont(9f));
			g.setColor(selected ? Color.WHITE : LABEL_COLOR);
			FontMetrics metrics = g.getFontMetrics();
			int availableWidth = width - 2 * TILE_PADDING;
			String shown = ellipsize(metrics, label, availableWidth);
			int textWidth = metrics.stringWidth(shown);
			int textX = (width - textWidth) / 2;
			int textY = thumbY + thumbSize + TILE_LABEL_GAP + metrics.getAscent();
			g.drawString(shown, textX, textY);

			g.dispose();
		}
	}
}
