package rs117.hd.dev.sceneloader.map;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;

/** Shares tile downloads and the decoded-image cache between editor map views. */
@Slf4j
public final class MapTileCache {
	private static final String TILE_URL = "https://raw.githubusercontent.com/Mark7625/osrs_map_tiles/refs/heads/master/";
	private static final int IMAGES_IN_MEMORY = 160;
	private static final int RECENT_FAILURES = 2048;
	private static final long MISSING_TILE_COOLDOWN = TimeUnit.HOURS.toNanos(12);
	private static final long FAILED_REQUEST_COOLDOWN = TimeUnit.SECONDS.toNanos(30);
	private static final long[] RETRY_DELAYS_MILLIS = {300, 1500};

	private static final class Shared {
		static final Store STORE = new Store(
			cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")),
				System.getenv("LOCALAPPDATA"), System.getenv("XDG_CACHE_HOME")),
			HdPlugin.PLUGIN_DIR.resolve("map-tiles").toPath(), TILE_URL);
	}

	private final Store store;
	private final Runnable onLoaded;
	private volatile boolean closed;

	public MapTileCache(Runnable onLoaded) {
		this(Shared.STORE, onLoaded);
	}

	MapTileCache(Store store, Runnable onLoaded) {
		this.store = store;
		this.onLoaded = onLoaded;
	}

	public void shutDown() {
		closed = true;
		store.unsubscribe(this);
	}

	@Nullable
	public BufferedImage get(int plane, int zoom, int tileX, int tileY) {
		if (closed || plane < 0 || plane > 3 || zoom < MapProjection.MIN_ZOOM || zoom > MapProjection.MAX_ZOOM)
			return null;
		int size = 1 << zoom;
		if (tileX < 0 || tileY < 0 || tileX >= size || tileY >= size)
			return null;
		return store.get(this, new Tile(plane, zoom, tileX, tileY));
	}

	static Path cacheDirectory(String osName, Path home, String localAppData, String xdgCache) {
		String os = osName.toLowerCase(Locale.ROOT);
		Path base;
		if (os.startsWith("windows")) {
			base = localAppData == null || localAppData.isBlank() ? home.resolve("AppData/Local") : Path.of(localAppData);
		} else if (os.startsWith("mac") || os.equals("darwin")) {
			base = home.resolve("Library/Application Support");
		} else {
			base = xdgCache == null || xdgCache.isBlank() ? home.resolve(".cache") : Path.of(xdgCache);
		}
		return base.resolve("117hd").resolve("world_map");
	}

	@lombok.RequiredArgsConstructor
	private static final class Tile {
		final int plane, zoom, x, y;

		long key() { return (long) plane << 48 | (long) zoom << 32 | (long) x << 16 | y; }

		String path() { return plane + "/" + zoom + "/" + x + "/" + y + ".png"; }
	}

	static final class Store {
		private final Path root, legacyRoot;
		private final String url;
		private volatile String legacyVersion;
		private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(6, task -> {
			Thread thread = new Thread(task, "117hd-world-map");
			thread.setDaemon(true);
			return thread;
		});
		private final Map<Long, BufferedImage> images = new LinkedHashMap<>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) {
				return size() > IMAGES_IN_MEMORY;
			}
		};
		private final Map<Long, Long> retryAfter = new LinkedHashMap<>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
				return size() > RECENT_FAILURES;
			}
		};
		private final Map<Long, Set<MapTileCache>> pending = new HashMap<>();

		Store(Path root, Path legacyRoot, String url) {
			this.root = root;
			this.legacyRoot = legacyRoot;
			this.url = url;
		}

		synchronized BufferedImage get(MapTileCache view, Tile tile) {
			if (view.closed)
				return null;
			long key = tile.key();
			var image = images.get(key);
			if (image != null)
				return image;
			Long cooldown = retryAfter.get(key);
			if (cooldown != null && System.nanoTime() - cooldown < 0)
				return null;
			retryAfter.remove(key);

			var views = pending.get(key);
			if (views != null) {
				views.add(view);
			} else {
				pending.put(key, new HashSet<>(Set.of(view)));
				worker.execute(() -> load(tile, 0));
			}
			return null;
		}

		synchronized void unsubscribe(MapTileCache view) {
			pending.values().forEach(views -> views.remove(view));
		}

		private void load(Tile tile, int attempt) {
			Path file = root.resolve(tile.path());
			BufferedImage image;
			try {
				image = read(file);
				if (image == null)
					image = readLegacy(tile, file);
				if (image == null) {
					byte[] bytes = download(url + tile.path());
					if (bytes == null) {
						finish(tile, null, MISSING_TILE_COOLDOWN);
						return;
					}
					image = ImageIO.read(new ByteArrayInputStream(bytes));
					if (image == null)
						throw new IOException("Invalid map PNG");
					save(file, bytes);
				}
			} catch (IOException | RuntimeException ex) {
				if (attempt < RETRY_DELAYS_MILLIS.length) {
					worker.schedule(() -> load(tile, attempt + 1), RETRY_DELAYS_MILLIS[attempt], TimeUnit.MILLISECONDS);
				} else {
					log.debug("Could not load map tile {}", tile.path(), ex);
					finish(tile, null, FAILED_REQUEST_COOLDOWN);
				}
				return;
			}
			finish(tile, image, 0);
		}

		private void finish(Tile tile, @Nullable BufferedImage image, long cooldown) {
			Set<MapTileCache> views;
			synchronized (this) {
				long key = tile.key();
				if (image == null)
					retryAfter.put(key, System.nanoTime() + cooldown);
				else
					images.put(key, image);
				views = pending.remove(key);
			}
			if (image != null) {
				for (var view : views)
					if (!view.closed)
						view.onLoaded.run();
			}
		}

		private BufferedImage read(Path file) {
			try (var in = Files.newInputStream(file)) {
				return ImageIO.read(in);
			} catch (IOException | RuntimeException ex) {
				// Missing or corrupt cache entries can be fetched again.
				return null;
			}
		}

		private BufferedImage readLegacy(Tile tile, Path file) {
			String version = legacyVersion;
			if (version == null) {
				version = "master";
				try (var marker = Files.newBufferedReader(legacyRoot.resolve("version"))) {
					String line = marker.readLine();
					if (line != null && line.strip().matches("[a-f0-9]{40}"))
						version = line.strip();
				} catch (IOException ignored) {
					// Older caches can use master directly if the version lookup failed.
				}
				legacyVersion = version;
			}
			for (String folder : version.equals("master") ? List.of("master") : List.of(version, "master")) {
				try {
					var bytes = Files.readAllBytes(legacyRoot.resolve(folder).resolve(tile.path()));
					var image = ImageIO.read(new ByteArrayInputStream(bytes));
					if (image != null) {
						save(file, bytes);
						return image;
					}
				} catch (IOException | RuntimeException ignored) {
					// Check the next legacy folder or fetch from the tile source.
				}
			}
			return null;
		}
	}

	private static byte[] download(String url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setRequestProperty("User-Agent", "117hd-devtools-world-map");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(8000);
		try {
			int status = connection.getResponseCode();
			if (status == HttpURLConnection.HTTP_NOT_FOUND)
				return null;
			if (status != HttpURLConnection.HTTP_OK)
				throw new IOException("Tile request returned HTTP " + status);
			try (var bytes = connection.getInputStream()) {
				return bytes.readAllBytes();
			}
		} finally {
			connection.disconnect();
		}
	}

	private static void save(Path file, byte[] bytes) {
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".tile-", ".part");
			try {
				Files.write(temporary, bytes);
				try {
					Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException ex) {
					Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException ex) {
			log.debug("Could not cache map tile {}", file, ex);
		}
	}
}
