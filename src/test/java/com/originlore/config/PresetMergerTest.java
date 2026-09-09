package com.originlore.config;

import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.PresetMerger.MergeResult;
import com.originlore.config.PresetMerger.PresetState;
import com.originlore.config.PresetMerger.PresetStatus;
import com.originlore.config.PresetMerger.RevertResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importing a preset rewrites a server's live configuration, and undoing it has to distinguish rules the import
 * created from rules the owner then edited. Getting that wrong either destroys someone's work or leaves a preset
 * nobody can remove, so the semantics are pinned here rather than left to in-game checking.
 */
class PresetMergerTest {
    private static final ConfigSnapshot PRESET = snapshot("""
            {
              "minecraft:apple": {"base": {"lore": ["preset apple"]}},
              "minecraft:stone": {
                "base": {"lore": ["preset stone"]},
                "sources": [{
                  "type": "CHEST_LOOT",
                  "variants": [
                    {"id": "fresh", "weight": 2, "rule": {"lore": ["fresh"]}},
                    {"id": "old", "weight": 1, "rule": {"lore": ["old"]}}
                  ]
                }]
              },
              "minecraft:diamond": {"base": {"lore": ["preset diamond"]}}
            }
            """);

    @Test
    void mergeLeavesRulesTheServerAlreadyHadAlone() {
        ConfigSnapshot current = snapshot("""
                {
                  "minecraft:apple": {"base": {"lore": ["my apple"]}},
                  "minecraft:bow": {"base": {"lore": ["my bow"]}}
                }
                """);

        MergeResult result = PresetMerger.merge(current, PRESET, false);

        assertEquals(2, result.added());
        assertEquals(1, result.skipped());
        assertEquals(0, result.overwritten());
        assertEquals(List.of("my apple"), result.items().get("minecraft:apple").base.lore);
        assertEquals(List.of("my bow"), result.items().get("minecraft:bow").base.lore);
        assertEquals(List.of("preset stone"), result.items().get("minecraft:stone").base.lore);
    }

    @Test
    void mergeOverwriteReplacesTheWholeRule() {
        ConfigSnapshot current = snapshot("""
                {"minecraft:stone": {"base": {"lore": ["my stone"], "maxStackSize": 4}}}
                """);

        MergeResult result = PresetMerger.merge(current, PRESET, true);

        assertEquals(2, result.added());
        assertEquals(0, result.skipped());
        assertEquals(1, result.overwritten());
        assertEquals(List.of("preset stone"), result.items().get("minecraft:stone").base.lore);
        assertNull(result.items().get("minecraft:stone").base.maxStackSize,
                "overwrite left a field behind that the preset does not set");
    }

    @Test
    void mergedConfigurationIsSortedByItemId() {
        MergeResult result = PresetMerger.merge(snapshot("{}"), PRESET, false);
        assertEquals(List.of("minecraft:apple", "minecraft:diamond", "minecraft:stone"),
                List.copyOf(result.items().keySet()));
    }

    @Test
    void revertRemovesOnlyRulesThatStillMatchThePreset() {
        ConfigSnapshot current = snapshot("""
                {
                  "minecraft:apple": {"base": {"lore": ["preset apple"]}},
                  "minecraft:stone": {
                    "base": {"lore": ["preset stone"]},
                    "sources": [{
                      "type": "CHEST_LOOT",
                      "variants": [
                        {"id": "fresh", "weight": 9, "rule": {"lore": ["fresh"]}},
                        {"id": "old", "weight": 1, "rule": {"lore": ["old"]}}
                      ]
                    }]
                  },
                  "minecraft:bow": {"base": {"lore": ["my bow"]}}
                }
                """);

        RevertResult result = PresetMerger.revert(current, PRESET);

        assertEquals(1, result.removed(), "only the untouched apple should have been removed");
        assertEquals(1, result.keptModified(), "the stone rule differs deep inside its variant weights");
        assertEquals(List.of("my bow"), result.items().get("minecraft:bow").base.lore);
        assertEquals(List.of("preset stone"), result.items().get("minecraft:stone").base.lore);
        assertFalse(result.items().containsKey("minecraft:apple"));
        assertFalse(result.items().containsKey("minecraft:diamond"),
                "revert must not add preset rules the server never had");
    }

    @Test
    void revertIsSafeToRunTwice() {
        RevertResult first = PresetMerger.revert(PRESET, PRESET);
        assertEquals(3, first.removed());

        RevertResult second = PresetMerger.revert(new ConfigSnapshot(0, first.items()), PRESET);
        assertEquals(0, second.removed());
        assertEquals(0, second.keptModified());
        assertTrue(second.items().isEmpty());
    }

    @Test
    void statusDistinguishesUnappliedAppliedAndEditedPresets() {
        assertEquals(PresetState.NOT_APPLIED, PresetMerger.status(snapshot("{}"), PRESET).state());
        assertEquals(PresetState.APPLIED, PresetMerger.status(PRESET, PRESET).state());

        PresetStatus partial = PresetMerger.status(snapshot("""
                {"minecraft:apple": {"base": {"lore": ["preset apple"]}}}
                """), PRESET);
        assertEquals(PresetState.MODIFIED, partial.state());
        assertEquals(3, partial.total());
        assertEquals(1, partial.present());
        assertEquals(1, partial.identical());

        PresetStatus edited = PresetMerger.status(snapshot("""
                {
                  "minecraft:apple": {"base": {"lore": ["preset apple"]}},
                  "minecraft:stone": {"base": {"lore": ["retitled stone"]}},
                  "minecraft:diamond": {"base": {"lore": ["preset diamond"]}}
                }
                """), PRESET);
        assertEquals(PresetState.MODIFIED, edited.state());
        assertEquals(3, edited.present());
        assertEquals(2, edited.identical());
    }

    @Test
    void anImportedPresetStillMatchesAfterSavingAndReloading() {
        MergeResult applied = PresetMerger.merge(snapshot("{}"), PRESET, false);
        ConfigSnapshot reloaded = roundTrip(roundTrip(new ConfigSnapshot(1, applied.items())));

        assertEquals(PresetState.APPLIED, PresetMerger.status(reloaded, PRESET).state());
        RevertResult reverted = PresetMerger.revert(reloaded, PRESET);
        assertEquals(3, reverted.removed(),
                "rules that only travelled through a save and a reload no longer matched the preset");
        assertTrue(reverted.items().isEmpty());
    }

    @Test
    void revertPreservesCustomDataWhoseKeysResemblePresetBookkeeping() {
        ConfigSnapshot preset = snapshot("""
                {"minecraft:apple":{"base":{"setComponents":{"minecraft:custom_data":{
                  "presetTexts":"bundled value","presetTextKey":"custom namespace"
                }}}}}
                """);
        ConfigSnapshot current = snapshot("""
                {"minecraft:apple":{"base":{"setComponents":{"minecraft:custom_data":{
                  "presetTexts":"hand edited value","presetTextKey":"custom namespace"
                }}}}}
                """);

        RevertResult reverted = PresetMerger.revert(current, preset);

        assertEquals(0, reverted.removed());
        assertEquals(1, reverted.keptModified());
        assertTrue(reverted.items().containsKey("minecraft:apple"));
    }

    private static ConfigSnapshot roundTrip(ConfigSnapshot snapshot) {
        return ItemComponentConfig.snapshotFromJson(ItemComponentConfig.snapshotToJson(snapshot));
    }

    private static ConfigSnapshot snapshot(String itemsJson) {
        return ItemComponentConfig.snapshotFromJson("{\"revision\": 0, \"items\": " + itemsJson + "}");
    }
}
