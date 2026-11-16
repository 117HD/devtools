package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Searchable multi-selection dialog. Only Done publishes the draft. */
public final class SelectionModal<T> {
	private boolean singleChoice;
	private static final Color SURFACE = new Color(0x262626);
	private static final Color FOOTER = new Color(0x222222);
	private static final Color DIVIDER = new Color(0x1E1E1E);
	private final String title;
	private final List<T> options;
	private final Set<T> original;
	private final Function<T, String> label;
	private Function<T, String> searchKey;
	private Function<String, T> rawOption = query -> null;
	private Function<T, String> nearby;
	private Consumer<T> hover = value -> {};
	private JComponent preview;
	private Function<T, JComponent> rowContent;
	private int rowHeight;
	private String selectionLabel = "IDs";
	private Function<T, String> rowMeta = value -> null;
	private String badgeLetter;
	private Color badgeColor;
	private String placeholder = "Search by name or ID";

	public SelectionModal(String title, List<T> options, Set<T> selected, Function<T, String> label) {
		this.title = title;
		this.options = options;
		this.original = new LinkedHashSet<>(selected);
		this.label = label;
		this.searchKey = label;
	}

	public SelectionModal<T> search(String placeholder, Function<T, String> searchKey) {
		this.placeholder = placeholder;
		this.searchKey = searchKey;
		return this;
	}

	public SelectionModal<T> rawOption(Function<String, T> factory) { rawOption = factory; return this; }
	public SelectionModal<T> nearby(Function<T, String> description) { nearby = description; return this; }

	public SelectionModal<T> singleChoice() { singleChoice = true; return this; }

	public SelectionModal<T> rowMeta(Function<T, String> description) { rowMeta = description; return this; }

	public SelectionModal<T> badge(String letter, Color color) {
		badgeLetter = letter;
		badgeColor = color;
		return this;
	}

	public SelectionModal<T> rows(int height, String selectionLabel, Function<T, JComponent> renderer) {
		rowHeight = height;
		this.selectionLabel = selectionLabel;
		rowContent = renderer;
		return this;
	}

	public SelectionModal<T> preview(JComponent component, Consumer<T> onHover) {
		preview = component;
		hover = onHover;
		return this;
	}

