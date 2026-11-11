package rs117.hd.dev.sceneloader.standalone;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;

/** Keeps the editor's isolated alpha pass on the existing RLHD renderer. */
final class PreviewZoneAccess {
	private static final Field SCENE_MANAGER = field(ZoneRenderer.class, "sceneManager");
	private static final Field ZONES = field(WorldViewContext.class, "zones");
	private static final Field ALPHA_MODELS = field(Zone.class, "alphaModels");
	private static final Method IS_TEMP = method(Zone.AlphaModel.class, "isTemp");
	// Reused across calls instead of allocating a new list every frame; always drained before returning,
	// and rendering happens on a single thread, so there's no overlap between calls.
	private static final List<Zone.AlphaModel> hidden = new ArrayList<>();

	static void drawAlpha(ZoneRenderer renderer, Projection projection, Scene scene, int level, int x, int z) {
		try {
			var context = ((SceneManager) SCENE_MANAGER.get(renderer)).getContext(scene);
			if (context == null)
				return;
			var zone = ((Zone[][]) ZONES.get(context))[x][z];
			@SuppressWarnings("unchecked")
			var models = (List<Zone.AlphaModel>) ALPHA_MODELS.get(zone);
			boolean hasWater = zone.hasWater;
			try {
				for (var iterator = models.iterator(); iterator.hasNext();) {
					var model = iterator.next();
					if (!(boolean) IS_TEMP.invoke(model)) {
						hidden.add(model);
						iterator.remove();
					}
				}
				zone.hasWater = false;
				renderer.drawZoneAlpha(projection, scene, level, x, z);
			} finally {
				models.addAll(hidden);
				hidden.clear();
				zone.hasWater = hasWater;
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not isolate the preview zone", e);
		}
	}

	private static Field field(Class<?> owner, String name) {
		try {
			var field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not access " + name, e);
		}
	}

	private static Method method(Class<?> owner, String name) {
		try {
			var method = owner.getDeclaredMethod(name);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not access " + name, e);
		}
	}
}
