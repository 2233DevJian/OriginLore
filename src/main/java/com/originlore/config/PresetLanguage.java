package com.originlore.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.Settings;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field-level ownership lets preset translation coexist with edited gameplay rules. */
public final class PresetLanguage {
    private static final Map<String, List<String>> GROUPS = Map.of(
            "name", List.of("itemName", "itemNameJson"),
            "customName", List.of("customName", "customNameJson"),
            "lore", List.of("lore", "loreJson"));

    private PresetLanguage() {}

    public static ConfigSnapshot prepare(ConfigSnapshot preset, String language) {
        Settings settings = preset.settings().copy();
        settings.presetLanguage = language;
        ConfigSnapshot result = preset.withSettings(settings);
        rules(result).forEach((key, rule) -> {
            rule.presetTextKey = key;
            rule.presetTexts = new LinkedHashMap<>();
            GROUPS.keySet().forEach(group -> {
                JsonObject value = group(rule, group);
                if (!value.isEmpty()) rule.presetTexts.put(group, value);
            });
        });
        return result;
    }

    public static ConfigSnapshot switchLanguage(ConfigSnapshot current, String language) {
        return switchLanguage(current, language,
                PresetLibrary.load("vanilla_" + current.settings().presetLanguage),
                PresetLibrary.load("vanilla_" + language));
    }

    static ConfigSnapshot switchLanguage(ConfigSnapshot current, String language,
                                         ConfigSnapshot previousPreset, ConfigSnapshot targetPreset) {
        Settings settings = current.settings().copy();
        settings.presetLanguage = language;
        settings.validate();
        ConfigSnapshot result = current.withSettings(settings);
        Map<String, ComponentRule> target = rules(targetPreset);
        Map<String, ComponentRule> previous = rules(previousPreset);
        rules(result).forEach((key, rule) -> {
            String identity = rule.presetTextKey == null ? key : rule.presetTextKey;
            ComponentRule translated = target.get(identity);
            if (translated == null) return;
            ComponentRule original = previous.get(identity);
            for (String group : GROUPS.keySet()) {
                JsonObject live = group(rule, group);
                JsonElement baseline = rule.presetTexts == null ? null : rule.presetTexts.get(group);
                // Old imports can be adopted only when their text still matches the bundled source.
                if (rule.presetTextKey == null && original != null && !live.isEmpty()
                        && live.equals(group(original, group))) baseline = live;
                if (baseline == null) continue;
                if (!baseline.equals(live)) {
                    // Once edited, the field remains manual even if a later language matches its old text.
                    rule.presetTexts.remove(group);
                    continue;
                }
                copyGroup(rule, translated, group);
                if (rule.presetTexts == null) rule.presetTexts = new LinkedHashMap<>();
                rule.presetTexts.put(group, group(rule, group));
            }
            // Even a wholly manual legacy rule must finish adoption once, so matching a
            // later language's wording cannot silently make its fields preset-owned.
            if (rule.presetTexts == null) rule.presetTexts = new LinkedHashMap<>();
            rule.presetTextKey = identity;
        });
        return result;
    }

    static void removeBookkeeping(JsonElement items) {
        if (!items.isJsonObject()) return;
        for (JsonElement element : items.getAsJsonObject().asMap().values()) {
            JsonObject item = element.getAsJsonObject();
            removeRuleBookkeeping(item.get("base"));
            if (!item.has("sources")) continue;
            for (JsonElement sourceElement : item.getAsJsonArray("sources")) {
                JsonObject source = sourceElement.getAsJsonObject();
                removeRuleBookkeeping(source.get("rule"));
                if (!source.has("variants")) continue;
                for (JsonElement variant : source.getAsJsonArray("variants")) {
                    removeRuleBookkeeping(variant.getAsJsonObject().get("rule"));
                }
            }
        }
    }

    private static void removeRuleBookkeeping(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        element.getAsJsonObject().remove("presetTextKey");
        element.getAsJsonObject().remove("presetTexts");
    }

    private static JsonObject group(ComponentRule rule, String group) {
        JsonObject json = JsonParser.parseString(ItemComponentConfig.componentRuleToJson(rule)).getAsJsonObject();
        JsonObject result = new JsonObject();
        for (String field : GROUPS.get(group)) if (json.has(field)) result.add(field, json.get(field).deepCopy());
        return result;
    }

    private static void copyGroup(ComponentRule target, ComponentRule source, String group) {
        ComponentRule copy = source.copy();
        switch (group) {
            case "name" -> { target.itemName = copy.itemName; target.itemNameJson = copy.itemNameJson; }
            case "customName" -> { target.customName = copy.customName; target.customNameJson = copy.customNameJson; }
            case "lore" -> { target.lore = copy.lore; target.loreJson = copy.loreJson; }
            default -> throw new IllegalArgumentException("unknown text group: " + group);
        }
    }

    private static Map<String, ComponentRule> rules(ConfigSnapshot snapshot) {
        Map<String, ComponentRule> result = new LinkedHashMap<>();
        for (Map.Entry<String, ItemEntry> entry : snapshot.items().entrySet()) {
            String itemKey = entry.getKey();
            result.put(itemKey + "|base", entry.getValue().base);
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            for (SourceRule source : entry.getValue().sources) {
                String sourceKey = itemKey + "|" + source.type + "|" + source.lootTableId + "|" + source.recipeId;
                int occurrence = occurrences.merge(sourceKey, 1, Integer::sum);
                if (occurrence > 1) sourceKey += "|duplicate_" + occurrence;
                result.put(sourceKey, source.rule);
                for (Variant variant : source.variants) result.put(sourceKey + "|" + variant.id, variant.rule);
            }
        }
        return result;
    }
}
