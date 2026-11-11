package rs117.hd.dev.editor;

import java.awt.Canvas;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.WorldView;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.scene.Definitions;
import rs117.hd.dev.sceneloader.scene.SceneBuilder;
import rs117.hd.dev.sceneloader.standalone.HdRuntime;
import rs117.hd.dev.sceneloader.standalone.HdSettings;
import rs117.hd.dev.sceneloader.standalone.Textures;

/** Owns the standalone editor's cache and OpenGL context for the lifetime of its window. */
@Slf4j
public final class EditorSession implements AutoCloseable {
	private final Canvas canvas = new Canvas();
	private final JWindow renderHost = new JWindow();
	private final CompletableFuture<Preview> preview = new CompletableFuture<>();
	private final Thread worker;
	private Definitions definitions;
	private Textures textures;
	private volatile CacheModels.Index modelCatalog;

	@FunctionalInterface
	public interface Preview {
		void run(HdRuntime runtime, BuiltScene scene) throws InterruptedException;
	}

	EditorSession(Consumer<String> progress, Consumer<EditorSession> onReady, Consumer<Exception> onFailure) {
		canvas.setIgnoreRepaint(true);
		renderHost.setFocusableWindowState(false);
		renderHost.add(canvas);
		renderHost.setBounds(-4096, -4096, 640, 560);
		renderHost.setVisible(true);
		worker = new Thread(() -> run(progress, onReady, onFailure), "117HD editor renderer");
		worker.start();
	}

	public int[] vanillaTexturePixels(int textureId) {
		return textures.get(textureId, 1.0);
	}

	public CacheModels.Index modelCatalog() {
		return modelCatalog;
	}

	/** Rebuilds the shared catalog on the thread that owns the cache. */
	public CacheModels.Index refreshModelCatalog(HdRuntime runtime) throws IOException {
		if (Thread.currentThread() != worker)
			throw new IllegalStateException("Cache refresh must run on the editor renderer thread");
		try (var gamevals = runtime.openGamevals()) {
			modelCatalog = CacheModels.index(definitions, gamevals);
			return modelCatalog;
		}
	}

	public net.runelite.cache.region.Location findObjectPlacement(int id, java.util.function.BooleanSupplier active) {
		return definitions.findObjectPlacement(id, active);
	}

	public BuiltScene buildPreviewScene(int x, int y, int id, net.runelite.cache.region.Location focus, boolean showOthers, boolean showAll) throws IOException {
		return new SceneBuilder(definitions).buildPreview(x, y, WorldView.TOPLEVEL, location -> {
			if (focus != null && location.getId() == id && location.getPosition().equals(focus.getPosition())
				&& location.getType() == focus.getType() && location.getOrientation() == focus.getOrientation())
				return false;
			return location.getId() == id ? showAll : showOthers;
		});
	}

	public net.runelite.cache.region.Position findWaterPlacement(String name, HdRuntime runtime,
		java.util.function.BooleanSupplier active) {
		return definitions.findWaterPlacement(name, runtime.tileOverrides(), runtime.terrain(), active);
	}

	public BuiltScene buildTerrainPreview(int x, int y, boolean showObjects) throws IOException {
		return new SceneBuilder(definitions).buildPreview(x, y, WorldView.TOPLEVEL, location -> showObjects);
	}

	public void clearPreviewCaches() {
		definitions.clearPreviewCaches();
	}

	void attachPreview(Preview task) {
		preview.complete(task);
	}

	@Override public void close() {
		worker.interrupt();
	}

	private void run(Consumer<String> progress, Consumer<EditorSession> onReady, Consumer<Exception> onFailure) {
		try {
			step(progress, "Opening the game cache");
			String cache = System.getProperty("rlhd.editor-cache");
			try (var opened = new Definitions(cache == null ? Definitions.findDefaultCache() : Path.of(cache), true)) {
				definitions = opened;
				var settings = new HdSettings();
				settings.set("daylightCycle", "DAY");
				settings.set("shadowMode", "OFF");
				settings.set("hideUnrelatedAreas", "false");
				textures = new Textures(opened);
				var runtime = new HdRuntime(canvas, opened, textures, settings);
				try {
					step(progress, "Starting the 117 HD renderer");
					runtime.setPreviewSky(0x2C2C2C);
					runtime.start();
					var scene = new BuiltScene(3200, 3200, WorldView.TOPLEVEL, new int[0]);
					runtime.setScene(scene);

					step(progress, "Warming up the preview");
					warmUp(runtime);

					step(progress, "Indexing NPCs, objects and graphics");
					refreshModelCatalog(runtime);
					step(progress, "Opening the editor");
					SwingUtilities.invokeLater(() -> {
						try {
							onReady.accept(this);
						} catch (RuntimeException e) {
							close();
							onFailure.accept(e);
						}
					});
					preview.get().run(runtime, scene);
				} finally {
					Thread.interrupted();
					runtime.stop();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			log.error("Could not start the standalone editor", e);
			SwingUtilities.invokeLater(() -> onFailure.accept(e));
		} finally {
			SwingUtilities.invokeLater(renderHost::dispose);
		}
	}

	private static void warmUp(HdRuntime runtime) throws IOException, InterruptedException {
		long deadline = System.nanoTime() + 30_000_000_000L;
		float[] camera = {52 * 128f + 200, -140, 52 * 128f - 200};
		int readyFrames = 0;
		while (readyFrames < 2) {
			runtime.frameModelPreview(camera, .55f, .30f);
			if (runtime.getPlugin().isPluginStopPending())
				throw new IOException("The 117 HD renderer stopped during startup");
			if (runtime.isReady())
				readyFrames++;
			else if (System.nanoTime() >= deadline)
				throw new IOException("Timed out waiting for the 117 HD renderer");
			Thread.sleep(8);
		}
	}

	private static void step(Consumer<String> progress, String message) {
		SwingUtilities.invokeLater(() -> progress.accept(message));
	}
}
