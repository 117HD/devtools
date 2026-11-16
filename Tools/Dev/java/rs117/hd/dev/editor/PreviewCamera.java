package rs117.hd.dev.editor;

import java.awt.Point;
import java.awt.event.*;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Swing input on the EDT and immutable camera snapshots for the renderer thread. */
public final class PreviewCamera {
	private enum Mode { ORBIT, FREECAM, PAN }

	@lombok.RequiredArgsConstructor
	public static final class View {
		public final double x, y, z, yaw, pitch, zoom;

		public Frame orbitAround(double x, double y, double z, double distance) {
			distance /= zoom;
			double horizontal = Math.cos(pitch) * distance;
			return frame(x + Math.sin(yaw) * horizontal, y - Math.sin(pitch) * distance,
				z - Math.cos(yaw) * horizontal);
		}

		public Frame frame(double x, double y, double z) {
			return frame(x, y, z, 1);
		}

		public Frame frame(double x, double y, double z, double projectionZoom) {
			return new Frame(new float[] {(float) x, (float) y, (float) z}, (float) yaw, (float) pitch, (float) projectionZoom);
		}

		private View move(double forward, double strafe, double lift) {
			double sin = Math.sin(yaw), cos = Math.cos(yaw);
			return new View(x - forward * sin + strafe * cos, y - lift, z + forward * cos + strafe * sin, yaw, pitch, zoom);
		}

		private View turn(double yawDelta, double pitchDelta, double limit) {
			return new View(x, y, z, yaw + yawDelta, clamp(pitch + pitchDelta, -limit, limit), zoom);
		}
	}

	@lombok.RequiredArgsConstructor
	public static final class Frame {
		final float[] position;
		final float yaw, pitch, projectionZoom;
	}

	private final Mode mode;
	private final View initial;
	private final double minZoom, maxZoom;
	private volatile View view;
	private final Set<Integer> keys = new HashSet<>();
	private final Timer movement = new Timer(16, event -> moveHeldKeys());
	private long lastMove;

	private PreviewCamera(Mode mode, View initial, double minZoom, double maxZoom) {
		this.mode = mode;
		this.initial = initial;
		this.minZoom = minZoom;
		this.maxZoom = maxZoom;
		view = initial;
	}

	static PreviewCamera orbit(double yaw, double pitch, double minZoom, double maxZoom) {
		return new PreviewCamera(Mode.ORBIT, new View(0, 0, 0, yaw, pitch, 1), minZoom, maxZoom);
	}

	static PreviewCamera freecam(double x, double y, double z, double yaw, double pitch) {
		return new PreviewCamera(Mode.FREECAM, new View(x, y, z, yaw, pitch, 1), .35, 4);
	}

	static PreviewCamera pan(double pitch, double minZoom, double maxZoom) {
		return new PreviewCamera(Mode.PAN, new View(0, 0, 0, 0, pitch, 1), minZoom, maxZoom);
	}

	public View view() { return view; }
	public View initialView() { return initial; }
	public synchronized void reset() { view = initial; }

	public synchronized void resetZoom() {
		var current = view;
		view = new View(current.x, current.y, current.z, current.yaw, current.pitch, initial.zoom);
	}

	public synchronized void drag(double dx, double dy) {
		var current = view;
		if (mode == Mode.PAN) {
			double scale = 4 / current.zoom;
			view = new View(clamp(current.x - dx * scale, -4096, 4096), current.y,
				clamp(current.z + dy * scale, -4096, 4096), current.yaw, current.pitch, current.zoom);
		} else {
			view = current.turn(-dx * .012, dy * .012, mode == Mode.FREECAM ? 1.50 : 1.35);
		}
	}

	public synchronized void scroll(double rotation) {
		var current = view;
		double zoom = clamp(current.zoom * Math.pow(mode == Mode.PAN ? 1.16 : 1.1, -rotation), minZoom, maxZoom);
		view = new View(current.x, current.y, current.z, current.yaw, current.pitch, zoom);
	}

	public void installControls(JComponent viewport, BooleanSupplier enabled, JComponent... overlays) {
		var mouse = new MouseAdapter() {
			private Point previous;

			@Override public void mousePressed(MouseEvent event) {
				if (!enabled.getAsBoolean())
					return;
				if (mode == Mode.FREECAM)
					viewport.requestFocusInWindow();
				if (SwingUtilities.isLeftMouseButton(event) || mode == Mode.FREECAM && SwingUtilities.isMiddleMouseButton(event)) {
					previous = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), viewport);
					if (mode == Mode.PAN)
						viewport.setCursor(WorkspaceStyle.cursor("orbit"));
				}
			}

