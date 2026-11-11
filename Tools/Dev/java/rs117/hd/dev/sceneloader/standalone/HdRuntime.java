package rs117.hd.dev.sceneloader.standalone;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import java.awt.Canvas;
import java.awt.Image;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Projection;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.overlay.OverlayManager;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.dev.sceneloader.scene.BuiltScene;
import rs117.hd.dev.sceneloader.scene.Definitions;
import rs117.hd.renderer.Renderer;
import rs117.hd.renderer.zone.ZoneRenderer;

import static net.runelite.api.Constants.MAX_Z;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import rs117.hd.dev.sceneloader.standalone.client.CacheTextureProvider;
import rs117.hd.dev.sceneloader.standalone.client.Fake;
import rs117.hd.dev.sceneloader.standalone.client.FakeClient;
import rs117.hd.dev.sceneloader.standalone.client.FakeObjects;

@Slf4j
public final class HdRuntime {
	private final Canvas canvas;
	private final Definitions defs;
	private final Textures textures;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		var thread = new Thread(runnable, "117 HD standalone");
		thread.setDaemon(true);
		return thread;
	});

	private final FakeClient clientState;
	private final Client client;
	private final InlineClientThread clientThread = new InlineClientThread();
	private final EventBus eventBus = new EventBus();
	private final PreviewDrawManager drawManager = new PreviewDrawManager();
	private final HdSettings settings;

	private Injector injector;
	private HdPlugin plugin;

	@Nullable
	private BuiltScene scene;
	@Nullable
	private Renderer handedTo;

	private int level;
	private boolean planesBelow = true;
	private boolean showRoofs;
	private Set<Integer> roofIds = Set.of();

	public HdRuntime(Canvas canvas, Definitions defs, Textures textures, HdSettings settings) {
		this.canvas = canvas;
		this.defs = defs;
		this.textures = textures;
		this.settings = settings;

		var provider = new CacheTextureProvider(defs, textures);
		clientState = new FakeClient(canvas, provider.asProvider(), new FakeObjects(defs));
		client = Fake.of(Client.class, clientState);
	}

	public HdPlugin getPlugin() {
		return plugin;
	}

	public rs117.hd.scene.GamevalManager.Handle openGamevals() {
		return injector.getInstance(rs117.hd.scene.GamevalManager.class).obtainHandle();
	}

	public Gson getGson() {
		return rs117.hd.utils.GsonUtils.wrap(injector.getInstance(Gson.class));
	}

	public FrameTimer frameTimer() {
		return injector.getInstance(FrameTimer.class);
	}

	public void nextFrameTo(Consumer<Image> whenDrawn) {
		drawManager.previewFrame = whenDrawn;
	}

	/** Preview captures run on the renderer thread. Replace a request if its draw was skipped. */
	private static final class PreviewDrawManager extends DrawManager {
		private Consumer<Image> previewFrame;

		@Override
		public void processDrawComplete(java.util.function.Supplier<Image> capture) {
			var listener = previewFrame;
			previewFrame = null;
			if (listener != null)
				requestNextFrameListener(listener);
			super.processDrawComplete(capture);
		}
	}

	public boolean isReady() {
		return plugin != null && plugin.renderer != null && clientThread.watchedFinished();
	}

	public void setLevels(int level, boolean planesBelow, boolean showRoofs) {
		this.level = Math.max(0, Math.min(MAX_Z - 1, level));
		this.planesBelow = planesBelow;
		this.showRoofs = showRoofs;
	}

	public void start() throws Exception {
		Thread owner = Thread.currentThread();
		clientState.ownedBy(owner);
		clientThread.ownedBy(owner);

		plugin = previewSkyEnabled ? new HdPlugin() {
			@Override public void drawUi(int overlayColor) {
				if (previewWireframe)
					polygonMode(org.lwjgl.opengl.GL11.GL_FILL);
				super.drawUi(overlayColor);
			}
		} : new HdPlugin();
		injector = Guice.createInjector(new Bindings());
		injector.injectMembers(plugin);

		var injectorField = Plugin.class.getDeclaredField("injector");
		injectorField.setAccessible(true);
		injectorField.set(plugin, injector);

		eventBus.register(plugin);
		settings.whenChanged(this::settingChanged);

		var startUp = HdPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		clientThread.watchNext();
		startUp.invoke(plugin);

		eventBus.post(gameStateChanged(GameState.LOGGED_IN));
	}

	private void settingChanged(String key) {
		var event = new ConfigChanged();
		event.setGroup(HdPluginConfig.CONFIG_GROUP);
		event.setKey(key);
		eventBus.post(event);
	}

	public void stop() {
		if (plugin == null)
			return;
		try {
			var shutDown = HdPlugin.class.getDeclaredMethod("shutDown");
			shutDown.setAccessible(true);
			shutDown.invoke(plugin);
		} catch (Exception ex) {
			log.warn("Could not stop the plugin cleanly", ex);
		}
		executor.shutdownNow();
	}

	public void setScene(@Nullable BuiltScene scene) {
		this.scene = scene;
		handedTo = null;
		roofIds = scene == null ? Set.of() : roofIdsOf(scene);
		clientState.setScene(scene);
	}

	/** Swap out the top-level renderer's scene so its terrain buffers can be released. */
	public void releasePreviewScene(BuiltScene empty) {
		nextFrameTo(null);
		setPreviewModel(null, null, null);
		setPreviewOverride(null, null);
		setScene(empty);
		if (isReady()) {
			var renderer = plugin.renderer;
			handOver(renderer, empty);
			handedTo = renderer;
		}
	}

	private final PreviewTerrain previewTerrain = new PreviewTerrain();
	private rs117.hd.scene.water_types.WaterType[] previewWaterTypes;

	private static final class PreviewTerrain extends rs117.hd.scene.ProceduralGenerator {
		volatile rs117.hd.scene.water_types.WaterType water;
		String sourceName;

		@Override public rs117.hd.scene.water_types.WaterType seasonalWaterType(
			rs117.hd.scene.tile_overrides.TileOverride override, int textureId
		) {
			var original = super.seasonalWaterType(override, textureId);
			var selected = water;
			return selected == null || original == null || original == rs117.hd.scene.water_types.WaterType.NONE
				|| (sourceName != null && !sourceName.equals(original.name)) ? original : selected;
		}
	}

	public rs117.hd.scene.TileOverrideManager tileOverrides() {
		return injector.getInstance(rs117.hd.scene.TileOverrideManager.class);
	}

	public rs117.hd.scene.ProceduralGenerator terrain() { return previewTerrain; }

	public void setPreviewWater(String json, String sourceName) {
		var manager = injector.getInstance(rs117.hd.scene.WaterTypeManager.class);
		// The existing uniform buffer retains this array. Reuse a water slot
		// while the Definitions scene is active so drafts also work before saving.
		if (previewWaterTypes != null) {
			if (previewWaterTypes == rs117.hd.scene.WaterTypeManager.WATER_TYPES)
				manager.uboWaterTypes.update();
			previewWaterTypes = null;
		}
		previewTerrain.water = null;
		if (json == null)
			return;
		var selected = getGson().fromJson(json, rs117.hd.scene.water_types.WaterType.class);
		var types = rs117.hd.scene.WaterTypeManager.WATER_TYPES;
		if (types.length < 2)
			throw new IllegalStateException("Water types are unavailable");
		// Keep the slot stable when switching definitions so existing terrain vertices stay valid.
		int index = 1;
		if (sourceName != null) {
			for (int i = 1; i < types.length; i++) {
				if (sourceName.equals(types[i].name)) {
					index = i;
					break;
				}
			}
		}
		var materials = injector.getInstance(rs117.hd.scene.MaterialManager.class);
		selected.normalize(index, materials.getMaterial("WATER_NORMAL_MAP_1"));
		previewWaterTypes = types;
		var original = types[index];
		types[index] = selected;
		try {
			manager.uboWaterTypes.update();
		} finally {
			types[index] = original;
		}
		previewTerrain.sourceName = sourceName;
		previewTerrain.water = selected;
	}

	public void isolatePreviewWater(BuiltScene scene, String sourceName) {
		var overrides = tileOverrides();
		var tiles = scene.getExtendedTiles();
		int[] position = new int[3];
		int[] ids = new int[2];
		for (int plane = 0; plane < tiles.length; plane++) {
			position[2] = plane;
			for (int x = 0; x < tiles[plane].length; x++) {
				position[0] = scene.getBaseX() + x - BuiltScene.SCENE_OFFSET;
				for (int y = 0; y < tiles[plane][x].length; y++) {
					var tile = tiles[plane][x][y];
					if (tile == null)
						continue;
					position[1] = scene.getBaseY() + y - BuiltScene.SCENE_OFFSET;
					ids[0] = rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG | scene.getOverlayIds()[plane][x][y];
					ids[1] = scene.getUnderlayIds()[plane][x][y];
					var paint = tile.getSceneTilePaint();
					var model = tile.getSceneTileModel();
					var rule = overrides.getOverrideBeforeReplacements(position, ids);
					boolean keep = matchesPreviewWater(rule, paint == null ? -1 : paint.getTexture(), sourceName);
					if (!keep && model != null && model.getTriangleTextureId() != null)
						for (int texture : model.getTriangleTextureId())
							if (matchesPreviewWater(rs117.hd.scene.tile_overrides.TileOverride.NONE, texture, sourceName)) {
								keep = true;
								break;
							}
					if (!keep)
						scene.removeTile(tile);
				}
			}
		}
	}

	private boolean matchesPreviewWater(rs117.hd.scene.tile_overrides.TileOverride rule, int texture, String name) {
		var water = previewTerrain.seasonalWaterType(rule, texture);
		return water != null && water != rs117.hd.scene.water_types.WaterType.NONE && (name == null || name.equals(water.name));
	}

	public boolean previewWaterNeedsRefresh() {
		return previewWaterTypes != rs117.hd.scene.WaterTypeManager.WATER_TYPES;
	}

	private final PreviewOverrides previewOverrides = new PreviewOverrides();

	private static final class PreviewOverrides extends rs117.hd.scene.ModelOverrideManager {
		private Integer uuid;
		private rs117.hd.scene.model_overrides.ModelOverride override;

		@Override
		public rs117.hd.scene.model_overrides.ModelOverride getOverride(int id, int[] worldPos) {
			if (uuid != null && uuid == rs117.hd.utils.ModelHash.getUuidWithoutSubType(id))
				return override;
			return super.getOverride(id, worldPos);
		}
	}

	/** Overrides only the inspected entity, leaving normal scene lookup intact. */
	private final PreviewSky previewSky = new PreviewSky();
	private boolean previewSkyEnabled;
	private boolean previewWireframe;

	public void setPreviewSky(int color) {
		previewSkyEnabled = true;
		previewSky.showSky = false;
		previewSky.neutralColor = color;
	}

	private static final class PreviewSky extends rs117.hd.renderer.SkyRenderer {
		boolean showSky;
		int neutralColor;
		@Override public boolean shouldRender(boolean hasVanillaSkybox) {
			return showSky && super.shouldRender(hasVanillaSkybox);
		}
		@Override public void clear(boolean hasVanillaSkybox) {
			super.clear(hasVanillaSkybox);
			if (!showSky) {
				org.lwjgl.opengl.GL11.glClearColor((neutralColor >> 16 & 255) / 255f, (neutralColor >> 8 & 255) / 255f,
					(neutralColor & 255) / 255f, 1);
				org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
			}
		}
	}

	public void setPreviewPass(boolean showSky, boolean wireframe) {
		previewSky.showSky = showSky;
		previewWireframe = wireframe;
	}

	public void setPreviewOverride(Integer uuid, rs117.hd.scene.model_overrides.ModelOverride override) {
		previewOverrides.uuid = uuid;
		previewOverrides.override = override;
	}

	private PreviewModel preview;

	private static final class PreviewModel {
		final net.runelite.api.TileObject tile;
		final net.runelite.api.Renderable renderable;
		final net.runelite.api.Model model;

		PreviewModel(net.runelite.api.TileObject tile, net.runelite.api.Renderable renderable, net.runelite.api.Model model) {
			this.tile = tile;
			this.renderable = renderable;
			this.model = model;
		}
	}

	/** Installs a cache preview model on the renderer thread. */
	public void setPreviewModel(net.runelite.api.TileObject tile, net.runelite.api.Renderable renderable, net.runelite.api.Model model) {
		preview = model == null ? null : new PreviewModel(tile, renderable, model);
	}

	public void frame(float[] cameraPosition, float yaw, float pitch) {
		frame(cameraPosition, yaw, pitch, false);
	}

	/** Draws the inspected model in the editor's empty preview scene. */
	public void frameModelPreview(float[] cameraPosition, float yaw, float pitch) {
		frame(cameraPosition, yaw, pitch, true);
	}

	private void frame(float[] cameraPosition, float yaw, float pitch, boolean modelOnly) {
		clientThread.runPending();
		clientState.sizeUi(Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()));
		clientState.setCamera(cameraPosition, yaw, pitch, level);
		clientState.tick();

		eventBus.post(new ClientTick());
		eventBus.post(new BeforeRender());

		if (!isReady())
			return;

		Renderer renderer = plugin.renderer;
		var scene = this.scene;
		if (scene == null) {
			renderer.draw(0);
			return;
		}

		if (handedTo != renderer) {
			handOver(renderer, scene);
			handedTo = renderer;
		}

		if (clientState.getGameCycle() % 30 == 0)
			eventBus.post(new GameTick());

		Projection projection = renderer instanceof ZoneRenderer ? ((ZoneRenderer) renderer).sceneCamera : null;
		int minLevel = planesBelow ? 0 : level;
		var hiddenRoofs = showRoofs ? Set.<Integer>of() : roofIds;

		renderer.preSceneDraw(
			scene, null,
			cameraPosition[0], cameraPosition[1], cameraPosition[2], pitch, yaw,
			minLevel, level, MAX_Z - 1, hiddenRoofs
		);

		if (plugin.sceneViewport != null) {
			if (!modelOnly)
				drawDynamics(renderer, projection, scene, minLevel, hiddenRoofs);
			if (preview != null && projection != null)
				renderer.drawDynamic(-1, projection, scene, preview.tile, preview.renderable, preview.model, preview.tile instanceof net.runelite.api.GameObject ? ((net.runelite.api.GameObject) preview.tile).getOrientation() : 0,
					preview.tile.getX(), preview.tile.getZ(), preview.tile.getY());

			if (!modelOnly) {
				for (int zx = 0; zx < NUM_ZONES; zx++)
					for (int zz = 0; zz < NUM_ZONES; zz++)
						renderer.drawZoneOpaque(projection, scene, zx, zz);
			}
			renderer.drawPass(projection, scene, DrawCallbacks.PASS_OPAQUE);

			renderer.drawPass(projection, scene, DrawCallbacks.PRE_PASS_ALPHA);
			if (modelOnly) {
				if (preview != null && projection != null) {
					// Transparent preview faces are stored in the model's zone.
					int zoneOffset = BuiltScene.SCENE_OFFSET >> 3;
					int zx = (preview.tile.getX() >> 10) + zoneOffset;
					int zz = (preview.tile.getY() >> 10) + zoneOffset;
					PreviewZoneAccess.drawAlpha((ZoneRenderer) renderer, projection, scene, preview.tile.getPlane(), zx, zz);
				}
			} else {
				int[] order = zonesFurthestFirst(cameraPosition);
				for (int at = minLevel; at < MAX_Z; at++)
					for (int zone : order)
						renderer.drawZoneAlpha(projection, scene, at, zone >> 8, zone & 0xFF);
			}
			renderer.drawPass(projection, scene, DrawCallbacks.PASS_ALPHA);

			renderer.postSceneDraw(scene);
		}

		if (previewWireframe)
			polygonMode(org.lwjgl.opengl.GL11.GL_LINE);
		try {
			renderer.draw(0);
		} finally {
			if (previewWireframe)
				polygonMode(org.lwjgl.opengl.GL11.GL_FILL);
		}
	}

	private static void polygonMode(int mode) {
		org.lwjgl.opengl.GL11.glPolygonMode(org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK, mode);
	}

	private void handOver(Renderer renderer, BuiltScene scene) {
		WorldView worldView = client.getTopLevelWorldView();
		if (renderer instanceof ZoneRenderer) {
			var previous = ((ZoneRenderer) renderer).getSceneContext();
			// An empty preview scene can otherwise seed the next terrain build with a zero-sized buffer.
			if (previous != null && previous.vertexTerrainNormals != null && previous.vertexTerrainNormals.length == 0)
				previous.vertexTerrainNormals = null;
		}
		renderer.loadScene(worldView, scene);
		renderer.swapScene(scene);

		for (int x = 0; x < NUM_ZONES; x++)
			for (int z = 0; z < NUM_ZONES; z++)
				renderer.invalidateZone(scene, x, z);

		log.debug("Handed over a scene at {}, {}", scene.getBaseX(), scene.getBaseY());
	}

	private void drawDynamics(
		Renderer renderer, @Nullable Projection projection, BuiltScene scene,
		int minLevel, Set<Integer> hiddenRoofs
	) {
		if (projection == null || scene.getDynamics().isEmpty())
			return;

		int cycle = clientState.getGameCycle();
		int[][][] roofs = scene.getRoofs();

		for (var dynamic : scene.getDynamics()) {
			if (!isShown(dynamic, roofs, minLevel, hiddenRoofs))
				continue;

			var model = dynamic.renderable.modelAt(cycle);
			if (model == null)
				continue;
			renderer.drawDynamic(
				-1, projection, scene, dynamic.tileObject, dynamic.renderable, model,
				dynamic.orientation, dynamic.x, dynamic.y, dynamic.z
			);
		}
	}

	private boolean isShown(BuiltScene.Dynamic dynamic, int[][][] roofs, int minLevel, Set<Integer> hiddenRoofs) {
		int objectLevel = dynamic.tileObject.getPlane();
		if (objectLevel < minLevel)
			return false;
		if (objectLevel <= level || objectLevel >= MAX_Z || hiddenRoofs.isEmpty())
			return true;

		int tileX = (dynamic.x >> 7) + BuiltScene.SCENE_OFFSET;
		int tileZ = (dynamic.z >> 7) + BuiltScene.SCENE_OFFSET;
		var plan = roofs[objectLevel];
		if (tileX < 0 || tileX >= plan.length || tileZ < 0 || tileZ >= plan[tileX].length)
			return true;

		int roofId = plan[tileX][tileZ];
		return roofId == 0 || !hiddenRoofs.contains(roofId);
	}

	private int[] zoneOrder;
	private long[] zoneDistance;

	private float sortedCameraX = Float.NaN;
	private float sortedCameraZ = Float.NaN;

	private int[] zonesFurthestFirst(float[] cameraPosition) {
		if (zoneOrder != null && cameraPosition[0] == sortedCameraX && cameraPosition[2] == sortedCameraZ)
			return zoneOrder;
		sortedCameraX = cameraPosition[0];
		sortedCameraZ = cameraPosition[2];
		int count = NUM_ZONES * NUM_ZONES;
		if (zoneOrder == null) {
			zoneOrder = new int[count];
			zoneDistance = new long[count];
		}

		int offset = BuiltScene.SCENE_OFFSET;
		for (int zx = 0, at = 0; zx < NUM_ZONES; zx++) {
			for (int zz = 0; zz < NUM_ZONES; zz++, at++) {
				float x = (zx * 8 - offset) * 128 + 512;
				float z = (zz * 8 - offset) * 128 + 512;
				float dx = x - cameraPosition[0];
				float dz = z - cameraPosition[2];
				zoneOrder[at] = zx << 8 | zz;
				zoneDistance[at] = (long) (dx * dx + dz * dz);
			}
		}

		for (int i = 1; i < count; i++) {
			int zone = zoneOrder[i];
			long distance = zoneDistance[i];
			int j = i - 1;
			while (j >= 0 && zoneDistance[j] < distance) {
				zoneOrder[j + 1] = zoneOrder[j];
				zoneDistance[j + 1] = zoneDistance[j];
				j--;
			}
			zoneOrder[j + 1] = zone;
			zoneDistance[j + 1] = distance;
		}
		return zoneOrder;
	}

	private static Set<Integer> roofIdsOf(BuiltScene scene) {
		var ids = new HashSet<Integer>();
		for (int[][] plane : scene.getRoofs())
			for (int[] column : plane)
				for (int roofId : column)
					if (roofId > 0)
						ids.add(roofId);
		return ids;
	}

	private static GameStateChanged gameStateChanged(GameState state) {
		var event = new GameStateChanged();
		event.setGameState(state);
		return event;
	}

	private final class Bindings extends AbstractModule {
		@Override
		protected void configure() {
			bind(HdPlugin.class).toInstance(plugin);
			bind(Client.class).toInstance(client);
			bind(rs117.hd.scene.ModelOverrideManager.class).toInstance(previewOverrides);
			if (previewSkyEnabled)
				bind(rs117.hd.scene.ProceduralGenerator.class).toInstance(previewTerrain);
			if (previewSkyEnabled)
				bind(rs117.hd.renderer.SkyRenderer.class).toInstance(previewSky);

			bindConstant().annotatedWith(Names.named("developerMode")).to(true);
			bindConstant().annotatedWith(Names.named("safeMode")).to(false);
			bindConstant().annotatedWith(Names.named("disableTelemetry")).to(true);
			bindConstant().annotatedWith(Names.named("insecureWriteCredentials")).to(false);
			bind(ClientThread.class).toInstance(clientThread);
			bind(EventBus.class).toInstance(eventBus);
			bind(DrawManager.class).toInstance(drawManager);
			bind(RenderCallbackManager.class).toInstance(new RenderCallbackManager());
			bind(HdPluginConfig.class).toInstance(settings.asConfig());
			bind(Gson.class).toInstance(new Gson());
			bind(ScheduledExecutorService.class).toInstance(executor);

			bind(ConfigManager.class).toInstance(nothing(ConfigManager.class));
			bind(ClientUI.class).toInstance(nothing(ClientUI.class));

			var plugins = nothing(PluginManager.class);
			org.mockito.Mockito.when(plugins.isPluginEnabled(org.mockito.ArgumentMatchers.any())).thenReturn(true);
			bind(PluginManager.class).toInstance(plugins);
			bind(OverlayManager.class).toInstance(nothing(OverlayManager.class));
			bind(KeyManager.class).toInstance(nothing(KeyManager.class));
			bind(MouseManager.class).toInstance(nothing(MouseManager.class));
			bind(ColorPickerManager.class).toInstance(nothing(ColorPickerManager.class));
			bind(ChatMessageManager.class).toInstance(nothing(ChatMessageManager.class));
		}
	}

	private static <T> T nothing(Class<T> type) {
		return org.mockito.Mockito.mock(type);
	}
}
