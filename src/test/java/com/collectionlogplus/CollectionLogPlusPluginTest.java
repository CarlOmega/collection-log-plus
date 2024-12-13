package com.collectionlogplus;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CollectionLogPlusPluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(CollectionLogPlusPlugin.class);
        RuneLite.main(args);
    }
}