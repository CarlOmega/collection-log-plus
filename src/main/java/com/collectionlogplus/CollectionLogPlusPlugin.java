package com.collectionlogplus;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import static net.runelite.client.RuneLite.RUNELITE_DIR;


import javax.inject.Inject;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;


@Slf4j
@PluginDescriptor(
        name = "Collection Log Plus"
)
public class CollectionLogPlusPlugin extends Plugin {
    final int COLLECTION_LOG_POPUP_WIDGET = 660;
    final int SKILL_GUIDE_WIDGET = 860;
    final int BANK_NOTE_ITEM_ID = 799;
    private final File LOG_DIR = new File(RUNELITE_DIR, "collection-log-plus");
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private CollectionLogPlusConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private ItemManager itemManager;
    @Inject
    private Gson gson;
    private File playerFolder = null;
    private Map<Integer, LogEntry> itemLogs = new HashMap<>();
    private WidgetNode logWidgetNode = null;
    private String openSkillGuideInterfaceSource = "";
    private String selectedTab = "";

    @Override
    protected void startUp() throws Exception {
        log.info("Collection Log Plus started!");
        LOG_DIR.mkdirs();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        switch (gameStateChanged.getGameState()) {
            case LOGGED_IN:
                loadLogsFromDisk();
                break;
            case LOGIN_SCREEN:
            case HOPPING: {
                playerFolder = null;
                itemLogs = new HashMap<>();
            }
        }
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown event) {
        saveLogs();
    }

    public Path getLogFilePath() {
        File logFile = new File(this.playerFolder, "logs.json");
        return logFile.toPath();
    }

    private File getPlayerFolder(String playerDir) {
        RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
        if (profileType != RuneScapeProfileType.STANDARD) {
            playerDir = playerDir + "-" + Text.titleCase(profileType);
        }
        File playerFolder = new File(LOG_DIR, playerDir);
        playerFolder.mkdirs();
        return playerFolder;
    }

