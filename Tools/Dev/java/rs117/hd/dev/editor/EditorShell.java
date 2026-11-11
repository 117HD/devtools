package rs117.hd.dev.editor;

import com.google.gson.JsonObject;
import java.awt.CardLayout;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import rs117.hd.dev.overrides.ModelOverridesScreen;

/** The 117 HD Editor's top-level window: tab switching, undo/redo, autosave and command search, shared across workspaces. */
public final class EditorShell extends EditorWindow {
	private static final String[] TAB_KEYS = { "overrides", "lights", "defs" };
	private static final String[] TAB_LABELS = { "Model Overrides", "Lights", "Definitions" };

	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private String activeTab = "overrides";
	private ModelOverridesScreen overrides;
	private rs117.hd.dev.definitions.DefinitionsScreen definitions;

	public static void main(String[] args) {
		SwingUtilities.invokeLater(EditorShell::launch);
	}

	private static void launch() {
		net.runelite.client.ui.laf.RuneLiteLAF.setup();
		var window = new AtomicReference<EditorShell>();
		var splash = new EditorSplash(() -> window.get().setVisible(true), EditorShell::launch, () -> System.exit(0));
		splash.setVisible(true);
		new EditorSession(splash::step, session -> {
			var shell = new EditorShell(session);
			shell.addNotify();
			shell.validate();
			window.set(shell);
			shell.overrides.whenInitialPreviewReady(splash::finish);
			session.attachPreview((runtime, scene) -> shell.overrides.render(runtime, scene, shell.definitions));
		}, error -> {
			splash.step("Could not open the editor · " + error.getMessage());
			splash.fail();
		});
	}

	private EditorShell(EditorSession session) {
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

		overrides = new ModelOverridesScreen(this, session);
		body.add(overrides, "overrides");
		body.add(stub("Lights"), "lights");
		definitions = new rs117.hd.dev.definitions.DefinitionsScreen();
		body.add(definitions, "defs");

		var status = EditorText.builder("").color(WorkspaceStyle.DIM).build();
		status.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 4, 10));
		status.setOpaque(true);
		status.setBackground(WorkspaceStyle.BAR);

		var actions = new WorkspaceActions(this, this::captureState, this::restoreState, status);
		actions.addCommandSource(() -> java.util.List.of(
			new WorkspaceActions.Command("Window", "Go to Model Overrides", "", () -> showTab("overrides")),
			new WorkspaceActions.Command("File", "Save model overrides", "", this::saveOverrides)));
		actions.reset("Model overrides");
		overrides.onSelectionChanged(() -> {
			String label = overrides.selectedOverrideLabel();
			if (label != null)
				actions.reset(label);
		});

		var tabButtons = new java.util.ArrayList<javax.swing.JButton>();
		for (int i = 0; i < TAB_KEYS.length; i++) {
			String key = TAB_KEYS[i];
			boolean ready = !key.equals("lights");
			var button = EditorButton.builder().style(WorkspaceStyle.Button.TAB).text(TAB_LABELS[i]).enabled(ready)
				.onClick(() -> selectTab(key, tabButtons)).build();
			button.setSelected(key.equals("overrides"));
			if (!ready)
				WorkspaceStyle.soon(button);
			tabButtons.add(button);
		}

		var saves = new JPanel();
		saves.setOpaque(false);

		var sidebarToggle = EditorButton.builder().icon("panel-browser", 16).tooltip("Show/hide the left column")
			.size(28, 24).buildToggle(true);
		sidebarToggle.addActionListener(e -> overrides.setSidebarVisible(sidebarToggle.isSelected()));

		var toolbar = editorToolbar(sidebarToggle, actions.toolbar(), saves, compact -> {},
			tabButtons.toArray(new javax.swing.JButton[0]));
		toolbar.setPreferredSize(new java.awt.Dimension(0, 40));
		toolbar.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
		toolbar.setBackground(WorkspaceStyle.MENU);
		installEditorContent(toolbar, body, status);

		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override public void windowOpened(java.awt.event.WindowEvent e) { actions.start(); }
			@Override public void windowIconified(java.awt.event.WindowEvent e) { updatePreviewVisibility(false); }
			@Override public void windowDeiconified(java.awt.event.WindowEvent e) { updatePreviewVisibility(true); }
			@Override public void windowClosing(java.awt.event.WindowEvent e) {
				if (definitions.confirmClose())
					dispose();
			}
			@Override public void windowClosed(java.awt.event.WindowEvent e) {
				actions.stop();
				session.close();
			}
		});
	}

	private void showTab(String key) {
		activeTab = key;
		cards.show(body, key);
		updatePreviewVisibility((getExtendedState() & ICONIFIED) == 0);
	}

	private void updatePreviewVisibility(boolean visible) {
		overrides.setPreviewActive(visible && "overrides".equals(activeTab));
		definitions.setPreviewActive(visible && "defs".equals(activeTab));
	}

	private void selectTab(String key, java.util.List<javax.swing.JButton> tabButtons) {
		for (int i = 0; i < TAB_KEYS.length; i++)
			tabButtons.get(i).setSelected(TAB_KEYS[i].equals(key));
		showTab(key);
	}

	private JsonObject captureState() { return ModelOverridesScreen.capture(overrides); }

	private void restoreState(JsonObject state) { ModelOverridesScreen.restore(overrides, state); }

	private void saveOverrides() { ModelOverridesScreen.saveSelected(overrides); }

	private static JPanel stub(String name) {
		var panel = new JPanel(new java.awt.BorderLayout());
		panel.setBackground(WorkspaceStyle.GAP);
		var label = EditorText.builder(name + " isn't built yet").color(WorkspaceStyle.DIM).build();
		label.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		panel.add(label);
		return panel;
	}
}
