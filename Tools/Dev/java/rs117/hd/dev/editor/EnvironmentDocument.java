package rs117.hd.dev.editor;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ResourcePath;

final class EnvironmentDocument {
	static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	final Map<String, JsonElement> defaults = new LinkedHashMap<>();
	final Path path = Path.of("environments.json").toAbsolutePath();
	private String source;
	JsonArray saved;
	JsonArray entries;

	EnvironmentDocument(Gson runtimeGson) throws IOException {
		// The runtime adapters encode colour and angle defaults in the file's units.
		var empty = new Environment();
		empty.varbitCondition = empty.varpCondition = null;
		runtimeGson.toJsonTree(empty).getAsJsonObject().entrySet().forEach(e -> defaults.put(e.getKey(), e.getValue()));
		source = Files.exists(path) ? Files.readString(path) : null;
		if (source == null) {
			try (var reader = ResourcePath.path(EnvironmentManager.class, "environments.json").toReader()) {
				saved = new JsonParser().parse(reader).getAsJsonArray();
			}
		} else {
			saved = new JsonParser().parse(source).getAsJsonArray();
		}
		entries = saved.deepCopy();
	}

	static String area(JsonObject entry) { return string(entry, "area", "NONE"); }
	static String key(JsonObject entry) { return string(entry, "key", area(entry)); }

	static String string(JsonObject entry, String name, String fallback) {
		var value = entry.get(name);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
	}

	JsonObject sparse(JsonObject entry) {
		var result = entry.deepCopy();
		for (var field : entry.entrySet()) {
			var name = field.getKey();
			if (name.equals("area"))
				continue;
			if (field.getValue().isJsonNull() || isDefault(name, field.getValue())
				|| (name.equals("key") && field.getValue().getAsString().equals(area(entry))))
				result.remove(name);
		}
		return result;
	}

	boolean isDefault(String key, JsonElement value) {
		var defaultValue = defaults.get(key);
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			&& defaultValue != null && defaultValue.isJsonPrimitive() && defaultValue.getAsJsonPrimitive().isNumber())
			return value.getAsBigDecimal().compareTo(defaultValue.getAsBigDecimal()) == 0;
		if (value.equals(defaultValue))
			return true;
		return key.endsWith("Color") && defaultValue != null && value.isJsonPrimitive()
			&& defaultValue.isJsonPrimitive() && value.getAsString().equalsIgnoreCase(defaultValue.getAsString());
	}

	void save() throws IOException {
		String current = Files.exists(path) ? Files.readString(path) : null;
		if (!java.util.Objects.equals(source, current))
			throw new IOException("environments.json changed on disk");
		var output = new JsonArray();
		for (var element : entries)
			output.add(sparse(element.getAsJsonObject()));
		String result = JSON.toJson(output) + "\n";
		var temp = Files.createTempFile(path.getParent(), ".environments-", ".tmp");
		try {
			Files.writeString(temp, result);
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
		source = result;
		saved = entries.deepCopy();
	}
}
