package rs117.hd.dev.definitions;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.dev.editor.EditorSession;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;

@Slf4j
public final class WaterPreview {
	private static final int WORLD_X = 1569;
	private static final int WORLD_Y = 2799;
	private static final float PITCH = .8f;

	private final EditorSession session;
	private final DefinitionsScreen screen;
	private final AtomicBoolean queuedFrame = new AtomicBoolean();
	private BuiltScene scene;
	private float focusX, focusY, focusZ;
	private DefinitionsScreen.WaterRequest applied, failed;
	private final java.util.Map<String, java.util.Optional<net.runelite.cache.region.Position>> locations = new java.util.HashMap<>();
	private net.runelite.cache.region.Position position;
	private boolean objects, isolated;
	private DefinitionsScreen.WaterCamera lastView;
	private long lastCapture;
	private boolean needsFrame;

	public WaterPreview(EditorSession session, DefinitionsScreen screen) {
		this.session = session;
		this.screen = screen;
	}

	public boolean render(HdRuntime runtime) {
		var request = screen.waterPreviewRequest();
		if (request == null)
			return false;
		if (request == failed)
			return true;

		try {
			boolean isolate = screen.isolateWater();
			boolean showObjects = screen.showWaterObjects() && !isolate;
			String source = request.originalName;
			net.runelite.cache.region.Position next;
			if (source == null) {
				next = new net.runelite.cache.region.Position(WORLD_X, WORLD_Y, 0);
			} else {
				if (!locations.containsKey(source)) {
					runtime.setPreviewWater(null, null);
					SwingUtilities.invokeLater(() -> screen.showWaterLocation(request, "Finding water…"));
					next = session.findWaterPlacement(source, runtime, () -> {
						var current = screen.waterPreviewRequest();
						return current != null && source.equals(current.originalName);
					});
					var current = screen.waterPreviewRequest();
					if (current == null || !source.equals(current.originalName))
						return true;
					locations.put(source, java.util.Optional.ofNullable(next));
				}
				next = locations.get(source).orElse(null);
			}
			boolean fallback = next == null;
			if (fallback)
				next = new net.runelite.cache.region.Position(WORLD_X, WORLD_Y, 0);
			String waterFilter = fallback ? null : source;
			boolean locationChanged = position == null || next.getX() != position.getX()
				|| next.getY() != position.getY() || next.getZ() != position.getZ();
			boolean installScene = locationChanged || showObjects != objects || isolate != isolated
				|| (applied != null && !java.util.Objects.equals(source, applied.originalName));
			if (installScene) {
				runtime.setPreviewWater(null, null);
				scene = session.buildTerrainPreview(next.getX(), next.getY(), showObjects);
				int x = next.getX() - scene.getBaseX(), y = next.getY() - scene.getBaseY();
				focusX = x * 128 + 64;
				focusY = scene.getTileHeights()[next.getZ()][x + BuiltScene.SCENE_OFFSET][y + BuiltScene.SCENE_OFFSET];
				focusZ = y * 128 + 64;
				if (isolate)
					runtime.isolatePreviewWater(scene, waterFilter);
				position = next;
				objects = showObjects;
				isolated = isolate;
			}
			if (installScene || request != applied || runtime.previewWaterNeedsRefresh()) {
				needsFrame = true;
				runtime.setPreviewModel(null, null, null);
				runtime.setPreviewOverride(null, null);
				boolean attachScene = installScene || applied == null || runtime.previewWaterNeedsRefresh();
				applied = request;
				runtime.setPreviewWater(request.json, waterFilter);
				if (attachScene)
					runtime.setScene(scene);
				runtime.setLevels(next.getZ(), true, false);
				runtime.setPreviewPass(true, false);
				String label = next.getX() + ", " + next.getY() + (next.getZ() > 0 ? " · Plane " + next.getZ() : "");
				String locationLabel = fallback ? label + " · Test site" : label;
				SwingUtilities.invokeLater(() -> screen.showWaterLocation(request, locationLabel));
			}
			var view = screen.waterCamera();
			long now = System.nanoTime();
			// Keep camera movement responsive and lower the capture rate for stationary water.
			if (queuedFrame.get() || (!needsFrame && view == lastView && now - lastCapture < 66_000_000L))
				return true;
			runtime.nextFrameTo(image -> {
				if (image == null || !queuedFrame.compareAndSet(false, true))
					return;
				lastCapture = now;
				lastView = view;
				needsFrame = false;
				SwingUtilities.invokeLater(() -> {
					try {
						screen.showWaterFrame(request, image, null);
					} finally {
						queuedFrame.set(false);
					}
				});
			});
			float[] camera = {focusX + view.east, focusY - 1450 / view.zoom, focusZ + view.north - 1400 / view.zoom};
			runtime.frame(camera, 0, PITCH);
		} catch (IOException | RuntimeException e) {
			log.warn("Could not render the Definitions water preview", e);
			failed = request;
			SwingUtilities.invokeLater(() -> screen.showWaterFrame(request, null, "Could not render water preview"));
		}
		return true;
	}

	public boolean detach(HdRuntime runtime) {
		failed = null;
		scene = null;
		position = null;
		lastView = null;
		if (applied == null)
			return false;
		runtime.nextFrameTo(null);
		runtime.setPreviewWater(null, null);
		applied = null;
		return true;
	}
}
