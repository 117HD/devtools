package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;
import javax.swing.*;

/** A dockable panel that can also snap to, or float over, the workspace viewport. */
public final class FloatingDockWindow extends JPanel {
	public enum Dock { PANEL, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT_SIDE, RIGHT_SIDE, FLOATING }

	private static final int MIN_WIDTH = 200;
	private static final int MIN_HEIGHT = 130;
	private static final int VIEWER_MARGIN = 8;
	private static final int SNAP_DISTANCE = 56;
	private static final int RESIZE_HIT_SIZE = 8;

	private final JFrame frame;
	private final JComponent viewer;
	private final JPanel header = new JPanel(new net.miginfocom.swing.MigLayout(
		"insets 0, fill, gap 0, hidemode 3", "[]6[]6[grow,fill][]2[]2[]2[]", "[grow,center]"));
	private final JLabel statusDot = new JLabel(WorkspaceStyle.badge("", new Color(0x6CB348)));
	private final JLabel title;
	private final JLabel subtitle = new JLabel();
	private final JPanel body = new JPanel(new BorderLayout());
	private final JPanel headerArea = new JPanel(new BorderLayout());
	private final JPanel resizeBar = resizeHandle(Cursor.N_RESIZE_CURSOR);
	private final JPanel widthBar = resizeHandle(Cursor.E_RESIZE_CURSOR);

