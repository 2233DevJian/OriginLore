package com.originlore.preset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests cannot bootstrap the item registry, so the authoritative id set is exported by the
 * {@code exportsTheAuthoritativeItemIdReference} game test and committed here. These checks only prove that the
 * committed copy still belongs to the Minecraft version this build targets, which is what stops a preset from being
 * validated against a stale universe of items after an upgrade.
 */
class ItemIdReferenceTest {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    @Test
    void referenceListExistsForTheTargetedMinecraftVersion() {
        String minecraft = gradleProperties().getProperty("minecraft_version");
        assertEquals(minecraft, header().get("minecraft"),
                "committed id list was exported from another Minecraft version; re-run"
                        + " ./gradlew runGametest and copy build/gametest/item_ids_"
                        + minecraft.replace('.', '_') + ".txt over " + resourceName(minecraft));
    }

    @Test
    void referenceHeaderRecordsTheYarnMappingsItWasExportedWith() {
        assertEquals(gradleProperties().getProperty("yarn_mappings"), header().get("yarn"),
                "committed id list was exported with other Yarn mappings; re-run ./gradlew runGametest and refresh "
                        + resourceName(gradleProperties().getProperty("minecraft_version")));
    }

    @Test
    void referenceHeaderCountMatchesTheListedIds() {
        assertEquals(String.valueOf(referenceItemIds().size()), header().get("count"),
                "the count in the reference header no longer matches the ids below it");
    }

    @Test
    void referenceIdsAreWellFormedSortedAndUnique() {
        List<String> ids = referenceItemIds();
        assertTrue(ids.size() > 1000, "reference list looks truncated: " + ids.size() + " ids");

        Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            assertTrue(ITEM_ID.matcher(id).matches(), "not a namespaced item id: " + id);
            if (index > 0) {
                assertTrue(ids.get(index - 1).compareTo(id) < 0,
                        "reference list is not strictly sorted at " + id + " (preceded by " + ids.get(index - 1) + ")");
            }
            Integer duplicate = firstSeen.putIfAbsent(id, index);
            assertTrue(duplicate == null, "duplicate id " + id + " at lines " + duplicate + " and " + index);
        }
    }

    /** The committed id universe, without its header. Shared with the preset pipeline checks. */
    static List<String> referenceItemIds() {
        List<String> ids = new ArrayList<>();
        for (String line : readReference()) {
            if (!line.startsWith("#") && !line.isBlank()) ids.add(line);
        }
        return List.copyOf(ids);
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : readReference()) {
            if (!line.startsWith("#")) break;
            int separator = line.indexOf('=');
            if (separator > 1) header.put(line.substring(1, separator).trim(), line.substring(separator + 1).trim());
        }
        return header;
    }

    private static List<String> readReference() {
        String resource = resourceName(gradleProperties().getProperty("minecraft_version"));
        try (InputStream input = ItemIdReferenceTest.class.getResourceAsStream("/" + resource)) {
            if (input == null) {
                fail("missing src/test/resources/" + resource + "; run ./gradlew runGametest and copy"
                        + " build/gametest/item_ids_<version>.txt into place");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException exception) {
            return fail("could not read " + resource, exception);
        }
    }

    private static String resourceName(String minecraftVersion) {
        return "reference/item_ids_" + minecraftVersion.replace('.', '_') + ".txt";
    }

    private static Properties gradleProperties() {
        Properties properties = new Properties();
        Path file = Path.of("gradle.properties");
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            return fail("could not read " + file.toAbsolutePath(), exception);
        }
        return properties;
    }
}
