package rs117.hd.dev.sceneloader.standalone;

import java.util.Map;
import rs117.hd.dev.sceneloader.standalone.client.FakeClient;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.environments.Environment;

/** Applies the editor's draft only while the standalone scene selects an environment. */
final class PreviewEnvironmentManager extends EnvironmentManager {
	private final FakeClient client;
	Environment draft;
	Map<Integer, Integer> varbits = Map.of(), varps = Map.of();
	int excludedIndex = -1;
	String excludedArea;

	PreviewEnvironmentManager(FakeClient client) {
		this.client = client;
	}

	@Override public void update(SceneContext scene) {
		var environments = scene.environments;
		Environment removed = null;
		int removedIndex = -1;
		if (excludedArea != null && excludedIndex >= 0) {
			int occurrence = 0;
			for (int i = 0; i < environments.size(); i++) {
				if (excludedArea.equals(environments.get(i).area.name) && occurrence++ == excludedIndex) {
					removedIndex = i;
					removed = environments.remove(i);
					break;
				}
			}
		}
		boolean forced = draft != null;
		if (forced)
			environments.add(0, draft);
		client.setEnvironmentTestValues(varbits, varps);
		try {
			super.update(scene);
		} finally {
			client.setEnvironmentTestValues(Map.of(), Map.of());
			if (forced)
				environments.remove(0);
			if (removed != null)
				environments.add(removedIndex, removed);
		}
	}
}
