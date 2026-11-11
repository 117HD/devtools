package rs117.hd.dev.editor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.SpotAnimLoader;
import rs117.hd.dev.sceneloader.scene.BuiltModel;
import rs117.hd.dev.sceneloader.scene.Definitions;
import rs117.hd.dev.sceneloader.scene.ModelFactory;
import rs117.hd.scene.GamevalManager;

public final class CacheModels {
	public enum Category { NPCs, Objects, Graphics }

	public interface ModelSource { BuiltModel load() throws IOException; }

	public static final class Entry {
		public final Category category;
		public final int id;
		public final String label;
		public final String name;
		public final String displayName;
		private final List<String> gamevals;
		public final String searchLabel;
		public final ModelSource source;

		public String gamevalName() { return gamevals.isEmpty() ? null : gamevals.get(0); }

		Entry(Category category, int id, String name, List<String> gamevals, ModelSource source) {
			this.category = category;
			this.id = id;
			this.name = name == null || name.isBlank() || name.equals("null") ? category == Category.Graphics ? "Spot animation" : "Unnamed" : name;
			this.gamevals = gamevals == null ? List.of() : gamevals;
			displayName = this.gamevals.isEmpty() ? this.name : this.gamevals.get(0);
			label = displayName + " (" + id + ")";
			searchLabel = (this.name + " " + id + " " + String.join(" ", this.gamevals)).toLowerCase(Locale.ROOT);
			this.source = source;
		}

		@Override public String toString() { return label; }
	}

	public static final class Group {
		public final Entry root;
		public final List<Entry> variants;

		Group(Entry root, List<Entry> variants) {
			this.root = root;
			this.variants = variants;
		}
		@Override public String toString() {
			return variants.size() > 1 ? root.label + " · " + variants.size() + " variants" : root.label;
		}
	}

	public static final class Index {
		public final List<Entry> entries;
		public final List<Group> groups;

		Index(List<Entry> entries, List<Group> groups) {
			this.entries = entries;
			this.groups = groups;
		}
	}

