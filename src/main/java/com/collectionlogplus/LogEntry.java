package com.collectionlogplus;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class LogEntry {
    int itemId;
    String name;
    String timestamp;
    WorldPoint worldPoint;
}

