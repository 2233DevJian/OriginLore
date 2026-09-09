package com.originlore.config;

import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetLanguageTest {
    private static final ConfigSnapshot CHINESE = preset("zh name", "zh lore");
    private static final ConfigSnapshot ENGLISH = preset("en name", "en lore");

    @Test
    void nameAndLoreHaveIndependentOwnershipAndNumbersStayUnchanged() {
        ConfigSnapshot current = PresetLanguage.prepare(CHINESE, "zh_cn");
        ComponentRule original = rule(current);
        original.itemName = "hand edited name";
        original.maxDamageRange = new int[]{150, 200};
        current.items().get("minecraft:iron_sword").sources.getFirst().variants.getFirst().weight = 17;

        ConfigSnapshot changed = switchTo(current, "en_us");

        assertEquals("hand edited name", rule(changed).itemName);
        assertEquals(List.of("en lore"), rule(changed).lore);
        assertEquals(150, rule(changed).maxDamageRange[0]);
        assertEquals(200, rule(changed).maxDamageRange[1]);
        assertEquals(17, changed.items().get("minecraft:iron_sword").sources.getFirst().variants.getFirst().weight);
        assertFalse(rule(changed).presetTexts.containsKey("name"));
        assertTrue(rule(changed).presetTexts.containsKey("lore"));
        assertEquals(List.of("zh lore"), original.lore, "switching must leave the source transaction intact");
        assertEquals("zh_cn", current.settings().presetLanguage);
    }

    @Test
    void editedAndDeletedFieldsRemainManualAcrossRoundTripsAndLanguageChanges() {
        ConfigSnapshot current = PresetLanguage.prepare(CHINESE, "zh_cn");
        rule(current).itemName = "en name";
        rule(current).lore = null;

        ConfigSnapshot english = roundTrip(switchTo(current, "en_us"));
        ConfigSnapshot chinese = switchTo(english, "zh_cn");
        ConfigSnapshot englishAgain = switchTo(roundTrip(chinese), "en_us");

        assertEquals("en name", rule(chinese).itemName);
        assertNull(rule(chinese).lore);
        assertEquals("en name", rule(englishAgain).itemName);
        assertNull(rule(englishAgain).lore);
        assertTrue(rule(englishAgain).presetTexts.isEmpty());
    }

    @Test
    void jsonTextStylesAndPlayerNameRulesAreNotFlattened() {
        ConfigSnapshot current = PresetLanguage.prepare(CHINESE, "zh_cn");
        rule(current).itemName = null;
        rule(current).itemNameJson = com.google.gson.JsonParser.parseString("""
                {"text":"custom style","bold":true,"color":"gold"}
                """);
        rule(current).customName = "player-owned naming rule";

        ConfigSnapshot changed = switchTo(current, "en_us");

        assertEquals(rule(current).itemNameJson, rule(changed).itemNameJson);
        assertNull(rule(changed).itemName);
        assertEquals("player-owned naming rule", rule(changed).customName);
        assertEquals(List.of("en lore"), rule(changed).lore);
    }

    @Test
    void legacyImportsAdoptOnlyFieldsMatchingThePreviousPreset() {
        ConfigSnapshot legacy = roundTrip(CHINESE);
        rule(legacy).lore = List.of("my wording");

        ConfigSnapshot changed = switchTo(legacy, "en_us");

        assertEquals("en name", rule(changed).itemName);
        assertEquals(List.of("my wording"), rule(changed).lore);
        assertTrue(rule(changed).presetTexts.containsKey("name"));
        assertFalse(rule(changed).presetTexts.containsKey("lore"));
    }

    @Test
    void whollyManualLegacyTextCannotBeReadoptedAfterLanguageRoundTrips() {
        ConfigSnapshot legacy = roundTrip(CHINESE);
        rule(legacy).itemName = "en name";
        rule(legacy).lore = List.of("en lore");

        ConfigSnapshot english = roundTrip(switchTo(legacy, "en_us"));
        assertTrue(rule(english).presetTexts.isEmpty());
        assertEquals("en name", rule(english).itemName);
        assertEquals(List.of("en lore"), rule(english).lore);

        ConfigSnapshot chinese = roundTrip(switchTo(english, "zh_cn"));
        ConfigSnapshot englishAgain = roundTrip(switchTo(chinese, "en_us"));
        assertEquals("en name", rule(chinese).itemName);
        assertEquals(List.of("en lore"), rule(chinese).lore);
        assertTrue(rule(chinese).presetTexts.isEmpty());
        assertEquals(rule(english).presetTextKey, rule(chinese).presetTextKey);
        assertEquals("en name", rule(englishAgain).itemName);
        assertEquals(List.of("en lore"), rule(englishAgain).lore);
        assertTrue(rule(englishAgain).presetTexts.isEmpty());
        assertNull(rule(legacy).presetTextKey, "adoption must not mutate the source snapshot");
    }

    @Test
    void stableVariantIdentitySurvivesFilterChangesAndDoesNotRestoreDeletedVariants() {
        ConfigSnapshot current = PresetLanguage.prepare(CHINESE, "zh_cn");
        String identity = rule(current).presetTextKey;
        current.items().get("minecraft:iron_sword").sources.getFirst().lootTableId = "minecraft:chests/village/village_weaponsmith";
        ConfigSnapshot changed = switchTo(current, "en_us");

        assertEquals(identity, rule(changed).presetTextKey);
        assertEquals("en name", rule(changed).itemName);
        changed.items().get("minecraft:iron_sword").sources.getFirst().variants.clear();
        assertTrue(switchTo(changed, "zh_cn").items().get("minecraft:iron_sword").sources.getFirst().variants.isEmpty());
    }

    @Test
    void duplicateSourceSelectorsDoNotLoseTextOwnership() {
        ConfigSnapshot current = roundTrip(CHINESE);
        var source = current.items().get("minecraft:iron_sword").sources.getFirst();
        current.items().get("minecraft:iron_sword").sources.add(source.copy());

        ConfigSnapshot prepared = PresetLanguage.prepare(current, "zh_cn");
        var sources = prepared.items().get("minecraft:iron_sword").sources;

        assertNotEquals(sources.get(0).rule.presetTextKey, sources.get(1).rule.presetTextKey);
        assertNotEquals(sources.get(0).variants.getFirst().rule.presetTextKey,
                sources.get(1).variants.getFirst().rule.presetTextKey);
        assertEquals("zh name", sources.get(0).variants.getFirst().rule.presetTexts.get("name")
                .getAsJsonObject().get("itemName").getAsString());
    }

    @Test
    void unsupportedLanguageDoesNotMutateTheSourceSnapshot() {
        ConfigSnapshot current = PresetLanguage.prepare(CHINESE, "zh_cn");
        assertThrows(IllegalArgumentException.class,
                () -> PresetLanguage.switchLanguage(current, "fr_fr", CHINESE, ENGLISH));
        assertEquals("zh_cn", current.settings().presetLanguage);
        assertEquals("zh name", rule(current).itemName);
    }

    private static ConfigSnapshot switchTo(ConfigSnapshot current, String language) {
        return PresetLanguage.switchLanguage(current, language,
                "zh_cn".equals(current.settings().presetLanguage) ? CHINESE : ENGLISH,
                "zh_cn".equals(language) ? CHINESE : ENGLISH);
    }

    private static ConfigSnapshot roundTrip(ConfigSnapshot snapshot) {
        return ItemComponentConfig.snapshotFromJson(ItemComponentConfig.snapshotToJson(snapshot));
    }

    private static ComponentRule rule(ConfigSnapshot snapshot) {
        return snapshot.items().get("minecraft:iron_sword").sources.getFirst().variants.getFirst().rule;
    }

    private static ConfigSnapshot preset(String name, String lore) {
        return ItemComponentConfig.snapshotFromJson("""
                {"items":{"minecraft:iron_sword":{"base":{"lore":["base"]},"sources":[{
                  "type":"CHEST_LOOT","variants":[{"id":"hand_forged","weight":1,
                    "rule":{"itemName":"%s","lore":["%s"]}}]
                }]}}}
                """.formatted(name, lore));
    }
}
