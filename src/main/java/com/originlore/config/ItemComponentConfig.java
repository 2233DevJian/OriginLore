package com.originlore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.util.Identifier;

/** Server-owned, versioned OriginLore configuration. */
public class ItemComponentConfig {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path configFile;
    private final Path configDirectory;
    private Map<String, ItemEntry> itemConfigs = new LinkedHashMap<>();
    private long revision;
    private String lastError;

    public ItemComponentConfig() {
        this(FabricLoader.getInstance().getConfigDir()
                .resolve("originlore")
                .resolve("item_components.json"));
    }

    /** A path constructor keeps the model testable outside a running server. */
    public ItemComponentConfig(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
        Path parent = this.configFile.getParent();
        this.configDirectory = parent == null ? Path.of(".").toAbsolutePath() : parent;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    /** Loads the file and retains the previous snapshot if parsing fails. */
    public synchronized LoadResult load() {
        return load(snapshot -> List.of());
    }

    /** Loads only after the complete candidate snapshot passes runtime validation. */
    public synchronized LoadResult load(Function<ConfigSnapshot, List<String>> validator) {
        Map<String, ItemEntry> previousItems = itemConfigs;
        long previousRevision = revision;
        try {
            Files.createDirectories(configDirectory);
            if (!Files.exists(configFile)) {
                Map<String, ItemEntry> candidate = createExampleConfig();
                validateCandidate(candidate, 0, validator);
                itemConfigs = candidate;
                revision = 0L;
                lastError = null;
                SaveResult saved = saveInternal(true);
                if (!saved.success()) {
                    itemConfigs = previousItems;
                    revision = previousRevision;
                    return LoadResult.failure(saved.message());
                }
                return LoadResult.success(false, revision, "created default configuration");
            }

            ParsedConfig parsed = readParsedConfig();
            validateCandidate(parsed.items, parsed.revision, validator);
            itemConfigs = parsed.items;
            revision = parsed.revision;
            lastError = null;
            if (parsed.migrated) {
                SaveResult migrated = saveInternal(true);
                if (!migrated.success()) {
                    itemConfigs = previousItems;
                    revision = previousRevision;
                    return LoadResult.failure("configuration migrated in memory but could not be written: " + migrated.message());
                }
            }
            return LoadResult.success(parsed.migrated, revision, parsed.migrated ? "migrated legacy configuration" : "loaded");
        } catch (Exception e) {
            itemConfigs = previousItems;
            revision = previousRevision;
            lastError = compactMessage(e);
            return LoadResult.failure(lastError);
        }
    }

    /** Runtime reload that keeps the old live snapshot unless the canonical rewrite succeeds. */
    public synchronized LoadResult reloadFromDisk() {
        return reloadFromDisk(snapshot -> List.of());
    }

    /** Runtime reload with registry-aware validation before changing live state. */
    public synchronized LoadResult reloadFromDisk(Function<ConfigSnapshot, List<String>> validator) {
        Map<String, ItemEntry> previousItems = itemConfigs;
        long previousRevision = revision;
        try {
            if (!Files.exists(configFile)) throw new IOException("configuration file does not exist: " + configFile);
            ParsedConfig parsed = readParsedConfig();
            validateCandidate(parsed.items, parsed.revision, validator);
            itemConfigs = parsed.items;
            revision = Math.max(previousRevision, parsed.revision);
            SaveResult saved = saveInternal(true);
            if (!saved.success()) {
                itemConfigs = previousItems;
                revision = previousRevision;
                return LoadResult.failure(saved.message());
            }
            lastError = null;
            return LoadResult.success(parsed.migrated, revision, "reloaded");
        } catch (Exception e) {
            itemConfigs = previousItems;
            revision = previousRevision;
            lastError = compactMessage(e);
            return LoadResult.failure(lastError);
        }
    }

    /** Saves atomically and increments the revision after a successful move. */
    public synchronized SaveResult save() {
        return saveInternal(true);
    }

    /** Transaction primitive used by the remote editor. */
    public synchronized SaveResult replaceSnapshot(ConfigSnapshot snapshot, long expectedRevision) {
        if (snapshot == null) return SaveResult.failure("snapshot is null", revision);
        if (expectedRevision != revision) return SaveResult.conflict(revision);
        try {
            Map<String, ItemEntry> copy = copyItems(snapshot.items());
            validateItems(copy);
            Map<String, ItemEntry> previous = itemConfigs;
            long previousRevision = revision;
            itemConfigs = copy;
            SaveResult result = saveInternal(true);
            if (!result.success()) {
                itemConfigs = previous;
                revision = previousRevision;
            }
            return result;
        } catch (Exception e) {
            return SaveResult.failure(compactMessage(e), revision);
        }
    }

    public synchronized ConfigSnapshot snapshot() {
        return new ConfigSnapshot(revision, copyItems(itemConfigs));
    }

    public synchronized String snapshotJson() {
        return snapshotToJson(snapshot());
    }

    public static String snapshotToJson(ConfigSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.addProperty("revision", snapshot == null ? 0 : snapshot.revision());
        JsonObject items = new JsonObject();
        if (snapshot != null) {
            for (Map.Entry<String, ItemEntry> entry : snapshot.items().entrySet()) {
                items.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
        }
        root.add("items", items);
        return GSON.toJson(root);
    }

    /** Parses a v2 wire snapshot without touching disk or live configuration state. */
    public static ConfigSnapshot snapshotFromJson(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new JsonParseException("snapshot root must be an object");
        JsonObject root = parsed.getAsJsonObject();
        int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
        if (schema > CURRENT_SCHEMA_VERSION) {
            throw new JsonParseException("unsupported schema version: " + schema);
        }
        JsonElement itemsElement = root.get("items");
        if (itemsElement == null || !itemsElement.isJsonObject()) throw new JsonParseException("items must be an object");
        long revision = root.has("revision") ? Math.max(0, root.get("revision").getAsLong()) : 0;
        Map<String, ItemEntry> items = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> value : itemsElement.getAsJsonObject().entrySet()) {
            if (!value.getValue().isJsonObject()) throw new JsonParseException("item rule for " + value.getKey() + " must be an object");
            ItemEntry entry = GSON.fromJson(value.getValue(), ItemEntry.class);
            if (entry == null) throw new JsonParseException("item rule for " + value.getKey() + " is null");
            entry.itemId = value.getKey();
            normalizeEntry(entry);
            validateItemId(entry.itemId);
            items.put(entry.itemId, entry);
        }
        validateItemsStatic(items);
        return new ConfigSnapshot(revision, items);
    }

    public static String componentRuleToJson(ComponentRule rule) {
        return GSON.toJson(rule == null ? new ComponentRule() : rule);
    }

    public static ComponentRule componentRuleFromJson(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new JsonParseException("component rule must be an object");
        ComponentRule rule = GSON.fromJson(parsed, ComponentRule.class);
        rule = rule == null ? new ComponentRule() : rule;
        normalizeRule(rule);
        return rule;
    }

    public synchronized ItemEntry getItemConfig(String itemId) {
        return itemConfigs.get(itemId);
    }

    public synchronized ItemEntry getItemData(String itemId) {
        return getItemConfig(itemId);
    }

    public synchronized boolean hasConfig(String itemId) {
        return itemConfigs.containsKey(itemId);
    }

    public synchronized Map<String, ItemEntry> getAllConfigs() {
        return Collections.unmodifiableMap(copyItems(itemConfigs));
    }

    public synchronized Map<String, ItemEntry> getAllItems() {
        return getAllConfigs();
    }

    public synchronized void setItemConfig(String itemId, ItemEntry data) {
        if (itemId == null || data == null) return;
        ItemEntry copy = data.copy();
        copy.itemId = itemId;
        itemConfigs.put(itemId, copy);
    }

    public synchronized void addOrUpdateItem(ItemEntry data) {
        if (data != null && data.itemId != null) setItemConfig(data.itemId, data);
    }

    public synchronized void removeItemConfig(String itemId) {
        if (itemId != null) itemConfigs.remove(itemId);
    }

    public synchronized void removeItem(String itemId) {
        removeItemConfig(itemId);
    }

    /** Returns the most specific matching source rule. */
    public SourceRule findSourceRule(ItemEntry entry, String sourceType, String lootTableId, String recipeId) {
        if (entry == null || entry.sources == null) return null;
        SourceRule best = null;
        int bestScore = -1;
        for (SourceRule candidate : entry.sources) {
            if (candidate == null || !candidate.matches(sourceType, lootTableId, recipeId)) continue;
            int score = candidate.specificity(lootTableId, recipeId);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private SaveResult saveInternal(boolean incrementRevision) {
        Path temporary = null;
        long oldRevision = revision;
        try {
            Files.createDirectories(configDirectory);
            if (incrementRevision) revision++;
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
            root.addProperty("revision", revision);
            JsonObject items = new JsonObject();
            for (Map.Entry<String, ItemEntry> entry : itemConfigs.entrySet()) {
                items.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
            root.add("items", items);

            temporary = configDirectory.resolve(configFile.getFileName() + ".tmp-" + UUID.randomUUID());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            lastError = null;
            return SaveResult.success(revision);
        } catch (Exception e) {
            revision = oldRevision;
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
            lastError = compactMessage(e);
            return SaveResult.failure(lastError, revision);
        }
    }

    private ParsedConfig readParsedConfig() throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new JsonParseException("root must be an object");
            root = parsed.getAsJsonObject();
        }
        return parseRoot(root);
    }

    private ParsedConfig parseRoot(JsonObject root) {
        int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
        if (schema > CURRENT_SCHEMA_VERSION) throw new JsonParseException("unsupported schema version: " + schema);
        long loadedRevision = root.has("revision") ? Math.max(0, root.get("revision").getAsLong()) : 0;
        JsonElement itemsElement = root.get("items");
        if (itemsElement == null || !itemsElement.isJsonObject()) throw new JsonParseException("items must be an object");

        Map<String, ItemEntry> parsed = new LinkedHashMap<>();
        boolean migrated = schema < CURRENT_SCHEMA_VERSION;
        for (Map.Entry<String, JsonElement> item : itemsElement.getAsJsonObject().entrySet()) {
            if (item.getKey().startsWith("_comment") && !item.getValue().isJsonObject()) {
                migrated = true;
                continue;
            }
            if (!item.getValue().isJsonObject()) throw new JsonParseException("item rule for " + item.getKey() + " must be an object");
            ItemEntry entry = parseItemEntry(item.getKey(), item.getValue().getAsJsonObject());
            validateItemId(entry.itemId);
            parsed.put(entry.itemId, entry);
        }
        validateItems(parsed);
        return new ParsedConfig(parsed, loadedRevision, migrated);
    }

    private ItemEntry parseItemEntry(String itemId, JsonObject object) {
        ItemEntry entry;
        if (object.has("base") || object.has("sources")) {
            entry = new ItemEntry(itemId);
            if (object.has("base") && object.get("base").isJsonObject()) entry.base = parseRule(object.getAsJsonObject("base"));
            if (object.has("sources")) {
                JsonElement sourceElement = object.get("sources");
                if (sourceElement.isJsonArray()) {
                    for (JsonElement value : sourceElement.getAsJsonArray()) entry.sources.add(parseSourceRule(value));
                } else if (sourceElement.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> source : sourceElement.getAsJsonObject().entrySet()) {
                        SourceRule parsed = parseSourceRule(source.getValue());
                        if (parsed.type == null || parsed.type.isBlank()) parsed.type = source.getKey();
                        entry.sources.add(parsed);
                    }
                } else throw new JsonParseException("sources for " + itemId + " must be an array or object");
            }
        } else if (object.has("loot") || object.has("default") || object.has("defaultRule") || object.has("crafted")) {
            entry = new ItemEntry(itemId);
            JsonElement defaultValue = object.has("defaultRule") ? object.get("defaultRule") : object.get("default");
            if (defaultValue != null && defaultValue.isJsonObject()) entry.base = parseRule(defaultValue.getAsJsonObject());
            if (object.has("loot") && object.get("loot").isJsonObject()) {
                SourceRule source = new SourceRule("CHEST_LOOT");
                source.rule = parseRule(object.getAsJsonObject("loot"));
                entry.sources.add(source);
            }
            if (object.has("crafted") && object.get("crafted").isJsonObject()) {
                SourceRule source = new SourceRule("CRAFTING");
                source.rule = parseRule(object.getAsJsonObject("crafted"));
                entry.sources.add(source);
            }
        } else {
            entry = new ItemEntry(itemId);
            entry.base = parseRule(object);
            entry.legacyFlat = true;
        }
        entry.itemId = itemId;
        normalizeEntry(entry);
        return entry;
    }

    private SourceRule parseSourceRule(JsonElement element) {
        if (!element.isJsonObject()) throw new JsonParseException("source rule must be an object");
        JsonObject object = element.getAsJsonObject();
        SourceRule source = new SourceRule();
        if (object.has("type")) source.type = object.get("type").getAsString();
        if (object.has("lootTableId")) source.lootTableId = nullableString(object.get("lootTableId"));
        if (object.has("recipeId")) source.recipeId = nullableString(object.get("recipeId"));
        if (object.has("rule") && object.get("rule").isJsonObject()) source.rule = parseRule(object.getAsJsonObject("rule"));
        else source.rule = parseRule(object);
        if (object.has("variants")) {
            if (!object.get("variants").isJsonArray()) throw new JsonParseException("variants must be an array");
            for (JsonElement value : object.getAsJsonArray("variants")) {
                if (!value.isJsonObject()) throw new JsonParseException("variant must be an object");
                JsonObject variantObject = value.getAsJsonObject();
                Variant variant = new Variant();
                if (variantObject.has("id")) variant.id = variantObject.get("id").getAsString();
                if (variantObject.has("weight")) variant.weight = variantObject.get("weight").getAsDouble();
                if (variantObject.has("rule") && variantObject.get("rule").isJsonObject()) variant.rule = parseRule(variantObject.getAsJsonObject("rule"));
                else variant.rule = parseRule(variantObject);
                source.variants.add(variant);
            }
        }
        source.ensureDefaults();
        return source;
    }

    private ComponentRule parseRule(JsonObject object) {
        ComponentRule rule = GSON.fromJson(object, ComponentRule.class);
        if (rule == null) rule = new ComponentRule();
        if (object.has("maxDamageRange") && object.get("maxDamageRange").isJsonArray()) rule.maxDamageRange = parseRange(object.getAsJsonArray("maxDamageRange"));
        if (object.has("maxStackSizeRange") && object.get("maxStackSizeRange").isJsonArray()) rule.maxStackSizeRange = parseRange(object.getAsJsonArray("maxStackSizeRange"));
        if (object.has("damageRange") && object.get("damageRange").isJsonArray()) rule.damageRange = parseRange(object.getAsJsonArray("damageRange"));
        normalizeRule(rule);
        return rule;
    }

    private static int[] parseRange(JsonArray array) {
        if (array.size() != 2) throw new JsonParseException("range must contain two numbers");
        return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt()};
    }

