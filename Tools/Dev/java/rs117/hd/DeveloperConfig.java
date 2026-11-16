package rs117.hd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("117hd-developer")
public interface DeveloperConfig extends Config {
	String CONFIG_GROUP = "117hd-developer";

	String KEY_CACHE_SCENE = "cacheScene";
	@ConfigItem(
		keyName = KEY_CACHE_SCENE,
		name = "Cache Scene",
		description = "Add the 117 HD Cache Scene sidebar, for loading any region straight from the cache without travelling there",
		position = 1
	)
	default boolean cacheScene() {
		return true;
	}
}
