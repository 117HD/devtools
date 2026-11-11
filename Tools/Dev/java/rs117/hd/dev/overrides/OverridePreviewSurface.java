package rs117.hd.dev.overrides;

import java.awt.*;
import javax.swing.JPanel;
import javax.swing.Timer;
import rs117.hd.dev.editor.WorkspaceStyle;

final class OverridePreviewSurface extends JPanel {
	private static final Color DIVIDER_COLOR = new Color(0xE8A33D);
	private static final Color VANILLA_LABEL_COLOR = new Color(0x9A9A9A);
	private static final Color CAPTION_SHADOW_COLOR = new Color(0, 0, 0, 204);
	private static final Color CAPTION_TEXT_COLOR = new Color(255, 255, 255, 191);
	private static final Font SPLIT_LABEL_FONT = WorkspaceStyle.SEMIBOLD.deriveFont(10f);
	private static final Font CAPTION_FONT = WorkspaceStyle.UI.deriveFont(10f);
	private static final int DIVIDER_HIT_RADIUS = 6;
	private double splitPosition = .5;
	private boolean dividerActive;
	private String toast;
	private String caption;
	private long toastStarted;
	private final Timer toastAnimation = new Timer(16, event -> {
		if (toastElapsed() >= 2.2)
			clearToast();
		repaint();
	});
	private Image vanilla;
	private Image overridden;
	private PreviewFacePicker vanillaPicker, overridePicker;
	private rs117.hd.scene.model_overrides.ModelOverride.AhslPredicate colorRule;
	private Integer hoverColor;
	private Point pickPoint;

	private String mode = "Override";
	private final boolean comparison;
	private int splitLabelBaseline = 58;

	OverridePreviewSurface(boolean comparison) {
		this.comparison = comparison;
		setBackground(WorkspaceStyle.HEADER);
	}

	void splitLabelBaseline(int toolbarBottom) { splitLabelBaseline = Math.max(58, toolbarBottom + 16); }

	void mode(String mode) {
		this.mode = mode;
		repaint();
	}

	int dividerX() {
		return (int) Math.round(getWidth() * splitPosition);
	}

	boolean isOverDivider(int x) {
		return comparison && "Split".equals(mode) && Math.abs(x - dividerX()) <= DIVIDER_HIT_RADIUS;
	}

	void setDividerActive(boolean active) {
		if (dividerActive != active) {
			dividerActive = active;
			repaint();
		}
	}

	void moveDivider(int x) {
		if (getWidth() <= 0)
			return;
		splitPosition = Math.max(.05, Math.min(.95, x / (double) getWidth()));
		repaint();
	}

	void frame(boolean isVanilla, Image image) {
		if (isVanilla)
			vanilla = image;
		else
			overridden = image;
		repaint();
	}

	void modelFrame(boolean isVanilla, Image image, PreviewFacePicker picker) {
		if (isVanilla)
			vanillaPicker = picker;
		else
			overridePicker = picker;
		frame(isVanilla, image);
	}

	void hoverPick(Point point) {
		if (!java.util.Objects.equals(pickPoint, point)) {
			pickPoint = point;
			repaint();
		}
	}

	void highlightColors(rs117.hd.scene.model_overrides.ModelOverride.AhslPredicate rule, Integer hover) {
		colorRule = rule;
		hoverColor = hover;
		repaint();
	}

	void caption(String text) { caption = text; }

	void toast(String text) {
		toast = text;
		toastStarted = System.nanoTime();
		toastAnimation.start();
		repaint();
	}

	private double toastElapsed() {
		return (System.nanoTime() - toastStarted) / 1e9;
	}

	private void clearToast() {
		toast = null;
		toastAnimation.stop();
	}

	@Override public void removeNotify() {
		clearToast();
		super.removeNotify();
	}

	void clear() {
		vanilla = overridden = null;
		vanillaPicker = overridePicker = null;
		clearToast();
		repaint();
	}

