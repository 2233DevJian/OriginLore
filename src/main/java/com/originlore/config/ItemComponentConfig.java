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
    public static final int CURRENT_SCHEMA_VERSION = 5;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path configFile;
    private final Path configDirectory;
    private Map<String, ItemEntry> itemConfigs = new LinkedHashMap<>();
    private Settings settings = new Settings();
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

    public synchronized String getPresetLanguage() {
        return settings.presetLanguage;
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
        Settings previousSettings = settings;
        long previousRevision = revision;
        try {
            Files.createDirectories(configDirectory);
            if (!Files.exists(configFile)) {
                ConfigSnapshot defaults = PresetLanguage.prepare(PresetLibrary.load("vanilla_zh_cn"), "zh_cn");
                Map<String, ItemEntry> candidate = copyItems(defaults.items());
                validateCandidate(candidate, 0, defaults.settings(), validator);
                itemConfigs = candidate;
                settings = defaults.settings().copy();
                revision = 0L;
                lastError = null;
                SaveResult saved = saveInternal(true);
                if (!saved.success()) {
                    itemConfigs = previousItems;
                    settings = previousSettings;
                    revision = previousRevision;
                    return LoadResult.failure(saved.message());
                }
                return LoadResult.success(false, revision, "created default configuration");
            }

            ParsedConfig parsed = readParsedConfig();
            validateCandidate(parsed.items, parsed.revision, parsed.settings, validator);
            itemConfigs = parsed.items;
            settings = parsed.settings;
            revision = parsed.revision;
            lastError = null;
            if (parsed.migrated) {
                SaveResult migrated = saveInternal(true);
                if (!migrated.success()) {
                    itemConfigs = previousItems;
                    settings = previousSettings;
                    revision = previousRevision;
                    return LoadResult.failure("configuration migrated in memory but could not be written: " + migrated.message());
                }
            }
            return LoadResult.success(parsed.migrated, revision, parsed.migrated ? "migrated legacy configuration" : "loaded");
        } catch (Exception e) {
            itemConfigs = previousItems;
            settings = previousSettings;
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
        Settings previousSettings = settings;
        long previousRevision = revision;
        try {
            if (!Files.exists(configFile)) throw new IOException("configuration file does not exist: " + configFile);
            ParsedConfig parsed = readParsedConfig();
            validateCandidate(parsed.items, parsed.revision, parsed.settings, validator);
            itemConfigs = parsed.items;
            settings = parsed.settings;
            revision = Math.max(previousRevision, parsed.revision);
            SaveResult saved = saveInternal(true);
            if (!saved.success()) {
                itemConfigs = previousItems;
                settings = previousSettings;
                revision = previousRevision;
                return LoadResult.failure(saved.message());
            }
            lastError = null;
            return LoadResult.success(parsed.migrated, revision, "reloaded");
        } catch (Exception e) {
            itemConfigs = previousItems;
            settings = previousSettings;
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
            snapshot.settings().validate();
            Map<String, ItemEntry> previous = itemConfigs;
            Settings previousSettings = settings;
            long previousRevision = revision;
            itemConfigs = copy;
            settings = snapshot.settings().copy();
            SaveResult result = saveInternal(true);
            if (!result.success()) {
                itemConfigs = previous;
                settings = previousSettings;
                revision = previousRevision;
            }
            return result;
        } catch (Exception e) {
            return SaveResult.failure(compactMessage(e), revision);
        }
    }

    public synchronized ConfigSnapshot snapshot() {
        return new ConfigSnapshot(revision, itemConfigs, settings);
    }

    public synchronized String snapshotJson() {
        return snapshotToJson(snapshot());
    }

    public static String snapshotToJson(ConfigSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.addProperty("revision", snapshot == null ? 0 : snapshot.revision());
        root.add("settings", GSON.toJsonTree(snapshot == null ? new Settings() : snapshot.settings()));
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
        return new ConfigSnapshot(revision, items, parseSettings(root));
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
            root.add("settings", GSON.toJsonTree(settings));
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
        return new ParsedConfig(parsed, loadedRevision, migrated, parseSettings(root));
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
        if (object.has("processing")) source.processing = GSON.fromJson(object.get("processing"), ProcessingRule.class);
        if (object.has("rule") && object.get("rule").isJsonObject()) source.rule = parseRule(object.getAsJsonObject("rule"));
        else source.rule = parseRule(object);
        if (object.has("variants")) {
            if (!object.get("variants").isJsonArray()) throw new JsonParseException("variants must be an array");
            for (JsonElement value : object.getAsJsonArray("variants")) {
                if (!value.isJsonObject()) throw new JsonParseException("variant must be an object");
                JsonObject variantObject = value.getAsJsonObject();
                Variant variant = GSON.fromJson(variantObject, Variant.class);
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
            validateRuleNumbers(entry.base);
            for (SourceRule source : entry.sources) {
                if (source == null) throw new JsonParseException("null source rule for " + entry.itemId);
                source.ensureDefaults();
                validateRuleNumbers(source.rule);
                if (source.type == null || source.type.isBlank()) throw new JsonParseException("source type is empty for " + entry.itemId);
                Set<String> variantIds = new LinkedHashSet<>();
                double totalWeight = 0.0;
                if (source.processing != null) {
                    validateUnitInterval(source.processing.riskRetention, "processing.riskRetention");
                    validateUnitInterval(source.processing.riskFloor, "processing.riskFloor");
                    validateEffects(source.processing.effects);
                }
                for (Variant variant : source.variants) {
                    if (variant == null || variant.id == null || variant.id.isBlank()) throw new JsonParseException("variant id is empty for " + entry.itemId);
                    if (!Double.isFinite(variant.weight) || variant.weight < 0) throw new JsonParseException("variant weight is invalid for " + entry.itemId);
                    if (!variantIds.add(variant.id)) throw new JsonParseException("duplicate variant id " + variant.id + " for " + entry.itemId);
                    validateRuleNumbers(variant.rule);
                    if (variant.qualityScore != null) validateUnitInterval(variant.qualityScore, "qualityScore");
                    if (variant.spoilage != null) validateUnitInterval(variant.spoilage, "spoilage");
                    double previousQuality = -1;
                    if (variant.ingredientWeights != null) for (WeightPoint point : variant.ingredientWeights) {
                        if (point == null) throw new JsonParseException("null ingredient weight point");
                        validateUnitInterval(point.quality, "ingredientWeights.quality");
                        if (point.quality <= previousQuality || !Double.isFinite(point.multiplier) || point.multiplier < 0) {
                            throw new JsonParseException("ingredient weights must have increasing quality and nonnegative multipliers");
                        }
                        if (!Double.isFinite(variant.weight * point.multiplier)) {
                            throw new JsonParseException("ingredient-adjusted variant weight is not finite");
                        }
                        previousQuality = point.quality;
                    }
                    totalWeight += variant.weight;
                }
                if (!Double.isFinite(totalWeight)) throw new JsonParseException("variant weight total is invalid for " + entry.itemId);
                if (!source.variants.isEmpty() && !(totalWeight > 0)) throw new JsonParseException("variant weights must contain a positive value for " + entry.itemId);
            }
        }
    }

    private static void validateUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JsonParseException(field + " must be between 0 and 1");
    }

    /** Checks every numeric domain without enumerating combinations of independent ranges. */
    public static void validateRuleNumbers(ComponentRule rule) {
        if (rule == null) return;
        checkNumber(rule.maxStackSize, 1, 99, "maxStackSize");
        checkRange(rule.maxStackSizeRange, 1, 99, "maxStackSizeRange");
        checkNumber(rule.maxDamage, 1, Integer.MAX_VALUE, "maxDamage");
        checkRange(rule.maxDamageRange, 1, Integer.MAX_VALUE, "maxDamageRange");
        checkNumber(rule.currentDamage, 0, Integer.MAX_VALUE, "currentDamage");
        checkRange(rule.attackDamage, 0, 2048, "attackDamage");
        checkRange(rule.attackDamageRange, -Double.MAX_VALUE, Double.MAX_VALUE, "attackDamageRange");
        if (rule.attackDamage != null && rule.attackDamageRange != null) {
            throw new JsonParseException("attackDamage and attackDamageRange cannot be combined in one rule");
        }
        checkRange(rule.projectileDamageMultiplier, 0, Double.MAX_VALUE, "projectileDamageMultiplier");
        if (rule.food != null) {
            FoodRule food = rule.food;
            checkNumber(food.nutrition, 0, Integer.MAX_VALUE, "food.nutrition");
            checkRange(food.nutritionRange, 0, Integer.MAX_VALUE, "food.nutritionRange");
            checkNumber(food.saturation, 0, Float.MAX_VALUE, "food.saturation");
            checkRange(food.saturationRange, 0, Float.MAX_VALUE, "food.saturationRange");
            checkNumber(food.eatSeconds, Float.MIN_VALUE, Integer.MAX_VALUE / 20.0, "food.eatSeconds");
            checkRange(food.eatSecondsRange, Float.MIN_VALUE, Integer.MAX_VALUE / 20.0, "food.eatSecondsRange");
            validateEffects(food.effects);
        }
        if (rule.attributes != null) for (AttributeRule attribute : rule.attributes) {
            if (attribute == null) throw new JsonParseException("null attribute");
            if (Boolean.TRUE.equals(attribute.total) && !"add_value".equalsIgnoreCase(attribute.operation)) {
                throw new JsonParseException("total attributes require add_value operation");
            }
            checkNumber(attribute.amount, -Double.MAX_VALUE, Double.MAX_VALUE, "attribute.amount");
            checkRange(attribute.amountRange, -Double.MAX_VALUE, Double.MAX_VALUE, "attribute.amountRange");
        }
        if (rule.tool != null) {
            ToolRule tool = rule.tool;
            checkNumber(tool.defaultMiningSpeed, 0, Float.MAX_VALUE, "tool.defaultMiningSpeed");
            checkRange(tool.defaultMiningSpeedRange, 0, Float.MAX_VALUE, "tool.defaultMiningSpeedRange");
            checkRange(tool.miningSpeedMultiplier, 0, Float.MAX_VALUE, "tool.miningSpeedMultiplier");
            checkNumber(tool.damagePerBlock, 0, Integer.MAX_VALUE, "tool.damagePerBlock");
            checkRange(tool.damagePerBlockRange, 0, Integer.MAX_VALUE, "tool.damagePerBlockRange");
            if (tool.rules != null) for (ToolRuleEntry entry : tool.rules) {
                if (entry == null) throw new JsonParseException("null tool rule");
                checkNumber(entry.speed, 0, Float.MAX_VALUE, "tool.rules.speed");
                checkRange(entry.speedRange, 0, Float.MAX_VALUE, "tool.rules.speedRange");
            }
        }
    }

    private static void validateEffects(List<EffectRule> effects) {
        if (effects == null) return;
        for (EffectRule effect : effects) {
            if (effect == null) throw new JsonParseException("null food effect");
            validateUnitInterval(effect.probability, "effect.probability");
            checkNumber(effect.duration, 0, Integer.MAX_VALUE, "effect.duration");
            checkNumber(effect.amplifier, 0, 255, "effect.amplifier");
        }
    }

    private static void checkNumber(Number value, double min, double max, String field) {
        if (value != null && (!Double.isFinite(value.doubleValue()) || value.doubleValue() < min || value.doubleValue() > max)) {
            throw new JsonParseException(field + " must be finite and between " + min + " and " + max);
        }
    }

    private static void checkRange(int[] range, double min, double max, String field) {
        if (range == null) return;
        if (range.length != 2 || range[0] > range[1]) throw new JsonParseException(field + " must contain ordered endpoints");
        checkNumber(range[0], min, max, field);
        checkNumber(range[1], min, max, field);
    }

    private static void checkRange(NumberRange range, double min, double max, String field) {
        if (range == null) return;
        checkNumber(range.min, min, max, field);
        checkNumber(range.max, min, max, field);
        if (range.min > range.max || !Double.isFinite(range.max - range.min)) {
            throw new JsonParseException(field + " must have ordered endpoints and a finite width");
        }
    }

    private static void validateItemId(String itemId) {
        if (itemId == null || itemId.isBlank() || !itemId.contains(":") || Identifier.tryParse(itemId) == null) {
            throw new JsonParseException("invalid item id: " + itemId);
        }
    }

    private static void validateCandidate(Map<String, ItemEntry> items, long candidateRevision, Settings candidateSettings,
                                          Function<ConfigSnapshot, List<String>> validator) {
        validateItemsStatic(items);
        candidateSettings.validate();
        List<String> errors = validator == null ? List.of()
                : validator.apply(new ConfigSnapshot(candidateRevision, items, candidateSettings));
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

    private static Settings parseSettings(JsonObject root) {
        Settings result = root.has("settings") ? GSON.fromJson(root.get("settings"), Settings.class) : new Settings();
        if (result == null) throw new JsonParseException("settings must be an object");
        result.validate();
        return result;
    }

    private record ParsedConfig(Map<String, ItemEntry> items, long revision, boolean migrated, Settings settings) {}

    public record ConfigSnapshot(long revision, Map<String, ItemEntry> items, Settings settings) {
        public ConfigSnapshot(long revision, Map<String, ItemEntry> items) { this(revision, items, new Settings()); }
        public ConfigSnapshot {
            items = items == null ? new LinkedHashMap<>() : copyItems(items);
            settings = settings == null ? new Settings() : settings.copy();
            settings.validate();
        }
        public ConfigSnapshot withItems(Map<String, ItemEntry> value) { return new ConfigSnapshot(revision, value, settings); }
        public ConfigSnapshot withSettings(Settings value) { return new ConfigSnapshot(revision, items, value); }
    }

    public static class Settings {
        public String presetLanguage = "zh_cn";
        public Settings copy() { Settings copy = new Settings(); copy.presetLanguage = presetLanguage; return copy; }
        public void validate() {
            if (!"zh_cn".equals(presetLanguage) && !"en_us".equals(presetLanguage)) {
                throw new IllegalArgumentException("settings.presetLanguage must be zh_cn or en_us");
            }
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
        public ProcessingRule processing;

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
            copy.processing = processing == null ? null : processing.copy();
            copy.rule = rule == null ? new ComponentRule() : rule.copy();
            copy.variants = new ArrayList<>();
            if (variants != null) for (Variant variant : variants) if (variant != null) copy.variants.add(variant.copy());
            return copy;
        }
    }

    public static class Variant {
        public String id = "default";
        public double weight = 1.0;
        public Double qualityScore;
        public Double spoilage;
        public List<WeightPoint> ingredientWeights;
        public ComponentRule rule = new ComponentRule();

        public Variant() {}
        public Variant(String id, double weight) { this.id = id; this.weight = weight; }
        public Variant copy() {
            Variant copy = new Variant(id, weight);
            copy.qualityScore = qualityScore;
            copy.spoilage = spoilage;
            if (ingredientWeights != null) {
                copy.ingredientWeights = new ArrayList<>();
                for (WeightPoint point : ingredientWeights) copy.ingredientWeights.add(point == null ? null : point.copy());
            }
            copy.rule = rule == null ? new ComponentRule() : rule.copy();
            return copy;
        }
    }

    public static class WeightPoint {
        public double quality;
        public double multiplier = 1.0;
        public WeightPoint() {}
        public WeightPoint(double quality, double multiplier) { this.quality = quality; this.multiplier = multiplier; }
        public WeightPoint copy() { return new WeightPoint(quality, multiplier); }
    }

    public static class ProcessingRule {
        public double riskRetention = 0.5;
        public double riskFloor = 0.1;
        public List<EffectRule> effects;
        public ProcessingRule copy() {
            ProcessingRule copy = new ProcessingRule();
            copy.riskRetention = riskRetention;
            copy.riskFloor = riskFloor;
            if (effects != null) {
                copy.effects = new ArrayList<>();
                for (EffectRule effect : effects) copy.effects.add(effect == null ? null : effect.copy());
            }
            return copy;
        }
    }

    public static class ComponentRule {
        public List<String> lore;
        public List<JsonElement> loreJson;
        public String customName;
        public JsonElement customNameJson;
        public String itemName;
        public JsonElement itemNameJson;
        public String presetTextKey;
        public Map<String, JsonElement> presetTexts;
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
        /** Final unenchanted player attack damage, including the player's base damage. */
        public NumberRange attackDamage;
        /** Legacy extra damage added to the native attack modifiers. */
        public NumberRange attackDamageRange;
        public NumberRange projectileDamageMultiplier;
        public FoodRule food;
        public Map<String, Integer> enchantments;
        public Map<String, Integer> storedEnchantments;
        public List<AttributeRule> attributes;
        public Boolean appendAttributes;
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
            copy.itemName = itemName;
            copy.itemNameJson = itemNameJson == null ? null : itemNameJson.deepCopy();
            copy.presetTextKey = presetTextKey;
            if (presetTexts != null) {
                copy.presetTexts = new LinkedHashMap<>();
                presetTexts.forEach((key, value) -> copy.presetTexts.put(key, value.deepCopy()));
            }
            copy.maxStackSize = maxStackSize;
            copy.maxDamage = maxDamage;
            copy.currentDamage = currentDamage;
            copy.fireResistant = fireResistant;
            copy.rarity = rarity;
            copy.rarityName = rarityName;
            copy.maxDamageRange = maxDamageRange == null ? null : maxDamageRange.clone();
            copy.maxStackSizeRange = maxStackSizeRange == null ? null : maxStackSizeRange.clone();
            copy.damageRange = damageRange == null ? null : damageRange.clone();
            copy.attackDamage = attackDamage == null ? null : attackDamage.copy();
            copy.attackDamageRange = attackDamageRange == null ? null : attackDamageRange.copy();
            copy.projectileDamageMultiplier = projectileDamageMultiplier == null ? null : projectileDamageMultiplier.copy();
            copy.food = food == null ? null : food.copy();
            copy.enchantments = enchantments == null ? null : new LinkedHashMap<>(enchantments);
            copy.storedEnchantments = storedEnchantments == null ? null : new LinkedHashMap<>(storedEnchantments);
            copy.attributes = attributes == null ? null : copyAttributes(attributes);
            copy.appendAttributes = appendAttributes;
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
                    && itemName == null && itemNameJson == null && projectileDamageMultiplier == null
                    && maxStackSize == null && maxDamage == null && currentDamage == null
                    && fireResistant == null && rarity == null && rarityName == null
                    && maxDamageRange == null && maxStackSizeRange == null && damageRange == null
                    && attackDamage == null && attackDamageRange == null && food == null && enchantments == null && storedEnchantments == null
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
            if (other.itemName != null || other.itemNameJson != null) {
                clearAdvancedOverride("minecraft:item_name");
                itemName = other.itemName;
                itemNameJson = other.itemNameJson == null ? null : other.itemNameJson.deepCopy();
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
            if (other.attackDamage != null) {
                clearAdvancedOverride("minecraft:attribute_modifiers");
                attackDamage = other.attackDamage.copy();
                attackDamageRange = null;
            }
            if (other.attackDamageRange != null) {
                clearAdvancedOverride("minecraft:attribute_modifiers");
                attackDamageRange = other.attackDamageRange.copy();
                attackDamage = null;
            }
            if (other.projectileDamageMultiplier != null) projectileDamageMultiplier = other.projectileDamageMultiplier.copy();
            if (other.food != null) { clearAdvancedOverride("minecraft:food"); food = food == null ? other.food.copy() : food.merge(other.food); }
            if (other.enchantments != null) { clearAdvancedOverride("minecraft:enchantments"); enchantments = new LinkedHashMap<>(other.enchantments); }
            if (other.storedEnchantments != null) { clearAdvancedOverride("minecraft:stored_enchantments"); storedEnchantments = new LinkedHashMap<>(other.storedEnchantments); }
            if (other.attributes != null) { clearAdvancedOverride("minecraft:attribute_modifiers"); attributes = copyAttributes(other.attributes); }
            if (other.appendAttributes != null) appendAttributes = other.appendAttributes;
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
            if (itemName != null || itemNameJson != null) result.add("minecraft:item_name");
            if (maxStackSize != null || maxStackSizeRange != null) result.add("minecraft:max_stack_size");
            if (maxDamage != null || maxDamageRange != null) result.add("minecraft:max_damage");
            if (currentDamage != null) result.add("minecraft:damage");
            if (fireResistant != null) result.add("minecraft:fire_resistant");
            if (rarity != null || rarityName != null) result.add("minecraft:rarity");
            if (food != null) result.add("minecraft:food");
            if (enchantments != null) result.add("minecraft:enchantments");
            if (storedEnchantments != null) result.add("minecraft:stored_enchantments");
            if (attributes != null || attackDamage != null || attackDamageRange != null || damageRange != null) result.add("minecraft:attribute_modifiers");
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
        public Boolean appendEffects;
        public Integer nutrition;
        public int[] nutritionRange;
        public Float saturation;
        public NumberRange saturationRange;
        public Boolean canAlwaysEat;
        public Float eatSeconds;
        public NumberRange eatSecondsRange;
        public List<EffectRule> effects;

        public FoodRule copy() {
            FoodRule copy = new FoodRule();
            copy.nutrition = nutrition;
            copy.nutritionRange = nutritionRange == null ? null : nutritionRange.clone();
            copy.saturation = saturation;
            copy.saturationRange = saturationRange == null ? null : saturationRange.copy();
            copy.canAlwaysEat = canAlwaysEat;
            copy.eatSeconds = eatSeconds;
            copy.eatSecondsRange = eatSecondsRange == null ? null : eatSecondsRange.copy();
            copy.appendEffects = appendEffects;
            if (effects != null) {
                copy.effects = new ArrayList<>();
                for (EffectRule effect : effects) copy.effects.add(effect == null ? null : effect.copy());
            }
            return copy;
        }

        public FoodRule merge(FoodRule other) {
            FoodRule result = copy();
            if (other.nutrition != null) { result.nutrition = other.nutrition; result.nutritionRange = null; }
            if (other.nutritionRange != null) { result.nutritionRange = other.nutritionRange.clone(); result.nutrition = null; }
            if (other.saturation != null) { result.saturation = other.saturation; result.saturationRange = null; }
            if (other.saturationRange != null) { result.saturationRange = other.saturationRange.copy(); result.saturation = null; }
            if (other.canAlwaysEat != null) result.canAlwaysEat = other.canAlwaysEat;
            if (other.eatSeconds != null) { result.eatSeconds = other.eatSeconds; result.eatSecondsRange = null; }
            if (other.eatSecondsRange != null) { result.eatSecondsRange = other.eatSecondsRange.copy(); result.eatSeconds = null; }
            if (other.appendEffects != null) result.appendEffects = other.appendEffects;
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
        public NumberRange amountRange;
        public Boolean total;
        public String operation = "add_value";
        public String slot = "any";
        public AttributeRule copy() {
            AttributeRule copy = new AttributeRule();
            copy.attribute = attribute;
            copy.id = id;
            copy.amount = amount;
            copy.amountRange = amountRange == null ? null : amountRange.copy();
            copy.total = total;
            copy.operation = operation;
            copy.slot = slot;
            return copy;
        }
    }

    public static class ToolRule {
        public Float defaultMiningSpeed;
        public NumberRange defaultMiningSpeedRange;
        public NumberRange miningSpeedMultiplier;
        public Integer damagePerBlock;
        public int[] damagePerBlockRange;
        public List<ToolRuleEntry> rules;
        public ToolRule copy() {
            ToolRule copy = new ToolRule();
            copy.defaultMiningSpeed = defaultMiningSpeed;
            copy.defaultMiningSpeedRange = defaultMiningSpeedRange == null ? null : defaultMiningSpeedRange.copy();
            copy.miningSpeedMultiplier = miningSpeedMultiplier == null ? null : miningSpeedMultiplier.copy();
            copy.damagePerBlock = damagePerBlock;
            copy.damagePerBlockRange = damagePerBlockRange == null ? null : damagePerBlockRange.clone();
            if (rules != null) {
                copy.rules = new ArrayList<>();
                for (ToolRuleEntry rule : rules) copy.rules.add(rule == null ? null : rule.copy());
            }
            return copy;
        }
        public ToolRule merge(ToolRule other) {
            ToolRule result = copy();
            if (other.defaultMiningSpeed != null) { result.defaultMiningSpeed = other.defaultMiningSpeed; result.defaultMiningSpeedRange = null; }
            if (other.defaultMiningSpeedRange != null) { result.defaultMiningSpeedRange = other.defaultMiningSpeedRange.copy(); result.defaultMiningSpeed = null; }
            if (other.miningSpeedMultiplier != null) result.miningSpeedMultiplier = other.miningSpeedMultiplier.copy();
            if (other.damagePerBlock != null) { result.damagePerBlock = other.damagePerBlock; result.damagePerBlockRange = null; }
            if (other.damagePerBlockRange != null) { result.damagePerBlockRange = other.damagePerBlockRange.clone(); result.damagePerBlock = null; }
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
        public NumberRange speedRange;
        public Boolean correctForDrops;
        public ToolRuleEntry copy() {
            ToolRuleEntry copy = new ToolRuleEntry();
            copy.blocks = blocks == null ? null : new ArrayList<>(blocks);
            copy.speed = speed;
            copy.speedRange = speedRange == null ? null : speedRange.copy();
            copy.correctForDrops = correctForDrops;
            return copy;
        }
    }
}
