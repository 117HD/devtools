package rs117.hd.dev.overrides;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Model;
import rs117.hd.utils.ColorUtils;

/** Distinct packed-AHSL colors on a {@link Model}, with a face count per color — nothing in the
 * codebase already does this, so it's built fresh here. Packing matches the renderer's own
 * convention ({@code SceneUploader}: alpha in bits 16-23, 16-bit Jagex HSL below that). */
final class ModelColorPalette {
	static final class Entry {
		final int ahsl;
		final int hue, saturation, lightness;
		final Color color;
		int faceCount;

		private Entry(int ahsl) {
			this.ahsl = ahsl;
			int hsl = ahsl & 0xFFFF;
			int[] unpacked = ColorUtils.unpackRawHsl(hsl);
			hue = unpacked[0];
			saturation = unpacked[1];
			lightness = unpacked[2];
			float[] srgb = ColorUtils.packedHslToSrgb(hsl);
			// Show the HSL independently of face opacity. Keep opacity in ahsl for matching.
			color = new Color(srgb[0], srgb[1], srgb[2]);
		}
	}

	private ModelColorPalette() {}

	static List<Entry> distinctColors(Model model) {
		Map<Integer, Entry> byAhsl = new LinkedHashMap<>();
		int[] colors1 = model.getFaceColors1();
		byte[] transparencies = model.getFaceTransparencies();
		int faceCount = model.getFaceCount();
		for (int face = 0; face < faceCount; face++) {
			int transparency = transparencies == null ? 0 : transparencies[face] & 0xFF;
			int ahsl = (0xFF - transparency) << 16 | (colors1[face] & 0xFFFF);
			byAhsl.computeIfAbsent(ahsl, Entry::new).faceCount++;
		}
		return new ArrayList<>(byAhsl.values());
	}
}
