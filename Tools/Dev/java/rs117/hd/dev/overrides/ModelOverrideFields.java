package rs117.hd.dev.overrides;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import rs117.hd.scene.model_overrides.ModelOverride;

/**
 * Read access to the handful of {@link ModelOverride} fields that are {@code private} with no
 * generated getter (the HSL/alpha ranges, hideAsWaterEffect, materials, faces, colors). Writing
 * still goes through the class's own public fluent setters (Lombok {@code @Setter @Accessors(fluent=true)}
 * already makes those public regardless of field visibility) — this is read-only, and never touches
 * the RLHD submodule itself.
 */
final class ModelOverrideFields {
	private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();

	private static final java.lang.reflect.Method PARSE_COLORS = colorParser();

	private ModelOverrideFields() {}

	private static java.lang.reflect.Method colorParser() {
		try {
			var method = ModelOverride.class.getDeclaredMethod("parseAhslConditions", com.google.gson.JsonElement.class);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot access the model override color parser", e);
		}
	}

	static ModelOverride.AhslPredicate colorPredicate(ModelOverride rule, com.google.gson.JsonElement colors) {
		try {
			return (ModelOverride.AhslPredicate) PARSE_COLORS.invoke(rule, colors);
		} catch (java.lang.reflect.InvocationTargetException e) {
			// A malformed condition should not prevent the editor from opening the rule.
			if (e.getCause() instanceof RuntimeException)
				return ahsl -> false;
			throw new IllegalStateException(e.getCause());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field field(String name) {
		return FIELDS.computeIfAbsent(name, key -> {
			try {
				Field field = ModelOverride.class.getDeclaredField(key);
				field.setAccessible(true);
				return field;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("No such ModelOverride field: " + key, e);
			}
		});
	}

	static int getInt(ModelOverride override, String name) {
		try {
			return field(name).getInt(override);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean getBoolean(ModelOverride override, String name) {
		try {
			return field(name).getBoolean(override);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static <T> T get(ModelOverride override, String name, Class<T> type) {
		try {
			return type.cast(field(name).get(override));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
