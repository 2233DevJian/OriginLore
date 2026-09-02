package com.originlore.source;

import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextType;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.util.Identifier;

/** Immutable provenance attached to an ItemStack at the generation boundary. */
public record SourceContext(SourceType type, String sourceId, String lootTableId, String recipeId) {
    public enum SourceType {
        BLOCK_DROP,
        CHEST_LOOT,
        ENTITY_DROP,
        FISHING,
        ARCHAEOLOGY,
        BARTER,
        GIFT,
        VAULT,
        COMMAND,
        CRAFTING,
        SMELTING,
        CUTTING,
        SMITHING,
        UNKNOWN;

        public static SourceType parse(String value) {
            if (value == null) return UNKNOWN;
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            if (normalized.equals("LOOT")) return CHEST_LOOT;
            if (normalized.equals("DEFAULT")) return UNKNOWN;
            if (normalized.equals("CRAFTED")) return CRAFTING;
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    public SourceContext {
        type = type == null ? SourceType.UNKNOWN : type;
        sourceId = emptyToNull(sourceId);
        lootTableId = emptyToNull(lootTableId);
        recipeId = emptyToNull(recipeId);
    }

    public SourceContext(SourceType type) {
        this(type, null, null, null);
    }

    public static SourceContext unknown() {
        return new SourceContext(SourceType.UNKNOWN);
    }

    public static SourceContext command() {
        return new SourceContext(SourceType.COMMAND);
    }

    public static SourceContext recipe(SourceType type, Identifier recipeId) {
        return new SourceContext(type, recipeId == null ? null : recipeId.toString(), null,
                recipeId == null ? null : recipeId.toString());
    }

    public static SourceContext loot(SourceType type, Identifier lootTableId) {
        return new SourceContext(type, lootTableId == null ? null : lootTableId.toString(),
                lootTableId == null ? null : lootTableId.toString(), null);
    }

    public static SourceContext fromLootContext(LootContext context, LootContextType contextType,
                                                Identifier lootTableId) {
        SourceType sourceType;
        if (contextType == LootContextTypes.BLOCK) sourceType = SourceType.BLOCK_DROP;
        else if (contextType == LootContextTypes.CHEST) sourceType = SourceType.CHEST_LOOT;
        else if (contextType == LootContextTypes.COMMAND) sourceType = SourceType.COMMAND;
        else if (contextType == LootContextTypes.FISHING) sourceType = SourceType.FISHING;
        else if (contextType == LootContextTypes.ENTITY) sourceType = SourceType.ENTITY_DROP;
        else if (contextType == LootContextTypes.ARCHAEOLOGY) sourceType = SourceType.ARCHAEOLOGY;
        else if (contextType == LootContextTypes.BARTER) sourceType = SourceType.BARTER;
        else if (contextType == LootContextTypes.GIFT) sourceType = SourceType.GIFT;
        else if (contextType == LootContextTypes.VAULT) sourceType = SourceType.VAULT;
        else if (context != null && context.hasParameter(LootContextParameters.BLOCK_STATE)) {
            sourceType = SourceType.BLOCK_DROP;
        } else if (context != null && context.hasParameter(LootContextParameters.DAMAGE_SOURCE)) {
            sourceType = SourceType.ENTITY_DROP;
        } else {
            sourceType = SourceType.UNKNOWN;
        }
        return loot(sourceType, lootTableId);
    }

    /** Compatibility fallback for callers that cannot expose their context type. */
    public static SourceContext fromLootContext(LootContext context) {
        return fromLootContext(context, null, null);
    }

    public String ruleType() {
        return type.name();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
