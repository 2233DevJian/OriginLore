package com.originlore.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetQualityTest {
    @Test
    void allSurvivalItemsHaveLoreAndBaseRulesCannotChangeGameplayOrNames() throws IOException {
        for (String language : PresetWriter.LANGUAGES) {
            Map<String, JsonObject> items = shards(language);
            assertEquals(1218, items.size());
            assertEquals(PresetWriter.categoryByItem().keySet(), items.keySet());
            assertFalse(items.containsKey("minecraft:player_head"));
            assertFalse(items.containsKey("minecraft:bundle"));
            for (Map.Entry<String, JsonObject> item : items.entrySet()) {
                JsonObject base = item.getValue().getAsJsonObject("base");
                assertEquals(Set.of("loreJson"), base.keySet(), item.getKey());
                JsonArray lore = base.getAsJsonArray("loreJson");
                assertFalse(lore.isEmpty(), item.getKey());
                for (JsonElement line : lore) assertFalse(line.getAsJsonObject().get("text").getAsString().isBlank(), item.getKey());
            }
        }
    }

    @Test
    void translationsCannotChangeSourcePoolsIdsWeightsOrGameplayValues() throws IOException {
        Map<String, JsonObject> chinese = shards("zh_cn");
        Map<String, JsonObject> english = shards("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        for (String id : chinese.keySet()) {
            assertEquals(withoutCopy(chinese.get(id)), withoutCopy(english.get(id)), id);
        }
    }

    @Test
    void ironSwordHasTheApprovedCraftAndChestOutcomes() throws IOException {
        JsonObject sword = shards("zh_cn").get("minecraft:iron_sword");
        JsonObject crafted = source(sword, "CRAFTING");
        assertEquals(1, crafted.getAsJsonArray("variants").size());
        JsonObject handmade = variant(crafted, "handmade");
        assertEquals(100, handmade.get("weight").getAsInt());
        assertSword(handmade, "手工打制铁剑", -2, -1, 150, 200);
        assertEquals("剑身上还留着深浅不一的锤痕，刃口也磨得不甚齐整。虽然不太趁手，但好在制作较为简单。",
                handmade.getAsJsonObject("rule").getAsJsonArray("loreJson").get(0).getAsJsonObject().get("text").getAsString());

        JsonObject chest = source(sword, "CHEST_LOOT");
        assertEquals(3, chest.getAsJsonArray("variants").size());
        JsonObject damaged = variant(chest, "damaged");
        JsonObject standard = variant(chest, "standard");
        JsonObject refined = variant(chest, "refined");
        assertEquals(70, damaged.get("weight").getAsInt());
        assertEquals(25, standard.get("weight").getAsInt());
        assertEquals(5, refined.get("weight").getAsInt());
        assertSword(damaged, "剑刃有缺口的铁剑", 0, 0, 75, 75);
        assertSword(standard, "铁匠打造的铁剑", 0, 0, 250, 250);
        assertSword(refined, "精钢铁剑", 1, 1, 275, 275);
    }

    @Test
    void approvedChineseAndEnglishCopyIsKeptVerbatimAcrossEveryApplicablePool() throws IOException {
        Map<String, String> swordLore = Map.of(
                "zh_cn", "剑身上还留着深浅不一的锤痕，刃口也磨得不甚齐整。虽然不太趁手，但好在制作较为简单。",
                "en_us", "Hammer marks of uneven depth remain on the blade, and the edge is far from neatly ground. It is awkward in the hand, but at least it is simple to make.");
        Map<String, String> breadLore = Map.of(
                "zh_cn", "外皮已经干裂，掰开便落下一把碎屑——作为填饱肚子而言，还算能吃的干粮。",
                "en_us", "The crust has cracked dry, and breaking it scatters a handful of crumbs. As something to fill an empty stomach, it is still edible trail bread.");
        for (String language : PresetWriter.LANGUAGES) {
            Map<String, JsonObject> items = shards(language);
            JsonObject handmade = variant(source(items.get("minecraft:iron_sword"), "CRAFTING"), "handmade");
            assertLore(handmade, swordLore.get(language));
            for (JsonElement source : items.get("minecraft:bread").getAsJsonArray("sources")) {
                assertLore(variant(source.getAsJsonObject(), "stale"), breadLore.get(language));
            }
        }
        JsonObject sword = shards("en_us").get("minecraft:iron_sword");
        assertSword(variant(source(sword, "CRAFTING"), "handmade"), "Hand-forged Iron Sword", -2, -1, 150, 200);
        JsonObject chest = source(sword, "CHEST_LOOT");
        assertSword(variant(chest, "damaged"), "Notched Iron Sword", 0, 0, 75, 75);
        assertSword(variant(chest, "standard"), "Smith-forged Iron Sword", 0, 0, 250, 250);
        assertSword(variant(chest, "refined"), "Fine Steel Sword", 1, 1, 275, 275);
    }

    @Test
    void allEdibleItemsAndEquipmentHaveQualityWhileOrdinaryItemsRemainLoreOnly() throws IOException {
        JsonObject stats = read(Path.of("tools/presets/_vanilla_reference.json")).getAsJsonObject("stats");
        Map<String, JsonObject> items = shards("zh_cn");
        for (Map.Entry<String, JsonObject> item : items.entrySet()) {
            String id = item.getKey();
            JsonObject vanilla = stats.has(id) ? stats.getAsJsonObject(id) : new JsonObject();
            boolean food = vanilla.has("nutrition") && !id.equals("minecraft:ominous_bottle") || id.equals("minecraft:cake");
            boolean gear = vanilla.has("durability") || id.endsWith("_horse_armor");
            if (!food && !gear) {
                assertFalse(item.getValue().has("sources"), id + " should have only lore");
                continue;
            }
            assertTrue(item.getValue().has("sources"), id);
            for (JsonElement source : item.getValue().getAsJsonArray("sources")) {
                JsonArray variants = source.getAsJsonObject().getAsJsonArray("variants");
                assertFalse(variants.isEmpty(), id);
                for (JsonElement element : variants) {
                    JsonObject variant = element.getAsJsonObject();
                    assertTrue(variant.get("qualityScore").getAsDouble() >= 0 && variant.get("qualityScore").getAsDouble() <= 1, id);
                    assertTrue(variant.getAsJsonObject("rule").has("itemName"), id);
                    assertFalse(variant.getAsJsonObject("rule").has("customName"), id);
                    if (food) {
                        assertTrue(variant.has("spoilage"), id);
                        JsonObject rule = variant.getAsJsonObject("rule").getAsJsonObject("food");
                        assertNotNull(rule, id);
                        assertTrue(rule.has("nutritionRange") && rule.has("saturationRange") && rule.has("eatSecondsRange"), id);
                    }
                }
            }
        }
    }

    @Test
    void sourceTablesDoNotInventCraftingOrChestLootForSpecialEquipment() throws IOException {
        Map<String, JsonObject> items = shards("zh_cn");
        assertEquals(Set.of("CRAFTING"), sourceTypes(items.get("minecraft:mace")));
        assertEquals(Set.of("ENTITY_DROP", "VAULT"), sourceTypes(items.get("minecraft:trident")));
        assertEquals(Set.of("ENTITY_DROP"), sourceTypes(items.get("minecraft:elytra")));
        JsonObject netherite = items.get("minecraft:netherite_sword");
        assertEquals(Set.of("SMITHING"), sourceTypes(netherite));
        JsonObject upgrade = source(netherite, "SMITHING");
        for (String id : Set.of("handmade", "damaged", "standard", "refined")) assertNotNull(variant(upgrade, id));
    }

    @Test
    void directHarvestHasItsOwnPoolsAndIsNotConfusedWithGifts() throws IOException {
        Map<String, JsonObject> items = shards("zh_cn");
        for (String id : Set.of("sweet_berries", "glow_berries", "honey_bottle", "mushroom_stew", "suspicious_stew")) {
            assertTrue(sourceTypes(items.get("minecraft:" + id)).contains("HARVEST"), id);
        }
        for (String id : Set.of("mushroom_stew", "suspicious_stew")) {
            assertFalse(sourceTypes(items.get("minecraft:" + id)).contains("GIFT"), id);
        }
    }

    @Test
    void defaultVillagerEquipmentTradesIncludeShieldsButNeverIronHoes() throws IOException {
        for (String language : PresetWriter.LANGUAGES) {
            Map<String, JsonObject> items = shards(language);
            assertTrue(sourceTypes(items.get("minecraft:shield")).contains("TRADING"));
            assertFalse(sourceTypes(items.get("minecraft:iron_hoe")).contains("TRADING"));
        }
    }

    @Test
    void baseDescriptionsDistinguishItemsWithinTheSameFamily() throws IOException {
        for (String language : PresetWriter.LANGUAGES) {
            Map<String, String> ownerByText = new LinkedHashMap<>();
            for (Map.Entry<String, JsonObject> item : shards(language).entrySet()) {
                StringBuilder text = new StringBuilder();
                for (JsonElement line : item.getValue().getAsJsonObject("base").getAsJsonArray("loreJson")) {
                    text.append(line.getAsJsonObject().get("text").getAsString());
                }
                String previous = ownerByText.put(text.toString(), item.getKey());
                assertTrue(previous == null, language + ": " + item.getKey() + " repeats " + previous);
            }
        }
    }

    @Test
    void foodKeepsNativeEffectsAndCakeValuesArePerBite() throws IOException {
        Map<String, JsonObject> items = shards("zh_cn");
        JsonObject bread = variant(source(items.get("minecraft:bread"), "CHEST_LOOT"), "stale");
        assertEquals("外皮已经干裂，掰开便落下一把碎屑——作为填饱肚子而言，还算能吃的干粮。",
                bread.getAsJsonObject("rule").getAsJsonArray("loreJson").get(0).getAsJsonObject().get("text").getAsString());
        JsonObject apple = variant(source(items.get("minecraft:golden_apple"), "CRAFTING"), "choice")
                .getAsJsonObject("rule").getAsJsonObject("food");
        assertTrue(apple.get("appendEffects").getAsBoolean());
        assertTrue(apple.getAsJsonArray("effects").isEmpty());
        JsonObject cake = variant(source(items.get("minecraft:cake"), "CRAFTING"), "ordinary")
                .getAsJsonObject("rule").getAsJsonObject("food");
        assertEquals(2, cake.getAsJsonArray("nutritionRange").get(1).getAsInt());
        assertEquals(0.4, cake.getAsJsonObject("saturationRange").get("max").getAsDouble());
        JsonObject wolf = variant(source(items.get("minecraft:wolf_armor"), "CRAFTING"), "handmade").getAsJsonObject("rule");
        assertTrue(wolf.has("maxDamageRange"));
        assertFalse(wolf.has("attributes"));
    }

    private static Set<String> sourceTypes(JsonObject item) {
        Set<String> types = new java.util.LinkedHashSet<>();
        for (JsonElement source : item.getAsJsonArray("sources")) types.add(source.getAsJsonObject().get("type").getAsString());
        return types;
    }

    private static void assertSword(JsonObject variant, String name, double minAttack, double maxAttack, int minDurability, int maxDurability) {
        JsonObject rule = variant.getAsJsonObject("rule");
        assertEquals(name, rule.get("itemName").getAsString());
        JsonObject attack = rule.getAsJsonObject("attackDamageRange");
        assertEquals(minAttack, attack.get("min").getAsDouble());
        assertEquals(maxAttack, attack.get("max").getAsDouble());
        assertEquals(minDurability, rule.getAsJsonArray("maxDamageRange").get(0).getAsInt());
        assertEquals(maxDurability, rule.getAsJsonArray("maxDamageRange").get(1).getAsInt());
    }

    private static void assertLore(JsonObject variant, String expected) {
        JsonArray lore = variant.getAsJsonObject("rule").getAsJsonArray("loreJson");
        assertEquals(1, lore.size());
        assertEquals(expected, lore.get(0).getAsJsonObject().get("text").getAsString());
    }

    private static JsonObject source(JsonObject item, String type) {
        for (JsonElement source : item.getAsJsonArray("sources")) {
            if (type.equals(source.getAsJsonObject().get("type").getAsString())) return source.getAsJsonObject();
        }
        throw new AssertionError("missing source " + type);
    }

    private static JsonObject variant(JsonObject source, String id) {
        for (JsonElement variant : source.getAsJsonArray("variants")) {
            if (id.equals(variant.getAsJsonObject().get("id").getAsString())) return variant.getAsJsonObject();
        }
        throw new AssertionError("missing variant " + id);
    }

    private static JsonElement withoutCopy(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!Set.of("loreJson", "itemName").contains(entry.getKey())) result.add(entry.getKey(), withoutCopy(entry.getValue()));
            }
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement value : element.getAsJsonArray()) result.add(withoutCopy(value));
            return result;
        }
        return element.deepCopy();
    }

    private static Map<String, JsonObject> shards(String language) throws IOException {
        Map<String, JsonObject> items = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(Path.of("tools/presets", language))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".json")).sorted().toList()) {
                for (Map.Entry<String, JsonElement> item : read(path).entrySet()) {
                    assertFalse(items.containsKey(item.getKey()), item.getKey());
                    items.put(item.getKey(), item.getValue().getAsJsonObject());
                }
            }
        }
        return items;
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
