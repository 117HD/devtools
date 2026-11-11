package rs117.hd.dev.overrides;

import java.awt.Point;
import net.runelite.api.Model;
import rs117.hd.utils.Mat4;

/** Finds the nearest projected triangle using the matrix from the displayed frame. */
final class PreviewFacePicker {
	private static final java.awt.Color HOVER_FILL_COLOR = new java.awt.Color(122, 165, 232, 89);
	private static final java.awt.Color OUTLINE_COLOR = new java.awt.Color(0xE8A33D);
	private static final java.awt.BasicStroke OUTLINE_STROKE = new java.awt.BasicStroke(2f);
	private final Model source, model;
	private final float[] matrix;
	private final int originX, originY, originZ, rotation;
	private float[][] projected;
	private int projectedWidth, projectedHeight;

	PreviewFacePicker(Model source, Model model, float[] matrix, int x, int y, int z, int rotation) {
		this.source = source;
		this.model = model;
		this.matrix = matrix.clone();
		originX = x;
		originY = y;
		originZ = z;
		this.rotation = rotation;
	}

	boolean matches(Model current) { return source == current; }

	float[] matrix() { return matrix; }

	int pick(Point point, int width, int height) {
		if (model == null || width <= 0 || height <= 0)
			return -1;
		var projected = project(width, height);
		int[] first = model.getFaceIndices1(), second = model.getFaceIndices2(), third = model.getFaceIndices3();
		int[] faceColors = model.getFaceColors3();
		int face = -1;
		float depth = Float.POSITIVE_INFINITY;
		for (int i = 0; i < model.getFaceCount(); i++) {
			float[] a = projected[first[i]], b = projected[second[i]], c = projected[third[i]];
			if (a[3] <= 0 || b[3] <= 0 || c[3] <= 0 || faceColors[i] == -2)
				continue;
			float area = edge(a, b, c[0], c[1]);
			if (area == 0)
				continue;
			float u = edge(b, c, point.x, point.y) / area;
			float v = edge(c, a, point.x, point.y) / area;
			float w = 1 - u - v;
			float hitDepth = u * a[2] + v * b[2] + w * c[2];
			if (u >= 0 && v >= 0 && w >= 0 && hitDepth < depth) {
				depth = hitDepth;
				face = i;
			}
		}
		return face;
	}

	static int faceColor(Model model, int face) {
		byte[] alpha = model.getFaceTransparencies();
		return ((255 - (alpha == null ? 0 : alpha[face] & 255)) << 16) | (model.getFaceColors1()[face] & 65535);
	}

	void highlight(java.awt.Graphics2D graphics, java.awt.Rectangle bounds,
		rs117.hd.scene.model_overrides.ModelOverride.AhslPredicate rule, Integer hover, int hoveredFace) {
		var vertices = project(bounds.width, bounds.height);
		var g = (java.awt.Graphics2D) graphics.create();
		try {
			g.clip(bounds);
			g.translate(bounds.x, bounds.y);
			g.setStroke(OUTLINE_STROKE);
			int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
			var faceShape = new java.awt.geom.Path2D.Float();
			int[] faceColors = model.getFaceColors3();
			for (int face = 0; face < model.getFaceCount(); face++) {
				if (faceColors[face] == -2)
					continue;
				int ahsl = faceColor(source, face);
				boolean hovered = face == hoveredFace || hover != null && (ahsl & 65535) == hover;
				if (!hovered && (rule == null || !rule.test(ahsl)))
					continue;
				float[] first = vertices[a[face]], second = vertices[b[face]], third = vertices[c[face]];
				if (first[3] <= 0 || second[3] <= 0 || third[3] <= 0)
					continue;
				faceShape.reset();
				faceShape.moveTo(first[0], first[1]);
				faceShape.lineTo(second[0], second[1]);
				faceShape.lineTo(third[0], third[1]);
				faceShape.closePath();
				if (hovered) {
					g.setColor(HOVER_FILL_COLOR);
					g.fill(faceShape);
				}
				g.setColor(hovered ? java.awt.Color.WHITE : OUTLINE_COLOR);
				g.draw(faceShape);
			}
		} finally {
			g.dispose();
		}
	}

	private float[][] project(int width, int height) {
		if (projected != null && projectedWidth == width && projectedHeight == height)
			return projected;
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		if (projected == null)
			projected = new float[model.getVerticesCount()][4];
		projectedWidth = width;
		projectedHeight = height;
		double angle = rotation * Math.PI / 180;
		float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
		var position = new float[] {0, 0, 0, 1};
		for (int i = 0; i < projected.length; i++) {
			position[0] = originX + x[i] * cos + z[i] * sin;
			position[1] = originY + y[i];
			position[2] = originZ + z[i] * cos - x[i] * sin;
			float[] vertex = projected[i];
			Mat4.mulVec(vertex, matrix, position);
			if (vertex[3] <= 0)
				continue;
			vertex[0] = (1 + vertex[0] / vertex[3]) * width / 2;
			vertex[1] = (1 - vertex[1] / vertex[3]) * height / 2;
			vertex[2] /= vertex[3];
		}
		return projected;
	}

	private static float edge(float[] a, float[] b, float x, float y) {
		return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
	}
}
