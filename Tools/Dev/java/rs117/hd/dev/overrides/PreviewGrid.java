package rs117.hd.dev.overrides;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import rs117.hd.utils.Mat4;

final class PreviewGrid {
	private static final int EXTENT = 256;
	private static final int SPACING = 32;
	private static final Color GRID = new Color(0x343434);
	private static final Color AXIS = new Color(0x3F3F3F);

	private BufferedImage overlay;
	private float[] projection;
	private int[] gridPixels = new int[256];
	private int gridPixelCount;
	private int x, z, y;

	void draw(BufferedImage frame, float[] viewProjection, int originX, int originZ, int groundY) {
		if (frame.getType() != BufferedImage.TYPE_INT_RGB)
			return;
		int width = frame.getWidth();
		int height = frame.getHeight();
		if (overlay == null || overlay.getWidth() != width || overlay.getHeight() != height
			|| x != originX || z != originZ || y != groundY || !java.util.Arrays.equals(projection, viewProjection)) {
			rebuild(width, height, viewProjection, originX, originZ, groundY);
		}

		// The center pass clears to a flat color. Keep model pixels above the grid.
		int[] pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
		int[] grid = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();
		int background = pixels[0] & 0xFFFFFF;
		for (int i = 0; i < gridPixelCount; i++) {
			int pixel = gridPixels[i];
			if ((pixels[pixel] & 0xFFFFFF) == background)
				pixels[pixel] = grid[pixel];
		}
	}

	private void rebuild(int width, int height, float[] viewProjection, int originX, int originZ, int groundY) {
		projection = viewProjection.clone();
		x = originX;
		z = originZ;
		y = groundY;
		if (overlay == null || overlay.getWidth() != width || overlay.getHeight() != height)
			overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		else
			java.util.Arrays.fill(((DataBufferInt) overlay.getRaster().getDataBuffer()).getData(), 0);
		var g = overlay.createGraphics();
		try {
			for (int offset = -EXTENT; offset <= EXTENT; offset += SPACING) {
				if (offset == 0)
					continue;
				g.setColor(GRID);
				line(g, viewProjection, width, height, originX, originZ, groundY, offset, false);
				line(g, viewProjection, width, height, originX, originZ, groundY, offset, true);
			}
			g.setColor(AXIS);
			line(g, viewProjection, width, height, originX, originZ, groundY, 0, false);
			line(g, viewProjection, width, height, originX, originZ, groundY, 0, true);
		} finally {
			g.dispose();
		}
		gridPixelCount = 0;
		int[] grid = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();
		for (int pixel = 0; pixel < grid.length; pixel++) {
			if (grid[pixel] == 0)
				continue;
			if (gridPixelCount == gridPixels.length)
				gridPixels = java.util.Arrays.copyOf(gridPixels, gridPixels.length * 2);
			gridPixels[gridPixelCount++] = pixel;
		}
	}

	private static void line(java.awt.Graphics2D g, float[] viewProjection, int width, int height,
		int originX, int originZ, int groundY, int offset, boolean alongX) {
		float[] start = {originX + (alongX ? -EXTENT : offset), groundY, originZ + (alongX ? offset : -EXTENT), 1};
		float[] end = {originX + (alongX ? EXTENT : offset), groundY, originZ + (alongX ? offset : EXTENT), 1};
		float[] a = new float[4];
		float[] b = new float[4];
		Mat4.mulVec(a, viewProjection, start);
		Mat4.mulVec(b, viewProjection, end);
		if (a[3] < 1 && b[3] < 1)
			return;
		clipBehindCamera(a, b);
		clipBehindCamera(b, a);
		g.drawLine(Math.round((a[0] / a[3] + 1) * width / 2),
			Math.round((1 - a[1] / a[3]) * height / 2),
			Math.round((b[0] / b[3] + 1) * width / 2),
			Math.round((1 - b[1] / b[3]) * height / 2));
	}

	private static void clipBehindCamera(float[] point, float[] other) {
		if (point[3] >= 1)
			return;
		float t = (1 - point[3]) / (other[3] - point[3]);
		point[0] += t * (other[0] - point[0]);
		point[1] += t * (other[1] - point[1]);
		point[3] = 1;
	}
}
