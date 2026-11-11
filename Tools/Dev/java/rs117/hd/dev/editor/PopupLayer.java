package rs117.hd.dev.editor;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import javax.swing.event.ChangeListener;

/** Shared placement/dismissal logic for every dropdown popup (material/texture pickers, ID pickers, swatch pickers). */
public final class PopupLayer {
	private static final int SHADOW_MARGIN = 20;
	private static final int SHADOW_OFFSET = 3;
	private static final int CORNER_ARC = 14;
	private static final int SHADOW_RADIUS = SHADOW_MARGIN - SHADOW_OFFSET;
	// Alpha only depends on spread/SHADOW_RADIUS, both compile-time constants, so this is shared by every popup.
	private static final Color[] SHADOW_COLORS = new Color[SHADOW_RADIUS];
	static {
		for (int spread = SHADOW_RADIUS; spread > 0; spread--) {
			float falloff = 1f - spread / (float) SHADOW_RADIUS;
			SHADOW_COLORS[spread - 1] = new Color(0, 0, 0, Math.round(22 * falloff * falloff));
		}
	}

	private PopupLayer() {}

	public static final class Handle {
		private final Runnable closeAction;
		private final java.util.List<Runnable> onClose = new java.util.ArrayList<>();
		private boolean closed;

		private Handle(Runnable closeAction) { this.closeAction = closeAction; }

		public void close() {
			if (closed)
				return;
			closed = true;
			closeAction.run();
			for (Runnable listener : onClose)
				listener.run();
		}

		public boolean isClosed() { return closed; }

		/** Called once, right after this popup closes for any reason (selection, Esc, outside click, window move/scroll). */
		public void onClose(Runnable listener) { onClose.add(listener); }
	}

	/** Shows {@code content} anchored below (or above, flipped) {@code trigger}, at least {@code minWidth} wide. */
	public static Handle show(JComponent trigger, JComponent content, int minWidth) {
		return show(trigger, content, minWidth, 0);
	}

	public static Handle show(JComponent trigger, JComponent content, int minWidth, int maxHeight) {
		return show(trigger, content, minWidth, maxHeight, 220);
	}