	private final JButton swapButton, floatButton, menuButton, closeButton;
	private Dock dock = Dock.PANEL;
	private WorkspaceDocks.Side panelSide = WorkspaceDocks.Side.RIGHT;
	private Point floatLocation;
	private int overlayTopInset;
	private int floatWidth = 280, floatHeight = 200;
	private int panelBodyHeight = 200;
	private Consumer<Dock> dockListener = d -> {};
	private Runnable hideListener = () -> {};
	private PopupLayer.Handle dockMenu;
	private Dock snapTarget = Dock.FLOATING;
	private final JComponent snapPreview = new JComponent() {
		@Override public boolean contains(int x, int y) { return false; }

		@Override protected void paintComponent(Graphics graphics) {
			var g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(71, 114, 179, 46));
			g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
			g.setColor(WorkspaceStyle.ACCENT);
			g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {5, 4}, 0));
			g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
			g.setFont(WorkspaceStyle.UI.deriveFont(11f));
			String label = snapTarget.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
			int w = g.getFontMetrics().stringWidth(label) + 16;
			int x = (getWidth() - w) / 2, y = (getHeight() - 22) / 2;
			g.fillRoundRect(x, y, w, 22, 6, 6);
			g.setColor(Color.WHITE);
			g.drawString(label, x + 8, y + 15);
			g.dispose();
		}
	};

	public FloatingDockWindow(JFrame frame, JComponent viewer, String titleText) {
		super(new BorderLayout());
		this.frame = frame;
		this.viewer = viewer;
		setBackground(WorkspaceStyle.PANEL);
		setBorder(BorderFactory.createLineBorder(WorkspaceStyle.BORDER, 1));

		title = new JLabel(titleText);
		title.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(12f));
		title.setForeground(WorkspaceStyle.TEXT);
		subtitle.setFont(WorkspaceStyle.MONO.deriveFont(10f));
		subtitle.setForeground(new Color(0x6F6F6F));

		var move = swapButton = headerButton("dock-swap-panel", "Move to the other panel");
		move.addActionListener(e -> setPanelDock(panelSide == WorkspaceDocks.Side.LEFT ? WorkspaceDocks.Side.RIGHT : WorkspaceDocks.Side.LEFT));
		var dockButton = menuButton = headerButton("dock-menu", "Choose where this docks");
		dockButton.addActionListener(e -> showDockMenu(dockButton));
		floatButton = headerButton("float-window", "Float");
		floatButton.addActionListener(e -> setDock(Dock.FLOATING));
		var hide = closeButton = headerButton("close", "Hide");
		hide.addActionListener(e -> hideListener.run());
		header.setOpaque(true);
		header.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
		header.add(statusDot, "cell 0 0, aligny center");
		header.add(title, "cell 1 0, aligny center");
		header.add(subtitle, "cell 2 0, width 0:pref, aligny center");
		header.add(move, "cell 3 0, aligny center");
		header.add(dockButton, "cell 4 0, aligny center");
		header.add(floatButton, "cell 5 0, aligny center");
		header.add(hide, "cell 6 0, aligny center");
		installDrag(header);
		installDrag(title);
		installDrag(subtitle);
		installDrag(statusDot);
		updateHeader();

		body.setOpaque(false);
		body.setPreferredSize(new Dimension(0, panelBodyHeight));

		resizeBar.setPreferredSize(new Dimension(0, RESIZE_HIT_SIZE));
		widthBar.setPreferredSize(new Dimension(RESIZE_HIT_SIZE, 0));
		installPanelResize();
		installWidthResize();

		headerArea.add(header);
		add(headerArea, BorderLayout.NORTH);
		add(body);
		updateResizeHandles();

		viewer.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) { layoutOverlay(); }
		});
	}

	public JComponent content() { return body; }

	public void setStatus(boolean live, String subtitleText) {
		statusDot.setIcon(WorkspaceStyle.badge("", live ? new Color(0x6CB348) : new Color(0x8A8A8A)));
		subtitle.setText(subtitleText);
		subtitle.setToolTipText(subtitleText.isEmpty() ? null : subtitleText);
	}

	public Dock getDock() { return dock; }
	public WorkspaceDocks.Side getPanelSide() { return panelSide; }

	private void setPanelDock(WorkspaceDocks.Side side) {
		panelSide = side;
		setDock(Dock.PANEL);
	}

	public void setDock(Dock next) {
		dock = next;
		updateHeader();
		updateResizeHandles();
		if (dockMenu != null)
			dockMenu.close();
		dockListener.accept(next);
		layoutOverlay();
	}

	public void onDockChanged(Consumer<Dock> listener) { dockListener = listener; }
	public void onHide(Runnable listener) { hideListener = listener; }

	public void setOverlayTopInset(int inset) {
		overlayTopInset = inset;
	}

	public void layoutOverlay() {
		if (dock == Dock.PANEL || getParent() != viewer)
			return;
		setBounds(overlayBounds(dock));
		if (viewer instanceof JLayeredPane)
			((JLayeredPane) viewer).moveToFront(this);
		revalidate();
		repaint();
	}

	private Rectangle overlayBounds(Dock position) {
		Rectangle area = new Rectangle(0, overlayTopInset, viewer.getWidth(), Math.max(0, viewer.getHeight() - overlayTopInset));
		int width = Math.max(MIN_WIDTH, Math.min(floatWidth, area.width - VIEWER_MARGIN * 2));
		int height = Math.max(MIN_HEIGHT, Math.min(floatHeight, area.height - VIEWER_MARGIN * 2));
		int x, y;
		switch (position) {
			case TOP_LEFT: x = area.x + VIEWER_MARGIN; y = area.y + VIEWER_MARGIN; break;
			case TOP_RIGHT: x = area.x + area.width - width - VIEWER_MARGIN; y = area.y + VIEWER_MARGIN; break;
			case BOTTOM_LEFT: x = area.x + VIEWER_MARGIN; y = area.y + area.height - height - VIEWER_MARGIN; break;
			case BOTTOM_RIGHT: x = area.x + area.width - width - VIEWER_MARGIN; y = area.y + area.height - height - VIEWER_MARGIN; break;
			case LEFT_SIDE: x = area.x + VIEWER_MARGIN; y = area.y + VIEWER_MARGIN; height = area.height - VIEWER_MARGIN * 2; break;
			case RIGHT_SIDE: x = area.x + area.width - width - VIEWER_MARGIN; y = area.y + VIEWER_MARGIN; height = area.height - VIEWER_MARGIN * 2; break;
			default:
				x = floatLocation == null ? (area.width - width) / 2 : floatLocation.x;
				y = floatLocation == null ? area.y + (area.height - height) / 2 : floatLocation.y;
				x = Math.max(VIEWER_MARGIN, Math.min(x, area.width - width - VIEWER_MARGIN));
				y = Math.max(area.y + VIEWER_MARGIN, Math.min(y, area.y + area.height - height - VIEWER_MARGIN));
		}
		return new Rectangle(x, y, width, height);
	}

	private void showSnapPreview(Dock target) {
		snapTarget = target;
		if (!(viewer instanceof JLayeredPane))
			return;
		if (target == Dock.FLOATING) {
			viewer.remove(snapPreview);
			viewer.repaint();
		} else {
			if (snapPreview.getParent() != viewer)
				((JLayeredPane) viewer).add(snapPreview, Integer.valueOf(JLayeredPane.MODAL_LAYER + 1));
			snapPreview.setBounds(overlayBounds(target));
			viewer.repaint();
		}
	}

	private void updateHeader() {
		boolean panel = dock == Dock.PANEL;
		header.setPreferredSize(new Dimension(0, panel ? 30 : 24));
		header.setBackground(panel ? WorkspaceStyle.HEADER : WorkspaceStyle.PANEL);
		title.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(panel ? 12f : 11f));
		swapButton.setVisible(panel);
		floatButton.setVisible(panel);
		menuButton.setVisible(!panel);
		menuButton.setIcon(WorkspaceStyle.icon(dock == Dock.FLOATING ? "dock-menu-floating" : "dock-menu", 13));
		closeButton.setPreferredSize(new Dimension(panel ? 22 : 20, panel ? 22 : 20));
		menuButton.setPreferredSize(new Dimension(20, 20));
		setBorder(panel ? BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x1A1A1A))
			: BorderFactory.createLineBorder(dock == Dock.FLOATING ? new Color(0x4A4A4A) : WorkspaceStyle.BORDER, 1, true));
	}

	private JButton headerButton(String icon, String tooltip) {
		var button = EditorButton.builder().style(WorkspaceStyle.Button.SEGMENT).icon(icon, 13).tooltip(tooltip).size(22, 22).build();
		return button;
	}

	private void installDrag(JComponent handle) {
		handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
		var drag = new MouseAdapter() {
			private Point pressed;
			private Point grabOffset;
			private boolean dragging;

			@Override public void mousePressed(MouseEvent e) {
				if (!SwingUtilities.isLeftMouseButton(e))
					return;
				pressed = e.getLocationOnScreen();
				grabOffset = SwingUtilities.convertPoint(handle, e.getPoint(), FloatingDockWindow.this);
			}

			@Override public void mouseDragged(MouseEvent e) {
				if (pressed == null || !dragging && pressed.distance(e.getLocationOnScreen()) < 5)
					return;
				dragging = true;
				var point = e.getLocationOnScreen();
				SwingUtilities.convertPointFromScreen(point, viewer);
				floatLocation = new Point(point.x - grabOffset.x, point.y - grabOffset.y);
				if (dock == Dock.FLOATING)
					layoutOverlay();
				else
					setDock(Dock.FLOATING);
				showSnapPreview(viewer.contains(point) ? spotFor(point) : Dock.FLOATING);
			}

			@Override public void mouseReleased(MouseEvent e) {
				if (dragging) {
					showSnapPreview(Dock.FLOATING);
					var point = e.getLocationOnScreen();
					SwingUtilities.convertPointFromScreen(point, viewer);
					if (point.x < 0)
						setPanelDock(WorkspaceDocks.Side.LEFT);
					else if (point.x >= viewer.getWidth())
						setPanelDock(WorkspaceDocks.Side.RIGHT);
					else
						setDock(spotFor(point));
				}
				pressed = null;
				dragging = false;
			}
		};
		handle.addMouseListener(drag);
		handle.addMouseMotionListener(drag);
	}

	private Dock spotFor(Point point) {
		boolean left = point.x < SNAP_DISTANCE;
		if (!left && point.x < viewer.getWidth() - SNAP_DISTANCE)
			return Dock.FLOATING;
		if (point.y < viewer.getHeight() / 3)
			return left ? Dock.TOP_LEFT : Dock.TOP_RIGHT;
		if (point.y >= viewer.getHeight() * 2 / 3)
			return left ? Dock.BOTTOM_LEFT : Dock.BOTTOM_RIGHT;
		return left ? Dock.LEFT_SIDE : Dock.RIGHT_SIDE;
	}

	private static JPanel resizeHandle(int cursorType) {
		var bar = new JPanel();
		bar.setBackground(WorkspaceStyle.MENU);
		bar.setCursor(Cursor.getPredefinedCursor(cursorType));
		bar.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) { bar.setBackground(WorkspaceStyle.ACCENT); }
			@Override public void mouseExited(MouseEvent e) { bar.setBackground(WorkspaceStyle.MENU); }
		});
		return bar;
	}

	private boolean rightAnchored() {
		return dock == Dock.TOP_RIGHT || dock == Dock.BOTTOM_RIGHT || dock == Dock.RIGHT_SIDE;
	}

	private boolean bottomAnchored() {
		return dock == Dock.BOTTOM_LEFT || dock == Dock.BOTTOM_RIGHT;
	}

	private void updateResizeHandles() {
		if (resizeBar.getParent() != null)
			resizeBar.getParent().remove(resizeBar);
		if (widthBar.getParent() != null)
			widthBar.getParent().remove(widthBar);

		if (bottomAnchored())
			headerArea.add(resizeBar, BorderLayout.NORTH);
		else if (dock != Dock.LEFT_SIDE && dock != Dock.RIGHT_SIDE)
			add(resizeBar, BorderLayout.SOUTH);
		if (dock != Dock.PANEL)
			add(widthBar, rightAnchored() ? BorderLayout.WEST : BorderLayout.EAST);
		revalidate();
		repaint();
	}

	private void installPanelResize() {
		var drag = new MouseAdapter() {
			private int startY, startHeight;

			@Override public void mousePressed(MouseEvent e) {
				startY = e.getYOnScreen();
				startHeight = dock == Dock.PANEL ? body.getHeight() : getHeight();
			}

			@Override public void mouseDragged(MouseEvent e) {
				int delta = e.getYOnScreen() - startY;
				if (dock != Dock.PANEL) {
					floatHeight = Math.max(MIN_HEIGHT, startHeight + (bottomAnchored() ? -delta : delta));
					layoutOverlay();
					return;
				}
				int next = Math.max(110, Math.min(startHeight + delta, frame.getHeight() * 60 / 100));
				panelBodyHeight = next;
				body.setPreferredSize(new Dimension(0, next));
				if (getParent() instanceof JSplitPane && ((JSplitPane) getParent()).getTopComponent() == FloatingDockWindow.this)
					((JSplitPane) getParent()).setDividerLocation(getPreferredSize().height);
				revalidate();
			}
		};
		resizeBar.addMouseListener(drag);
		resizeBar.addMouseMotionListener(drag);
	}

	private void installWidthResize() {
		var drag = new MouseAdapter() {
			private int startX, startWidth;

			@Override public void mousePressed(MouseEvent e) {
				startX = e.getXOnScreen();
				startWidth = getWidth();
			}

			@Override public void mouseDragged(MouseEvent e) {
				int delta = e.getXOnScreen() - startX;
				floatWidth = Math.max(MIN_WIDTH, startWidth + (rightAnchored() ? -delta : delta));
				layoutOverlay();
			}
		};
		widthBar.addMouseListener(drag);
		widthBar.addMouseMotionListener(drag);
	}

	private void showDockMenu(JComponent trigger) {
		var content = new JPanel(new BorderLayout(0, 6));
		content.setBackground(WorkspaceStyle.MENU);
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var caption = new JLabel("DOCK TO");
		caption.setFont(WorkspaceStyle.UI.deriveFont(10f));
		caption.setForeground(WorkspaceStyle.DIM);
		content.add(caption, BorderLayout.NORTH);

		var map = new JPanel(new BorderLayout(4, 0));
		map.setOpaque(false);
		map.setPreferredSize(new Dimension(150, 84));
		map.add(mapBar("Left panel", WorkspaceDocks.Side.LEFT), BorderLayout.WEST);
		map.add(mapInner(), BorderLayout.CENTER);
		map.add(mapBar("Right panel", WorkspaceDocks.Side.RIGHT), BorderLayout.EAST);
		content.add(map);

		var floatRow = EditorButton.builder().text("Float").icon("open-external", 13).onClick(() -> setDock(Dock.FLOATING)).build();
		var hint = new JLabel("<html><div style='width:150px'>Outer bars dock into the Browser / Properties panels. You can also drag the title bar there.</div></html>");
		hint.setFont(WorkspaceStyle.UI.deriveFont(10f));
		hint.setForeground(new Color(0x6F6F6F));
		var south = new JPanel(new BorderLayout(0, 6));
		south.setOpaque(false);
		south.add(floatRow, BorderLayout.NORTH);
		south.add(hint, BorderLayout.SOUTH);
		content.add(south, BorderLayout.SOUTH);

		dockMenu = PopupLayer.show(trigger, content, 168);
	}

	private JComponent mapBar(String tooltip, WorkspaceDocks.Side side) {
		var bar = dockTarget(tooltip, dock == Dock.PANEL && panelSide == side, () -> setPanelDock(side));
		bar.setPreferredSize(new Dimension(14, 84));
		return bar;
	}

	private JComponent mapInner() {
		var inner = new JPanel(new BorderLayout(2, 2));
		inner.setOpaque(false);
		var corners = new JPanel(new GridLayout(2, 2, 2, 2));
		corners.setOpaque(false);
		corners.add(spotButton("Top left", Dock.TOP_LEFT));
		corners.add(spotButton("Top right", Dock.TOP_RIGHT));
		corners.add(spotButton("Bottom left", Dock.BOTTOM_LEFT));
		corners.add(spotButton("Bottom right", Dock.BOTTOM_RIGHT));
		inner.add(spotButton("Left side", Dock.LEFT_SIDE), BorderLayout.WEST);
		inner.add(corners);
		inner.add(spotButton("Right side", Dock.RIGHT_SIDE), BorderLayout.EAST);
		return inner;
	}

	private JButton spotButton(String tooltip, Dock spot) {
		return dockTarget(tooltip, dock == spot, () -> setDock(spot));
	}

	private JButton dockTarget(String tooltip, boolean selected, Runnable action) {
		var button = new JButton() {
			@Override protected void paintComponent(Graphics g) {
				g.setColor(selected ? WorkspaceStyle.ACCENT : WorkspaceStyle.BORDER);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
			}
		};
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setFocusPainted(false);
		button.setToolTipText(tooltip);
		button.setPreferredSize(new Dimension(24, 16));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.addActionListener(e -> action.run());
		return button;
	}
}
