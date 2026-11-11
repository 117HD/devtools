package rs117.hd.dev.overrides;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import rs117.hd.dev.editor.CacheModels;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;

/**
 * Edits {@link ModelOverride} entries one at a time while preserving everything else in
 * {@code model_overrides.json} byte-for-byte — comments included. Never re-serializes the whole
 * file; only the entry being saved is re-written and spliced back into the original text, exactly
 * like the editor's previous raw-JSON incarnation (now typed against the real {@link ModelOverride}
 * class instead of a generic {@code JsonObject}).
 */
final class ModelOverrideDocument {
	private final Gson gson;
	private final Path file;
	private final Map<String, Map<String, Integer>> gamevals;
	private final JsonObject defaults;
	private String source;
	private int arrayClose;
	private int lastValueEnd;
	private boolean trailingComma;
	private final List<Entry> entries = new ArrayList<>();

	static final class Entry {
		private int start;
		private int end;
		String savedBlock;
		ModelOverride override;

		private Entry(int start, int end, ModelOverride override) {
			this.start = start;
			this.end = end;
			this.override = override;
		}

		/** True once this entry has actually been written into the file at least once. */
		boolean isPersisted() { return savedBlock != null; }
	}

	ModelOverrideDocument(Gson gson, Path file, rs117.hd.scene.GamevalManager.Handle names) throws IOException {
		gamevals = Map.of("npcIds", names.getNpcs(), "objectIds", names.getObjects(),
			"projectileIds", names.getSpotanims(), "graphicsObjectIds", names.getSpotanims());
		// Material.Definition also matches RLHD's exclude-defaults factory, which writes objects.
		// Register a factory ahead of it so both runtime material types and map keys use names.
		this.gson = gson.newBuilder()
			.registerTypeAdapterFactory(new TypeAdapterFactory() {
				private final TypeAdapter<Material> materialAdapter = new Material.Adapter().nullSafe();

				@Override
				@SuppressWarnings("unchecked")
				public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
					return Material.class.isAssignableFrom(type.getRawType())
						? (TypeAdapter<T>) materialAdapter : null;
				}
			})
			.enableComplexMapKeySerialization()
			.create();
		this.defaults = this.gson.toJsonTree(new ModelOverride(), ModelOverride.class).getAsJsonObject();
		this.file = file;
		reload();
	}

	synchronized List<Entry> entries() { return List.copyOf(entries); }

	Gson gson() { return gson; }

	private void reload() throws IOException {
		source = Files.readString(file);
		entries.clear();
		int cursor = skipTrivia(source, 0);
		if (cursor == source.length() || source.charAt(cursor++) != '[')
			throw new IOException("Expected an array of model overrides");
		lastValueEnd = cursor;
		while (true) {
			cursor = skipTrivia(source, cursor);
			if (cursor == source.length())
				throw new IOException("Unclosed override array");
			char token = source.charAt(cursor);
			if (token == ']') {
				arrayClose = cursor;
				break;
			}
			if (token == ',') {
				trailingComma = true;
				cursor++;
				continue;
			}
			if (source.startsWith("null", cursor)) {
				cursor += 4;
				lastValueEnd = cursor;
				trailingComma = false;
				continue;
			}
			if (token != '{')
				throw new IOException("Expected an override object at character " + cursor);
			int end = objectEnd(source, cursor);
			ModelOverride override = gson.fromJson(source.substring(cursor, end), ModelOverride.class);
			var entry = new Entry(cursor, end, override);
			entry.savedBlock = source.substring(cursor, end);
			entries.add(entry);
			cursor = lastValueEnd = end;
			trailingComma = false;
		}
	}

	/** A brand new, not-yet-saved entry, appended to the in-memory list only. */
	synchronized Entry create(ModelOverride seed) {
		Entry entry = new Entry(-1, -1, seed);
		entries.add(entry);
		return entry;
	}

	/** A deep copy of {@code original}'s current state, as its own new not-yet-saved entry. */
	Entry duplicate(Entry original) {
		ModelOverride copy = gson.fromJson(gson.toJson(original.override, ModelOverride.class), ModelOverride.class);
		return create(copy);
	}

	synchronized void discard(Entry entry) { entries.remove(entry); }

	/** True if the override directly targets the given category and ID. */
	boolean targets(ModelOverride override, CacheModels.Category category, int id) {
		return targetIds(override, category).contains(id);
	}

	synchronized Set<Integer> targetIds(CacheModels.Category category) {
		var ids = new HashSet<Integer>();
		for (var entry : entries)
			ids.addAll(targetIds(entry.override, category));
		return ids;
	}

	private static Set<Integer> targetIds(ModelOverride override, CacheModels.Category category) {
		Set<Integer> ids;
		switch (category) {
			case NPCs: ids = override.npcIds; break;
			case Objects: ids = override.objectIds; break;
			case Graphics:
				var spotanims = new HashSet<Integer>();
				if (override.graphicsObjectIds != null)
					spotanims.addAll(override.graphicsObjectIds);
				if (override.projectileIds != null)
					spotanims.addAll(override.projectileIds);
				return spotanims;
			default: throw new AssertionError(category);
		}
		return ids == null ? Set.of() : ids;
	}

	/** Serializes an override using the same sparse data as the on-disk block. */
	String toSparseJson(ModelOverride override) {
		return format(sparse(gson.toJsonTree(override, ModelOverride.class).getAsJsonObject()),
			"", "\n");
	}

	synchronized String toSparseJson(Entry entry) {
		var edited = sparse(gson.toJsonTree(entry.override, ModelOverride.class).getAsJsonObject());
		var original = entry.isPersisted()
			? new JsonParser().parse(entry.savedBlock).getAsJsonObject() : new JsonObject();
		return format(orderLike(original, edited), "", "\n");
	}

	private JsonObject orderLike(JsonObject original, JsonObject edited) {
		var ordered = new JsonObject();
		for (String originalKey : original.keySet()) {
			String key = originalKey.equals("material") && edited.has("baseMaterial") ? "baseMaterial" : originalKey;
			var value = edited.get(key);
			if (value == null)
				continue;
			var previous = original.get(originalKey);
			if (gamevals.containsKey(key) && previous.isJsonArray() && value.isJsonArray())
				value = orderTargets(previous.getAsJsonArray(), value.getAsJsonArray(), gamevals.get(key));
			else if (previous.isJsonObject() && value.isJsonObject())
				value = orderLike(previous.getAsJsonObject(), value.getAsJsonObject());
			else if (key.equals("colorOverrides") && previous.isJsonArray() && value.isJsonArray()
				&& previous.getAsJsonArray().size() == value.getAsJsonArray().size()) {
				var rules = new JsonArray();
				for (int i = 0; i < value.getAsJsonArray().size(); i++) {
					var oldRule = previous.getAsJsonArray().get(i);
					var newRule = value.getAsJsonArray().get(i);
					rules.add(oldRule.isJsonObject() && newRule.isJsonObject()
						? orderLike(oldRule.getAsJsonObject(), newRule.getAsJsonObject()) : newRule);
				}
				value = rules;
			}
			ordered.add(key, value);
		}
		for (var field : edited.entrySet())
			if (!ordered.has(field.getKey()))
				ordered.add(field.getKey(), field.getValue());
		return ordered;
	}

	private static JsonArray orderTargets(JsonArray previous, JsonArray edited, Map<String, Integer> names) {
		var remaining = new LinkedHashMap<Integer, JsonElement>();
		for (var target : edited)
			remaining.put(targetId(target, names), target);
		var ordered = new JsonArray();
		var preservedIds = new HashSet<Integer>();
		for (var target : previous) {
			Integer id = targetId(target, names);
			var current = remaining.get(id);
			if (current != null) {
				ordered.add(target.getAsJsonPrimitive().isString() ? target : current);
				preservedIds.add(id);
			}
		}
		preservedIds.forEach(remaining::remove);
		remaining.values().forEach(ordered::add);
		return ordered;
	}

	private static Integer targetId(JsonElement target, Map<String, Integer> names) {
		return target.getAsJsonPrimitive().isNumber() ? Integer.valueOf(target.getAsInt()) : names.get(target.getAsString());
	}

	private JsonObject sparse(JsonObject full) {
		JsonObject result = new JsonObject();
		for (var field : full.entrySet()) {
			String key = field.getKey();
			JsonElement value = field.getValue();
			if (value.equals(defaults.get(key)))
				continue;
			if (key.equals("colorOverrides") && value.isJsonArray()) {
				var rules = new com.google.gson.JsonArray();
				for (var rule : value.getAsJsonArray())
					rules.add(rule.isJsonObject() ? sparse(rule.getAsJsonObject()) : rule);
				value = rules;
			} else if (key.equals("materialOverrides") && value.isJsonObject()) {
				var materials = new JsonObject();
				for (var material : value.getAsJsonObject().entrySet()) {
					var rule = material.getValue();
					materials.add(material.getKey(), rule.isJsonObject() ? sparse(rule.getAsJsonObject()) : rule);
				}
				value = materials;
			}
			result.add(key.equals("material") ? "baseMaterial" : key, value);
		}
		return result;
	}

	private String format(JsonElement value, String indent, String newline) {
		String childIndent = indent + "  ";
		var lines = new ArrayList<String>();
		if (value.isJsonObject()) {
			for (var field : value.getAsJsonObject().entrySet())
				lines.add(gson.toJson(field.getKey()) + ": " + format(field.getValue(), childIndent, newline));
			return block("{", "}", lines, indent, newline);
		}
		if (value.isJsonArray()) {
			boolean simple = true;
			for (var item : value.getAsJsonArray()) {
				lines.add(format(item, childIndent, newline));
				simple &= item.isJsonPrimitive() || item.isJsonNull();
			}
			if (lines.isEmpty())
				return "[]";
			String inline = "[ " + String.join(", ", lines) + " ]";
			if (simple && indent.length() + inline.length() <= 100)
				return inline;
			return block("[", "]", lines, indent, newline);
		}
		return gson.toJson(value);
	}

	private static String block(String open, String close, List<String> lines, String indent, String newline) {
		if (lines.isEmpty())
			return open + close;
		String prefix = newline + indent + "  ";
		return open + prefix + String.join("," + prefix, lines) + newline + indent + close;
	}

	/** Splices a single block and adjusts offsets without reloading the editor's entries. */
	synchronized void save(Entry entry, String json) throws IOException {
		if (!entries.contains(entry))
			throw new IOException("This override is no longer open");
		String current = Files.readString(file);
		if (!current.equals(source))
			rebaseOffsets(current, entry);

		if (entry.isPersisted() && entry.start < 0)
			throw new IOException("This override changed on disk. Your edits are still open.");

		String newline = source.contains("\r\n") ? "\r\n" : "\n";
		boolean append = !entry.isPersisted();
		int closeLine = source.lastIndexOf('\n', arrayClose - 1) + 1;
		boolean bracketOnOwnLine = source.substring(closeLine, arrayClose).isBlank();
		int insertAt = bracketOnOwnLine ? closeLine : arrayClose;
		String indent = append ? "  " : entryIndent(entry.start);
		String replacement = json.replace("\n", newline + indent);
		int newStart;
		String result;
		if (append) {
			boolean comma = !trailingComma && lastValueEnd > 0 && source.charAt(lastValueEnd - 1) != '[';
			String before = source.substring(0, insertAt);
			if (comma)
				before = before.substring(0, lastValueEnd) + "," + before.substring(lastValueEnd);
			if (!before.endsWith("\n"))
				before += newline;
			before += indent;
			newStart = before.length();
			result = before + replacement + newline + source.substring(insertAt);
		} else {
			newStart = entry.start;
			result = source.substring(0, entry.start) + replacement + source.substring(entry.end);
		}
		if (result.equals(source))
			return;

		Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".model-overrides-", ".tmp");
		try {
			Files.writeString(temporary, result);
			if (!Files.readString(file).equals(current))
				throw new IOException("The file changed during saving. Try saving again.");
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}

		int delta = result.length() - source.length();
		if (append) {
			lastValueEnd = newStart + replacement.length();
			trailingComma = false;
		} else {
			for (var other : entries) {
				if (other.start >= entry.end) {
					other.start += delta;
					other.end += delta;
				}
			}
			lastValueEnd += delta;
		}
		entry.start = newStart;
		entry.end = newStart + replacement.length();
		entry.savedBlock = replacement;
		arrayClose += delta;
		source = result;
	}

	private void rebaseOffsets(String current, Entry selected) throws IOException {
		var blocks = new LinkedHashMap<String, List<Integer>>();
		int cursor = skipTrivia(current, 0);
		if (cursor == current.length() || current.charAt(cursor++) != '[')
			throw new IOException("Expected an array of model overrides");
		int last = cursor;
		boolean comma = false;
		while (true) {
			cursor = skipTrivia(current, cursor);
			if (cursor == current.length())
				throw new IOException("Unclosed override array");
			char token = current.charAt(cursor);
			if (token == ']')
				break;
			if (token == ',') {
				comma = true;
				cursor++;
			} else if (current.startsWith("null", cursor)) {
				cursor += 4;
				last = cursor;
				comma = false;
			} else if (token == '{') {
				int end = objectEnd(current, cursor);
				blocks.computeIfAbsent(blockKey(current.substring(cursor, end)), key -> new ArrayList<>()).add(cursor);
				cursor = end;
				last = end;
				comma = false;
			} else {
				throw new IOException("Expected an override object at character " + cursor);
			}
		}
		var positions = new LinkedHashMap<Entry, Integer>();
		for (var entry : entries) {
			if (entry.savedBlock == null)
				continue;
			var matches = blocks.get(blockKey(entry.savedBlock));
			if (matches != null && matches.size() == 1)
				positions.put(entry, matches.get(0));
		}
		if (selected.savedBlock != null && !positions.containsKey(selected))
			throw new IOException("This override was changed, removed, or duplicated on disk. Your edits are still open.");
		for (var entry : entries) {
			entry.start = positions.getOrDefault(entry, -1);
			entry.end = entry.start < 0 ? -1 : objectEnd(current, entry.start);
		}
		source = current;
		arrayClose = cursor;
		lastValueEnd = last;
		trailingComma = comma;
	}

	private static String blockKey(String block) {
		return new JsonParser().parse(block).toString();
	}

	private String entryIndent(int start) {
		int lineStart = source.lastIndexOf('\n', start - 1) + 1;
		String indent = source.substring(lineStart, start);
		return indent.isBlank() ? indent : "  ";
	}

	private static int objectEnd(String text, int start) {
		int depth = 0;
		for (int i = start; i < text.length();) {
			char c = text.charAt(i++);
			if (c == '"' || c == '\'') {
				while (i < text.length()) {
					char next = text.charAt(i++);
					if (next == '\\')
						i++;
					else if (next == c)
						break;
				}
			} else if (c == '#' || c == '/' && i < text.length()
				&& (text.charAt(i) == '/' || text.charAt(i) == '*')) {
				i = skipTrivia(text, i - 1);
			} else if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unclosed override object");
	}

	private static int skipTrivia(String text, int cursor) {
		while (cursor < text.length()) {
			char c = text.charAt(cursor);
			if (Character.isWhitespace(c) || c == '﻿') {
				cursor++;
			} else if (c == '#' || text.startsWith("//", cursor)) {
				int end = text.indexOf('\n', cursor);
				cursor = end < 0 ? text.length() : end + 1;
			} else if (text.startsWith("/*", cursor)) {
				int end = text.indexOf("*/", cursor + 2);
				if (end < 0)
					throw new IllegalArgumentException("Unclosed comment");
				cursor = end + 2;
			} else {
				break;
			}
		}
		return cursor;
	}
}