    private void validateItems(Map<String, ItemEntry> items) {
        validateItemsStatic(items);
    }

    private static void validateItemsStatic(Map<String, ItemEntry> items) {
        for (ItemEntry entry : items.values()) {
            if (entry == null) throw new JsonParseException("null item rule");
            validateItemId(entry.itemId);
            normalizeEntry(entry);
            for (SourceRule source : entry.sources) {
                if (source == null) throw new JsonParseException("null source rule for " + entry.itemId);
                source.ensureDefaults();
                if (source.type == null || source.type.isBlank()) throw new JsonParseException("source type is empty for " + entry.itemId);
                Set<String> variantIds = new LinkedHashSet<>();
                double totalWeight = 0.0;
                for (Variant variant : source.variants) {
                    if (variant == null || variant.id == null || variant.id.isBlank()) throw new JsonParseException("variant id is empty for " + entry.itemId);
                    if (!Double.isFinite(variant.weight) || variant.weight < 0) throw new JsonParseException("variant weight is invalid for " + entry.itemId);
                    if (!variantIds.add(variant.id)) throw new JsonParseException("duplicate variant id " + variant.id + " for " + entry.itemId);
                    totalWeight += variant.weight;
                }
                if (!Double.isFinite(totalWeight)) throw new JsonParseException("variant weight total is invalid for " + entry.itemId);
                if (!source.variants.isEmpty() && !(totalWeight > 0)) throw new JsonParseException("variant weights must contain a positive value for " + entry.itemId);
            }
        }
    }

