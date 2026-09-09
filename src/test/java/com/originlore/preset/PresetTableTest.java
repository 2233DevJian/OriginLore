package com.originlore.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The category and exclusion tables together decide which items the presets cover. They are hand-authored, so these
 * checks are what stop a typo or an overlooked item from silently shrinking that universe.
 */
class PresetTableTest {
    private static final Path CATEGORIES = Path.of("tools", "presets", "categories.json");
    private static final Path EXCLUSIONS = Path.of("tools", "presets", "exclusions.json");
    private static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    @Test
    void everyReferenceItemIsEitherCategorizedOrExplicitlyExcluded() {
        Set<String> reference = new LinkedHashSet<>(ItemIdReferenceTest.referenceItemIds());
        Set<String> categorized = categorized();
        Set<String> excluded = exclusions().keySet();

        Set<String> missing = new LinkedHashSet<>(reference);
        missing.removeAll(categorized);
        missing.removeAll(excluded);
        assertTrue(missing.isEmpty(), "reference items covered by neither table: " + missing);

        Set<String> unknown = new LinkedHashSet<>(categorized);
        unknown.addAll(excluded);
        unknown.removeAll(reference);
        assertTrue(unknown.isEmpty(), "table entries that are not real items: " + unknown);
    }

    @Test
    void noItemIsBothCategorizedAndExcluded() {
        Set<String> overlap = new LinkedHashSet<>(categorized());
        overlap.retainAll(exclusions().keySet());
        assertTrue(overlap.isEmpty(), "items claimed by both tables: " + overlap);
    }

    @Test
    void everyItemAppearsInExactlyOneCategory() {
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : categories().entrySet()) {
            for (String itemId : entry.getValue()) {
                assertTrue(seen.add(itemId), itemId + " is listed under more than one category");
            }
        }
    }

    @Test
    void everyExclusionStatesWhyTheItemIsSkipped() {
        for (Map.Entry<String, String> entry : exclusions().entrySet()) {
            assertTrue(entry.getValue() != null && !entry.getValue().isBlank(),
                    entry.getKey() + " is excluded without a reason");
        }
    }

    @Test
    void everyCategoryHasANameInEveryLanguage() {
        for (String language : LANGUAGES) {
            JsonObject translations = readLang(language);
            List<String> absent = new ArrayList<>();
            for (String category : categories().keySet()) {
                String key = "originlore.category." + category;
                JsonElement value = translations.get(key);
                if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) absent.add(key);
            }
            assertTrue(absent.isEmpty(), language + " is missing category names: " + absent);
        }
    }

    private static Set<String> categorized() {
        Set<String> ids = new LinkedHashSet<>();
        for (List<String> entry : categories().values()) ids.addAll(entry);
        return ids;
    }

    private static Map<String, List<String>> categories() {
        JsonObject root = readObject(CATEGORIES);
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            assertTrue(entry.getValue().isJsonArray(), "category " + entry.getKey() + " is not a list");
            List<String> ids = new ArrayList<>();
            for (JsonElement id : entry.getValue().getAsJsonArray()) {
                assertTrue(id.isJsonPrimitive(), "category " + entry.getKey() + " holds a non-string entry");
                ids.add(id.getAsString());
            }
            assertTrue(!ids.isEmpty(), "category " + entry.getKey() + " is empty");
            categories.put(entry.getKey(), ids);
        }
        return categories;
    }

    private static Map<String, String> exclusions() {
        JsonObject root = readObject(EXCLUSIONS);
        Map<String, String> exclusions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            assertTrue(entry.getValue().isJsonPrimitive(), "exclusion reason for " + entry.getKey() + " is not text");
            exclusions.put(entry.getKey(), entry.getValue().getAsString());
        }
        return exclusions;
    }

    private static JsonObject readLang(String language) {
        return readObject(Path.of("src", "main", "resources", "assets", "originlore", "lang", language + ".json"));
    }

    private static JsonObject readObject(Path path) {
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return fail("could not read " + path.toAbsolutePath(), exception);
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) return fail(path + " does not hold a JSON object");
        return parsed.getAsJsonObject();
    }
}