	public void open(JComponent anchor, Consumer<Set<T>> commit) {
		var window = SwingUtilities.getWindowAncestor(anchor);
		if (!(window instanceof JFrame))
			return;

		var owner = (JFrame) window;
		var draft = new LinkedHashSet<>(original);
		// options can be the entire NPC/object/graphic catalog (thousands of entries); precompute each
		// one's lowercase search key once instead of redoing it for every item on every keystroke.
		var searchKeysLower = new java.util.ArrayList<String>(options.size());
		for (T item : options)
			searchKeysLower.add(searchKey.apply(item).toLowerCase(Locale.ROOT));
		var content = panel(new BorderLayout());
		var titleRow = panel(new BorderLayout());
		titleRow.setPreferredSize(new Dimension(0, 45));
		titleRow.setBorder(sectionBorder(0, 16, 0, 10, false));
		if (badgeLetter != null) {
			var badge = new JLabel(badgeLetter, SwingConstants.CENTER) {
				@Override protected void paintComponent(Graphics graphics) {
					var g = (Graphics2D) graphics.create();
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setColor(badgeColor);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
					g.dispose();
					super.paintComponent(graphics);
				}
			};
			badge.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(10f));
			badge.setForeground(Color.WHITE);
			badge.setPreferredSize(new Dimension(16, 16));
			var badgeSlot = new JPanel(new GridBagLayout());
			badgeSlot.setOpaque(false);
			badgeSlot.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 9));
			badgeSlot.add(badge);
			titleRow.add(badgeSlot, BorderLayout.WEST);
		}
		titleRow.add(EditorText.builder(title).font(WorkspaceStyle.SEMIBOLD.deriveFont(13f)).build());
		Runnable close = () -> SwingUtilities.getWindowAncestor(content).dispose();
		var closeSlot = new JPanel(new GridBagLayout());
		closeSlot.setOpaque(false);
		closeSlot.add(EditorButton.builder().style(WorkspaceStyle.Button.TAB).icon("close", 10).size(24, 24).tooltip("Close").onClick(close).build());
		titleRow.add(closeSlot, BorderLayout.EAST);
		content.add(titleRow, BorderLayout.NORTH);

		var searchRow = EditorSearch.builder(placeholder).background(new Color(0x1A1A1A))
			.outline(WorkspaceStyle.ACCENT).gap(8).padding(8, 8).height(28).build();
		var search = searchRow.field();
		var results = EditorText.builder("").font(WorkspaceStyle.UI.deriveFont(11f)).color(new Color(0x7D7D7D)).build();
		var filters = panel(new BorderLayout(6, 8));
		filters.add(searchRow, BorderLayout.NORTH);
		filters.add(results, BorderLayout.EAST);

		var model = new DefaultListModel<T>();
		var list = new JList<T>(model) {
			@Override public String getToolTipText(MouseEvent event) {
				int index = rowAt(this, event);
				return rowContent != null && index >= 0 ? label.apply(model.get(index)) : null;
			}
		};
		if (rowContent != null)
			ToolTipManager.sharedInstance().registerComponent(list);
		list.setFixedCellHeight(rowHeight > 0 ? rowHeight : preview == null ? 32 : 34);
		list.setFixedCellWidth(1);
		list.setBackground(SURFACE);
		list.setCellRenderer((source, item, index, highlighted, focused) -> {
			var row = new JPanel(new BorderLayout(8, 0));
			row.setBackground(draft.contains(item) ? new Color(0x2A3446) : highlighted ? new Color(0x2E2E2E) : SURFACE);
			row.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
			boolean checked = draft.contains(item);
			var check = new JComponent() {
				@Override protected void paintComponent(Graphics g) {
					int y = (getHeight() - 14) / 2;
					g.setColor(checked ? WorkspaceStyle.ACCENT : new Color(0x555555));
					if (singleChoice) {
						g.fillOval(0, y, 14, 14);
						if (checked) {
							g.setColor(Color.WHITE);
							g.fillOval(4, y + 4, 6, 6);
						}
					} else if (checked) {
						g.fillRoundRect(0, y, 14, 14, 4, 4);
						g.setColor(Color.WHITE);
						g.drawLine(3, y + 7, 6, y + 10);
						g.drawLine(6, y + 10, 11, y + 4);
					} else {
						g.drawRoundRect(0, y, 13, 13, 4, 4);
					}
				}
			};
			check.setPreferredSize(new Dimension(14, 14));
			row.add(check, BorderLayout.WEST);
			if (rowContent != null) {
				row.add(rowContent.apply(item));
				return row;
			}
			boolean isRaw = item.equals(rawOption.apply(search.getText().strip()));
			row.add(EditorText.builder(isRaw ? "+ Add " + label.apply(item) + " (not in gamevals)" : label.apply(item))
				.font(WorkspaceStyle.MONO.deriveFont(12f)).build());
			if (isRaw)
				row.setBackground(new Color(0x2A3446));
			String tag = nearby == null ? null : nearby.apply(item);
			var meta = new JPanel(new BorderLayout(14, 0));
			meta.setOpaque(false);
			if (tag != null) {
				var badge = EditorText.builder(tag).font(WorkspaceStyle.SEMIBOLD.deriveFont(10f))
					.color(new Color(0x9FCF86)).build();
				badge.setOpaque(true);
				badge.setBackground(new Color(0x24331F));
				badge.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
				var badgeSlot = new JPanel(new GridBagLayout());
				badgeSlot.setOpaque(false);
				badgeSlot.add(badge);
				meta.add(badgeSlot, BorderLayout.WEST);
			}
			String details = rowMeta.apply(item);
			if (details != null) {
				var value = EditorText.builder(details).font(WorkspaceStyle.MONO.deriveFont(11f))
					.color(new Color(0x7D7D7D)).build();
				value.setHorizontalAlignment(SwingConstants.RIGHT);
				if (preview == null)
					value.setPreferredSize(new Dimension(52, 16));
				meta.add(value, BorderLayout.EAST);
			}
			row.add(meta, BorderLayout.EAST);
			return row;
		});
		var left = panel(new BorderLayout(0, 6));
		left.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
		left.add(filters, BorderLayout.NORTH);
		var resultsScroll = scroll(list);
		resultsScroll.getVerticalScrollBar().setUnitIncrement(list.getFixedCellHeight());
		left.add(resultsScroll);
		if (preview == null) {
			content.add(left);
		} else {
			var columns = panel(new GridLayout(1, 2, 8, 0));
			columns.add(left);
			columns.add(preview);
			content.add(columns);
		}

		var selected = new JPanel();
		selected.setLayout(new BoxLayout(selected, BoxLayout.X_AXIS));
		selected.setBackground(SURFACE);
		var count = EditorText.builder("").font(WorkspaceStyle.UI.deriveFont(11f)).color(new Color(0x9A9A9A)).build();
		var caption = EditorText.builder("").font(WorkspaceStyle.SEMIBOLD.deriveFont(10f))
			.color(new Color(0x7D7D7D)).build();
		Runnable[] refreshSelection = new Runnable[1];
		refreshSelection[0] = () -> {
			if (preview != null) {
				selected.removeAll();
				if (draft.isEmpty())
					selected.add(EditorText.builder("Nothing selected yet.").color(WorkspaceStyle.DIM).build());
				for (T item : draft) {
					if (selected.getComponentCount() > 0)
						selected.add(Box.createHorizontalStrut(4));
					selected.add(EditorButton.builder().text(label.apply(item) + "  ×").onClick(() -> {
						draft.remove(item);
						refreshSelection[0].run();
					}).build());
				}
				caption.setText("SELECTED · " + draft.size());
			}
			count.setText(draft.size() + " " + (preview == null ? selectionLabel : "areas") + " selected");
			selected.revalidate();
			selected.repaint();
			list.repaint();
		};
		refreshSelection[0].run();

		Runnable applyFilter = () -> {
			model.clear();
			String query = search.getText().strip();
			String lower = query.toLowerCase(Locale.ROOT);
			T raw = rawOption.apply(query);
			if (raw != null)
				model.addElement(raw);
			int matches = model.size();
			for (int i = 0; i < options.size(); i++) {
				if (searchKeysLower.get(i).contains(lower)) {
					model.addElement(options.get(i));
					matches++;
				}
			}
			resultsScroll.getViewport().setViewPosition(new Point());
			results.setText(matches == 0 ? "No matches." : matches + (matches == 1 ? " result" : " results"));
		};
		search.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { applyFilter.run(); }
			public void removeUpdate(DocumentEvent e) { applyFilter.run(); }
			public void changedUpdate(DocumentEvent e) { applyFilter.run(); }
		});
		applyFilter.run();

		list.addMouseMotionListener(new MouseMotionAdapter() {
			@Override public void mouseMoved(MouseEvent e) {
				int index = rowAt(list, e);
				if (index >= 0)
					hover.accept(model.get(index));
			}
		});
		Consumer<T> toggle = item -> {
			hover.accept(item);
			if (singleChoice) {
				draft.clear();
				draft.add(item);
			} else if (!draft.remove(item)) {
				draft.add(item);
			}
			if (item.equals(rawOption.apply(search.getText().strip())))
				search.setText("");
			refreshSelection[0].run();
		};
		list.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) { showRemoveMenu(e); }
			@Override public void mouseReleased(MouseEvent e) { showRemoveMenu(e); }

			private void showRemoveMenu(MouseEvent e) {
				if (rowContent == null || !e.isPopupTrigger())
					return;
				int index = rowAt(list, e);
				if (index < 0 || !draft.contains(model.get(index)))
					return;
				T item = model.get(index);
				new HdContextMenu("Area Edit")
					.danger("Remove", "trash", "", () -> {
						draft.remove(item);
						refreshSelection[0].run();
					}).show(list, e.getX(), e.getY());
			}

			@Override public void mouseClicked(MouseEvent e) {
				int index = rowAt(list, e);
				if (index >= 0 && SwingUtilities.isLeftMouseButton(e))
					toggle.accept(model.get(index));
			}
		});
		list.registerKeyboardAction(e -> {
			if (list.getSelectedValue() != null)
				toggle.accept(list.getSelectedValue());
		}, KeyStroke.getKeyStroke("SPACE"), JComponent.WHEN_FOCUSED);

		var footer = panel(new BorderLayout());
		if (preview != null) {
			var selectedStrip = scroll(selected);
			selectedStrip.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			selectedStrip.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
			selectedStrip.getViewport().setBackground(SURFACE);
			selectedStrip.setPreferredSize(new Dimension(0, 29));
			var selectionPanel = panel(new BorderLayout(0, 2));
			selectionPanel.setPreferredSize(new Dimension(0, 55));
			selectionPanel.setBorder(sectionBorder(8, 16, 3, 16, true));
			selectionPanel.add(caption, BorderLayout.NORTH);
			selectionPanel.add(selectedStrip);
			footer.add(selectionPanel, BorderLayout.NORTH);
		}
		var actionRow = panel(new BorderLayout());
		actionRow.setBackground(FOOTER);
		actionRow.setPreferredSize(new Dimension(0, 49));
		actionRow.setBorder(sectionBorder(0, 16, 0, 16, true));
		actionRow.add(count, BorderLayout.WEST);
		var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 10));
		actions.setOpaque(false);
		actions.add(EditorButton.builder().style(WorkspaceStyle.Button.SECONDARY)
			.text("Cancel").size(61, 28).onClick(close).build());
		var done = EditorButton.builder().style(WorkspaceStyle.Button.PRIMARY).text("Done").size(57, 28).onClick(() -> {
			close.run();
			if (!original.equals(draft))
				commit.accept(draft);
		}).build();
		actions.add(Box.createHorizontalStrut(8));
		done.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(11f));
		actions.add(done);
		actionRow.add(actions, BorderLayout.EAST);
		footer.add(actionRow, BorderLayout.SOUTH);
		content.add(footer, BorderLayout.SOUTH);
		search.addActionListener(e -> {});
		var dialog = EditorModal.builder(owner, title).content(content)
			.size(Math.min(preview == null ? 520 : 720, owner.getWidth() - 40),
				Math.min(preview == null ? 560 : 520, owner.getHeight() - 40))
			.initialFocus(search).open();
		dialog.getRootPane().setDefaultButton(done);
	}

	private static int rowAt(JList<?> list, MouseEvent event) {
		int index = list.locationToIndex(event.getPoint());
		return index >= 0 && list.getCellBounds(index, index).contains(event.getPoint()) ? index : -1;
	}

	private static JScrollPane scroll(JComponent component) {
		var scroll = new EditorScrollPane(component, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		return scroll;
	}

	private static javax.swing.border.Border sectionBorder(int top, int left, int bottom, int right, boolean topDivider) {
		return BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(topDivider ? 1 : 0, 0,
			topDivider ? 0 : 1, 0, DIVIDER), BorderFactory.createEmptyBorder(top, left, bottom, right));
	}

	private static JPanel panel(LayoutManager layout) {
		var panel = new JPanel(layout);
		panel.setBackground(SURFACE);
		return panel;
	}
}

