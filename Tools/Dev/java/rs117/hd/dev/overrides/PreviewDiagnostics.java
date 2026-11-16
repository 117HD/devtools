package rs117.hd.dev.overrides;

import rs117.hd.dev.sceneloader.scene.BuiltModel;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ColorUtils;

final class PreviewDiagnostics {
	private PreviewDiagnostics() {}

	static BuiltModel normals(BuiltModel model) {
		var colors = new int[model.getFaceCount()];
		for (int face = 0; face < colors.length; face++) {
			int a = model.getFaceIndices1()[face], b = model.getFaceIndices2()[face], c = model.getFaceIndices3()[face];
			float ax = model.getVerticesX()[b] - model.getVerticesX()[a];
			float ay = model.getVerticesY()[b] - model.getVerticesY()[a];
			float az = model.getVerticesZ()[b] - model.getVerticesZ()[a];
			float bx = model.getVerticesX()[c] - model.getVerticesX()[a];
			float by = model.getVerticesY()[c] - model.getVerticesY()[a];
			float bz = model.getVerticesZ()[c] - model.getVerticesZ()[a];
			float x = ay * bz - az * by, y = az * bx - ax * bz, z = ax * by - ay * bx;
			float length = (float) Math.sqrt(x * x + y * y + z * z);
			colors[face] = length == 0 ? 0 : packedRgb(x / length * .5f + .5f, y / length * .5f + .5f, z / length * .5f + .5f);
		}
		return model.withDiagnosticColors(colors);
	}

	static BuiltModel uv(BuiltModel model, ModelOverride override) {
		int light = packedRgb(.89f, .89f, .89f);
		int dark = packedRgb(.28f, .45f, .70f);
		var colors = new int[model.getFaceCount()];
		var coordinates = new float[12];
		var working = new float[12];
		for (int face = 0; face < colors.length; face++) {
			var type = override.uvType;
			float u, v;
			switch (type) {
				case VANILLA:
				case GEOMETRY:
				case WORLD_XY:
				case WORLD_XZ:
				case WORLD_YZ:
					int a = model.getFaceIndices1()[face], b = model.getFaceIndices2()[face], c = model.getFaceIndices3()[face];
					float x = (model.getVerticesX()[a] + model.getVerticesX()[b] + model.getVerticesX()[c]) / 384f;
					float y = (model.getVerticesY()[a] + model.getVerticesY()[b] + model.getVerticesY()[c]) / 384f;
					float z = (model.getVerticesZ()[a] + model.getVerticesZ()[b] + model.getVerticesZ()[c]) / 384f;
					u = type == rs117.hd.scene.model_overrides.UvType.WORLD_YZ ? y : x;
					v = type == rs117.hd.scene.model_overrides.UvType.WORLD_YZ || type == rs117.hd.scene.model_overrides.UvType.WORLD_XZ ? z : y;
					break;
				default:
					override.fillUvsForFace(coordinates, model, 0, type, face, working);
					u = (coordinates[0] + coordinates[4] + coordinates[8]) / 3;
					v = (coordinates[1] + coordinates[5] + coordinates[9]) / 3;
			}
			int checker = (int) Math.floor(u * 8) + (int) Math.floor(v * 8);
			colors[face] = (checker & 1) == 0 ? light : dark;
		}
		return model.withDiagnosticColors(colors);
	}

	private static int packedRgb(float r, float g, float b) {
		return ColorUtils.packHsl(ColorUtils.srgbToHsl(r, g, b));
	}
}