	@Override protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		var g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int width = getWidth();
		int height = getHeight();
		if (comparison && "Split".equals(mode)) {
			int divider = dividerX();
			var clip = g.getClip();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setFont(SPLIT_LABEL_FONT);
			g.clipRect(0, 0, divider, height);
			drawFrame(g, vanilla, 0, width, height);
			g.setColor(VANILLA_LABEL_COLOR);
			g.drawString("VANILLA", divider - 10 - g.getFontMetrics().stringWidth("VANILLA"), splitLabelBaseline);
			g.setClip(clip);
			g.clipRect(divider, 0, width - divider, height);
			drawFrame(g, overridden, 0, width, height);
			g.setColor(DIVIDER_COLOR);
			g.drawString("OVERRIDE", divider + 10, splitLabelBaseline);
			g.setClip(clip);
			g.fillRect(divider - (dividerActive ? 1 : 0), 0, dividerActive ? 3 : 1, height);
		} else {
			drawFrame(g, comparison && "Vanilla".equals(mode) ? vanilla : overridden, 0, width, height);
		}
		if (caption != null && !comparison) {
			g.setFont(CAPTION_FONT);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(CAPTION_SHADOW_COLOR);
			g.drawString(caption, 8, height - 7);
			g.setColor(CAPTION_TEXT_COLOR);
			g.drawString(caption, 8, height - 8);
		}
		paintToast(g, width, height);
		g.dispose();
	}

	private void paintToast(Graphics2D g, int width, int height) {
		if (toast == null)
			return;
		double elapsed = toastElapsed();
		float alpha = (float) Math.max(0, Math.min(1, Math.min(elapsed / .18, (2.2 - elapsed) / .3)));
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(WorkspaceStyle.SEMIBOLD);
		int pillWidth = g.getFontMetrics().stringWidth(toast) + 24;
		int x = (width - pillWidth) / 2, y = height - 40 + (int) ((1 - alpha) * 8);
		g.setColor(WorkspaceStyle.MENU);
		g.fillRoundRect(x, y, pillWidth, 28, 10, 10);
		g.setColor(WorkspaceStyle.BORDER);
		g.drawRoundRect(x, y, pillWidth, 28, 10, 10);
		g.setColor(WorkspaceStyle.TEXT);
		g.drawString(toast, x + 12, y + 18);
	}

	Rectangle imageBounds(boolean isVanilla) {
		return imageBounds(isVanilla ? vanilla : overridden, 0, getWidth(), getHeight());
	}

	private Rectangle imageBounds(Image image, int x, int width, int height) {
		if (image == null || width <= 0 || height <= 0)
			return new Rectangle();
		int iw = image.getWidth(this), ih = image.getHeight(this);
		if (iw <= 0 || ih <= 0)
			return new Rectangle();
		double scale = Math.min(width / (double) iw, height / (double) ih);
		int w = (int) (iw * scale), h = (int) (ih * scale);
		return new Rectangle(x + (width - w) / 2, (height - h) / 2, w, h);
	}

	private void drawFrame(Graphics2D g, Image image, int x, int width, int height) {
		var bounds = imageBounds(image, x, width, height);
		if (!bounds.isEmpty()) {
			g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, this);
			var picker = image == vanilla ? vanillaPicker : overridePicker;
			if (picker == null)
				return;
			boolean pickThisSide = pickPoint != null && !isOverDivider(pickPoint.x) && bounds.contains(pickPoint)
				&& (!"Split".equals(mode) || (pickPoint.x < dividerX()) == (image == vanilla));
			int hoveredFace = pickThisSide
				? picker.pick(new Point(pickPoint.x - bounds.x, pickPoint.y - bounds.y), bounds.width, bounds.height)
				: -1;
			if (colorRule != null || hoverColor != null || hoveredFace >= 0)
				picker.highlight(g, bounds, colorRule, hoverColor, hoveredFace);
		}
	}
}
