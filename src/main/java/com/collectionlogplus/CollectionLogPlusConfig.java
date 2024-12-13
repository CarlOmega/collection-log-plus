package com.collectionlogplus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("collection-log-plus")
public interface CollectionLogPlusConfig extends Config {
    @ConfigSection(
            name = "Examine Log",
            description = "Custom Examine log things.",
            position = 1
    )
    String logsSection = "logs";

    @ConfigItem(
            keyName = "enableCollectionLogPopup",
            name = "Custom Collection Log Popup",
            description = "Enable Collection Log Popup",
            section = logsSection
    )
    default boolean enableCollectionLogPopup() {
        return true;
    }

    @ConfigItem(
            keyName = "enableCustomCollectionLog",
            name = "Custom Collection Log Interface",
            description = "Enable Custom Log Interface, right click collection log in character summary.",
            section = logsSection
    )
    default boolean enableCustomCollectionLog() {
        return true;
    }

}