    private static void validateItemId(String itemId) {
        if (itemId == null || itemId.isBlank() || !itemId.contains(":") || Identifier.tryParse(itemId) == null) {
            throw new JsonParseException("invalid item id: " + itemId);
        }
    }

    private static void validateCandidate(Map<String, ItemEntry> items, long candidateRevision,
                                          Function<ConfigSnapshot, List<String>> validator) {
        validateItemsStatic(items);
        List<String> errors = validator == null ? List.of()
                : validator.apply(new ConfigSnapshot(candidateRevision, items));
        if (errors != null && !errors.isEmpty()) {
            int shown = Math.min(errors.size(), 8);
            throw new JsonParseException("configuration validation failed: "
                    + String.join("; ", errors.subList(0, shown))
                    + (errors.size() > shown ? "; ..." : ""));
        }
    }

    private static void normalizeEntry(ItemEntry entry) {
        entry.ensureDefaults();
        normalizeRule(entry.base);
        for (SourceRule source : entry.sources) {
            if (source == null) continue;
            source.ensureDefaults();
            normalizeRule(source.rule);
            for (Variant variant : source.variants) {
                if (variant == null) continue;
                if (variant.rule == null) variant.rule = new ComponentRule();
                normalizeRule(variant.rule);
            }
        }
    }

