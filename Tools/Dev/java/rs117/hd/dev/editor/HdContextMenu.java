package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;

public final class HdContextMenu extends JPopupMenu {
	private static final int MAX_CAPTION_WIDTH = 236;
	private static final Font LABEL_FONT = WorkspaceStyle.UI.deriveFont(12f);
	private static final Font SHORTCUT_FONT = WorkspaceStyle.MONO.deriveFont(10f);
	private static final Color SURFACE = new Color(0x262626);
	private static final Color ACTIVE = new Color(0x334D73);
	private static final Color BORDER = new Color(0x3A3A3A);

	public HdContextMenu(String caption) {
		this(null, null, caption);
	}

	public HdContextMenu(String badge, Color color, String caption) {
		stylePopup(this);
		AWTEventListener closeOnScroll = event -> {
			if (event instanceof MouseWheelEvent)
				MenuSelectionManager.defaultManager().clearSelectedPath();
		};
		addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
				Toolkit.getDefaultToolkit().addAWTEventListener(closeOnScroll, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
			}

			@Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
				Toolkit.getDefaultToolkit().removeAWTEventListener(closeOnScroll);
			}

			@Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
				Toolkit.getDefaultToolkit().removeAWTEventListener(closeOnScroll);
			}
		});
		var title = EditorText.builder(caption).font(WorkspaceStyle.MEDIUM.deriveFont(10f))
			.color(new Color(0x9A9A9A)).build();
		if (badge != null)
			title.setIcon(WorkspaceStyle.badge(badge, color));
		title.setToolTipText(caption);
		title.setIconTextGap(8);
		title.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x1E1E1E)),
			BorderFactory.createEmptyBorder(5, 8, 6, 8)));
		title.setPreferredSize(new Dimension(Math.min(title.getPreferredSize().width, MAX_CAPTION_WIDTH), 26));
		add(title);
	}

	public static void install(JComponent target, Consumer<Point> open) {
		target.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) { handle(event); }
			@Override public void mouseReleased(MouseEvent event) { handle(event); }

			private void handle(MouseEvent event) {
				if (event.isPopupTrigger()) {
					event.consume();
					open.accept(event.getPoint());
				}
			}
		});
		for (var key : new KeyStroke[] { KeyStroke.getKeyStroke("shift F10"), KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0) })
			target.registerKeyboardAction(e -> open.accept(null), key, JComponent.WHEN_FOCUSED);
	}

	public HdContextMenu item(String label, String icon, String key, Runnable action) {
		return row(label, icon, key, false, null, action);
	}

	public HdContextMenu check(String label, String icon, boolean selected, Consumer<Boolean> change) {
		var item = new JCheckBoxMenuItem(label, icon(icon), selected);
		item.setUI(new BasicMenuItemUI() {
			@Override protected void doClick(MenuSelectionManager manager) {
				item.doClick(0);
			}

			@Override public void paint(Graphics graphics, JComponent component) {
				paintRow(graphics, item, item.isSelected() ? "✓" : "", false);
			}
		});
		configure(item, "✓");
		item.addActionListener(event -> {
			change.accept(item.isSelected());
			item.repaint();
		});
		add(item);
		return this;
	}

	public HdContextMenu disabled(String label, String icon, String reason) {
		return row(label, icon, "", false, reason, null);
	}

	public HdContextMenu danger(String label, String icon, String key, Runnable action) {
		return row(label, icon, key, true, null, action);
	}

	public HdContextMenu separator() {
		if (getComponentCount() > 1 && !(getComponent(getComponentCount() - 1) instanceof JSeparator)) {
			var line = new JSeparator();
			line.setForeground(new Color(0x333333));
			line.setBackground(SURFACE);
			line.setPreferredSize(new Dimension(1, 8));
			add(line);
		}
		return this;
	}

	private HdContextMenu row(String label, String icon, String key, boolean danger, String reason, Runnable action) {
		var item = new JMenuItem(label, icon(icon));
		item.setUI(new BasicMenuItemUI() {
			@Override public void paint(Graphics graphics, JComponent component) {
				paintRow(graphics, item, key, danger);
			}
		});
		configure(item, key);
		item.setEnabled(action != null);
		item.setToolTipText(reason);
		if (action != null)
			item.addActionListener(e -> action.run());
		add(item);
		return this;
	}

	public HdContextMenu submenu(String label, String icon, Consumer<JMenu> entries) {
		var menu = new JMenu(label);
		menu.setIcon(icon(icon));
		menu.setDelay(150);
		menu.setUI(new BasicMenuUI() {
			@Override public void paint(Graphics graphics, JComponent component) {
				paintRow(graphics, menu, "›", false);
			}
		});
		configure(menu, "›");
		stylePopup(menu.getPopupMenu());
		menu.getPopupMenu().setOpaque(true);
		entries.accept(menu);
		menu.setEnabled(menu.getItemCount() > 0);
		add(menu);
		return this;
	}

	public static void subItem(JMenu menu, String label, Icon icon, Runnable action) {
		var item = new JMenuItem(label, icon);
		item.setUI(new BasicMenuItemUI() {
			@Override public void paint(Graphics graphics, JComponent component) {
				paintRow(graphics, item, "", false);
			}
		});
		configure(item, "");
		item.addActionListener(e -> action.run());
		menu.add(item);
	}

	@Override public void show(Component invoker, int x, int y) {
		if (getComponent(getComponentCount() - 1) instanceof JSeparator)
			remove(getComponentCount() - 1);
		var root = SwingUtilities.getRootPane(invoker);
		var window = SwingUtilities.getWindowAncestor(invoker);
		if (window instanceof JDialog && window.getOwner() instanceof RootPaneContainer)
			root = ((RootPaneContainer) window.getOwner()).getRootPane();
		if (root != null) {
			Point origin = SwingUtilities.convertPoint(invoker, x, y, root);
			Dimension size = getPreferredSize();
			int left = origin.x + size.width > root.getWidth() - 8 ? origin.x - size.width : origin.x;
			int top = origin.y + size.height > root.getHeight() - 8 ? origin.y - size.height : origin.y;
			origin.setLocation(Math.max(8, left), Math.max(8, top));
			var local = SwingUtilities.convertPoint(root, origin, invoker);
			x = local.x;
			y = local.y;
		}
		super.show(invoker, x, y);
	}

	@Override protected void paintComponent(Graphics graphics) {
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(SURFACE);
		g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
		g.setColor(BORDER);
		g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
		g.dispose();
	}

	private static Icon icon(String name) { return WorkspaceStyle.icon("context/" + name, 14); }

	private static void stylePopup(JPopupMenu menu) {
		menu.setBackground(SURFACE);
		menu.setOpaque(false);
		menu.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
	}

	private static void configure(JMenuItem item, String shortcut) {
		item.setOpaque(false);
		int labelWidth = item.getFontMetrics(LABEL_FONT).stringWidth(item.getText());
		int shortcutWidth = shortcut.isEmpty() ? 0 : 12 + item.getFontMetrics(SHORTCUT_FONT).stringWidth(shortcut);
		item.setPreferredSize(new Dimension(42 + labelWidth + shortcutWidth, 26));
	}

	private static void paintRow(Graphics graphics, JMenuItem item, String key, boolean danger) {
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		boolean enabled = item.isEnabled();
		boolean active = enabled && (item.isArmed() || item.isSelected());
		if (active) {
			g.setColor(danger ? new Color(0x3A2626) : ACTIVE);
			g.fillRoundRect(0, 0, item.getWidth(), item.getHeight(), 8, 8);
		}
		if (item.getIcon() != null) {
			g.setComposite(AlphaComposite.SrcOver.derive(enabled ? .85f : .5f));
			item.getIcon().paintIcon(item, g, 8, 6);
			g.setComposite(AlphaComposite.SrcOver);
		}
		g.setFont(SHORTCUT_FONT);
		int shortcutX = item.getWidth() - 8 - g.getFontMetrics().stringWidth(key);
		g.setColor(!enabled ? new Color(0x4A4A4A) : active ? new Color(0xCFE0FF) : new Color(0x6F6F6F));
		g.drawString(key, shortcutX, 17);
		g.setFont(LABEL_FONT);
		g.setColor(!enabled ? new Color(0x5A5A5A) : danger ? new Color(active ? 0xFFB0B0 : 0xE8A0A0)
			: active ? Color.WHITE : new Color(0xD6D6D6));
		var metrics = g.getFontMetrics();
		String label = item.getText();
		int available = shortcutX - 34;
		if (metrics.stringWidth(label) > available) {
			while (!label.isEmpty() && metrics.stringWidth(label + "…") > available)
				label = label.substring(0, label.length() - 1);
			label += "…";
		}
		g.drawString(label, 30, 17);
		g.dispose();
	}
}
