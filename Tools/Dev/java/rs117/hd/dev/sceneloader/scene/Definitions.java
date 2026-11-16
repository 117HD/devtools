package rs117.hd.dev.sceneloader.scene;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.TextureDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.ConfigType;
import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FrameLoader;
import net.runelite.cache.definitions.loaders.FramemapLoader;
import net.runelite.cache.definitions.loaders.LocationsLoader;
import net.runelite.cache.definitions.loaders.MapLoader;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.util.Djb2;

@Slf4j
public class Definitions implements Closeable {
	private final Store store;
	private final Index maps;
	private final boolean mapsAreNamed;
	private final Index models;
	private final Index frameArchives;
	private final Index framemapArchives;

	private final Map<Integer, byte[]> modelData;

	private final Map<Integer, SequenceDefinition> sequences = new HashMap<>();
	private final Map<Integer, ArchiveFiles> frameFiles;
	private final Map<Integer, FramemapDefinition> framemaps;
	private final Map<Integer, FrameDefinition> frames;

	private final UnderlayManager underlays;
	private final OverlayManager overlays;
	private final ObjectManager objects;
	private final TextureManager textures;

	private java.util.Set<Integer> mapArchiveNames;
	private final Map<Integer, Region> regions;

	public Definitions(Path cacheDirectory) throws IOException {
		this(cacheDirectory, false);
	}

	public Definitions(Path cacheDirectory, boolean limitPreviewCaches) throws IOException {
		frameFiles = limitPreviewCaches ? limitedCache(64) : new HashMap<>();
		framemaps = limitPreviewCaches ? limitedCache(256) : new HashMap<>();
		frames = limitPreviewCaches ? limitedCache(512) : new HashMap<>();
		modelData = limitPreviewCaches ? limitedCache(1024) : new HashMap<>();
		regions = limitPreviewCaches ? limitedCache(128) : new HashMap<>();
		store = new Store(cacheDirectory.toFile());
		store.load();

		maps = store.getIndex(IndexType.MAPS);
		mapsAreNamed = maps.findArchiveByName("m50_50") != null;
		models = store.getIndex(IndexType.MODELS);
		frameArchives = store.getIndex(IndexType.ANIMATIONS);
		framemapArchives = store.getIndex(IndexType.SKELETONS);

		underlays = new UnderlayManager(store);
		underlays.load();
		overlays = new OverlayManager(store);
		overlays.load();
		objects = new ObjectManager(store);
		objects.load();
		textures = new TextureManager(store);
		textures.load();

		loadSequences();
	}

	public void clearPreviewCaches() {
		regions.clear();
		modelData.clear();
		frameFiles.clear();
		framemaps.clear();
		frames.clear();
	}

