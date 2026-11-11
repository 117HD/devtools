package rs117.hd.dev.overrides;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import rs117.hd.scene.materials.Material;

/** Cached thumbnails of the actual HD diffuse textures. */
public final class MaterialThumbnails {
	// Both the small trigger icon and larger grid tile can be cached for a material.
	private static final int MAX_THUMBNAILS = 512;
	private static final Map<PreviewKey, Image> CACHE = new LinkedHashMap<PreviewKey, Image>(64, .75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<PreviewKey, Image> eldest) {
			return size() > MAX_THUMBNAILS;
		}
	};

	private MaterialThumbnails() {}

	static Image vanillaPreview(int[] pixels) {
		int size = rs117.hd.dev.sceneloader.standalone.Textures.SIZE;
		if (pixels == null || pixels.length != size * size)
			return null;

		int[] argb = new int[pixels.length];
		for (int i = 0; i < pixels.length; i++)
			argb[i] = pixels[i] == 0 ? 0 : 0xFF000000 | (pixels[i] & 0xFFFFFF);
		var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, size, size, argb, 0, size);
		return image;
	}

	private static final class PreviewKey {
		private final Material material;
		private final int size;

		private PreviewKey(Material material, int size) {
			this.material = material;
			this.size = size;
		}

		@Override public int hashCode() { return 31 * System.identityHashCode(material) + size; }
		@Override public boolean equals(Object other) {
			if (!(other instanceof PreviewKey))
				return false;
			var key = (PreviewKey) other;
			return key.material == material && key.size == size;
		}
	}

	private static final java.util.WeakHashMap<java.awt.Component, Boolean> waiting = new java.util.WeakHashMap<>();
	private static final Map<PreviewKey, java.util.WeakHashMap<java.awt.Component, Boolean>> pending = new java.util.HashMap<>();
	private static final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
		var thread = new Thread(task, "117HD material thumbnails");
		thread.setDaemon(true);
		return thread;
	});

	public static Image preview(Material material, int size, java.awt.Component observer) {
		if (material == null || material == Material.NONE)
			return null;
		var key = new PreviewKey(material, size);
		synchronized (CACHE) {
			if (CACHE.containsKey(key))
				return CACHE.get(key);
			var observers = pending.get(key);
			if (observers == null) {
				// Rapid scrolling should not queue the entire texture library.
				if (pending.size() >= 64) {
					waiting.put(observer, Boolean.TRUE);
					return null;
				}
				observers = new java.util.WeakHashMap<>();
				pending.put(key, observers);
				loader.execute(() -> loadPreview(key));
			}
			observers.put(observer, Boolean.TRUE);
		}
		return null;
	}

	private static void loadPreview(PreviewKey key) {
		Image image = null;
		try {
			image = load(key.material, key.size);
		} finally {
			java.util.List<java.awt.Component> observers;
			synchronized (CACHE) {
				CACHE.put(key, image);
				observers = new java.util.ArrayList<>(pending.remove(key).keySet());
				if (pending.isEmpty()) {
					observers.addAll(waiting.keySet());
					waiting.clear();
				}
			}
			javax.swing.SwingUtilities.invokeLater(() -> observers.forEach(java.awt.Component::repaint));
		}
	}

	/** Classifies maps by how RLHD references them, independent of their filenames. */
	static java.util.List<Material> withoutAuxiliaryMaps(java.util.List<Material> materials) {
		var mapNames = new java.util.HashSet<String>();
		try {
			for (Material material : materials) {
				for (var field : AUXILIARY_MAPS) {
					var map = (Material) field.get(material);
					if (map != null && map != Material.NONE)
						mapNames.add(map.name);
				}
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Unable to read auxiliary material maps", e);
		}
		materials.removeIf(material -> mapNames.contains(material.name));
		return materials;
	}

	private static final java.lang.reflect.Field[] AUXILIARY_MAPS = java.util.stream.Stream.of(
		"normalMap", "displacementMap", "roughnessMap", "ambientOcclusionMap", "flowMap", "shadowAlphaMap")
		.map(MaterialThumbnails::materialField).toArray(java.lang.reflect.Field[]::new);

	private static Image load(Material material, int size) {
		if (material == Material.NONE)
			return null;
		for (Material current = material; current != null; current = parentOf(current)) {
			Image image = loadTexture(current.getTextureName(), size);
			if (image != null)
				return image;
		}
		// Untextured materials still have a surface. Show a neutral lighting sample.
		var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		try {
			float center = size / 2f;
			boolean unlit = "UNLIT".equals(material.name);
			g.setPaint(new java.awt.RadialGradientPaint(center, center, size * .72f, new float[] {0, 1},
				new java.awt.Color[] {new java.awt.Color(unlit ? 0xFFFFFF : 0xACAAA5), new java.awt.Color(unlit ? 0xB0B0B0 : 0x62605D)}));
			g.fillRect(0, 0, size, size);
		} finally {
			g.dispose();
		}
		return image;
	}

	private static final java.lang.reflect.Field PARENT = materialField("parent");

	private static java.lang.reflect.Field materialField(String name) {
		try {
			var field = Material.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to access material field " + name, e);
		}
	}

	private static Material parentOf(Material material) {
		try {
			return (Material) PARENT.get(material);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Unable to read material inheritance", e);
		}
	}

	private static Image loadTexture(String textureName, int size) {
		if (textureName == null)
			return null;
		for (String extension : new String[] {".png", ".jpg", ".jpeg"}) {
			try (InputStream stream = Material.class.getResourceAsStream("/rs117/hd/scene/textures/" + textureName + extension)) {
				if (stream == null)
					continue;
				BufferedImage image = ImageIO.read(stream);
				if (image != null)
					return scale(image, size);
			} catch (IOException ignored) {
				// Try the next supported extension.
			}
		}
		return null;
	}
	static BufferedImage scale(Image image, int size) {
		var scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		var graphics = scaled.createGraphics();
		try {
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.drawImage(image, 0, 0, size, size, null);
		} finally {
			graphics.dispose();
		}
		return scaled;
	}

}
