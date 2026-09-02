package com.originlore.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Server registry identifiers used by the optional administrator client for completion. */
public record RegistryCatalog(List<String> itemIds, List<String> lootTableIds,
                              List<String> recipeIds, List<String> componentIds,
                              List<String> blockIds, List<String> statusEffectIds,
                              List<String> attributeIds, List<String> enchantmentIds) {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public RegistryCatalog {
        itemIds = immutableSorted(itemIds);
        lootTableIds = immutableSorted(lootTableIds);
        recipeIds = immutableSorted(recipeIds);
        componentIds = immutableSorted(componentIds);
        blockIds = immutableSorted(blockIds);
        statusEffectIds = immutableSorted(statusEffectIds);
        attributeIds = immutableSorted(attributeIds);
        enchantmentIds = immutableSorted(enchantmentIds);
    }

    public static RegistryCatalog fromServer(MinecraftServer server) {
        List<String> recipes = server.getRecipeManager().values().stream()
                .map(RecipeEntry::id).map(Object::toString).toList();
        List<String> lootTables = server.getReloadableRegistries()
                .getIds(RegistryKeys.LOOT_TABLE).stream().map(Object::toString).toList();
        return new RegistryCatalog(
                Registries.ITEM.getIds().stream().map(Object::toString).toList(),
                lootTables,
                recipes,
                Registries.DATA_COMPONENT_TYPE.getIds().stream().map(Object::toString).toList(),
                Registries.BLOCK.getIds().stream().map(Object::toString).toList(),
                Registries.STATUS_EFFECT.getIds().stream().map(Object::toString).toList(),
                Registries.ATTRIBUTE.getIds().stream().map(Object::toString).toList(),
                server.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getIds().stream()
                        .map(Object::toString).toList());
    }

    public static RegistryCatalog empty() {
        return new RegistryCatalog(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static RegistryCatalog fromJson(String json) {
        if (json == null || json.isBlank()) return empty();
        RegistryCatalog parsed = GSON.fromJson(json, RegistryCatalog.class);
        return parsed == null ? empty() : parsed;
    }

    private static List<String> immutableSorted(Collection<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        ArrayList<String> result = new ArrayList<>(source);
        result.removeIf(value -> value == null || value.isBlank());
        result.sort(String::compareTo);
        return List.copyOf(result);
    }
}