	private static <T> Map<Integer, T> limitedCache(int capacity) {
		return new java.util.LinkedHashMap<Integer, T>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Integer, T> eldest) {
				return size() > capacity;
			}
		};
	}

	private void loadSequences() throws IOException {
		Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SEQUENCE.getId());
		if (archive == null)
			return;

		ArchiveFiles files = archive.getFiles(store.getStorage().loadArchive(archive));
		SequenceLoader loader = new SequenceLoader().configureForRevision(archive.getRevision());
		for (var file : files.getFiles()) {
			try {
				sequences.put(file.getFileId(), loader.load(file.getFileId(), file.getContents()));
			} catch (Exception ex) {
				log.debug("Ignoring animation {}, which would not decode", file.getFileId(), ex);
			}
		}
	}

	@Nullable
	public SequenceDefinition sequence(int id) {
		return sequences.get(id);
	}

	@Nullable
	public FrameDefinition frame(int packedFrameId) {
		FrameDefinition cached = frames.get(packedFrameId);
		if (cached != null || frames.containsKey(packedFrameId))
			return cached;

		FrameDefinition frame = null;
		try {
			frame = loadFrame(packedFrameId);
		} catch (Exception ex) {
			log.debug("Ignoring frame {}, which would not decode", packedFrameId, ex);
		}
		frames.put(packedFrameId, frame);
		return frame;
	}

	@Nullable
	private FrameDefinition loadFrame(int packedFrameId) throws IOException {
		int archiveId = packedFrameId >> 16;
		int fileId = packedFrameId & 0xFFFF;

		ArchiveFiles files = frameFiles.get(archiveId);
		if (files == null) {
			if (frameFiles.containsKey(archiveId))
				return null;

			Archive archive = frameArchives.getArchive(archiveId);
			files = archive == null
				? null
				: archive.getFiles(store.getStorage().loadArchive(archive));
			frameFiles.put(archiveId, files);
			if (files == null)
				return null;
		}

		var file = files.findFile(fileId);
		if (file == null)
			return null;

		byte[] data = file.getContents();
		if (data == null || data.length < 2)
			return null;

		int framemapId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
		FramemapDefinition framemap = framemap(framemapId);
		if (framemap == null)
			return null;

		FrameDefinition frame = new FrameLoader().load(framemap, fileId, data);
		if (frame != null) {
			frame.id = packedFrameId;
		}
		return frame;
	}

	@Nullable
	private FramemapDefinition framemap(int id) throws IOException {
		FramemapDefinition cached = framemaps.get(id);
		if (cached != null || framemaps.containsKey(id))
			return cached;

		FramemapDefinition framemap = null;
		Archive archive = framemapArchives.getArchive(id);
		if (archive != null) {
			byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
			if (data != null)
				framemap = new FramemapLoader().load(id, data);
		}
		framemaps.put(id, framemap);
		return framemap;
	}

	public static Path findDefaultCache() throws IOException {
		Path home = Paths.get(System.getProperty("user.home"));
		Path[] candidates = {
			home.resolve(".runelite").resolve("jagexcache").resolve("oldschool").resolve("LIVE"),
			home.resolve("jagexcache").resolve("oldschool").resolve("LIVE")
		};
		for (Path candidate : candidates)
			if (Files.isRegularFile(candidate.resolve("main_file_cache.dat2")))
				return candidate;
		throw new IOException("No OSRS cache found in " + home.resolve("jagexcache") + " or " + home.resolve(".runelite"));
	}

	@Nullable
	public Region region(int regionX, int regionY) {
		int id = (regionX << 8) | regionY;
		Region cached = regions.get(id);
		if (cached != null)
			return cached;
		if (regions.containsKey(id))
			return null;

		Region region;
		try {
			region = load(regionX, regionY);
		} catch (Exception ex) {
			log.debug("Ignoring map square {},{}, which would not decode", regionX, regionY, ex);
			region = null;
		}
		regions.put(id, region);
		return region;
	}

	@Nullable
	private Region load(int regionX, int regionY) throws IOException {
		byte[] terrain = archive("m" + regionX + "_" + regionY, (regionX << 8) | regionY, 0);
		if (terrain == null)
			return null;

		MapDefinition map = new MapLoader().load(regionX, regionY, terrain);
		Region region = new Region(regionX, regionY);
		region.loadTerrain(map);

		byte[] locations = archive("l" + regionX + "_" + regionY, (regionX << 8) | regionY, 1);
		if (locations != null) {
			LocationsDefinition locs = new LocationsLoader().load(regionX, regionY, locations);
			region.loadLocations(locs);
		}

		return region;
	}

	@Nullable
	private byte[] archive(String name, int regionId, int fileId) throws IOException {
		Storage storage = store.getStorage();

		if (mapsAreNamed) {
			Archive named = maps.findArchiveByName(name);
			return named == null ? null : named.decompress(storage.loadArchive(named));
		}

		Archive archive = maps.getArchive(regionId);
		if (archive == null)
			return null;

		var file = archive.getFiles(storage.loadArchive(archive)).findFile(fileId);
		return file == null ? null : file.getContents();
	}

	private boolean hasMapArchive(String name, int regionId) {
		if (!mapsAreNamed)
			return maps.getArchive(regionId) != null;
		if (mapArchiveNames == null) {
			mapArchiveNames = new java.util.HashSet<>();
			for (Archive archive : maps.getArchives())
				mapArchiveNames.add(archive.getNameHash());
		}
		return mapArchiveNames.contains(Djb2.hash(name));
	}

	public net.runelite.cache.region.Position findWaterPlacement(
		String name, rs117.hd.scene.TileOverrideManager overrides,
		rs117.hd.scene.ProceduralGenerator terrain, java.util.function.BooleanSupplier active
	) {
		var priority = new java.util.LinkedHashSet<Integer>();
		var rules = rs117.hd.scene.TileOverrideManager.OVERRIDES;
		if (rules != null) {
			for (var rule : rules) {
				if (rule.waterType == null || !name.equals(rule.waterType.name) || rule.area == null || rule.area.aabbs == null)
					continue;
				for (var box : rule.area.aabbs) {
					int minX = Math.max(0, box.minX >> 6), maxX = Math.min(255, box.maxX >> 6);
					int minY = Math.max(0, box.minY >> 6), maxY = Math.min(255, box.maxY >> 6);
					if ((long) (maxX - minX + 1) * (maxY - minY + 1) > 1024)
						continue;
					for (int x = minX; x <= maxX; x++)
						for (int y = minY; y <= maxY; y++)
							priority.add(x << 8 | y);
				}
			}
		}
		var regionsToSearch = java.util.stream.IntStream.concat(priority.stream().mapToInt(Integer::intValue),
			java.util.stream.IntStream.range(0, 65536).filter(id -> !priority.contains(id))).iterator();
		while (regionsToSearch.hasNext() && active.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
			int id = regionsToSearch.nextInt();
			int x = id >> 8, y = id & 255;
			String archiveName = "m" + x + "_" + y;
			if (!hasMapArchive(archiveName, id))
				continue;
			try {
				byte[] data = archive(archiveName, id, 0);
				if (data == null)
					continue;
				var region = new Region(x, y);
				region.loadTerrain(new MapLoader().load(x, y, data));
				var position = findWaterTile(name, overrides, terrain, region, active);
				if (position != null)
					return position;
			} catch (IOException | RuntimeException ex) {
				log.trace("Could not inspect water in region {}", id, ex);
			}
		}
		return null;
	}

	private net.runelite.cache.region.Position findWaterTile(
		String name, rs117.hd.scene.TileOverrideManager overrides, rs117.hd.scene.ProceduralGenerator terrain,
		Region region, java.util.function.BooleanSupplier active
	) {
		int[] position = new int[3];
		int[] ids = new int[2];
		for (int plane = 0; plane < 4 && active.getAsBoolean(); plane++) {
			position[2] = plane;
			for (int x = 0; x < 64; x++) {
				position[0] = region.getBaseX() + x;
				for (int y = 0; y < 64; y++) {
					position[1] = region.getBaseY() + y;
					int overlayId = region.getOverlayId(plane, x, y);
					int underlayId = region.getUnderlayId(plane, x, y);
					if (overlayId == 0 && underlayId == 0)
						continue;
					ids[0] = rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG | overlayId;
					ids[1] = underlayId;
					var overlay = overlay(overlayId - 1);
					var rule = overrides.getOverrideBeforeReplacements(position, ids);
					var water = terrain.seasonalWaterType(rule, overlay == null ? -1 : overlay.getTexture());
					if (water != null && name.equals(water.name))
						return new net.runelite.cache.region.Position(position[0], position[1], plane);
				}
			}
		}
		return null;
	}

	/** Searches decoded map locations without retaining terrain for every region. */
	@Nullable
	public Location findObjectPlacement(int objectId, java.util.function.BooleanSupplier active) {

		for (int regionId = 0; regionId < 65536 && active.getAsBoolean() && !Thread.currentThread().isInterrupted(); regionId++) {
			int x = regionId >> 8, y = regionId & 255;
			String name = "l" + x + "_" + y;
			if (!hasMapArchive(name, regionId))
				continue;
			try {
				byte[] data = archive(name, regionId, 1);
				if (data == null)
					continue;
				for (var location : new LocationsLoader().load(x, y, data).getLocations())
					if (location.getId() == objectId) {
						var pos = location.getPosition();
						return new Location(objectId, location.getType(), location.getOrientation(),
							new net.runelite.cache.region.Position((x << 6) + pos.getX(), (y << 6) + pos.getY(), pos.getZ()));
					}
			} catch (IOException | RuntimeException ex) {
				log.trace("Could not read object placements in region {}", regionId, ex);
			}
		}
		return null;
	}

	@Nullable
	public ModelDefinition model(int id) throws IOException {
		byte[] data = modelData.get(id);
		if (data == null) {
			if (modelData.containsKey(id))
				return null;

			Archive archive = models.getArchive(id);
			data = archive == null ? null : archive.decompress(store.getStorage().loadArchive(archive));
			modelData.put(id, data);
			if (data == null)
				return null;
		}
		return new ModelLoader().load(id, data);
	}

	@Nullable
	public UnderlayDefinition underlay(int id) {
		return underlays.provide(id);
	}

	@Nullable
	public OverlayDefinition overlay(int id) {
		return overlays.provide(id);
	}

	@Nullable
	public java.util.Collection<ObjectDefinition> objects() {
		return objects.getObjects();
	}

	public ObjectDefinition object(int id) {
		return objects.getObject(id);
	}

	@Nullable
	public TextureDefinition texture(int id) {
		return textures.findTexture(id);
	}

	public List<TextureDefinition> textures() {
		return textures.getTextures();
	}

	public int textureColor(int id) {
		TextureDefinition texture = texture(id);
		return texture == null ? 0 : texture.missingColor;
	}

	public Store getStore() {
		return store;
	}

	@Override
	public void close() throws IOException {
		store.close();
	}
}
