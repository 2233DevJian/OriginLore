package com.originlore.config;

import com.originlore.RandomRanges;
import com.originlore.config.ItemComponentConfig.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NumericConfigTest {
    @TempDir Path directory;

    @Test
    void newFieldsRoundTripThroughSnapshotsAndDisk() {
        ItemEntry entry = new ItemEntry("minecraft:bread");
        entry.base.food = new FoodRule();
        entry.base.food.nutritionRange = new int[]{1, 7};
        entry.base.food.saturationRange = new NumberRange(1, 6);
        entry.base.food.eatSecondsRange = new NumberRange(0.8, 2);
        SourceRule source = new SourceRule("CRAFTING");
        source.processing = new ProcessingRule();
        source.processing.riskRetention = 0.2;
        source.processing.riskFloor = 0.1;
        source.processing.effects = List.of(new EffectRule("minecraft:hunger", 100, 0, 0.4f));
        Variant quality = new Variant("ordinary", 100);
        quality.qualityScore = 0.5;
        quality.spoilage = 0.1;
        WeightPoint point = new WeightPoint();
        point.quality = 0.5;
        point.multiplier = 2;
        quality.ingredientWeights = List.of(point);
        source.variants.add(quality);
        entry.sources.add(source);
        Settings settings = new Settings();
        settings.presetLanguage = "en_us";
        ItemComponentConfig config = new ItemComponentConfig(directory.resolve("config.json"));
        assertTrue(config.replaceSnapshot(new ConfigSnapshot(0, Map.of(entry.itemId, entry), settings), 0).success());
        String saved = config.snapshotJson();
        assertEquals(saved, ItemComponentConfig.snapshotToJson(ItemComponentConfig.snapshotFromJson(saved)));
        ItemComponentConfig loaded = new ItemComponentConfig(config.getConfigFile());
        assertTrue(loaded.load(candidate -> {
            assertEquals("en_us", candidate.settings().presetLanguage);
            return List.of();
        }).success());
        assertEquals(saved, loaded.snapshotJson());
    }

    @Test
    void failedWriteRestoresItemsSettingsAndRevision() throws Exception {
        Path file = directory.resolve("config.json");
        ItemComponentConfig config = new ItemComponentConfig(file);
        ItemEntry entry = new ItemEntry("minecraft:stone");
        entry.base.lore = List.of("original");
        assertTrue(config.replaceSnapshot(new ConfigSnapshot(0, Map.of(entry.itemId, entry)), 0).success());
        String before = config.snapshotJson();
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "forces replacement to fail");
        Settings settings = new Settings();
        settings.presetLanguage = "en_us";
        SaveResult result = config.replaceSnapshot(new ConfigSnapshot(config.getRevision(), Map.of(), settings), config.getRevision());
        assertFalse(result.success());
        assertEquals(before, config.snapshotJson());
    }

    @Test
    void rejectsEveryNewRangeDomainAndOverflow() {
        ComponentRule rule = new ComponentRule();
        rule.food = new FoodRule();
        rule.food.eatSecondsRange = new NumberRange(0, 1);
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.validateRuleNumbers(rule));
        rule.food = null;
        rule.tool = new ToolRule();
        rule.tool.damagePerBlockRange = new int[]{0, -1};
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.validateRuleNumbers(rule));
        rule.tool = null;
        rule.projectileDamageMultiplier = new NumberRange(1, Double.POSITIVE_INFINITY);
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.validateRuleNumbers(rule));
        rule.projectileDamageMultiplier = null;
        rule.attackDamageRange = new NumberRange(-Double.MAX_VALUE, Double.MAX_VALUE);
        assertThrows(RuntimeException.class, () -> ItemComponentConfig.validateRuleNumbers(rule));
    }

    @Test
    void manyIndependentAttributeRangesHaveBoundedValidationCost() {
        ItemEntry entry = new ItemEntry("minecraft:iron_sword");
        entry.base.attributes = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            AttributeRule attribute = new AttributeRule();
            attribute.id = "originlore:test_" + i;
            attribute.attribute = "minecraft:generic.attack_damage";
            attribute.amountRange = new NumberRange(-1, 1);
            entry.base.attributes.add(attribute);
        }
        String json = ItemComponentConfig.snapshotToJson(new ConfigSnapshot(0, Map.of(entry.itemId, entry)));
        assertTimeout(Duration.ofSeconds(2), () -> assertNotNull(ItemComponentConfig.snapshotFromJson(json)));
    }

    @Test
    void mappingPreservesPositionAndPositiveRemainingDurability() {
        assertEquals(240, RandomRanges.map(RandomRanges.position(170, 150, 200), 200, 300));
        assertEquals(50, RandomRanges.mapDamage(100, 200, 100));
        assertEquals(0, RandomRanges.mapDamage(199, 200, 1));
        assertEquals(1, RandomRanges.mapDamage(200, 200, 1));
    }
}
