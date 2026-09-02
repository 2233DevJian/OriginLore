package com.originlore;

import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.network.RegistryCatalog;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleResolutionTest {
    @Test
    void sourceMatchingPrefersSpecificRuleAndFallsBackToCategory() {
        ItemEntry entry = new ItemEntry("minecraft:apple");
        SourceRule generic = new SourceRule("CHEST_LOOT");
        SourceRule specific = new SourceRule("CHEST_LOOT");
        specific.lootTableId = "minecraft:chests/simple_dungeon";
        entry.sources.add(generic);
        entry.sources.add(specific);
        ItemComponentConfig config = new ItemComponentConfig(Path.of("build", "unused-test-config.json"));

        assertEquals(specific, config.findSourceRule(entry, "CHEST_LOOT",
                "minecraft:chests/simple_dungeon", null));
        assertEquals(generic, config.findSourceRule(entry, "CHEST_LOOT", null, null));
        assertNull(config.findSourceRule(entry, "BLOCK_DROP", null, null));
    }

    @Test
    void componentRulesMergeOnlyExplicitFields() {
        ComponentRule base = new ComponentRule();
        base.lore = List.of("base");
        base.maxStackSize = 16;
        base.fireResistant = true;
        ComponentRule source = new ComponentRule();
        source.lore = List.of("source");
        source.maxDamage = 250;
        ComponentRule variant = new ComponentRule();
        variant.maxStackSize = 1;

        ComponentRule merged = base.copy();
        merged.mergeFrom(source);
        merged.mergeFrom(variant);

        assertEquals(List.of("source"), merged.lore);
        assertEquals(1, merged.maxStackSize);
        assertEquals(250, merged.maxDamage);
        assertEquals(true, merged.fireResistant);
    }

    @Test
    void weightedSelectionUsesStableIntervals() {
        SourceRule source = new SourceRule("CHEST_LOOT");
        source.variants.add(new Variant("normal", 1));
        source.variants.add(new Variant("old", 3));
        source.variants.add(new Variant("rotten", 1));

        assertEquals("normal", ItemComponentManager.selectVariant(source, () -> 0.0));
        assertEquals("normal", ItemComponentManager.selectVariant(source, () -> 0.199999));
        assertEquals("old", ItemComponentManager.selectVariant(source, () -> 0.2));
        assertEquals("old", ItemComponentManager.selectVariant(source, () -> 0.799999));
        assertEquals("rotten", ItemComponentManager.selectVariant(source, () -> 0.8));
        assertEquals("rotten", ItemComponentManager.selectVariant(source, () -> 1.0));
    }

    @Test
    void unknownSourceIsNeverGuessed() {
        SourceContext context = SourceContext.fromLootContext(null);
        assertEquals(SourceType.UNKNOWN, context.type());
        assertNull(context.sourceId());
        assertEquals(SourceType.UNKNOWN, SourceType.parse("not-a-source"));
    }

    @Test
    void registryCatalogAcceptsSnapshotsFromBeforeEnchantmentCompletion() {
        RegistryCatalog catalog = RegistryCatalog.fromJson("""
                {"itemIds":["minecraft:stone"],"attributeIds":["minecraft:generic.attack_damage"]}
                """);

        assertEquals(List.of("minecraft:stone"), catalog.itemIds());
        assertTrue(catalog.enchantmentIds().isEmpty());
        assertTrue(catalog.blockIds().isEmpty());
    }
}
