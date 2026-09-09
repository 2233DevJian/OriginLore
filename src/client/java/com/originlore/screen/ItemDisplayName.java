package com.originlore.screen;

import com.google.gson.JsonParser;
import com.originlore.client.GuiText;
import com.originlore.config.PresetLibrary;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves registry names in the OriginLore editor language, independently of server-authored names.
 */
final class ItemDisplayName {
    static final String UNCATEGORIZED = "";

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static Map<String, String> categories = Map.of();
    private static Map<String, String> names = Map.of();
    private static Object language;
    private static boolean loaded;

    private ItemDisplayName() {
    }

    static Text of(String itemId) {
        return entry(itemId).name();
    }

    static String plainOf(String itemId) {
        return entry(itemId).plain();
    }

    static ItemStack iconOf(String itemId) {
        return entry(itemId).icon();
    }

    /** Bundled category key, or {@link #UNCATEGORIZED} for ids the preset table does not cover. */
    static String categoryOf(String itemId) {
        refresh();
        return categories.getOrDefault(itemId, UNCATEGORIZED);
    }

    static Text categoryLabel(String key) {
        return GuiText.text(key.isEmpty() ? "originlore.category.uncategorized"
                : "originlore.category." + key);
    }

    private static Entry entry(String itemId) {
        refresh();
        return CACHE.computeIfAbsent(itemId, ItemDisplayName::resolve);
    }

    private static void refresh() {
        Object current = currentLanguage();
        if (loaded && Objects.equals(current, language)) return;
        loaded = true;
        language = current;
        CACHE.clear();
        categories = PresetLibrary.categories();
        names = loadNames(GuiText.language());
    }

    private static Object currentLanguage() {
        return GuiText.language();
    }

    private static Map<String, String> loadNames(String code) {
        Map<String, String> result = new HashMap<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return result;
        for (String languageCode : new String[]{"en_us", code}) {
            for (Resource resource : client.getResourceManager().getAllResources(Identifier.of("minecraft", "lang/" + languageCode + ".json"))) {
                try (Reader reader = resource.getReader()) {
                    JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(entry -> {
                        if (entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString());
                    });
                } catch (IOException | RuntimeException ignored) {
                    // An invalid resource-pack translation falls back to the preceding pack or vanilla name.
                }
            }
        }
        return result;
    }

    private static Entry resolve(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        Optional<Item> found = id == null ? Optional.empty() : Registries.ITEM.getOrEmpty(id);
        if (found.isEmpty() || found.get() == Items.AIR) {
            // The server may run mods this client lacks; keep the row visible under its raw id instead of dropping it.
            return new Entry(Text.literal(itemId), itemId, ItemStack.EMPTY);
        }
        ItemStack icon = new ItemStack(found.get());
        String translated = names.get(found.get().getTranslationKey());
        Text name = translated == null ? icon.getName() : Text.literal(translated);
        return new Entry(name, name.getString(), icon);
    }

    private record Entry(Text name, String plain, ItemStack icon) {
    }
}