			@Override public void mouseDragged(MouseEvent event) {
				if (previous == null || !enabled.getAsBoolean())
					return;
				Point current = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), viewport);
				drag(current.x - previous.x, current.y - previous.y);
				previous = current;
				viewport.setCursor(WorkspaceStyle.cursor("orbit"));
			}

			@Override public void mouseReleased(MouseEvent event) {
				if (previous == null)
					return;
				previous = null;
				viewport.setCursor(enabled.getAsBoolean() ? WorkspaceStyle.cursor("grab") : java.awt.Cursor.getDefaultCursor());
			}

			@Override public void mouseWheelMoved(MouseWheelEvent event) {
				if (!enabled.getAsBoolean())
					return;
				scroll(event.getPreciseWheelRotation());
				event.consume();
			}
		};
		installMouse(viewport, mouse);
		for (var overlay : overlays)
			installMouse(overlay, mouse);
		if (mode == Mode.FREECAM)
			installKeys(viewport, enabled);
	}

	private static void installMouse(JComponent component, MouseAdapter mouse) {
		component.addMouseListener(mouse);
		component.addMouseMotionListener(mouse);
		component.addMouseWheelListener(mouse);
	}

	private void installKeys(JComponent viewport, BooleanSupplier enabled) {
		viewport.setFocusable(true);
		viewport.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent event) { stopMoving(); }
		});
		viewport.addKeyListener(new KeyAdapter() {
			@Override public void keyPressed(KeyEvent event) {
				if (!enabled.getAsBoolean() || event.isControlDown() || event.isAltDown() || event.isMetaDown())
					return;
				switch (event.getKeyCode()) {
					case KeyEvent.VK_W: case KeyEvent.VK_A: case KeyEvent.VK_S: case KeyEvent.VK_D:
					case KeyEvent.VK_Q: case KeyEvent.VK_E: case KeyEvent.VK_R: case KeyEvent.VK_F:
					case KeyEvent.VK_SPACE: case KeyEvent.VK_SHIFT:
					case KeyEvent.VK_LEFT: case KeyEvent.VK_RIGHT: case KeyEvent.VK_UP: case KeyEvent.VK_DOWN:
						keys.add(event.getKeyCode());
						if (!movement.isRunning()) {
							lastMove = System.nanoTime();
							movement.start();
						}
						event.consume();
						break;
					default:
						break;
				}
			}

			@Override public void keyReleased(KeyEvent event) {
				if (keys.remove(event.getKeyCode())) {
					if (keys.isEmpty())
						movement.stop();
					event.consume();
				}
			}
		});
	}

	public void stopMoving() {
		keys.clear();
		movement.stop();
	}

	private void moveHeldKeys() {
		long now = System.nanoTime();
		double seconds = Math.min(.05, (now - lastMove) / 1e9);
		lastMove = now;
		int forward = axis(held(KeyEvent.VK_W), held(KeyEvent.VK_S));
		int strafe = axis(held(KeyEvent.VK_D), held(KeyEvent.VK_A));
		int lift = axis(held(KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_SPACE), held(KeyEvent.VK_Q, KeyEvent.VK_F, KeyEvent.VK_SHIFT));
		int turn = axis(held(KeyEvent.VK_LEFT), held(KeyEvent.VK_RIGHT));
		int tilt = axis(held(KeyEvent.VK_DOWN), held(KeyEvent.VK_UP));
		if (turn != 0 || tilt != 0)
			view = view.turn(turn * seconds * 1.8, tilt * seconds * 1.5, 1.50);
		if (forward != 0 || strafe != 0 || lift != 0) {
			double step = seconds * 1050 / Math.sqrt(forward * forward + strafe * strafe + lift * lift);
			view = view.move(forward * step, strafe * step, lift * step);
		}
	}

	private boolean held(int... codes) {
		for (int code : codes)
			if (keys.contains(code))
				return true;
		return false;
	}

	private static int axis(boolean positive, boolean negative) { return (positive ? 1 : 0) - (negative ? 1 : 0); }
	private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