    public void writeLogsToDisk(String itemLogsJson) {
        if (this.playerFolder == null) {
            return;
        }
        Path filePath = getLogFilePath();
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            writer.write(itemLogsJson);
        } catch (IOException e) {
            log.error("Unable to write item logs to: " + filePath, e);
        }
    }

    public void loadLogsFromDisk() {
        if (this.playerFolder != null) {
            return;
        }

        String profileKey = configManager.getRSProfileKey();

        this.playerFolder = getPlayerFolder(profileKey);
        log.debug("Loading logs for profile: {}", this.playerFolder.getName());

        final Path logsPath = getLogFilePath();
        if (Files.exists(logsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(logsPath);
                 JsonReader jsonReader = new JsonReader(reader)) {
                final Type type = new TypeToken<Map<Integer, LogEntry>>() {
                }.getType();
                itemLogs = gson.fromJson(jsonReader, type);
            } catch (IOException | JsonSyntaxException e) {
                log.error("Unable to read item logs at: " + logsPath, e);
            }
        }
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Collection Log Plus stopped!");
        saveLogs();
    }

    void saveLogs() {
        if (!itemLogs.isEmpty()) {
            writeLogsToDisk(gson.toJson(itemLogs));
        }
    }

    @Provides
    CollectionLogPlusConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CollectionLogPlusConfig.class);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        final ItemContainer itemContainer = event.getItemContainer();
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) {
            return;
        }
        Set<Integer> currentInventory = new HashSet<>();
        Arrays.stream(itemContainer.getItems())
                .forEach(item -> currentInventory.add(item.getId()));

        for (int itemId : currentInventory) {
            if (itemId == -1) {
                continue;
            }
            final ItemComposition itemComposition = itemManager.getItemComposition(itemId);
            String name = itemComposition.getName();
            // treat all banknotes as the same item
            if (itemComposition.getNote() != -1) {
                itemId = BANK_NOTE_ITEM_ID;
                name = "Bank note";
            }
            if (!itemLogs.containsKey(itemId)) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
                LogEntry logEntry = new LogEntry(
                        itemId,
                        name,
                        timestamp,
                        playerPos
                );
                itemLogs.put(itemId, logEntry);
                openPopUp(logEntry);
                saveLogs();
            }
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
        final Collection<ItemStack> items = npcLootReceived.getItems();
        for (ItemStack item : items) {
            int itemId = item.getId();
            final ItemComposition itemComposition = itemManager.getItemComposition(itemId);
            String name = itemComposition.getName();
            // treat all banknotes as the same item
            if (itemComposition.getNote() != -1) {
                itemId = BANK_NOTE_ITEM_ID;
                name = "Bank note";
            }
            if (!itemLogs.containsKey(itemId)) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                WorldPoint playerPos = client.getLocalPlayer().getWorldLocation();
                LogEntry logEntry = new LogEntry(
                        itemId,
                        name,
                        timestamp,
                        playerPos
                );
                itemLogs.put(itemId, logEntry);
                openPopUp(logEntry);
                saveLogs();
            }
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (config.enableCustomCollectionLog()
                && event.getMenuEntry().getWidget() != null
                && event.getMenuEntry().getWidget().getParentId() == ComponentID.CHARACTER_SUMMARY_CONTAINER + 1
                && Objects.equals(event.getMenuEntry().getOption(), "Collection Log")
        ) {
            client.getMenu().createMenuEntry(-1)
                    .setOption("Collection Log Plus")
                    .setTarget(event.getTarget())
                    .setIdentifier(event.getIdentifier())
                    .setType(MenuAction.RUNELITE)
                    .onClick(this::openCollectionLogPlus);
        }
    }

    private void openCollectionLogPlus(MenuEntry menuEntry) {
        clientThread.invokeLater(() -> {
            // Handles both resizable and fixed modes
            int componentId = (client.getTopLevelInterfaceId() << 16) | (client.isResized() ? 18 : 42);
            this.logWidgetNode = client.openInterface(
                    componentId, SKILL_GUIDE_WIDGET,
                    WidgetModalMode.MODAL_NOCLICKTHROUGH
            );
            this.openSkillGuideInterfaceSource = "characterSummary";
            this.selectedTab = "items";
            client.runScript(1902, 1, 0);
        });
    }

    private void openPopUp(LogEntry newLogEntry) {
        if (!config.enableCollectionLogPopup()) {
            return;
        }
        clientThread.invokeLater(() -> {
            // Handles both resizable and fixed modes
            int componentId = (client.getTopLevelInterfaceId() << 16) | (client.isResized() ? 13 : 43);
            WidgetNode widgetNode = client.openInterface(
                    componentId,
                    COLLECTION_LOG_POPUP_WIDGET,
                    WidgetModalMode.MODAL_CLICKTHROUGH
            );
            String title = "Collection Log +";
            String description = String.format(
                    "New item:<br><br><col=ffffff>%s</col>",
                    newLogEntry.getName()
            );
            client.runScript(3343, title, description, -1);

            clientThread.invokeLater(() -> {
                Widget w = client.getWidget(COLLECTION_LOG_POPUP_WIDGET, 1);
                if (w == null || w.getWidth() > 0) {
                    return false;
                }
                try {
                    client.closeInterface(widgetNode, true);
                } catch (IllegalArgumentException e) {
                    log.debug("Interface attempted to close, but was no longer valid.");
                }
                return true;
            });
        });
    }


    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        // Catch the close interface failsafe if skill summary is closed normally while log is open
        if (event.getScriptId() == 489 && this.logWidgetNode != null) {
            clientThread.invokeLater(() -> {
                client.closeInterface(this.logWidgetNode, true);
                this.logWidgetNode = null;
                this.openSkillGuideInterfaceSource = "";
                return true;
            });
        }
        // Checks if the open source was from somewhere unknown and resets log to not render
        if (event.getScriptId() == 1902 && this.logWidgetNode != null) {
            if (this.openSkillGuideInterfaceSource.isEmpty()) {
                this.logWidgetNode = null;
            } else {
                this.openSkillGuideInterfaceSource = "";
            }
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (config.enableCustomCollectionLog()
                && (event.getScriptId() == 1903 || event.getScriptId() == 1906)
                && this.logWidgetNode != null
        ) {
            // render UI
            /*
             * TITLE
             */
            Widget skillGuideUIContainer = client.getWidget(SKILL_GUIDE_WIDGET, 3);
            if (skillGuideUIContainer == null) {
                return;
            }
            Widget[] skillGuideUIParts = skillGuideUIContainer.getDynamicChildren();
            skillGuideUIParts[1].setText("Collection Log Plus");

            /*
             * TABS
             */
            Widget skillGuideTabsContainer = client.getWidget(SKILL_GUIDE_WIDGET, 7);
            if (skillGuideTabsContainer == null || skillGuideTabsContainer.getChildren() == null) {
                return;
            }
            Widget[] skillGuideTabParts = Arrays.copyOfRange(skillGuideTabsContainer.getChildren(), 0, 9);
            skillGuideTabsContainer.deleteAllChildren();
            skillGuideTabsContainer.setChildren(skillGuideTabParts);

            if (skillGuideTabParts[0].getName().isEmpty()) {
                this.selectedTab = "items";
            } else {
                skillGuideTabParts[0].setName("<col=ff9040>Items</col>");
            }
            skillGuideTabParts[8].setText("Items");

            /*
             * Entries
             */
            Widget rowEntriesContainer = client.getWidget(SKILL_GUIDE_WIDGET, 8);
            if (rowEntriesContainer == null) {
                return;
            }
            rowEntriesContainer.deleteAllChildren();
            int y = 0;
            switch (selectedTab) {
                case "items": {
                    y = renderItemLog(rowEntriesContainer);
                    break;
                }
            }
            /*
             * Scroll Bar
             */
            Widget entriesScrollBar = client.getWidget(SKILL_GUIDE_WIDGET, 10);
            if (entriesScrollBar != null && y > 0) {
                rowEntriesContainer.setScrollHeight(y);
                int scrollHeight = (rowEntriesContainer.getScrollY() * y) / rowEntriesContainer.getScrollHeight();
                rowEntriesContainer.revalidateScroll();
                clientThread.invokeLater(() ->
                                                 client.runScript(
                                                         ScriptID.UPDATE_SCROLLBAR,
                                                         entriesScrollBar.getId(),
                                                         rowEntriesContainer.getId(),
                                                         scrollHeight
                                                 )
                );
                rowEntriesContainer.setScrollY(0);
                entriesScrollBar.setScrollY(0);
            }
        }
    }

    private int renderItemLog(Widget rowEntriesContainer) {
        int index = 0;
        int y = 0;
        for (LogEntry entry : itemLogs.values()) {
            int itemId = entry.getItemId();
            String name = itemId == BANK_NOTE_ITEM_ID
                    ? "Bank note"
                    : entry.getName();
            int rowHeight = renderLogRow(
                    rowEntriesContainer,
                    new String[]{name},
                    index,
                    y,
                    itemId
            );
            index++;
            y += rowHeight;
        }
        return y;
    }

    private int renderLogRow(
            Widget rowEntriesContainer, String[] descriptionTexts, int index, int y, int itemId
    ) {
        final int ODD_OPACITY = 200;
        final int EVEN_OPACITY = 220;
        final int PADDING = 12;
        final int LINE_HEIGHT = 12;
        final int ITEM_SIZE = PADDING + LINE_HEIGHT + PADDING;
        // Limit rendering to only 20 as get index out of bounds with > 100 log entries
        final int CHUNK_SIZE = 20;

        boolean hasItem = itemId > -1;

        // Background Box widget
        Widget logRowBox = rowEntriesContainer.createChild(-1, WidgetType.RECTANGLE);
        logRowBox.setFilled(true);
        logRowBox.setOpacity(index % 2 == 0 ? ODD_OPACITY : EVEN_OPACITY);
        logRowBox.setBorderType(0);
        logRowBox.setWidthMode(1);
        logRowBox.setOriginalY(y);
        logRowBox.revalidate();

        // Text entries for the row
        int textX = PADDING + (hasItem ? ITEM_SIZE : 0);
        int textColor = Integer.parseInt("ff981f", 16);
        Widget logRowText = rowEntriesContainer.createChild(-1, WidgetType.TEXT);
        logRowText.setTextColor(textColor);
        logRowText.setLineHeight(LINE_HEIGHT);
        logRowText.setTextShadowed(true);
        logRowText.setFontId(FontID.PLAIN_12);
        logRowText.setOriginalWidth(textX);
        logRowText.setWidthMode(1);
        logRowText.setOriginalX(textX);
        logRowText.setOriginalY(y + PADDING);
        logRowText.revalidate();

        // Word wrapping without being able to get the text box height this is works well
        List<String> logText = buildLogTextLines(
                logRowText,
                descriptionTexts
        );

        int maxLines = logText.size();
        int boxHeight = PADDING + maxLines * LINE_HEIGHT + PADDING;

        // Recalculating height once the line count is known
        // Background Box
        logRowBox.setOriginalHeight(boxHeight);
        logRowBox.revalidate();
        // Log texts
        List<List<String>> logTextChunks = Lists.partition(logText, CHUNK_SIZE);

        logRowText.setOriginalHeight(logTextChunks.get(0).size() * LINE_HEIGHT);
        logRowText.setText(String.join("<br>", logTextChunks.get(0)));
        logRowText.revalidate();

        // Splits text boxes into chunks to avoid reaching capacity for the text font face
        for (int chunk = 1; chunk < logTextChunks.size(); chunk++) {
            List<String> textChunk = logTextChunks.get(chunk);
            logRowText = rowEntriesContainer.createChild(-1, WidgetType.TEXT);
            logRowText.setTextColor(textColor);
            logRowText.setLineHeight(LINE_HEIGHT);
            logRowText.setTextShadowed(true);
            logRowText.setFontId(FontID.PLAIN_12);
            logRowText.setOriginalWidth(textX);
            logRowText.setWidthMode(1);
            logRowText.setOriginalX(textX);
            logRowText.setOriginalY(y + PADDING + chunk * CHUNK_SIZE * LINE_HEIGHT);
            logRowText.setOriginalHeight(textChunk.size() * LINE_HEIGHT);
            logRowText.setText(String.join("<br>", textChunk));
            logRowText.revalidate();
        }

        // Rendering Item sprite for item logs
        if (hasItem) {
            Widget logRowItem = rowEntriesContainer.createChild(-1, WidgetType.GRAPHIC);
            logRowItem.setItemId(itemId);
            logRowItem.setItemQuantity(-1);
            logRowItem.setOriginalX(PADDING);
            logRowItem.setOriginalWidth(ITEM_SIZE);
            logRowItem.setOriginalHeight(ITEM_SIZE - 2);
            logRowItem.setOriginalY((y + 3) + (maxLines - 1) * (LINE_HEIGHT / 2)); // to center the item sprite
            logRowItem.revalidate();
        }

        return boxHeight;
    }

    List<String> buildLogTextLines(
            Widget logTextBox, String[] descriptionTexts
    ) {
        FontTypeFace font = logTextBox.getFont();
        int width = logTextBox.getWidth();

        List<String> lines = new ArrayList<>();
        for (String descriptionText : descriptionTexts) {
            List<String> words = new LinkedList<>(Arrays.asList(descriptionText.trim().split("\\s+")));
            StringBuilder line = new StringBuilder("- ").append(words.remove(0));
            for (String word : words) {
                if (font.getTextWidth(line + " " + word) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                    continue;
                }
                line.append(" ").append(word);
            }
            lines.add(line.toString());
        }

        return lines;
    }
}
