package rs117.hd.dev.overrides;

import rs117.hd.dev.sceneloader.scene.BuiltModel;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.WindDisplacement;

final class WindPreview {
	private static final double PLAYER_RADIUS = 48;
	private static final double[] STRENGTH = { .25, .5, .75, 1, 1.25, 1.5, 2 };

	private WindPreview() {}

	static BuiltModel deform(BuiltModel model, ModelOverride override, double time, float[] pointer,
		float[] projection, int originX, int originY, int originZ, int bottom) {
		int count = model.getVerticesCount();
		float[] x = model.getVerticesX().clone();
		float[] y = model.getVerticesY().clone();
		float[] z = model.getVerticesZ().clone();
		double height = 1;
		for (int i = 0; i < count; i++)
			height = Math.max(height, bottom - y[i]);

		var mode = override.windDisplacementMode;
		double[] player = mode == WindDisplacement.OBJECT
			? groundPoint(pointer, projection, originY) : null;
		double angle = Math.toRadians(override.rotate);
		double sin = Math.sin(angle), cos = Math.cos(angle);
		double playerX = player == null ? 0 : (player[0] - originX) * cos - (player[1] - originZ) * sin;
		double playerZ = player == null ? 0 : (player[1] - originZ) * cos + (player[0] - originX) * sin;
		int modifier = Math.max(-3, Math.min(3, override.windDisplacementModifier));
		double amplitude = Math.min(24, Math.max(2, height * .09)) * STRENGTH[modifier + 3];
		double gust = Math.sin(time * 2.4) * .7 + Math.sin(time * 4.1) * .3;

		for (int i = 0; i < count; i++) {
			double weight = (bottom - y[i]) / height;
			if (override.invertDisplacementStrength)
				weight = 1 - weight;
			double wave = gust;
			switch (mode) {
				case VERTEX_JIGGLE:
					wave = .35 * Math.sin(time * 9 + x[i] * .09 + z[i] * .07);
					break;
				case VERTEX:
				case VERTEX_WITH_HEMISPHERE_BLEND:
					wave += .25 * Math.sin(time * 4 + x[i] * .07 + z[i] * .04);
					if (mode == WindDisplacement.VERTEX_WITH_HEMISPHERE_BLEND)
						weight *= Math.min(1, Math.max(0, (weight - .3) * 2 + Math.hypot(x[i], z[i]) / 120));
					break;
				default:
					break;
			}

			if (player != null) {
				double dx = x[i] - playerX, dz = z[i] - playerZ;
				double distance = Math.hypot(dx, dz);
				if (distance < PLAYER_RADIUS) {
					double falloff = 1 - distance / PLAYER_RADIUS;
					double bend = Math.min(height, 96) * weight * falloff * falloff;
					x[i] += dx * bend * .5 / PLAYER_RADIUS;
					z[i] += dz * bend * .5 / PLAYER_RADIUS;
					y[i] += bend * falloff;
				}
			}
			x[i] += wave * amplitude * weight;
			z[i] += wave * amplitude * weight * .4;
		}
		return model.withVertices(x, y, z);
	}

	// Solve screenX = clipX / clipW and screenY = clipY / clipW on the ground plane.
	static double[] groundPoint(float[] pointer, float[] matrix, double groundY) {
		if (pointer == null || matrix == null)
			return null;
		double sx = pointer[0], sy = pointer[1];
		double a = matrix[0] - sx * matrix[3], b = matrix[8] - sx * matrix[11];
		double c = matrix[1] - sy * matrix[3], d = matrix[9] - sy * matrix[11];
		double w = groundY * matrix[7] + matrix[15];
		double u = sx * w - groundY * matrix[4] - matrix[12];
		double v = sy * w - groundY * matrix[5] - matrix[13];
		double determinant = a * d - b * c;
		if (Math.abs(determinant) < 1e-9)
			return null;
		double x = (u * d - b * v) / determinant;
		double z = (a * v - u * c) / determinant;
		return matrix[3] * x + matrix[11] * z + w > 0 ? new double[] { x, z } : null;
	}
}
