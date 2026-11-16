package rs117.hd.dev.definitions;

import java.io.IOException;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.dev.editor.EditorPreview;
import rs117.hd.dev.editor.EditorSession;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;

@Slf4j
public final class WaterPreview {
	private final EditorSession session;
	private final DefinitionsScreen screen;
	private final EditorPreview viewport;
	private BuiltScene scene;
	private float focusX, focusY, focusZ;
	private DefinitionsScreen.WaterRequest applied, failed;
	private final java.util.Map<String, java.util.Optional<net.runelite.cache.region.Position>> locations = new java.util.HashMap<>();
	private net.runelite.cache.region.Position position;
	private boolean objects, isolated;

	public WaterPreview(EditorSession session, DefinitionsScreen screen) {
		this.session = session;
		this.screen = screen;
		this.viewport = screen.waterViewport();
	}

	public boolean render(HdRuntime runtime) {
		var request = screen.waterPreviewRequest();
		if (request == null)
			return false;
		if (request == failed)
			return true;

		try {
			boolean isolate = screen.isolateWater();
			boolean showObjects = !isolate && viewport.objectsVisible();
			String source = request.originalName;
			net.runelite.cache.region.Position next;
			if (source == null) {
				next = viewport.location();
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
				next = viewport.location();
			String waterFilter = fallback ? null : source;
			boolean locationChanged = position == null || next.getX() != position.getX()
				|| next.getY() != position.getY() || next.getZ() != position.getZ();
			boolean installScene = locationChanged || showObjects != objects || isolate != isolated
				|| (applied != null && !java.util.Objects.equals(source, applied.originalName));
			if (installScene) {
				runtime.setPreviewWater(null, null);
				scene = viewport.loadScene(session, next, showObjects);
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
				runtime.setPreviewModel(null, null, null);
				runtime.setPreviewOverride(null, null);
				boolean attachScene = installScene || applied == null || runtime.previewWaterNeedsRefresh();
				applied = request;
				runtime.setPreviewWater(request.json, waterFilter);
				if (attachScene)
					runtime.setScene(scene);
				String label = next.getX() + ", " + next.getY() + (next.getZ() > 0 ? " · Plane " + next.getZ() : "");
				String locationLabel = fallback ? label + " · Test site" : label;
				SwingUtilities.invokeLater(() -> screen.showWaterLocation(request, locationLabel));
			}
			var view = viewport.camera().view();
			var camera = view.frame(focusX + view.x, focusY - 1450 / view.zoom, focusZ + view.z - 1400 / view.zoom);
			viewport.drawToEdt(runtime, next.getZ(), camera, image -> screen.showWaterFrame(request, image, null));
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
		if (applied == null)
			return false;
		runtime.nextFrameTo(null);
		runtime.setPreviewWater(null, null);
		viewport.pauseSceneClock();
		applied = null;
		return true;
	}
}