    /** In pre-v3 files damageRange represented randomized attack damage. */
    private static void normalizeRule(ComponentRule rule) {
        if (rule == null || rule.damageRange == null) return;
        if (rule.damageRange.length == 2 && rule.attackDamageRange == null) {
            rule.attackDamageRange = new NumberRange(rule.damageRange[0], rule.damageRange[1]);
        }
        rule.damageRange = null;
    }

    private static String nullableString(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Map<String, ItemEntry> copyItems(Map<String, ItemEntry> source) {
        Map<String, ItemEntry> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        for (Map.Entry<String, ItemEntry> entry : source.entrySet()) copy.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
        return copy;
    }

    private static Map<String, ItemEntry> createExampleConfig() {
        Map<String, ItemEntry> examples = new LinkedHashMap<>();
        ItemEntry berries = new ItemEntry("minecraft:sweet_berries");
        berries.base.loreJson = new ArrayList<>(List.of(styledText("酸甜可口的浆果，吃起来不错！", "green")));
        SourceRule chest = new SourceRule("CHEST_LOOT");
        Variant fresh = new Variant("fresh", 6);
        Variant old = new Variant("stored", 3);
        old.rule.loreJson = new ArrayList<>(List.of(styledText("存放很久的浆果，吃着有股怪味...", "gold")));
        old.rule.food = new FoodRule();
        old.rule.food.nutrition = 1;
        old.rule.food.saturation = 0.1f;
        Variant rotten = new Variant("rotten", 1);
        rotten.rule.loreJson = new ArrayList<>(List.of(styledText("腐烂的浆果，好恶心...", "red")));
        rotten.rule.food = new FoodRule();
        rotten.rule.food.nutrition = 1;
        rotten.rule.food.saturation = 0.0f;
        rotten.rule.food.effects = new ArrayList<>(List.of(new EffectRule("minecraft:nausea", 100, 0, 1.0f)));
        chest.variants.add(fresh);
        chest.variants.add(old);
        chest.variants.add(rotten);
        berries.sources.add(chest);
        examples.put(berries.itemId, berries);

        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.loreJson = new ArrayList<>(List.of(styledText("一把普通的铁剑。", "gray")));
        SourceRule crafted = new SourceRule("CRAFTING");
        crafted.rule.loreJson = new ArrayList<>(List.of(styledText("玩家自制的铁剑。", "gray")));
        crafted.rule.attackDamageRange = new NumberRange(3.0, 5.0);
        sword.sources.add(crafted);
        examples.put(sword.itemId, sword);
        return examples;
    }

    private static JsonObject styledText(String text, String color) {
        JsonObject result = new JsonObject();
        result.addProperty("text", text);
        result.addProperty("color", color);
        result.addProperty("italic", false);
        return result;
    }

    private record ParsedConfig(Map<String, ItemEntry> items, long revision, boolean migrated) {}

    public record ConfigSnapshot(long revision, Map<String, ItemEntry> items) {
        public ConfigSnapshot {
            items = items == null ? new LinkedHashMap<>() : copyItems(items);
        }
    }

    public record LoadResult(boolean success, boolean migrated, long revision, String message) {
        public static LoadResult success(boolean migrated, long revision, String message) { return new LoadResult(true, migrated, revision, message); }
        public static LoadResult failure(String message) { return new LoadResult(false, false, -1, message); }
    }

    public record SaveResult(boolean success, boolean conflict, long revision, String message) {
        public static SaveResult success(long revision) { return new SaveResult(true, false, revision, "saved"); }
        public static SaveResult failure(String message, long revision) { return new SaveResult(false, false, revision, message); }
        public static SaveResult conflict(long revision) { return new SaveResult(false, true, revision, "configuration revision conflict"); }
    }

    public static class ItemEntry {
        public String itemId;
        public ComponentRule base = new ComponentRule();
        public List<SourceRule> sources = new ArrayList<>();

        /** Compatibility aliases for the pre-v2 API; not serialized. */
        public transient ComponentRule loot;
        public transient ComponentRule defaultRule;
        public transient ComponentRule crafted;
        public transient boolean legacyFlat;

        public ItemEntry() {}
        public ItemEntry(String itemId) { this.itemId = itemId; }

        public void ensureDefaults() {
            if (base == null) base = new ComponentRule();
            if (sources == null) sources = new ArrayList<>();
            if (loot != null) {
                SourceRule source = new SourceRule("CHEST_LOOT");
                source.rule = loot;
                sources.add(source);
                loot = null;
            }
            if (crafted != null) {
                SourceRule source = new SourceRule("CRAFTING");
                source.rule = crafted;
                sources.add(source);
                crafted = null;
            }
            if (defaultRule != null && base.isEmpty()) {
                base = defaultRule;
                defaultRule = null;
            }
        }

        public ItemEntry copy() {
            ItemEntry copy = new ItemEntry(itemId);
            copy.base = base == null ? new ComponentRule() : base.copy();
            copy.sources = new ArrayList<>();
            if (sources != null) for (SourceRule source : sources) if (source != null) copy.sources.add(source.copy());
            copy.legacyFlat = legacyFlat;
            return copy;
        }
    }

    public static class SourceRule {
        public String type = "UNKNOWN";
        public String lootTableId;
        public String recipeId;
        public ComponentRule rule = new ComponentRule();
        public List<Variant> variants = new ArrayList<>();

        public SourceRule() {}
        public SourceRule(String type) { this.type = type; }

        public void ensureDefaults() {
            if (type == null || type.isBlank()) type = "UNKNOWN";
            if (rule == null) rule = new ComponentRule();
            if (variants == null) variants = new ArrayList<>();
        }

        public boolean matches(String sourceType, String lootId, String recipeId) {
            if (sourceType == null || type == null || !type.equalsIgnoreCase(sourceType)) return false;
            if (lootTableId != null && !lootTableId.equals(lootId)) return false;
            if (this.recipeId != null && !this.recipeId.equals(recipeId)) return false;
            return true;
        }

        public int specificity(String lootId, String recipeId) {
            int score = 0;
            if (lootTableId != null && lootTableId.equals(lootId)) score += 2;
            if (recipeId != null && recipeId.equals(this.recipeId)) score += 2;
            return score;
        }

        public SourceRule copy() {
            SourceRule copy = new SourceRule(type);
            copy.lootTableId = lootTableId;
            copy.recipeId = recipeId;
            copy.rule = rule == null ? new ComponentRule() : rule.copy();
            copy.variants = new ArrayList<>();
            if (variants != null) for (Variant variant : variants) if (variant != null) copy.variants.add(variant.copy());
            return copy;
        }
    }

    public static class Variant {
        public String id = "default";
        public double weight = 1.0;
        public ComponentRule rule = new ComponentRule();

        public Variant() {}
        public Variant(String id, double weight) { this.id = id; this.weight = weight; }
        public Variant copy() {
            Variant copy = new Variant(id, weight);
            copy.rule = rule == null ? new ComponentRule() : rule.copy();
            return copy;
        }
    }

    public static class ComponentRule {
        public List<String> lore;
        public List<JsonElement> loreJson;
        public String customName;
        public JsonElement customNameJson;
        public Integer maxStackSize;
        public Integer maxDamage;
        public Integer currentDamage;
        public Boolean fireResistant;
        public Integer rarity;
        public String rarityName;
        public int[] maxDamageRange;
        public int[] maxStackSizeRange;
        /** Read-only migration field from schemas before v3. */
        public int[] damageRange;
        public NumberRange attackDamageRange;
        public FoodRule food;
        public Map<String, Integer> enchantments;
        public Map<String, Integer> storedEnchantments;
        public List<AttributeRule> attributes;
        public ToolRule tool;
        public Boolean hideTooltip;
        public Boolean hideAdditionalTooltip;
        public Integer customModelData;
        public Map<String, JsonElement> setComponents;
        public Set<String> removeComponents;

        public ComponentRule copy() {
            ComponentRule copy = new ComponentRule();
            copy.lore = lore == null ? null : new ArrayList<>(lore);
            if (loreJson != null) {
                copy.loreJson = new ArrayList<>();
                for (JsonElement line : loreJson) copy.loreJson.add(line == null ? null : line.deepCopy());
            }
            copy.customName = customName;
            copy.customNameJson = customNameJson == null ? null : customNameJson.deepCopy();
            copy.maxStackSize = maxStackSize;
            copy.maxDamage = maxDamage;
            copy.currentDamage = currentDamage;
            copy.fireResistant = fireResistant;
            copy.rarity = rarity;
            copy.rarityName = rarityName;
            copy.maxDamageRange = maxDamageRange == null ? null : maxDamageRange.clone();
            copy.maxStackSizeRange = maxStackSizeRange == null ? null : maxStackSizeRange.clone();
            copy.damageRange = damageRange == null ? null : damageRange.clone();
            copy.attackDamageRange = attackDamageRange == null ? null : attackDamageRange.copy();
            copy.food = food == null ? null : food.copy();
            copy.enchantments = enchantments == null ? null : new LinkedHashMap<>(enchantments);
            copy.storedEnchantments = storedEnchantments == null ? null : new LinkedHashMap<>(storedEnchantments);
            copy.attributes = attributes == null ? null : copyAttributes(attributes);
            copy.tool = tool == null ? null : tool.copy();
            copy.hideTooltip = hideTooltip;
            copy.hideAdditionalTooltip = hideAdditionalTooltip;
            copy.customModelData = customModelData;
            if (setComponents != null) {
                copy.setComponents = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : setComponents.entrySet()) copy.setComponents.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().deepCopy());
            }
            copy.removeComponents = removeComponents == null ? null : new LinkedHashSet<>(removeComponents);
            return copy;
        }

