package rs117.hd.dev.editor;

import java.io.IOException;
import java.util.*;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.region.Position;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ExpressionPredicate;

import static net.runelite.api.Constants.SCENE_SIZE;

/** Keeps terrain and draft environments on the thread that owns the standalone renderer. */
@Slf4j
public final class EnvironmentPreview {
	private final EditorSession session;
	private final EnvironmentsScreen screen;
	private final EditorPreview viewport;
	private BuiltScene scene;
	private Position location;
	private Position requestedPosition;
	private boolean loadedObjects;
	private float anchorHeight;
	private Area requestedArea;
	private long locationRevision;
	private EnvironmentsScreen.PreviewRequest applied, failed;
	private Environment draft;

	EnvironmentPreview(EditorSession session, EnvironmentsScreen screen) {
		this.session = session;
		this.screen = screen;
		this.viewport = screen.viewport();
	}

	public void load(HdRuntime runtime, rs117.hd.scene.GamevalManager.Handle gamevals) {
		try {
			var document = new EnvironmentDocument(runtime.getGson());
			var varbits = index(runtime, gamevals.getVarbits(), session.varbitMaximums(), true);
			var varps = index(runtime, gamevals.getVarps(), Map.of(), false);
			SwingUtilities.invokeLater(() -> screen.load(document, varbits, varps));
		} catch (IOException | RuntimeException e) {
			log.warn("Could not load the Environments editor", e);
			SwingUtilities.invokeLater(() -> screen.loadFailed(e.getMessage()));
		}
	}

	private static Map<String, ConditionBuilder.Gameval> index(HdRuntime runtime, Map<String, Integer> names,
		Map<Integer, Integer> ranges, boolean varbit) {
		var result = new TreeMap<String, ConditionBuilder.Gameval>();
		names.forEach((name, id) -> {
			int min = varbit ? 0 : Integer.MIN_VALUE;
			int max = ranges.getOrDefault(id, Integer.MAX_VALUE);
			int value = Math.max(min, Math.min(max, runtime.sceneVariable(varbit, id)));
			result.put(name, new ConditionBuilder.Gameval(name, id, min, max, value));
		});
		return Collections.unmodifiableMap(result);
	}

	public boolean isActive() { return screen.previewRequest() != null; }

	public boolean render(HdRuntime runtime) {
		var request = screen.previewRequest();
		if (request == null)
			return false;
		if (request == failed)
			return true;
		try {
			boolean objects = viewport.objectsVisible();
			boolean reloaded = request.locationRevision != locationRevision;
			if (scene == null || reloaded || request.area != requestedArea || !Objects.equals(request.position, requestedPosition)) {
				var next = request.position == null ? EnvironmentsScreen.locationFor(request.area) : request.position;
				if (next == null)
					next = location == null || requestedPosition != null ? viewport.location() : location;
				if (scene == null || reloaded || !sameLocation(next, location) || !insideScene(next.getX(), next.getY())) {
					scene = viewport.loadScene(session, next, objects);
					loadedObjects = objects;
				}
				location = next;
				int x = next.getX() - scene.getBaseX() + BuiltScene.SCENE_OFFSET;
				int z = next.getY() - scene.getBaseY() + BuiltScene.SCENE_OFFSET;
				anchorHeight = scene.getTileHeights()[next.getZ()][x][z];
				requestedArea = request.area;
				requestedPosition = request.position;
				locationRevision = request.locationRevision;
				runtime.setPreviewModel(null, null, null);
				runtime.setPreviewOverride(null, null);
				runtime.setScene(scene);
				Position shown = location;
				SwingUtilities.invokeLater(() -> screen.previewLocation(request, shown));
				if (reloaded)
					SwingUtilities.invokeLater(() -> screen.previewReloaded(request));
			}
			if (request != applied) {
				if (applied == null || !request.json.equals(applied.json)) {
					var fields = new com.google.gson.JsonParser().parse(request.json).getAsJsonObject();
					var environment = runtime.getGson().fromJson(fields, Environment.class);
					if (fields.has("isOverworld"))
						environment.area = Area.ALL;
					environment.normalize();
					environment.area = Area.ALL;
					environment.instantTransition = true;
					environment.varbitCondition = environment.varpCondition = ExpressionPredicate.TRUE;
					draft = environment;
				}
				boolean fallback = request.testing && !request.matches;
				runtime.setPreviewEnvironment(fallback ? null : draft, fallback ? request.excludedArea : null, request.excludedIndex,
					request.varbits, request.varps);
				applied = request;
			}
			var view = viewport.camera().view();
			double worldX = location.getX() * 128.0 + 64 + view.x;
			double worldZ = location.getY() * 128.0 + 64 + view.z;
			int tileX = mapTile(worldX), tileZ = mapTile(worldZ);
			if (loadedObjects != objects || !insideScene(tileX, tileZ)) {
				var shown = new Position(tileX, tileZ, location.getZ());
				scene = viewport.loadScene(session, shown, objects);
				loadedObjects = objects;
				runtime.setScene(scene);
				SwingUtilities.invokeLater(() -> screen.previewLocation(request, shown));
			}
			float cameraX = (float) (worldX - scene.getBaseX() * 128.0);
			float cameraZ = (float) (worldZ - scene.getBaseY() * 128.0);
			// Keep the initial focal point on the selected area, then move it with the camera.
			runtime.setPreviewFocalPoint(cameraX, cameraZ - (float) viewport.camera().initialView().z);
			viewport.drawToEdt(runtime, location.getZ(), view.frame(cameraX, anchorHeight + view.y, cameraZ, view.zoom),
				image -> screen.showFrame(request, image, null));
		} catch (IOException | RuntimeException e) {
			failed = request;
			runtime.nextFrameTo(null);
			log.warn("Could not render environment {}", request.key, e);
			SwingUtilities.invokeLater(() -> screen.showFrame(request, null, "Could not render this area"));
		}
		return true;
	}

	private boolean insideScene(int x, int z) {
		int localX = x - scene.getBaseX(), localZ = z - scene.getBaseY();
		int margin = 16;
		return localX >= margin && localX < SCENE_SIZE - margin && localZ >= margin && localZ < SCENE_SIZE - margin;
	}

	private static int mapTile(double coordinate) {
		return Math.max(0, Math.min(16383, (int) Math.floor(coordinate / 128)));
	}

	private static boolean sameLocation(Position a, Position b) {
		return b != null && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
	}

	public boolean detach(HdRuntime runtime) {
		failed = null;
		if (scene == null)
			return false;
		scene = null;
		location = null;
		requestedArea = null;
		requestedPosition = null;
		draft = null;
		applied = null;
		runtime.nextFrameTo(null);
		runtime.setPreviewEnvironment(null, null, -1, Map.of(), Map.of());
		runtime.clearPreviewFocalPoint();
		viewport.pauseSceneClock();
		return true;
	}
}
