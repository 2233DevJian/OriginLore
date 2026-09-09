package com.originlore.config;

import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetLanguagePersistenceTest {
    @TempDir
    Path directory;

    @Test
    void failedLanguageWriteRollsBackSettingsTextOwnershipAndRevision() throws Exception {
        ConfigSnapshot chinese = preset("original wording");
        ConfigSnapshot english = preset("translated wording");
        Path file = directory.resolve("item_components.json");
        Files.writeString(file, ItemComponentConfig.snapshotToJson(PresetLanguage.prepare(chinese, "zh_cn")));
        ItemComponentConfig config = new ItemComponentConfig(file);
        assertTrue(config.load().success(), config::getLastError);
        String before = config.snapshotJson();
        ConfigSnapshot translated = PresetLanguage.switchLanguage(config.snapshot(), "en_us", chinese, english);

        Path original = directory.resolve("original.json");
        Files.move(file, original);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction.txt"), "The test intentionally prevents replacement.");

        ItemComponentConfig.SaveResult result = config.replaceSnapshot(translated, config.getRevision());

        assertFalse(result.success());
        assertFalse(result.conflict());
        assertEquals(before, config.snapshotJson());
        assertEquals(before, Files.readString(original));
        assertEquals("zh_cn", config.snapshot().settings().presetLanguage);
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void aStaleAdministratorCannotOverwriteALanguageChange() throws Exception {
        ConfigSnapshot chinese = preset("original wording");
        ConfigSnapshot english = preset("translated wording");
        Path file = directory.resolve("item_components.json");
        Files.writeString(file, ItemComponentConfig.snapshotToJson(PresetLanguage.prepare(chinese, "zh_cn")));
        ItemComponentConfig config = new ItemComponentConfig(file);
        assertTrue(config.load().success(), config::getLastError);
        ConfigSnapshot firstAdmin = config.snapshot();
        ConfigSnapshot secondAdmin = config.snapshot();
        ConfigSnapshot translated = PresetLanguage.switchLanguage(firstAdmin, "en_us", chinese, english);

        assertTrue(config.replaceSnapshot(translated, firstAdmin.revision()).success());
        String committed = config.snapshotJson();
        secondAdmin.items().get("minecraft:apple").base.lore.set(0, "stale edit");
        ItemComponentConfig.SaveResult rejected = config.replaceSnapshot(secondAdmin, secondAdmin.revision());

        assertTrue(rejected.conflict());
        assertFalse(rejected.success());
        assertEquals(committed, config.snapshotJson());
        assertEquals(committed, Files.readString(file));
        assertEquals("en_us", config.snapshot().settings().presetLanguage);
    }

    private static ConfigSnapshot preset(String lore) {
        return ItemComponentConfig.snapshotFromJson("""
                {"revision":5,"items":{"minecraft:apple":{"base":{"lore":["%s"]}}}}
                """.formatted(lore));
    }
}