	public static Handle show(JComponent trigger, JComponent content, int minWidth, int maxHeight, int flipThreshold) {
		Window window = SwingUtilities.getWindowAncestor(trigger);
		if (!(window instanceof JFrame))
			throw new IllegalStateException("PopupLayer needs a JFrame ancestor");
		JFrame frame = (JFrame) window;
		JLayeredPane layered = frame.getLayeredPane();

		JPanel inner = new JPanel(new BorderLayout());
		inner.setOpaque(false);
		inner.add(content);
		JPanel card = new JPanel(new BorderLayout()) {
			private int shadowWidth = -1, shadowHeight = -1;
			private final RoundRectangle2D[] shadowShapes = new RoundRectangle2D[SHADOW_RADIUS];

			private RoundRectangle2D outline(double inset) {
				double edge = SHADOW_MARGIN + inset;
				double arc = CORNER_ARC - 2 * inset;
				return new RoundRectangle2D.Double(edge, edge, getWidth() - 2 * edge - 1,
					getHeight() - 2 * edge - 1, arc, arc);
			}

			private void ensureShadowShapes() {
				int width = getWidth(), height = getHeight();
				if (width == shadowWidth && height == shadowHeight)
					return;
				shadowWidth = width;
				shadowHeight = height;
				for (int spread = SHADOW_RADIUS; spread > 0; spread--)
					shadowShapes[spread - 1] = outline(-spread);
			}

			@Override protected void paintComponent(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				ensureShadowShapes();
				g.translate(0, SHADOW_OFFSET);
				for (int spread = SHADOW_RADIUS; spread > 0; spread--) {
					g.setColor(SHADOW_COLORS[spread - 1]);
					g.fill(shadowShapes[spread - 1]);
				}
				g.translate(0, -SHADOW_OFFSET);
				g.setColor(WorkspaceStyle.MENU);
				g.fill(outline(0));
				g.dispose();
			}

			@Override protected void paintChildren(Graphics graphics) {
				var g = (Graphics2D) graphics.create();
				g.clip(outline(0.5));
				super.paintChildren(g);
				g.dispose();

				g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(WorkspaceStyle.BORDER);
				g.draw(outline(0));
				g.dispose();
			}

			@Override public boolean contains(int x, int y) {
				return outline(0).contains(x, y);
			}
		};
		card.setOpaque(false);
		card.setBorder(BorderFactory.createEmptyBorder(SHADOW_MARGIN, SHADOW_MARGIN, SHADOW_MARGIN, SHADOW_MARGIN));
		card.add(inner);
		card.setFocusable(true);

		int width = Math.max(trigger.getWidth(), minWidth);
		int maxX = layered.getWidth() - 16 - width;
		if (maxX < 8) {
			width = Math.max(60, layered.getWidth() - 16);
			maxX = 8;
		}
		Point triggerTop = SwingUtilities.convertPoint(trigger, 0, 0, layered);
		int x = Math.max(8, Math.min(triggerTop.x, maxX));

		Dimension preferred = inner.getPreferredSize();
		int wanted = maxHeight > 0 ? Math.min(preferred.height, maxHeight) : preferred.height;
		int below = layered.getHeight() - (triggerTop.y + trigger.getHeight()) - 8;
		int above = triggerTop.y - 8;
		int y, height;
		if (below >= Math.min(wanted, flipThreshold) || below >= above) {
			y = triggerTop.y + trigger.getHeight() + 3;
			height = Math.max(60, Math.min(wanted, below + 8 - 3));
		} else {
			height = Math.max(60, Math.min(wanted, above));
			y = triggerTop.y - 3 - height;
		}

		card.setBounds(x - SHADOW_MARGIN, y - SHADOW_MARGIN, width + 2 * SHADOW_MARGIN, height + 2 * SHADOW_MARGIN);
		layered.add(card, JLayeredPane.POPUP_LAYER);
		layered.revalidate();
		layered.repaint();

		Handle[] handleRef = new Handle[1];

		AWTEventListener outsideClick = event -> {
			if (event.getID() != MouseEvent.MOUSE_PRESSED)
				return;
			MouseEvent mouse = (MouseEvent) event;
			Object source = mouse.getSource();
			if (!(source instanceof Component))
				return;
			Component component = (Component) source;
			var menu = component instanceof JPopupMenu ? (JPopupMenu) component
				: (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, component);
			while (menu != null) {
				var invoker = menu.getInvoker();
				if (invoker == null)
					break;
				if (SwingUtilities.isDescendingFrom(invoker, card))
					return;
				menu = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, invoker);
			}
			Point onCard = SwingUtilities.convertPoint(component, mouse.getPoint(), card);
			if (card.contains(onCard))
				return;
			Point onTrigger = SwingUtilities.convertPoint(component, mouse.getPoint(), trigger);
			if (trigger.contains(onTrigger))
				return;
			handleRef[0].close();
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(outsideClick, AWTEvent.MOUSE_EVENT_MASK);

		ActionListener escapeAction = event -> handleRef[0].close();
		card.registerKeyboardAction(escapeAction, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		ComponentAdapter windowTracking = new ComponentAdapter() {
			@Override public void componentMoved(ComponentEvent e) { handleRef[0].close(); }
			@Override public void componentResized(ComponentEvent e) { handleRef[0].close(); }
		};
		frame.addComponentListener(windowTracking);

		ChangeListener scrollTracking = e -> handleRef[0].close();
		JScrollPane enclosingScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, trigger);
		if (enclosingScroll != null)
			enclosingScroll.getViewport().addChangeListener(scrollTracking);

		HierarchyListener visibilityTracking = event -> {
			if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !trigger.isShowing())
				handleRef[0].close();
		};

		handleRef[0] = new Handle(() -> {
			Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClick);
			frame.removeComponentListener(windowTracking);
			trigger.removeHierarchyListener(visibilityTracking);
			if (enclosingScroll != null)
				enclosingScroll.getViewport().removeChangeListener(scrollTracking);
			layered.remove(card);
			layered.revalidate();
			layered.repaint();
		});
		trigger.addHierarchyListener(visibilityTracking);
		card.requestFocusInWindow();
		return handleRef[0];
	}

	/** Closes {@code handle} and runs {@code selection} — the common "pick a row/tile, then dismiss" pattern. */
	public static void select(Handle handle, Runnable selection) {
		handle.close();
		selection.run();
	}
}
