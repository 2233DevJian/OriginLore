package com.originlore.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Merges the hand-authored content shards under {@code tools/presets} into the preset files bundled in the mod jar.
 *
 * <p>The writer deliberately does not know what a rule looks like. Shards are verbatim {@code items} fragments; they are
 * concatenated, then pushed through {@link ItemComponentConfig#snapshotFromJson} and
 * {@link ItemComponentConfig#snapshotToJson} so the bundled file is byte-shaped exactly like a configuration the mod
 * itself saved, and so any field the model does not recognise is reported instead of silently dropped.
 */
public final class PresetWriter {
    static final Path SOURCE_ROOT = Path.of("tools", "presets");
    static final Path OUTPUT_ROOT = Path.of("src", "main", "resources", "originlore", "presets");
    static final String CATEGORIES_FILE = "categories.json";
    static final List<String> LANGUAGES = List.of("zh_cn", "en_us");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PresetWriter() {
    }

    public static void main(String[] args) {
        try {
            for (Path written : write()) System.out.println("wrote " + written);
        } catch (IOException | RuntimeException exception) {
            System.err.println("preset generation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    /** Regenerates every bundled preset file and returns the paths written. */
    public static List<Path> write() throws IOException {
        Files.createDirectories(OUTPUT_ROOT);
        List<Path> written = new ArrayList<>();
        for (String language : LANGUAGES) {
            written.add(Files.writeString(presetFile(language), presetJson(language), StandardCharsets.UTF_8));
        }
        written.add(Files.writeString(OUTPUT_ROOT.resolve(CATEGORIES_FILE), categoriesJson(), StandardCharsets.UTF_8));
        return List.copyOf(written);
    }

    public static Path presetFile(String language) {
        return OUTPUT_ROOT.resolve("vanilla_" + language + ".json");
    }

    /** The bundled preset for {@code language}, as it should appear on disk. */
    public static String presetJson(String language) {
        TreeMap<String, JsonObject> items = new TreeMap<>();
        for (Path shard : shards(language)) {
            for (Map.Entry<String, JsonElement> entry : readObject(shard).entrySet()) {
                String itemId = entry.getKey();
                if (!itemId.contains(":")) {
                    throw new IllegalArgumentException(shard + ": \"" + itemId + "\" is not a namespaced item id;"
                            + " comment keys are not allowed inside a shard");
                }
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException(shard + ": rule for " + itemId + " must be an object");
                }
                JsonObject previous = items.put(itemId, entry.getValue().getAsJsonObject());
                if (previous != null) throw new IllegalArgumentException(shard + ": " + itemId + " is already defined");
            }
        }
        if (items.isEmpty()) throw new IllegalStateException("no preset content found for " + language);

        JsonObject merged = new JsonObject();
        merged.addProperty("revision", 0);
        JsonObject mergedItems = new JsonObject();
        items.forEach(mergedItems::add);
        merged.add("items", mergedItems);

        ConfigSnapshot snapshot = parse(merged.toString(), language);
        String json = ItemComponentConfig.snapshotToJson(snapshot);
        assertNothingDropped(items, json, language);
        return json;
    }

    /** The bundled item-id to category-key table, inverted from {@code tools/presets/categories.json}. */
    public static String categoriesJson() {
        TreeMap<String, String> byItem = new TreeMap<>();
        for (Map.Entry<String, JsonElement> category : readObject(SOURCE_ROOT.resolve(CATEGORIES_FILE)).entrySet()) {
            if (!category.getValue().isJsonArray()) {
                throw new IllegalArgumentException("category " + category.getKey() + " is not a list");
            }
            for (JsonElement itemId : category.getValue().getAsJsonArray()) {
                String previous = byItem.put(itemId.getAsString(), category.getKey());
                if (previous != null) {
                    throw new IllegalArgumentException(itemId.getAsString() + " is in both " + previous
                            + " and " + category.getKey());
                }
            }
        }
        JsonObject root = new JsonObject();
        byItem.forEach(root::addProperty);
        return GSON.toJson(root);
    }

    static Set<String> authoredIds(String language) {
        Set<String> ids = new LinkedHashSet<>();
        for (Path shard : shards(language)) ids.addAll(readObject(shard).keySet());
        return ids;
    }

    /** The bundled category table as item id to category key. */
    static Map<String, String> categoryByItem() {
        JsonObject root = JsonParser.parseString(categoriesJson()).getAsJsonObject();
        Map<String, String> categories = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) categories.put(entry.getKey(), entry.getValue().getAsString());
        return Map.copyOf(categories);
    }

    private static List<Path> shards(String language) {
        Path directory = SOURCE_ROOT.resolve(language);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("could not list " + directory.toAbsolutePath(), exception);
        }
    }

    private static ConfigSnapshot parse(String json, String language) {
        try {
            return ItemComponentConfig.snapshotFromJson(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("preset " + language + " is invalid: " + exception.getMessage(), exception);
        }
    }

    /**
     * The shards are the author's source of truth, so a field the configuration model does not know would vanish
     * without a trace. Comparing field paths rather than values keeps this usable: defaults the model fills in are
     * additions, and {@code "weight": 2} widening to {@code 2.0} is not a loss.
     */
    private static void assertNothingDropped(TreeMap<String, JsonObject> shards, String json, String language) {
        JsonObject written = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("items");
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : shards.entrySet()) {
            Set<String> source = new LinkedHashSet<>(paths(entry.getValue(), ""));
            source.remove("itemId");
            Set<String> writtenPaths = new LinkedHashSet<>(paths(written.get(entry.getKey()), ""));
            source.removeAll(writtenPaths);
            for (String path : source) dropped.add(entry.getKey() + "." + path);
        }
        if (!dropped.isEmpty()) {
            throw new IllegalArgumentException("preset " + language + " has fields the configuration model ignores: "
                    + dropped + " (fix the spelling, or extend ComponentRule if the field is meant to be supported)");
        }
    }

    private static List<String> paths(JsonElement element, String prefix) {
        List<String> paths = new ArrayList<>();
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                String path = prefix.isEmpty() ? member.getKey() : prefix + "." + member.getKey();
                paths.add(path);
                paths.addAll(paths(member.getValue(), path));
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                paths.addAll(paths(array.get(index), prefix + "[" + index + "]"));
            }
        }
        return paths;
    }

    private static JsonObject readObject(Path path) {
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not read " + path.toAbsolutePath(), exception);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + " is not valid JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException(path + " does not hold a JSON object");
        return parsed.getAsJsonObject();
    }
}
