package rs117.hd.dev.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public final class PropertySection extends JPanel {
	private final EditorSection section;
	private final String title;
	private final JComponent fields;
	private final JLabel changedBadge;
	private final JLabel inactiveNote;
	private final JLabel resetLink;
	private boolean active = true;
	private Runnable resetAction;

	public PropertySection(String title, JComponent content) {
		this(title, content, null);
	}

	public PropertySection(String title, JComponent content, java.util.function.Consumer<Boolean> onToggle) {
		super(new BorderLayout());
		this.title = title;
		this.fields = content;
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2A2A2A)),
			BorderFactory.createEmptyBorder(14, 0, 14, 0)));

		var body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		content.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(content, BorderLayout.CENTER);
		body.setMinimumSize(new java.awt.Dimension(0, 0));

		section = EditorSection.builder(title)
			.content(body)
			.bodyBorder(BorderFactory.createEmptyBorder(6, 17, 0, 10))
			.titleColor(new Color(0xE6E6E6))
			.onToggle(onToggle)
			.build();
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(section, BorderLayout.CENTER);
		section.header().setOpaque(false);
		section.header().setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		section.header().setPreferredSize(new java.awt.Dimension(0, 24));
		section.header().setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 24));

		for (Component child : section.header().getComponents()) {
			if (child instanceof JLabel && title.equals(((JLabel) child).getText())) {
				JLabel titleLabel = (JLabel) child;
				titleLabel.setFont(WorkspaceStyle.SEMIBOLD.deriveFont(13f));
				titleLabel.setForeground(new Color(0xE6E6E6));
			}
		}

		changedBadge = pill(new Color(0x334D73), new Color(0xCFE0FF));
		changedBadge.setVisible(false);

		inactiveNote = new JLabel();
		inactiveNote.setFont(WorkspaceStyle.UI.deriveFont(11f));
		inactiveNote.setForeground(new Color(0x7D7D7D));
		inactiveNote.setVisible(false);

		resetLink = resetLink();
		resetLink.setVisible(false);

		var trailing = new JPanel();
		trailing.setOpaque(false);
		trailing.setLayout(new BoxLayout(trailing, BoxLayout.X_AXIS));
		var titleLabel = ((BorderLayout) section.header().getLayout()).getLayoutComponent(BorderLayout.CENTER);
		section.header().remove(titleLabel);
		var heading = new JPanel(new BorderLayout());
		heading.setOpaque(false);
		var titleGroup = new JPanel(new java.awt.GridBagLayout());
		titleGroup.setOpaque(false);
		var cell = new java.awt.GridBagConstraints();
		cell.gridy = 0;
		titleGroup.add(titleLabel, cell);
		cell.insets = new java.awt.Insets(0, 8, 0, 0);
		titleGroup.add(changedBadge, cell);
		heading.add(titleGroup, BorderLayout.WEST);
		section.header().add(heading, BorderLayout.CENTER);
		heading.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e))
					setExpanded(!isExpanded());
			}
		});
		trailing.add(Box.createHorizontalStrut(8));
		trailing.add(inactiveNote);
		trailing.add(Box.createHorizontalStrut(8));
		trailing.add(resetLink);
		section.header().add(trailing, BorderLayout.EAST);
	}

	@Override public java.awt.Dimension getMinimumSize() {
		return new java.awt.Dimension(0, super.getMinimumSize().height);
	}

	public String title() { return title; }
	public JComponent fields() { return fields; }

	public void setChangedCount(int count) {
		setChangedCount(count, count > 0);
	}

	public void setChangedCount(int count, boolean showReset) {
		changedBadge.setText(String.valueOf(count));
		changedBadge.setVisible(count > 0);
		resetLink.setVisible(showReset && resetAction != null);
		revalidate();
		repaint();
	}

	public void onReset(Runnable action) {
		resetAction = action;
		resetLink.setVisible(action != null && changedBadge.isVisible());
	}

	public void setActive(boolean active, String inactiveNoteText) {
		this.active = active;
		setInactiveNote(active ? null : inactiveNoteText);
	}

	public void setInactiveNote(String text) {
		inactiveNote.setText(text);
		inactiveNote.setVisible(text != null && !text.isEmpty());
		revalidate();
		repaint();
	}

	public void setExpanded(boolean expanded) {
		section.setExpanded(expanded);
	}

	public boolean isExpanded() {
		return section.isExpanded();
	}

	@Override
	public void paint(Graphics graphics) {
		super.paint(graphics);
		if (active)
			return;
		Graphics2D g = (Graphics2D) graphics.create();
		g.setColor(new Color(0, 0, 0, 140));
		var bounds = SwingUtilities.convertRectangle(section, section.content().getBounds(), this);
		if (section.isExpanded())
			g.fill(bounds);
		g.dispose();
	}

	private static JLabel pill(Color background, Color foreground) {
		JLabel label = new JLabel("", SwingConstants.CENTER) {
			@Override public java.awt.Dimension getPreferredSize() {
				return new java.awt.Dimension(super.getPreferredSize().width, 16);
			}

			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(background);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
				g.dispose();
				super.paintComponent(graphics);
			}
		};
		label.setOpaque(false);
		label.setFont(WorkspaceStyle.MONO.deriveFont(10f));
		label.setForeground(foreground);
		label.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
		return label;
	}

	private JLabel resetLink() {
		JLabel label = new JLabel("Reset") {
			private boolean hover;

			@Override
			protected void paintComponent(Graphics graphics) {
				if (hover) {
					Graphics2D g = (Graphics2D) graphics.create();
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setColor(new Color(0x2C2C2C));
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
					g.dispose();
				}
				super.paintComponent(graphics);
			}

			{
				addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
					@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
					@Override public void mouseClicked(MouseEvent event) {
						if (SwingUtilities.isLeftMouseButton(event) && resetAction != null)
							resetAction.run();
					}
				});
			}
		};
		label.setOpaque(false);
		label.setFont(WorkspaceStyle.UI.deriveFont(11f));
		label.setForeground(new Color(0x9A9A9A));
		label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return label;
	}
}
