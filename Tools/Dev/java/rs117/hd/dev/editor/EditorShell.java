package rs117.hd.dev.editor;

import java.awt.CardLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import rs117.hd.dev.definitions.DefinitionsScreen;
import rs117.hd.dev.overrides.ModelOverridesScreen;

/** Owns the editor workspaces and routes shared controls to the active one. */
public final class EditorShell extends EditorWindow {
	private static final String[] TAB_KEYS = { "overrides", "environments", "lights", "defs" };
	private static final String[] TAB_LABELS = { "Model Overrides", "Environments", "Lights", "Definitions" };

	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private final CardLayout actionCards = new CardLayout();
	private final JPanel actionBar = new JPanel(actionCards);
	private final JButton[] tabButtons = new JButton[TAB_KEYS.length];
	private final ModelOverridesScreen overrides;
	private final DefinitionsScreen definitions = new DefinitionsScreen();
	private final EnvironmentsScreen environments = new EnvironmentsScreen();
	private final WorkspaceActions modelActions, environmentActions;
	private final JLabel modelStatus = statusLabel();
	private final JLabel environmentStatus = statusLabel();
	private final CardLayout statusCards = new CardLayout();
	private final JPanel statusBar = new JPanel(statusCards);
	private String activeTab = "overrides";

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
			var environmentPreview = new EnvironmentPreview(session, shell.environments);
			session.attachPreview((runtime, scene) -> shell.overrides.render(runtime, scene, shell.definitions, environmentPreview));
		}, error -> {
			splash.step("Could not open the editor · " + error.getMessage());
			splash.fail();
		});
	}

	private EditorShell(EditorSession session) {
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		overrides = new ModelOverridesScreen(this, session);
		body.add(overrides, "overrides");
		body.add(environments, "environments");
		body.add(new JPanel(), "lights");
		body.add(definitions, "defs");

		modelActions = new WorkspaceActions(this, () -> ModelOverridesScreen.capture(overrides),
			state -> ModelOverridesScreen.restore(overrides, state), modelStatus, "draft.json");
		environmentActions = new WorkspaceActions(this, environments::capture, environments::restore, environmentStatus, "environment-draft.json");
		environments.attachActions(environmentActions);
		var windowCommands = List.of(
			new WorkspaceActions.Command("Window", "Go to Model Overrides", "", () -> showTab("overrides")),
			new WorkspaceActions.Command("Window", "Go to Environments", "", () -> showTab("environments")));
		modelActions.addCommandSource(() -> windowCommands);
		environmentActions.addCommandSource(() -> windowCommands);
		modelActions.addCommandSource(() -> List.of(
			new WorkspaceActions.Command("File", "Save model overrides", "", this::saveOverrides)));
		environmentActions.addCommandSource(() -> List.of(
			new WorkspaceActions.Command("File", "Save environments", "Ctrl S", environments::save),
			new WorkspaceActions.Command("Edit", "Duplicate environment", "Ctrl D", environments::duplicate),
			new WorkspaceActions.Command("Edit", "Delete environment", "Delete", environments::delete)));
		modelActions.reset("Model overrides");
		overrides.onSelectionChanged(() -> {
			String label = overrides.selectedOverrideLabel();
			if (label != null)
				modelActions.reset(label);
		});

		bind("ctrl Z", () -> activeActions().undo(), false);
		bind("ctrl shift Z", () -> activeActions().redo(), false);
		bind("ctrl Y", () -> activeActions().redo(), false);
		bind("ctrl K", () -> activeActions().showPalette(), true);
		bind("typed ?", () -> activeActions().showShortcuts(), false);
		bind("ctrl S", () -> { if (isEnvironments()) environments.save(); }, true);
		bind("ctrl D", () -> { if (isEnvironments()) environments.duplicate(); }, false);
		bind("DELETE", () -> { if (isEnvironments()) environments.delete(); }, false);

		for (int i = 0; i < TAB_KEYS.length; i++) {
			String key = TAB_KEYS[i];
			boolean ready = !key.equals("lights");
			var builder = EditorButton.builder().style(WorkspaceStyle.Button.TAB).text(TAB_LABELS[i]).enabled(ready)
				.onClick(() -> showTab(key));

			var button = builder.build();
			button.setSelected(key.equals("overrides"));
			if (!ready)
				WorkspaceStyle.soon(button);
			tabButtons[i] = button;
		}

		actionBar.setOpaque(false);
		actionBar.add(modelActions.toolbar(), "model");
		actionBar.add(environmentActions.toolbar(), "environments");
		statusBar.add(modelStatus, "model");
		var status = new JPanel(new java.awt.BorderLayout());
		status.setBackground(WorkspaceStyle.BAR);
		status.add(EditorText.builder("Definitions · Environments").color(WorkspaceStyle.DIM)
			.border(BorderFactory.createEmptyBorder(4, 10, 4, 10)).build(), java.awt.BorderLayout.WEST);
		status.add(environmentStatus, java.awt.BorderLayout.EAST);
		statusBar.add(status, "environments");

		var sidebarToggle = EditorButton.builder().icon("panel-browser", 16).tooltip("Show/hide the left column")
			.size(28, 24).buildToggle(true);
		sidebarToggle.addActionListener(e -> {
			overrides.setSidebarVisible(sidebarToggle.isSelected());
			environments.setSidebarVisible(sidebarToggle.isSelected());
		});
		var saves = new JPanel();
		saves.setOpaque(false);
		var toolbar = editorToolbar(sidebarToggle, actionBar, saves, compact -> {}, tabButtons);
		toolbar.setPreferredSize(new java.awt.Dimension(0, 40));
		toolbar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		toolbar.setBackground(WorkspaceStyle.MENU);
		installEditorContent(toolbar, body, statusBar);

		addWindowListener(new WindowAdapter() {
			@Override public void windowOpened(WindowEvent e) { modelActions.start(); environmentActions.start(); }
			@Override public void windowIconified(WindowEvent e) { updatePreviewVisibility(false); }
			@Override public void windowDeiconified(WindowEvent e) { updatePreviewVisibility(true); }
			@Override public void windowClosing(WindowEvent e) {
				if (definitions.confirmClose() && environments.confirmClose())
					dispose();
			}
			@Override public void windowClosed(WindowEvent e) {
				modelActions.stop();
				environmentActions.stop();
				session.close();
			}
		});
	}

	private static JLabel statusLabel() {
		var label = EditorText.builder("").color(WorkspaceStyle.DIM).build();
		label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
		label.setOpaque(true);
		label.setBackground(WorkspaceStyle.BAR);
		return label;
	}

	private void bind(String shortcut, Runnable action, boolean allowText) {
		getRootPane().registerKeyboardAction(e -> {
			if (allowText || !(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent))
				action.run();
		}, KeyStroke.getKeyStroke(shortcut), JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	private boolean isEnvironments() { return "environments".equals(activeTab); }

	private WorkspaceActions activeActions() { return isEnvironments() ? environmentActions : modelActions; }

	private void showTab(String key) {
		activeTab = key;
		for (int i = 0; i < TAB_KEYS.length; i++)
			tabButtons[i].setSelected(TAB_KEYS[i].equals(key));
		cards.show(body, key);
		String controls = isEnvironments() ? "environments" : "model";
		actionCards.show(actionBar, controls);
		statusCards.show(statusBar, controls);
		updatePreviewVisibility((getExtendedState() & ICONIFIED) == 0);
	}

	private void updatePreviewVisibility(boolean visible) {
		overrides.setPreviewActive(visible && "overrides".equals(activeTab));
		definitions.setPreviewActive(visible && "defs".equals(activeTab));
		environments.setPreviewActive(visible && isEnvironments());
	}

	private void saveOverrides() { ModelOverridesScreen.saveSelected(overrides); }
}