	static Index index(Definitions defs, GamevalManager.Handle gamevals) throws IOException {
		var groups = new ArrayList<Group>();
		var definitions = new TreeMap<Integer, Entry>();
		var transforms = new TreeMap<Integer, int[]>();
		var entries = new ArrayList<Entry>();
		var npcGamevals = byId(gamevals.getNpcs());
		var objectGamevals = byId(gamevals.getObjects());
		var spotanimGamevals = byId(gamevals.getSpotanims());
		var npcs = new NpcManager(defs.getStore());
		npcs.load();
		for (var npc : npcs.getNpcs()) {
			boolean hasModel = npc.models != null && npc.models.length != 0;
			if (npc.configs != null)
				transforms.put(npc.id, npc.configs);
			if (!hasModel && npc.configs == null)
				continue;
			var entry = new Entry(Category.NPCs, npc.id, npc.name, npcGamevals.get(npc.id), !hasModel ? null : () -> {
				ModelDefinition model = loadParts(defs, npc.models);
				if (model == null)
					return null;
				applyMaterials(model, npc.recolorToFind, npc.recolorToReplace, npc.retextureToFind, npc.retextureToReplace);
				model.resize(npc.widthScale, npc.heightScale, npc.widthScale);
				return ModelFactory.light(model, 64 + npc.ambient, 850 + npc.contrast);
			});
			definitions.put(entry.id, entry);
		}
		groupTransforms(definitions, transforms, groups, entries);
		definitions.clear();
		transforms.clear();

		var factory = new ModelFactory(defs);
		for (var object : defs.objects()) {
			int id = object.getId();
			int[] destinations = object.getConfigChangeDest();
			if (destinations != null)
				transforms.put(id, destinations);
			int[] models = object.getObjectModels();
			boolean hasModel = models != null && models.length != 0;
			if (!hasModel && destinations == null)
				continue;
			int[] types = object.getObjectTypes();
			int shape = types == null || types.length == 0 ? 10 : types[0];
			definitions.put(id, new Entry(Category.Objects, id, object.getName(), objectGamevals.get(id),
				hasModel ? () -> factory.model(object, shape, 0) : null));
		}
		groupTransforms(definitions, transforms, groups, entries);

		var store = defs.getStore();
		var archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SPOTANIM.getId());
		if (archive != null) {
			var files = archive.getFiles(store.getStorage().loadArchive(archive));
			var loader = new SpotAnimLoader();
			for (var file : files.getFiles()) {
				var spot = loader.load(file.getFileId(), file.getContents());
				var entry = new Entry(Category.Graphics, spot.id, spot.debugName, spotanimGamevals.get(spot.id), () -> {
					var model = defs.model(spot.modelId);
					if (model == null)
						return null;
					applyMaterials(model, spot.recolorToFind, spot.recolorToReplace, spot.textureToFind, spot.textureToReplace);
					model.resize(spot.resizeX, spot.resizeY, spot.resizeX);
					for (int turns = Math.floorMod(spot.rotaton / 90, 4); turns > 0; turns--)
						model.rotate1();
					return ModelFactory.light(model, 64 + spot.ambient, 850 + spot.contrast);
				});
				entries.add(entry);
				groups.add(new Group(entry, List.of(entry)));
			}
		}
		entries.sort(Comparator.comparingInt(entry -> entry.id));
		groups.sort(Comparator.comparingInt(group -> group.root.id));
		return new Index(List.copyOf(entries), List.copyOf(groups));
	}

	private static Map<Integer, List<String>> byId(Map<String, Integer> gamevals) {
		var names = new HashMap<Integer, List<String>>();
		gamevals.forEach((name, id) -> names.computeIfAbsent(id, ignored -> new ArrayList<>()).add(name));
		return names;
	}

	private static void groupTransforms(Map<Integer, Entry> definitions, Map<Integer, int[]> transforms,
		List<Group> groups, List<Entry> entries) {
		var children = new HashSet<Integer>();
		transforms.forEach((id, destinations) -> {
			for (int destination : destinations)
				if (destination >= 0 && destination != id)
					children.add(destination);
		});
		var covered = new HashSet<Integer>();
		var selectable = new TreeMap<Integer, Entry>();
		for (int id : transforms.keySet())
			if (!children.contains(id))
				addTransformGroup(id, definitions, transforms, covered, selectable, groups);
		// A cycle can leave every transforming definition with an incoming edge.
		for (int id : transforms.keySet())
			if (!covered.contains(id))
				addTransformGroup(id, definitions, transforms, covered, selectable, groups);
		for (var entry : definitions.values()) {
			if (entry.source != null) {
				selectable.putIfAbsent(entry.id, entry);
				if (!covered.contains(entry.id))
					groups.add(new Group(entry, List.of(entry)));
			}
		}
		entries.addAll(selectable.values());
	}

	private static void addTransformGroup(int rootId, Map<Integer, Entry> definitions, Map<Integer, int[]> transforms,
		Set<Integer> covered, Map<Integer, Entry> selectable, List<Group> groups) {
		var seen = new LinkedHashSet<Integer>();
		var pending = new ArrayDeque<Integer>();
		pending.add(rootId);
		while (!pending.isEmpty()) {
			int id = pending.removeFirst();
			if (!seen.add(id))
				continue;
			int[] destinations = transforms.get(id);
			if (destinations != null)
				for (int destination : destinations)
					if (destination >= 0 && definitions.containsKey(destination))
						pending.addLast(destination);
		}
		covered.addAll(seen);
		var variants = new ArrayList<Entry>();
		for (int id : seen) {
			var entry = definitions.get(id);
			if (entry != null && entry.source != null)
				variants.add(entry);
		}
		if (variants.isEmpty())
			return;

		var root = definitions.get(rootId);
		if (root.source == null) {
			var first = variants.get(0);
			root = new Entry(root.category, root.id, root.name.equals("Unnamed") ? first.name : root.name, root.gamevals, first.source);
			variants.add(0, root);
		}
		selectable.put(root.id, root);
		groups.add(new Group(root, List.copyOf(variants)));
	}

	private static ModelDefinition loadParts(Definitions defs, int[] ids) throws IOException {
		var parts = new ModelDefinition[ids.length];
		for (int i = 0; i < ids.length; i++) {
			parts[i] = defs.model(ids[i]);
			if (parts[i] == null)
				return null;
		}
		return parts.length == 1 ? parts[0] : ModelFactory.merge(parts);
	}

	private static void applyMaterials(ModelDefinition model, short[] colors, short[] recolors, short[] textures, short[] retextures) {
		if (colors != null && recolors != null)
			for (int i = 0; i < Math.min(colors.length, recolors.length); i++)
				model.recolor(colors[i], recolors[i]);
		if (textures != null && retextures != null)
			for (int i = 0; i < Math.min(textures.length, retextures.length); i++)
				model.retexture(textures[i], retextures[i]);
	}
}
