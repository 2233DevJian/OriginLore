package com.originlore.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The bundled presets are generated artifacts that ship inside the mod jar, so nobody notices when they drift from the
 * shards they were built from. These checks make that drift a build failure, and make sure the artifact the jar carries
 * is one the running mod will actually accept.
 */
class PresetPipelineTest {
    private static final Path EXCLUSIONS = Path.of("tools", "presets", "exclusions.json");

    @Test
    void bundledPresetsMatchWhatTheShardsGenerate() {
        for (String language : PresetWriter.LANGUAGES) {
            assertFresh(PresetWriter.presetJson(language), PresetWriter.presetFile(language));
        }
        assertFresh(PresetWriter.categoriesJson(), PresetWriter.OUTPUT_ROOT.resolve(PresetWriter.CATEGORIES_FILE));
    }

    @Test
    void generationIsDeterministic() {
        for (String language : PresetWriter.LANGUAGES) {
            assertSameDocument(PresetWriter.presetJson(language), PresetWriter.presetJson(language),
                    language + " preset generation is not reproducible");
        }
        assertSameDocument(PresetWriter.categoriesJson(), PresetWriter.categoriesJson(),
                "category table generation is not reproducible");
    }

    @Test
    void bundledPresetsAreConfigurationsTheModAccepts() {
        for (String language : PresetWriter.LANGUAGES) {
            ConfigSnapshot snapshot = parse(committed(PresetWriter.presetFile(language)), language);
            assertSameItems(PresetWriter.authoredIds(language), snapshot.items().keySet(),
                    language + " preset does not carry exactly the authored items");

            for (ItemEntry entry : snapshot.items().values()) {
                for (SourceRule source : entry.sources) {
                    assertTrue(!source.type.isBlank(), entry.itemId + " has a source without a type");
                    double total = 0.0;
                    Set<String> variantIds = new LinkedHashSet<>();
                    for (Variant variant : source.variants) {
                        assertTrue(variantIds.add(variant.id),
                                entry.itemId + " repeats variant id " + variant.id);
                        assertTrue(Double.isFinite(variant.weight) && variant.weight >= 0,
                                entry.itemId + " variant " + variant.id + " has weight " + variant.weight);
                        total += variant.weight;
                    }
                    assertTrue(source.variants.isEmpty() || total > 0,
                            entry.itemId + " has variants whose weights add up to " + total);
                }
            }
        }
    }

    @Test
    void everyLanguageCoversExactlyTheSameItems() {
        Set<String> reference = PresetWriter.authoredIds(PresetWriter.LANGUAGES.get(0));
        for (String language : PresetWriter.LANGUAGES) {
            assertSameItems(reference, PresetWriter.authoredIds(language),
                    language + " covers a different item set");
        }
    }

    @Test
    void authoredItemsAreRealCategorizedItemsAndNeverExcludedOnes() {
        Set<String> reference = new LinkedHashSet<>(ItemIdReferenceTest.referenceItemIds());
        Map<String, String> categories = PresetWriter.categoryByItem();
        Set<String> excluded = readObject(EXCLUSIONS).keySet();

        for (String language : PresetWriter.LANGUAGES) {
            for (String itemId : PresetWriter.authoredIds(language)) {
                assertTrue(reference.contains(itemId), language + " writes copy for an item that does not exist: " + itemId);
                assertTrue(!excluded.contains(itemId), language + " writes copy for an excluded item: " + itemId);
                assertTrue(categories.containsKey(itemId), language + " writes copy for an uncategorized item: " + itemId);
            }
        }
    }

    private static ConfigSnapshot parse(String json, String language) {
        try {
            return ItemComponentConfig.snapshotFromJson(json);
        } catch (RuntimeException exception) {
            return fail("bundled " + language + " preset is not a valid configuration: " + exception.getMessage());
        }
    }

    private static void assertFresh(String generated, Path file) {
        assertSameDocument(lf(generated), committed(file),
                file + " is stale; run ./gradlew writePresets and commit the result");
    }

    /** Both sides are whole preset documents, so point at where they part company instead of dumping them. */
    private static void assertSameDocument(String expected, String actual, String message) {
        if (expected.equals(actual)) return;
        fail(message + ". " + firstDifference(expected, actual));
    }

    /** The sets run to over a thousand ids, so report what each side holds alone rather than the sets themselves. */
    private static void assertSameItems(Set<String> expected, Set<String> actual, String message) {
        if (expected.equals(actual)) return;
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new LinkedHashSet<>(actual);
        extra.removeAll(expected);
        fail(message + "; missing " + missing + ", extra " + extra);
    }

    /** Dumping either whole document would bury the answer, so name the first line that disagrees. */
    private static String firstDifference(String generated, String bundled) {
        List<String> expected = generated.lines().toList();
        List<String> actual = bundled.lines().toList();
        for (int line = 0; line < Math.min(expected.size(), actual.size()); line++) {
            if (!expected.get(line).equals(actual.get(line))) {
                return "Line " + (line + 1) + " should read <" + clip(expected.get(line))
                        + "> but reads <" + clip(actual.get(line)) + ">.";
            }
        }
        return "Generated " + expected.size() + " lines but the file holds " + actual.size() + ".";
    }

    private static String clip(String line) {
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }

    private static String committed(Path file) {
        try {
            return lf(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return fail("could not read " + file.toAbsolutePath() + "; run ./gradlew writePresets", exception);
        }
    }

    /** Gradle hands the generator LF, but a Windows checkout may hand the test CRLF. */
    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }

    private static JsonObject readObject(Path path) {
        JsonElement parsed = JsonParser.parseString(committed(path));
        if (!parsed.isJsonObject()) return fail(path + " does not hold a JSON object");
        return parsed.getAsJsonObject();
    }
}
