package rs117.hd.dev.editor;

import java.awt.Image;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;

/** Settings and rendering shared by the editor's OpenGL previews. Each viewport owns one instance. */
public final class EditorPreview {
	private final PreviewCamera camera;
	private final BooleanSupplier roofs, objects, animations, planesBelow;
	private final boolean sky;
	private final Position location;
	private volatile boolean capturePending;
	private int sceneCycle;
	private long lastSceneFrame;
	private long animationRemainder;

	private EditorPreview(Builder builder) {
		camera = java.util.Objects.requireNonNull(builder.camera, "Choose a preview camera mode");
		roofs = builder.roofs;
		objects = builder.objects;
		animations = builder.animations;
		sky = builder.sky;
		planesBelow = builder.planesBelow;
		location = builder.location;
	}

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private PreviewCamera camera;
		private BooleanSupplier roofs = () -> false;
		private BooleanSupplier objects = () -> true;
		private BooleanSupplier animations = () -> true;
		private BooleanSupplier planesBelow = () -> true;
		private boolean sky = true;
		private Position location = new Position(0, 0, 0);

		public Builder orbit(double yaw, double pitch, double minZoom, double maxZoom) {
			camera = PreviewCamera.orbit(yaw, pitch, minZoom, maxZoom);
			return this;
		}

		public Builder freecam(double x, double y, double z, double yaw, double pitch) {
			camera = PreviewCamera.freecam(x, y, z, yaw, pitch);
			return this;
		}

		public Builder pan(double pitch, double minZoom, double maxZoom) {
			camera = PreviewCamera.pan(pitch, minZoom, maxZoom);
			return this;
		}

		public Builder roofs(BooleanSupplier visible) { roofs = visible; return this; }
		public Builder objects(BooleanSupplier visible) { objects = visible; return this; }
		public Builder animations(BooleanSupplier enabled) { animations = enabled; return this; }
		public Builder sky(boolean visible) { sky = visible; return this; }
		public Builder planesBelow(BooleanSupplier visible) { planesBelow = visible; return this; }

		public Builder location(int x, int y, int plane) {
			location = new Position(x, y, plane);
			return this;
		}

		public EditorPreview build() { return new EditorPreview(this); }
	}

	public PreviewCamera camera() { return camera; }
	public Position location() { return location; }
	public boolean objectsVisible() { return objects.getAsBoolean(); }
	public boolean animationsEnabled() { return animations.getAsBoolean(); }

	public BuiltScene loadScene(EditorSession session, Position at, boolean includeObjects) throws IOException {
		return loadScene(session, at, object -> includeObjects);
	}

	public BuiltScene loadScene(EditorSession session, Position at, Predicate<Location> includes) throws IOException {
		return session.loadPreviewScene(at.getX(), at.getY(), includes);
	}

	public void draw(HdRuntime runtime, int plane, PreviewCamera.Frame frame, boolean modelOnly, boolean wireframe,
		Consumer<Image> capture) {
		runtime.setLevels(plane, planesBelow.getAsBoolean(), roofs.getAsBoolean());
		runtime.setPreviewPass(sky, wireframe);
		runtime.nextFrameTo(capture);
		if (modelOnly) {
			runtime.frameModelPreview(frame.position, frame.yaw, frame.pitch);
		} else {
			runtime.framePreviewScene(frame.position, frame.yaw, frame.pitch, advanceSceneClock(), frame.projectionZoom);
		}
	}

	public void drawToEdt(HdRuntime runtime, int plane, PreviewCamera.Frame frame, Consumer<Image> display) {
		draw(runtime, plane, frame, false, false, capturePending ? null : image -> {
			if (image == null)
				return;
			capturePending = true;
			SwingUtilities.invokeLater(() -> {
				try {
					display.accept(image);
				} finally {
					capturePending = false;
				}
			});
		});
	}

	public void pauseSceneClock() {
		lastSceneFrame = 0;
	}

	private int advanceSceneClock() {
		long now = System.nanoTime();
		if (lastSceneFrame != 0 && animationsEnabled())
			animationRemainder += Math.min(now - lastSceneFrame, 5 * EditorSession.PREVIEW_FRAME_NANOS);
		lastSceneFrame = now;
		sceneCycle += (int) (animationRemainder / EditorSession.PREVIEW_FRAME_NANOS);
		animationRemainder %= EditorSession.PREVIEW_FRAME_NANOS;
		return sceneCycle;
	}
}