        public boolean isEmpty() {
            return lore == null && loreJson == null && customName == null && customNameJson == null
                    && maxStackSize == null && maxDamage == null && currentDamage == null
                    && fireResistant == null && rarity == null && rarityName == null
                    && maxDamageRange == null && maxStackSizeRange == null && damageRange == null
                    && attackDamageRange == null && food == null && enchantments == null && storedEnchantments == null
                    && attributes == null && tool == null
                    && hideTooltip == null && hideAdditionalTooltip == null && customModelData == null
                    && (setComponents == null || setComponents.isEmpty())
                    && (removeComponents == null || removeComponents.isEmpty());
        }

        /** Merges only explicitly supplied fields. */
        public void mergeFrom(ComponentRule other) {
            if (other == null) return;
            if (other.lore != null || other.loreJson != null) {
                clearAdvancedOverride("minecraft:lore");
                lore = other.lore == null ? null : new ArrayList<>(other.lore);
                loreJson = null;
                if (other.loreJson != null) {
                    loreJson = new ArrayList<>();
                    for (JsonElement line : other.loreJson) loreJson.add(line == null ? null : line.deepCopy());
                }
            }
            if (other.customName != null || other.customNameJson != null) {
                clearAdvancedOverride("minecraft:custom_name");
                customName = other.customName;
                customNameJson = other.customNameJson == null ? null : other.customNameJson.deepCopy();
            }
            if (other.maxStackSize != null) {
                clearAdvancedOverride("minecraft:max_stack_size");
                maxStackSize = other.maxStackSize;
                maxStackSizeRange = null;
            }
            if (other.maxDamage != null) {
                clearAdvancedOverride("minecraft:max_damage");
                maxDamage = other.maxDamage;
                maxDamageRange = null;
            }
            if (other.currentDamage != null) {
                clearAdvancedOverride("minecraft:damage");
                currentDamage = other.currentDamage;
                damageRange = null;
            }
            if (other.fireResistant != null) { clearAdvancedOverride("minecraft:fire_resistant"); fireResistant = other.fireResistant; }
            if (other.rarity != null || other.rarityName != null) {
                clearAdvancedOverride("minecraft:rarity");
                rarity = other.rarity;
                rarityName = other.rarityName;
            }
            if (other.maxDamageRange != null) {
                clearAdvancedOverride("minecraft:max_damage");
                maxDamageRange = other.maxDamageRange.clone();
                maxDamage = null;
            }
            if (other.maxStackSizeRange != null) {
                clearAdvancedOverride("minecraft:max_stack_size");
                maxStackSizeRange = other.maxStackSizeRange.clone();
                maxStackSize = null;
            }
            if (other.damageRange != null) {
                clearAdvancedOverride("minecraft:damage");
                damageRange = other.damageRange.clone();
                currentDamage = null;
            }
            if (other.attackDamageRange != null) attackDamageRange = other.attackDamageRange.copy();
            if (other.food != null) { clearAdvancedOverride("minecraft:food"); food = food == null ? other.food.copy() : food.merge(other.food); }
            if (other.enchantments != null) { clearAdvancedOverride("minecraft:enchantments"); enchantments = new LinkedHashMap<>(other.enchantments); }
            if (other.storedEnchantments != null) { clearAdvancedOverride("minecraft:stored_enchantments"); storedEnchantments = new LinkedHashMap<>(other.storedEnchantments); }
            if (other.attributes != null) { clearAdvancedOverride("minecraft:attribute_modifiers"); attributes = copyAttributes(other.attributes); }
            if (other.tool != null) { clearAdvancedOverride("minecraft:tool"); tool = tool == null ? other.tool.copy() : tool.merge(other.tool); }
            if (other.hideTooltip != null) { clearAdvancedOverride("minecraft:hide_tooltip"); hideTooltip = other.hideTooltip; }
            if (other.hideAdditionalTooltip != null) { clearAdvancedOverride("minecraft:hide_additional_tooltip"); hideAdditionalTooltip = other.hideAdditionalTooltip; }
            if (other.customModelData != null) { clearAdvancedOverride("minecraft:custom_model_data"); customModelData = other.customModelData; }
            if (other.setComponents != null && !other.setComponents.isEmpty()) {
                if (setComponents == null) setComponents = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : other.setComponents.entrySet()) {
                    setComponents.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().deepCopy());
                    if (removeComponents != null) removeComponents.remove(entry.getKey());
                }
            }
            if (other.removeComponents != null && !other.removeComponents.isEmpty()) {
                if (removeComponents == null) removeComponents = new LinkedHashSet<>();
                for (String id : other.removeComponents) {
                    removeComponents.add(id);
                    if (setComponents != null) setComponents.remove(id);
                }
            }
        }

        private void clearAdvancedOverride(String componentId) {
            if (setComponents != null) setComponents.remove(componentId);
            if (removeComponents != null) removeComponents.remove(componentId);
        }

        public Set<String> controlledComponents() {
            Set<String> result = new LinkedHashSet<>();
            if (lore != null || loreJson != null) result.add("minecraft:lore");
            if (customName != null || customNameJson != null) result.add("minecraft:custom_name");
            if (maxStackSize != null || maxStackSizeRange != null) result.add("minecraft:max_stack_size");
            if (maxDamage != null || maxDamageRange != null) result.add("minecraft:max_damage");
            if (currentDamage != null) result.add("minecraft:damage");
            if (fireResistant != null) result.add("minecraft:fire_resistant");
            if (rarity != null || rarityName != null) result.add("minecraft:rarity");
            if (food != null) result.add("minecraft:food");
            if (enchantments != null) result.add("minecraft:enchantments");
            if (storedEnchantments != null) result.add("minecraft:stored_enchantments");
            if (attributes != null || attackDamageRange != null || damageRange != null) result.add("minecraft:attribute_modifiers");
            if (tool != null) result.add("minecraft:tool");
            if (hideTooltip != null) result.add("minecraft:hide_tooltip");
            if (hideAdditionalTooltip != null) result.add("minecraft:hide_additional_tooltip");
            if (customModelData != null) result.add("minecraft:custom_model_data");
            if (setComponents != null) result.addAll(setComponents.keySet());
            if (removeComponents != null) result.addAll(removeComponents);
            return result;
        }

        private static List<AttributeRule> copyAttributes(List<AttributeRule> source) {
            List<AttributeRule> copy = new ArrayList<>();
            for (AttributeRule rule : source) copy.add(rule == null ? null : rule.copy());
            return copy;
        }
    }

    public static class NumberRange {
        public double min;
        public double max;
        public NumberRange() {}
        public NumberRange(double min, double max) { this.min = min; this.max = max; }
        public NumberRange copy() { return new NumberRange(min, max); }
    }

    public static class FoodRule {
        public Integer nutrition;
        public Float saturation;
        public Boolean canAlwaysEat;
        public Float eatSeconds;
        public List<EffectRule> effects;

        public FoodRule copy() {
            FoodRule copy = new FoodRule();
            copy.nutrition = nutrition;
            copy.saturation = saturation;
            copy.canAlwaysEat = canAlwaysEat;
            copy.eatSeconds = eatSeconds;
            if (effects != null) {
                copy.effects = new ArrayList<>();
                for (EffectRule effect : effects) copy.effects.add(effect == null ? null : effect.copy());
            }
            return copy;
        }

        public FoodRule merge(FoodRule other) {
            FoodRule result = copy();
            if (other.nutrition != null) result.nutrition = other.nutrition;
            if (other.saturation != null) result.saturation = other.saturation;
            if (other.canAlwaysEat != null) result.canAlwaysEat = other.canAlwaysEat;
            if (other.eatSeconds != null) result.eatSeconds = other.eatSeconds;
            if (other.effects != null) {
                result.effects = new ArrayList<>();
                for (EffectRule effect : other.effects) result.effects.add(effect == null ? null : effect.copy());
            }
            return result;
        }
    }

    public static class EffectRule {
        public String id;
        public int duration = 100;
        public int amplifier;
        public boolean ambient;
        public boolean showParticles = true;
        public boolean showIcon = true;
        public float probability = 1.0f;
        public EffectRule() {}
        public EffectRule(String id, int duration, int amplifier, float probability) { this.id = id; this.duration = duration; this.amplifier = amplifier; this.probability = probability; }
        public EffectRule copy() {
            EffectRule copy = new EffectRule(id, duration, amplifier, probability);
            copy.ambient = ambient;
            copy.showParticles = showParticles;
            copy.showIcon = showIcon;
            return copy;
        }
    }

    public static class AttributeRule {
        public String attribute;
        public String id;
        public double amount;
        public String operation = "add_value";
        public String slot = "any";
        public AttributeRule copy() {
            AttributeRule copy = new AttributeRule();
            copy.attribute = attribute;
            copy.id = id;
            copy.amount = amount;
            copy.operation = operation;
            copy.slot = slot;
            return copy;
        }
    }

    public static class ToolRule {
        public Float defaultMiningSpeed;
        public Integer damagePerBlock;
        public List<ToolRuleEntry> rules;
        public ToolRule copy() {
            ToolRule copy = new ToolRule();
            copy.defaultMiningSpeed = defaultMiningSpeed;
            copy.damagePerBlock = damagePerBlock;
            if (rules != null) {
                copy.rules = new ArrayList<>();
                for (ToolRuleEntry rule : rules) copy.rules.add(rule == null ? null : rule.copy());
            }
            return copy;
        }
        public ToolRule merge(ToolRule other) {
            ToolRule result = copy();
            if (other.defaultMiningSpeed != null) result.defaultMiningSpeed = other.defaultMiningSpeed;
            if (other.damagePerBlock != null) result.damagePerBlock = other.damagePerBlock;
            if (other.rules != null) {
                result.rules = new ArrayList<>();
                for (ToolRuleEntry rule : other.rules) result.rules.add(rule == null ? null : rule.copy());
            }
            return result;
        }
    }

    public static class ToolRuleEntry {
        public List<String> blocks;
        public Float speed;
        public Boolean correctForDrops;
        public ToolRuleEntry copy() {
            ToolRuleEntry copy = new ToolRuleEntry();
            copy.blocks = blocks == null ? null : new ArrayList<>(blocks);
            copy.speed = speed;
            copy.correctForDrops = correctForDrops;
            return copy;
        }
    }
}
