package com.originlore.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Combines a bundled preset with a server's live configuration.
 *
 * <p>Whether a rule still matches the preset is decided on its canonical serialized form rather than field by field.
 * A rule carries lore, text components, enchantments, attributes, tools, foods and nested source variants; a
 * hand-written comparison would quietly fall behind every time that grows, and a stale comparison here means
 * {@code revert} deleting a rule the server owner had edited.
 */
public final class PresetMerger {
    public enum PresetState {
        NOT_APPLIED, APPLIED, MODIFIED
    }

    public record MergeResult(Map<String, ItemEntry> items, int added, int skipped, int overwritten) {
    }

    public record RevertResult(Map<String, ItemEntry> items, int removed, int keptModified) {
    }

    /** How much of {@code preset} the live configuration still holds, which is what {@code preset list} reports. */
    public record PresetStatus(int total, int present, int identical) {
        public PresetState state() {
            if (present == 0) return PresetState.NOT_APPLIED;
            return present == total && identical == total ? PresetState.APPLIED : PresetState.MODIFIED;
        }
    }

    private PresetMerger() {
    }

    /**
     * Adds every preset rule the configuration does not have yet. Existing rules are only replaced when
     * {@code overwrite} is set, so importing a preset never destroys work the server owner did by hand.
     */
    public static MergeResult merge(ConfigSnapshot current, ConfigSnapshot preset, boolean overwrite) {
        Map<String, ItemEntry> merged = sorted(current);
        int added = 0;
        int skipped = 0;
        int overwritten = 0;
        for (Map.Entry<String, ItemEntry> entry : preset.items().entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue().copy());
                added++;
            } else if (overwrite) {
                merged.put(entry.getKey(), entry.getValue().copy());
                overwritten++;
            } else {
                skipped++;
            }
        }
        return new MergeResult(Collections.unmodifiableMap(merged), added, skipped, overwritten);
    }

    /**
     * Drops only the rules that are still byte-identical to the preset's, because those are the ones the import
     * created. Anything the owner touched afterwards is theirs and stays, which is what makes this safe to run
     * without recording what was imported.
     */
    public static RevertResult revert(ConfigSnapshot current, ConfigSnapshot preset) {
        JsonObject live = canonical(current);
        JsonObject bundled = canonical(preset);
        Map<String, ItemEntry> kept = sorted(current);
        int removed = 0;
        int keptModified = 0;
        for (Map.Entry<String, JsonElement> entry : bundled.entrySet()) {
            String itemId = entry.getKey();
            JsonElement currentRule = live.get(itemId);
            if (currentRule == null) continue;
            if (currentRule.equals(entry.getValue())) {
                kept.remove(itemId);
                removed++;
            } else {
                keptModified++;
            }
        }
        return new RevertResult(Collections.unmodifiableMap(kept), removed, keptModified);
    }

    public static PresetStatus status(ConfigSnapshot current, ConfigSnapshot preset) {
        JsonObject live = canonical(current);
        JsonObject bundled = canonical(preset);
        int present = 0;
        int identical = 0;
        for (Map.Entry<String, JsonElement> entry : bundled.entrySet()) {
            JsonElement currentRule = live.get(entry.getKey());
            if (currentRule == null) continue;
            present++;
            if (currentRule.equals(entry.getValue())) identical++;
        }
        return new PresetStatus(bundled.size(), present, identical);
    }

    /** Sorted so that importing a preset leaves the configuration file in a stable, navigable order. */
    private static Map<String, ItemEntry> sorted(ConfigSnapshot snapshot) {
        return new TreeMap<>(snapshot.items());
    }

    private static JsonObject canonical(ConfigSnapshot snapshot) {
        JsonObject result = JsonParser.parseString(ItemComponentConfig.snapshotToJson(snapshot))
                .getAsJsonObject().getAsJsonObject("items");
        PresetLanguage.removeBookkeeping(result);
        return result;
    }
}
