package com.originlore.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the vanilla presets and category table bundled inside this mod's own jar. */
public final class PresetLibrary {
    private static final String PRESET_ROOT = "originlore/presets";
    private static final String PRESET_PREFIX = "vanilla_";
    private static final String JSON_SUFFIX = ".json";
    private static final String CATEGORIES_FILE = "categories.json";
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    public record PresetInfo(String id, String language, int itemCount) {
    }

    private PresetLibrary() {
    }

    public static List<PresetInfo> discover() {
        Path root = root().orElse(null);
        if (root == null) return List.of();
        List<PresetInfo> presets = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                String fileName = entry.getFileName().toString();
                if (!fileName.startsWith(PRESET_PREFIX) || !fileName.endsWith(JSON_SUFFIX)) continue;
                String id = fileName.substring(0, fileName.length() - JSON_SUFFIX.length());
                String json = read(entry);
                if (json == null) continue;
                presets.add(new PresetInfo(id, id.substring(PRESET_PREFIX.length()), countItems(json)));
            }
        } catch (IOException exception) {
            return List.of();
        }
        return List.copyOf(presets);
    }

    /** Resolves {@code id} against the jar only; an id that is not a plain slug is refused outright. */
    public static ItemComponentConfig.ConfigSnapshot load(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("unknown preset: " + id);
        }
        String json = readBundled(id + JSON_SUFFIX);
        if (json == null) throw new IllegalArgumentException("unknown preset: " + id);
        return ItemComponentConfig.snapshotFromJson(json);
    }

    /** Maps item id to category key; empty when no category table is bundled. */
    public static Map<String, String> categories() {
        String json = readBundled(CATEGORIES_FILE);
        if (json == null) return Map.of();

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            return Map.of();
        }
        if (!parsed.isJsonObject()) return Map.of();

        Map<String, String> categories = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String category = entry.getValue().getAsString();
            if (!category.isBlank()) categories.put(entry.getKey(), category);
        }
        return Map.copyOf(categories);
    }

    private static Optional<Path> root() {
        return FabricLoader.getInstance().getModContainer("originlore")
                .flatMap(container -> container.findPath(PRESET_ROOT));
    }

    private static String read(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String readBundled(String fileName) {
        Path root = root().orElse(null);
        if (root != null) return read(root.resolve(fileName));
        // Config tooling and unit tests use the bundled resources without a Fabric game bootstrap.
        try (InputStream input = PresetLibrary.class.getResourceAsStream("/" + PRESET_ROOT + "/" + fileName)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static int countItems(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return 0;
            JsonObject items = parsed.getAsJsonObject().getAsJsonObject("items");
            return items == null ? 0 : items.size();
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
