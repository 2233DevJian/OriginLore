package com.originlore.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.LoadResult;
import com.originlore.config.ItemComponentConfig.SaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemComponentConfigTest {
    @TempDir
    Path directory;

    @Test
    void migratesLegacyFlatRulesAndAttackDamageRange() throws Exception {
        Path file = directory.resolve("item_components.json");
        Files.writeString(file, """
                {
                  "revision": 7,
                  "items": {
                    "_comment_1": "legacy comment",
                    "minecraft:iron_sword": {
                      "itemId": "minecraft:iron_sword",
                      "lore": ["legacy"],
                      "damageRange": [3, 5]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        ItemComponentConfig config = new ItemComponentConfig(file);
        LoadResult result = config.load();

        assertTrue(result.success());
        assertTrue(result.migrated());
        assertEquals(8, result.revision());
        ItemEntry sword = config.snapshot().items().get("minecraft:iron_sword");
        assertNotNull(sword);
        assertEquals(List.of("legacy"), sword.base.lore);
        assertNull(sword.base.damageRange);
        assertEquals(3.0, sword.base.attackDamageRange.min);
        assertEquals(5.0, sword.base.attackDamageRange.max);

        JsonObject canonical = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(ItemComponentConfig.CURRENT_SCHEMA_VERSION, canonical.get("schemaVersion").getAsInt());
        assertEquals(8, canonical.get("revision").getAsLong());
        assertFalse(Files.list(directory).anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
    }

    @Test
    void corruptReloadRetainsLastValidSnapshot() throws Exception {
        Path file = directory.resolve("item_components.json");
        ItemComponentConfig config = new ItemComponentConfig(file);
        assertTrue(config.load().success());
        ConfigSnapshot before = config.snapshot();

        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        LoadResult result = config.reloadFromDisk();

        assertFalse(result.success());
        assertEquals(before.revision(), config.getRevision());
        assertEquals(before.items().keySet(), config.snapshot().items().keySet());
    }

    @Test
    void runtimeValidatorRejectsCandidateBeforeItBecomesLive() throws Exception {
        Path file = directory.resolve("item_components.json");
        ItemComponentConfig config = new ItemComponentConfig(file);
        assertTrue(config.load().success());
        ConfigSnapshot before = config.snapshot();
        Files.writeString(file, """
                {
                  "schemaVersion": 3,
                  "revision": 99,
                  "items": {
                    "minecraft:stone": {"base": {"setComponents": {"missing:type": {}}}}
                  }
                }
                """, StandardCharsets.UTF_8);

        LoadResult result = config.reloadFromDisk(snapshot -> List.of("unknown component: missing:type"));

        assertFalse(result.success());
        assertTrue(result.message().contains("unknown component"));
        assertEquals(before.revision(), config.getRevision());
        assertEquals(before.items().keySet(), config.snapshot().items().keySet());
    }

    @Test
    void replaceSnapshotUsesOptimisticRevisionAndAtomicIncrement() {
        Path file = directory.resolve("item_components.json");
        ItemComponentConfig config = new ItemComponentConfig(file);
        assertTrue(config.load().success());
        ConfigSnapshot initial = config.snapshot();
        Map<String, ItemEntry> changed = new LinkedHashMap<>(initial.items());
        changed.put("minecraft:stone", new ItemEntry("minecraft:stone"));

        SaveResult saved = config.replaceSnapshot(new ConfigSnapshot(initial.revision(), changed), initial.revision());
        assertTrue(saved.success());
        assertEquals(initial.revision() + 1, saved.revision());

        SaveResult conflict = config.replaceSnapshot(new ConfigSnapshot(initial.revision(), Map.of()), initial.revision());
        assertFalse(conflict.success());
        assertTrue(conflict.conflict());
        assertEquals(saved.revision(), config.getRevision());
    }

    @Test
    void snapshotsAreDeepTransactionalCopies() {
        ItemComponentConfig config = new ItemComponentConfig(directory.resolve("item_components.json"));
        assertTrue(config.load().success());
        ConfigSnapshot first = config.snapshot();
        first.items().get("minecraft:sweet_berries").base.loreJson.getFirst().getAsJsonObject()
                .addProperty("text", "changed only in transaction");

        String liveLore = config.snapshot().items().get("minecraft:sweet_berries").base.loreJson.getFirst()
                .getAsJsonObject().get("text").getAsString();
        assertFalse(liveLore.equals("changed only in transaction"));
    }

    @Test
    void rejectsFutureSchemaAndDuplicateVariants() {
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.snapshotFromJson("""
                {"schemaVersion": 999, "revision": 0, "items": {}}
                """));
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.snapshotFromJson("""
                {
                  "schemaVersion": 3,
                  "revision": 0,
                  "items": {
                    "minecraft:stone": {
                      "base": {},
                      "sources": [{
                        "type": "UNKNOWN",
                        "variants": [
                          {"id": "same", "weight": 1, "rule": {}},
                          {"id": "same", "weight": 1, "rule": {}}
                        ]
                      }]
                    }
                  }
                }
                """));
    }

    @Test
    void rejectsVariantWeightTotalOverflow() {
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.snapshotFromJson("""
                {
                  "schemaVersion": 3,
                  "revision": 0,
                  "items": {
                    "minecraft:stone": {
                      "sources": [{
                        "type": "UNKNOWN",
                        "variants": [
                          {"id": "first", "weight": 1.7976931348623157E308},
                          {"id": "second", "weight": 1.7976931348623157E308}
                        ]
                      }]
                    }
                  }
                }
                """));
    }
}
