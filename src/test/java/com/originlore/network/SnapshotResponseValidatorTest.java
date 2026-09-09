package com.originlore.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotResponseValidatorTest {
    @Test
    void compressedSnapshotPreservesLanguageAndFieldOwnership() {
        ItemEntry item = new ItemEntry("minecraft:apple");
        item.base.lore = List.of("English lore");
        item.base.presetTextKey = "minecraft:apple|base";
        item.base.presetTexts = Map.of("lore", JsonParser.parseString("{\"lore\":[\"English lore\"]}"));
        Settings settings = new Settings();
        settings.presetLanguage = "en_us";
        String json = ItemComponentConfig.snapshotToJson(new ConfigSnapshot(42, Map.of(item.itemId, item), settings));
        byte[] compressed = PayloadCompression.compressUtf8(json, 16_384, 16_384);

        ConfigSnapshot received = SnapshotResponseValidator.parse(
                PayloadCompression.decompressUtf8(compressed, 16_384, 16_384), 42);

        assertEquals(42, received.revision());
        assertEquals("en_us", received.settings().presetLanguage);
        assertEquals(item.base.presetTextKey, received.items().get(item.itemId).base.presetTextKey);
        assertEquals(item.base.presetTexts, received.items().get(item.itemId).base.presetTexts);
    }

    @Test
    void mismatchedRevisionCannotBeAcceptedAsALanguageSaveConfirmation() {
        String json = ItemComponentConfig.snapshotToJson(new ConfigSnapshot(41, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotResponseValidator.parse(json, 42));
    }

    @Test
    void olderSchemaWithoutNewFieldsIsRejectedInsteadOfResettingSettings() {
        JsonObject root = currentSnapshot();
        root.addProperty("schemaVersion", ItemComponentConfig.CURRENT_SCHEMA_VERSION - 1);
        assertThrows(IllegalArgumentException.class, () -> SnapshotResponseValidator.parse(root.toString(), 42));
        root.addProperty("schemaVersion", ItemComponentConfig.CURRENT_SCHEMA_VERSION);
        root.remove("settings");
        assertThrows(IllegalArgumentException.class, () -> SnapshotResponseValidator.parse(root.toString(), 42));
    }

    @Test
    void missingRevisionIsRejectedInsteadOfAssumingZero() {
        JsonObject root = currentSnapshot();
        root.remove("revision");
        assertThrows(IllegalArgumentException.class, () -> SnapshotResponseValidator.parse(root.toString(), 0));
    }

    private static JsonObject currentSnapshot() {
        return JsonParser.parseString(ItemComponentConfig.snapshotToJson(new ConfigSnapshot(42, Map.of())))
                .getAsJsonObject();
    }
}
