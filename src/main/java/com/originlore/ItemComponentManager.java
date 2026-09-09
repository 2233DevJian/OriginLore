package com.originlore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.originlore.component.ComponentCodecSupport;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.WeightPoint;
import com.originlore.gameplay.FoodUnits;
import com.originlore.gameplay.FoodTooltip;
import com.originlore.config.ItemComponentConfig.AttributeRule;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.EffectRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.NumberRange;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.ToolRuleEntry;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.mixin.ItemStackAccessor;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Applies server-owned rules as reversible, validated ItemStack transactions. */
public final class ItemComponentManager {
    public static final String METADATA_KEY = "originlore";
    private static final int METADATA_VERSION = 4;
    private static final String ATTACK_DAMAGE_RANDOM_KEY = "attack_damage";
    private static final Identifier ATTACK_DAMAGE_MODIFIER_ID = Identifier.of("originlore", "attack_damage");

    private final ItemComponentConfig config;

    public ItemComponentManager(ItemComponentConfig config) {
        this.config = config;
    }

    public ApplyResult applyComponents(ItemStack stack, SourceContext source,
                                       RegistryWrapper.WrapperLookup lookup) {
        if (stack == null || stack.isEmpty()) return ApplyResult.unchanged();
        if (lookup == null) return ApplyResult.failure("registry lookup is unavailable");

        if (source == null || source.type() == SourceType.UNKNOWN) {
            ApplyResult foodResult = FoodUnits.refresh(stack, this, lookup);
            if (foodResult != null) return foodResult;
        }

        Metadata metadata = Metadata.read(stack);
        Identifier currentItemId = Registries.ITEM.getId(stack.getItem());
        SourceContext requestedSource = source == null ? SourceContext.unknown() : source;
        boolean itemChanged = metadata.present && metadata.managedItemId != null
                && !metadata.managedItemId.equals(currentItemId.toString());
        boolean sourceChanged = metadata.present && requestedSource.type() != SourceType.UNKNOWN
                && !metadata.matchesSource(requestedSource);
        boolean reclassify = itemChanged || sourceChanged;
        if (metadata.present && metadata.metadataVersion == METADATA_VERSION
                && metadata.revision == config.getRevision() && !reclassify
                && !(stack.isOf(Items.CAKE) && stack.contains(DataComponentTypes.FOOD))) {
            return FoodTooltip.refresh(stack, lookup, config.getPresetLanguage()) ? ApplyResult.applied() : ApplyResult.unchanged();
        }

        ItemEntry currentEntry = config.getItemConfig(currentItemId.toString());
        if (!metadata.present && currentEntry == null) return ApplyResult.unchanged();

        ItemStack candidate = stack.copy();
        int previousMaxDamage = stack.getMaxDamage();
        int previousDamage = stack.getDamage();
        try {
            FoodTooltip.strip(candidate);
            restoreOriginals(candidate, metadata, lookup);
            if (itemChanged) metadata.originals = new NbtCompound();
            Identifier itemId = Registries.ITEM.getId(candidate.getItem());
            ItemEntry entry = config.getItemConfig(itemId.toString());
            if (entry == null) {
                removeMetadata(candidate);
                preserveWear(candidate, previousMaxDamage, previousDamage);
                validateCandidate(candidate);
                commit(stack, candidate);
                return ApplyResult.applied();
            }

            if (reclassify) {
                metadata.variantSelected = false;
                metadata.variantId = "";
                metadata.randomValues = new NbtCompound();
                metadata.randomPositions = new NbtCompound();
            }
            SourceContext effectiveSource = metadata.present && !reclassify
                    ? metadata.sourceContext() : requestedSource;
            SourceRule sourceRule = config.findSourceRule(entry, effectiveSource.ruleType(),
                    effectiveSource.lootTableId(), effectiveSource.recipeId());
            String variantId = metadata.present && metadata.variantSelected
                    ? metadata.variantId : selectVariant(sourceRule, metadata.ingredientQuality, ThreadLocalRandom.current()::nextDouble);
            ComponentRule merged = merge(entry, sourceRule, variantId);
            Variant variant = findVariant(sourceRule, variantId);
            if (variant != null) {
                metadata.qualityScore = variant.qualityScore == null ? 0.5 : variant.qualityScore;
                double residual = metadata.ingredientRisk;
                if (sourceRule.processing != null && residual > 0) {
                    residual = Math.max(residual * sourceRule.processing.riskRetention, sourceRule.processing.riskFloor);
                }
                metadata.spoilageRisk = Math.max(variant.spoilage == null ? 0 : variant.spoilage, residual);
            }
            Set<String> managed = merged.controlledComponents();
            boolean residualEffects = sourceRule != null && sourceRule.processing != null
                    && sourceRule.processing.effects != null && !sourceRule.processing.effects.isEmpty()
                    && metadata.ingredientRisk > 0;
            if (residualEffects) managed.add("minecraft:food");
            if (candidate.isOf(Items.CAKE) && candidate.contains(DataComponentTypes.FOOD)) managed.add("minecraft:food");
            for (String componentId : managed) {
                ComponentType<?> type = componentType(componentId);
                if (!metadata.originals.contains(componentId)) {
                    backupOriginal(candidate, metadata, componentId, type, lookup);
                }
            }
            retainOriginals(metadata, managed);
            Text playerName = metadata.playerNamed ? candidate.get(DataComponentTypes.CUSTOM_NAME) : null;
            applyRule(candidate, merged, metadata, lookup);
            if (playerName != null) candidate.set(DataComponentTypes.CUSTOM_NAME, playerName);
            if (residualEffects) applyResidualEffects(candidate, sourceRule, metadata);
            metadata.cakeFood = null;
            if (candidate.isOf(Items.CAKE) && candidate.contains(DataComponentTypes.FOOD)) {
                // Slice data must not make the handheld cake edible on an unmodified client.
                metadata.cakeFood = ComponentCodecSupport.encodeNbt(DataComponentTypes.FOOD,
                        candidate.get(DataComponentTypes.FOOD), lookup);
                candidate.remove(DataComponentTypes.FOOD);
            }
            if (!managed.contains("minecraft:damage")) preserveWear(candidate, previousMaxDamage, previousDamage);
            metadata.present = true;
            metadata.metadataVersion = METADATA_VERSION;
            metadata.managedItemId = itemId.toString();
            metadata.sourceType = effectiveSource.type();
            metadata.sourceId = effectiveSource.sourceId();
            metadata.lootTableId = effectiveSource.lootTableId();
            metadata.recipeId = effectiveSource.recipeId();
            metadata.variantSelected = true;
            metadata.variantId = variantId;
            metadata.revision = config.getRevision();
            writeMetadata(candidate, metadata);
            FoodTooltip.refresh(candidate, lookup, config.getPresetLanguage());

            validateCandidate(candidate);
            commit(stack, candidate);
            return ApplyResult.applied();
        } catch (RuntimeException exception) {
            return ApplyResult.failure(message(exception));
        }
    }

    public ApplyResult refresh(ItemStack stack, RegistryWrapper.WrapperLookup lookup) {
        return applyComponents(stack, SourceContext.unknown(), lookup);
    }

    public ApplyResult applyProducedComponents(ItemStack stack, SourceContext source, RegistryWrapper.WrapperLookup lookup,
                                                double ingredientQuality, double ingredientRisk) {
        if (stack == null || stack.isEmpty()) return ApplyResult.unchanged();
        ItemStack candidate = stack.copy();
        try {
            restoreOriginals(candidate, Metadata.read(candidate), lookup);
            removeMetadata(candidate);
            Metadata fresh = new Metadata();
            fresh.managedItemId = Registries.ITEM.getId(candidate.getItem()).toString();
            fresh.setSource(source == null ? SourceContext.unknown() : source);
            fresh.ingredientQuality = Math.clamp(ingredientQuality, 0, 1);
            fresh.ingredientRisk = Math.clamp(ingredientRisk, 0, 1);
            fresh.revision = Long.MIN_VALUE;
            writeMetadata(candidate, fresh);
            ApplyResult result = applyComponents(candidate, source, lookup);
            if (result.success()) commit(stack, candidate);
            return result;
        } catch (RuntimeException exception) { return ApplyResult.failure(message(exception)); }
    }

    public ApplyResult applyInheritedComponents(ItemStack output, ItemStack original, SourceContext source,
                                                 RegistryWrapper.WrapperLookup lookup) {
        if (output == null || output.isEmpty() || original == null || original.isEmpty()) return ApplyResult.unchanged();
        Metadata inherited = Metadata.read(original);
        if (!inherited.present) return applyComponents(output, SourceContext.unknown(), lookup);
        ItemStack candidate = output.copy();
        boolean upgraded = output.getItem() != original.getItem();
        int previousMax = upgraded ? original.getMaxDamage() : output.getMaxDamage();
        int previousDamage = upgraded ? original.getDamage() : output.getDamage();
        try {
            if (!Objects.equals(output.get(DataComponentTypes.CUSTOM_NAME), original.get(DataComponentTypes.CUSTOM_NAME))) {
                inherited.playerNamed = output.contains(DataComponentTypes.CUSTOM_NAME);
            }
            // Maintenance can legitimately change controlled components, such as anvil names
            // and enchantments. Capture those changes before restoring our previous overrides.
            for (String componentId : new ArrayList<>(inherited.originals.getKeys())) {
                ComponentType<?> type = componentType(componentId);
                // Combining repairs temporarily uses the larger input maximum, not a new
                // native durability for the primary item.
                if (!upgraded && type == DataComponentTypes.MAX_DAMAGE) continue;
                if (!Objects.equals(output.getComponentChanges().get(type), original.getComponentChanges().get(type))) {
                    inherited.originals.remove(componentId);
                    backupOriginal(candidate, inherited, componentId, type, lookup);
                }
            }
            if (upgraded) {
                restoreOriginals(candidate, inherited, lookup);
                inherited.originals = new NbtCompound();
                inherited.setSource(source == null ? new SourceContext(SourceType.SMITHING) : source);
                if (previousMax > 0 && candidate.getMaxDamage() > 0) {
                    candidate.setDamage(RandomRanges.mapDamage(previousDamage, previousMax, candidate.getMaxDamage()));
                }
            }
            inherited.managedItemId = Registries.ITEM.getId(candidate.getItem()).toString();
            inherited.revision = Long.MIN_VALUE;
            writeMetadata(candidate, inherited);
            ApplyResult result = applyComponents(candidate, SourceContext.unknown(), lookup);
            if (result.success()) {
                ItemEntry entry = config.getItemConfig(Registries.ITEM.getId(candidate.getItem()).toString());
                SourceContext context = inherited.sourceContext();
                SourceRule sourceRule = entry == null ? null : config.findSourceRule(entry, context.ruleType(), context.lootTableId(), context.recipeId());
                ComponentRule merged = entry == null ? null : merge(entry, sourceRule, inherited.variantId);
                if (upgraded && (merged == null || !merged.controlledComponents().contains("minecraft:damage"))
                        && previousMax > 0 && candidate.getMaxDamage() > 0) {
                    candidate.setDamage(RandomRanges.mapDamage(previousDamage, previousMax, candidate.getMaxDamage()));
                }
                validateCandidate(candidate);
                commit(output, candidate);
            }
            return result;
        } catch (RuntimeException exception) { return ApplyResult.failure(message(exception)); }
    }

    public static double getQualityScore(ItemStack stack) { return Metadata.read(stack).qualityScore; }
    public static double getSpoilageRisk(ItemStack stack) { return Metadata.read(stack).spoilageRisk; }
    public static double getProjectileDamageMultiplier(ItemStack stack) { return Metadata.read(stack).projectileDamageMultiplier; }

    public static FoodComponent getCakeFood(ItemStack stack, RegistryWrapper.WrapperLookup lookup) {
        if (stack == null || !stack.isOf(Items.CAKE) || lookup == null) return null;
        NbtElement food = Metadata.read(stack).cakeFood;
        return food == null ? null : FoodComponent.CODEC.parse(lookup.getOps(NbtOps.INSTANCE), food).result().orElse(null);
    }

    private static void preserveWear(ItemStack stack, int previousMax, int previousDamage) {
        int maximum = stack.getMaxDamage();
        if (previousMax > 0 && maximum > 0 && maximum != previousMax) {
            stack.set(DataComponentTypes.DAMAGE, RandomRanges.mapDamage(previousDamage, previousMax, maximum));
        }
    }

    /** Reuses an existing output stack's variant identity when a machine extends that stack. */
    public ApplyResult applyComponentsUsingIdentity(ItemStack stack, SourceContext source, ItemStack identity,
                                                    RegistryWrapper.WrapperLookup lookup) {
        if (stack == null || stack.isEmpty() || identity == null || identity.isEmpty()) {
            return applyComponents(stack, source, lookup);
        }
        Metadata existing = Metadata.read(identity);
        Identifier stackId = Registries.ITEM.getId(stack.getItem());
        if (existing.present && existing.matchesSource(source)
                && (existing.managedItemId == null || existing.managedItemId.equals(stackId.toString()))) {
            ItemStack candidate = stack.copy();
            Metadata copied = existing.copy();
            copied.revision = Long.MIN_VALUE;
            writeMetadata(candidate, copied);
            ApplyResult result = applyComponents(candidate, source, lookup);
            if (result.success() && result.changed()) commit(stack, candidate);
            return result;
        }
        return applyComponents(stack, source, lookup);
    }

    public long getManagedRevision(ItemStack stack) {
        return Metadata.read(stack).revision;
    }

    /** Returns true when the stack carries OriginLore's provenance record. */
    public static boolean hasOriginLoreMetadata(ItemStack stack) {
        return stack != null && !stack.isEmpty() && Metadata.read(stack).present;
    }

    /**
     * Compares two managed stacks while ignoring bookkeeping that should not
     * prevent normal inventory/furnace stacking. The selected variant remains
     * part of the comparison, as do every non-OriginLore component and all
     * effective component values.
     */
    public static boolean canStackIgnoringBookkeeping(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()
                || !left.isOf(right.getItem())) return false;
        Metadata leftMetadata = Metadata.read(left);
        Metadata rightMetadata = Metadata.read(right);
        if (!leftMetadata.present || !rightMetadata.present
                || !Objects.equals(leftMetadata.variantId, rightMetadata.variantId)) return false;

        ItemStack normalizedLeft = normalizeForStackComparison(left);
        ItemStack normalizedRight = normalizeForStackComparison(right);
        return Objects.equals(normalizedLeft.getComponents(), normalizedRight.getComponents());
    }

    private static ItemStack normalizeForStackComparison(ItemStack stack) {
        ItemStack normalized = stack.copy();
        NbtCompound root = normalized.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        if (!root.contains(METADATA_KEY, NbtElement.COMPOUND_TYPE)) return normalized;

        NbtCompound origin = root.getCompound(METADATA_KEY);
        NbtCompound stable = new NbtCompound();
        if (origin.contains("variant_id", NbtElement.STRING_TYPE)) {
            stable.putString("variant_id", origin.getString("variant_id"));
        }
        root.remove(METADATA_KEY);
        if (!stable.isEmpty()) root.put(METADATA_KEY, stable);
        if (root.isEmpty()) normalized.remove(DataComponentTypes.CUSTOM_DATA);
        else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, normalized, root);
        return normalized;
    }

    /** Validates all base/source/variant combinations without mutating live stacks. */
    public List<String> validateConfiguration(ItemComponentConfig.ConfigSnapshot snapshot,
                                              RegistryWrapper.WrapperLookup lookup) {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, ItemEntry> configured : snapshot.items().entrySet()) {
            Identifier id = Identifier.tryParse(configured.getKey());
            if (id == null || !Registries.ITEM.containsId(id)) {
                errors.add("items." + configured.getKey() + ": unknown item id");
                continue;
            }
            ItemEntry entry = configured.getValue();
            validateRuleCombination(errors, configured.getKey() + ".base", id, entry.base, lookup);
            for (int sourceIndex = 0; sourceIndex < entry.sources.size(); sourceIndex++) {
                SourceRule source = entry.sources.get(sourceIndex);
                try {
                    SourceType.valueOf(source.type.toUpperCase(Locale.ROOT));
                } catch (RuntimeException exception) {
                    errors.add("items." + configured.getKey() + ".sources[" + sourceIndex + "].type: unknown source type");
                }
                validateOptionalId(errors, configured.getKey(), "lootTableId", source.lootTableId);
                validateOptionalId(errors, configured.getKey(), "recipeId", source.recipeId);
                if (source.processing != null && source.processing.effects != null) {
                    ComponentRule processing = new ComponentRule();
                    processing.food = new FoodRule();
                    processing.food.appendEffects = true;
                    processing.food.effects = source.processing.effects;
                    validateRuleCombination(errors, configured.getKey() + ".sources[" + sourceIndex + "].processing", id, processing, lookup);
                }
                ComponentRule sourceMerged = entry.base.copy();
                sourceMerged.mergeFrom(source.rule);
                validateRuleCombination(errors, configured.getKey() + ".sources[" + sourceIndex + "]", id, sourceMerged, lookup);
                Set<String> variantIds = new LinkedHashSet<>();
                for (int variantIndex = 0; variantIndex < source.variants.size(); variantIndex++) {
                    Variant variant = source.variants.get(variantIndex);
                    if (!variantIds.add(variant.id)) {
                        errors.add("items." + configured.getKey() + ".sources[" + sourceIndex + "].variants: duplicate id " + variant.id);
                    }
                    ComponentRule variantMerged = sourceMerged.copy();
                    variantMerged.mergeFrom(variant.rule);
                    validateRuleCombination(errors,
                            configured.getKey() + ".sources[" + sourceIndex + "].variants[" + variantIndex + "]",
                            id, variantMerged, lookup);
                }
            }
        }
        return errors;
    }

    private void validateRuleCombination(List<String> errors, String path, Identifier itemId,
                                         ComponentRule rule, RegistryWrapper.WrapperLookup lookup) {
        if (rule == null || rule.isEmpty()) return;
        try {
            for (String componentId : rule.controlledComponents()) componentType(componentId);
            for (Metadata metadata : validationSamples(rule)) {
                ItemStack test = new ItemStack(Registries.ITEM.get(itemId));
                applyRule(test, rule, metadata, lookup);
                validateCandidate(test);
            }
        } catch (RuntimeException exception) {
            errors.add(path + ": " + message(exception));
        }
    }

    private static List<Metadata> validationSamples(ComponentRule rule) {
        List<RangeSeed> ranges = new ArrayList<>();
        if (rule.maxStackSize == null && rule.maxStackSizeRange != null) {
            validateIntRange("max_stack_size", rule.maxStackSizeRange);
            ranges.add(new RangeSeed("max_stack_size", rule.maxStackSizeRange[0], rule.maxStackSizeRange[1]));
        }
        if (rule.maxDamage == null && rule.maxDamageRange != null) {
            validateIntRange("max_damage", rule.maxDamageRange);
            ranges.add(new RangeSeed("max_damage", rule.maxDamageRange[0], rule.maxDamageRange[1]));
        }
        if (rule.attackDamage != null || rule.attackDamageRange != null) {
            NumberRange range = rule.attackDamage != null ? rule.attackDamage : rule.attackDamageRange;
            if (!Double.isFinite(range.min) || !Double.isFinite(range.max) || range.min > range.max) {
                throw new IllegalArgumentException("attack_damage range is invalid");
            }
            ranges.add(new RangeSeed(ATTACK_DAMAGE_RANDOM_KEY, range.min, range.max));
        }

        addRange(ranges, "projectile_damage_multiplier", rule.projectileDamageMultiplier);
        if (rule.food != null) {
            addRange(ranges, "food.nutrition", rule.food.nutritionRange);
            addRange(ranges, "food.saturation", rule.food.saturationRange);
            addRange(ranges, "food.eat_seconds", rule.food.eatSecondsRange);
        }
        if (rule.attributes != null) for (AttributeRule attribute : rule.attributes) {
            if (attribute == null) throw new IllegalArgumentException("attribute modifier is null");
            addRange(ranges, "attribute." + attribute.id + "." + attribute.slot, attribute.amountRange);
        }
        if (rule.tool != null) {
            addRange(ranges, "tool.default_mining_speed", rule.tool.defaultMiningSpeedRange);
            addRange(ranges, "tool.mining_speed_multiplier", rule.tool.miningSpeedMultiplier);
            addRange(ranges, "tool.damage_per_block", rule.tool.damagePerBlockRange);
            if (rule.tool.rules != null) for (int i = 0; i < rule.tool.rules.size(); i++) {
                ToolRuleEntry entry = rule.tool.rules.get(i);
                if (entry == null) throw new IllegalArgumentException("tool rule is null");
                addRange(ranges, "tool.rule." + i + ".speed", entry.speedRange);
            }
        }
        ItemComponentConfig.validateRuleNumbers(rule);
        // Numeric domains are checked directly. Probe both global extremes and one axis at
        // a time for component constraints, keeping work linear in the number of ranges.
        List<Metadata> samples = new ArrayList<>();
        samples.add(rangeSample(ranges, 0));
        samples.add(rangeSample(ranges, 1));
        for (RangeSeed range : ranges) {
            Metadata low = rangeSample(ranges, 0.5);
            low.randomPositions.putDouble(range.key, 0);
            samples.add(low);
            Metadata high = rangeSample(ranges, 0.5);
            high.randomPositions.putDouble(range.key, 1);
            samples.add(high);
        }
        return samples;
    }

    private static Metadata rangeSample(List<RangeSeed> ranges, double position) {
        Metadata metadata = new Metadata();
        for (RangeSeed range : ranges) metadata.randomPositions.putDouble(range.key, position);
        return metadata;
    }

    private static void addRange(List<RangeSeed> ranges, String key, NumberRange range) {
        if (range != null) ranges.add(new RangeSeed(key, range.min, range.max));
    }

    private static void addRange(List<RangeSeed> ranges, String key, int[] range) {
        if (range != null) {
            validateIntRange(key, range);
            ranges.add(new RangeSeed(key, range[0], range[1]));
        }
    }

    private static void validateIntRange(String key, int[] range) {
        if (range.length != 2 || range[0] > range[1]) {
            throw new IllegalArgumentException(key + " range is invalid");
        }
    }

    private record RangeSeed(String key, double min, double max, boolean integral) {
        private RangeSeed(String key, int min, int max) {
            this(key, min, max, true);
        }

        private RangeSeed(String key, double min, double max) {
            this(key, min, max, false);
        }
    }

    private static void validateOptionalId(List<String> errors, String itemId, String field, String value) {
        if (value != null && Identifier.tryParse(value) == null) {
            errors.add("items." + itemId + "." + field + ": invalid identifier");
        }
    }

    private static ComponentRule merge(ItemEntry entry, SourceRule sourceRule, String variantId) {
        ComponentRule merged = entry.base == null ? new ComponentRule() : entry.base.copy();
        if (sourceRule != null) {
            merged.mergeFrom(sourceRule.rule);
            Variant variant = findVariant(sourceRule, variantId);
            if (variant != null) merged.mergeFrom(variant.rule);
        }
        return merged;
    }

    private static Variant findVariant(SourceRule sourceRule, String variantId) {
        if (sourceRule == null || variantId == null || variantId.isEmpty()) return null;
        for (Variant variant : sourceRule.variants) if (variantId.equals(variant.id)) return variant;
        return null;
    }

    private static String selectVariant(SourceRule sourceRule) {
        return selectVariant(sourceRule, ThreadLocalRandom.current()::nextDouble);
    }

    static String selectVariant(SourceRule sourceRule, DoubleSupplier randomFraction) {
        return selectVariant(sourceRule, 0.5, randomFraction);
    }

    static String selectVariant(SourceRule sourceRule, double ingredientQuality, DoubleSupplier randomFraction) {
        if (sourceRule == null || sourceRule.variants == null || sourceRule.variants.isEmpty()) return "";
        double total = 0;
        Variant lastPositive = null;
        for (Variant variant : sourceRule.variants) {
            if (variant == null || variant.weight <= 0) continue;
            double weight = effectiveWeight(variant, ingredientQuality);
            total += weight;
            if (weight > 0) lastPositive = variant;
        }
        if (!(total > 0)) {
            if (sourceRule.variants.stream().noneMatch(variant -> variant != null && variant.weight > 0)) return "";
            SourceRule fallback = sourceRule.copy();
            fallback.variants.forEach(variant -> variant.ingredientWeights = null);
            return selectVariant(fallback, 0.5, randomFraction);
        }
        if (!Double.isFinite(total)) throw new IllegalArgumentException("variant weight total is not finite");
        double fraction = randomFraction == null ? ThreadLocalRandom.current().nextDouble() : randomFraction.getAsDouble();
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("variant random value is not finite");
        double selected = Math.max(0.0, Math.min(Math.nextDown(1.0), fraction)) * total;
        for (Variant variant : sourceRule.variants) {
            if (variant == null || variant.weight <= 0) continue;
            selected -= effectiveWeight(variant, ingredientQuality);
            if (selected < 0) return variant.id;
        }
        return lastPositive == null ? "" : lastPositive.id;
    }

    private static double effectiveWeight(Variant variant, double quality) {
        if (variant.ingredientWeights == null || variant.ingredientWeights.isEmpty()) return variant.weight;
        List<WeightPoint> points = variant.ingredientWeights;
        WeightPoint previous = points.getFirst();
        if (quality <= previous.quality) return variant.weight * previous.multiplier;
        for (int index = 1; index < points.size(); index++) {
            WeightPoint point = points.get(index);
            if (quality <= point.quality) {
                double position = (quality - previous.quality) / (point.quality - previous.quality);
                return variant.weight * (previous.multiplier + position * (point.multiplier - previous.multiplier));
            }
            previous = point;
        }
        return variant.weight * previous.multiplier;
    }

    private static void restoreOriginals(ItemStack candidate, Metadata metadata,
                                         RegistryWrapper.WrapperLookup lookup) {
        for (String componentId : new ArrayList<>(metadata.originals.getKeys())) {
            ComponentType<?> type = componentType(componentId);
            NbtCompound backup = metadata.originals.getCompound(componentId);
            if (metadata.metadataVersion >= 2) {
                restoreOriginalPatch(candidate, componentId, type, backup, lookup);
                continue;
            }
            // Metadata v1 stored only the effective value, so exact patch restoration is unavailable.
            if (!backup.getBoolean("present")) {
                removeRaw(candidate, type);
            } else {
                NbtElement encoded = backup.get("value");
                if (encoded == null) throw new IllegalStateException("missing original value for " + componentId);
                ComponentCodecSupport.DecodedComponent decoded = ComponentCodecSupport.decodeNbt(componentId, encoded, lookup);
                setRaw(candidate, decoded.type(), decoded.value());
            }
        }
    }

    private static void restoreOriginalPatch(ItemStack candidate, String componentId, ComponentType<?> type,
                                             NbtCompound backup, RegistryWrapper.WrapperLookup lookup) {
        String state = backup.getString("patch_state");
        if (state.equals("set")) {
            NbtElement encoded = backup.get("patch_value");
            if (encoded == null) throw new IllegalStateException("missing original patch value for " + componentId);
            ComponentCodecSupport.DecodedComponent decoded = ComponentCodecSupport.decodeNbt(componentId, encoded, lookup);
            setRaw(candidate, decoded.type(), decoded.value());
        } else if (state.equals("removed")) {
            removeRaw(candidate, type);
        } else {
            resetToDefault(candidate, type);
        }
    }

    private static void backupOriginal(ItemStack candidate, Metadata metadata, String componentId,
                                       ComponentType<?> type, RegistryWrapper.WrapperLookup lookup) {
        NbtCompound backup = new NbtCompound();
        Object value = getRaw(candidate, type);
        backup.putBoolean("present", value != null);
        if (value != null) {
            if (type == DataComponentTypes.CUSTOM_DATA) {
                NbtCompound customData = ((NbtComponent) value).copyNbt();
                customData.remove(METADATA_KEY);
                value = NbtComponent.of(customData);
            }
            backup.put("value", ComponentCodecSupport.encodeNbt(type, value, lookup));
        }
        Optional<?> patch = candidate.getComponentChanges().get(type);
        if (patch == null) {
            backup.putString("patch_state", "default");
        } else if (patch.isEmpty()) {
            backup.putString("patch_state", "removed");
        } else {
            backup.putString("patch_state", "set");
            Object patchValue = patch.get();
            if (type == DataComponentTypes.CUSTOM_DATA) {
                NbtCompound customData = ((NbtComponent) patchValue).copyNbt();
                customData.remove(METADATA_KEY);
                patchValue = NbtComponent.of(customData);
            }
            backup.put("patch_value", ComponentCodecSupport.encodeNbt(type, patchValue, lookup));
        }
        metadata.originals.put(componentId, backup);
    }

    private static void resetToDefault(ItemStack stack, ComponentType<?> type) {
        Object defaultValue = stack.getDefaultComponents().get(type);
        if (defaultValue == null) removeRaw(stack, type);
        else setRaw(stack, type, defaultValue);
    }

    private static void retainOriginals(Metadata metadata, Set<String> managed) {
        NbtCompound retained = new NbtCompound();
        for (String componentId : new ArrayList<>(metadata.originals.getKeys())) {
            if (managed.contains(componentId)) {
                NbtElement value = metadata.originals.get(componentId);
                if (value != null) retained.put(componentId, value.copy());
            }
        }
        metadata.originals = retained;
    }

    private static void applyRule(ItemStack stack, ComponentRule rule, Metadata metadata,
                                  RegistryWrapper.WrapperLookup lookup) {
        if (rule.itemNameJson != null) {
            stack.set(DataComponentTypes.ITEM_NAME, Text.Serialization.fromJsonTree(rule.itemNameJson, lookup));
        } else if (rule.itemName != null) stack.set(DataComponentTypes.ITEM_NAME, Text.literal(rule.itemName));
        if (!metadata.playerNamed && rule.customNameJson != null) {
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.Serialization.fromJsonTree(rule.customNameJson, lookup));
        } else if (!metadata.playerNamed && rule.customName != null) {
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(rule.customName));
        }

        if (rule.loreJson != null || rule.lore != null) {
            List<Text> lines = new ArrayList<>();
            if (rule.loreJson != null) {
                for (JsonElement line : rule.loreJson) {
                    if (line == null || line.isJsonNull()) throw new IllegalArgumentException("lore JSON line is null");
                    lines.add(Text.Serialization.fromJsonTree(line, lookup));
                }
            } else {
                for (String line : rule.lore) lines.add(Text.literal(line == null ? "" : line));
            }
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }

        Integer maxStack = sampleInt(metadata, "max_stack_size", rule.maxStackSize != null ? new int[]{rule.maxStackSize, rule.maxStackSize} : rule.maxStackSizeRange);
        Integer maxDamage = sampleInt(metadata, "max_damage", rule.maxDamage != null ? new int[]{rule.maxDamage, rule.maxDamage} : rule.maxDamageRange);
        Integer currentDamage = rule.currentDamage;
        if (maxStack != null) stack.set(DataComponentTypes.MAX_STACK_SIZE, maxStack);
        if (maxDamage != null) {
            if (!stack.getDefaultComponents().contains(DataComponentTypes.MAX_DAMAGE)) {
                throw new IllegalArgumentException("maximum durability requires a naturally durable item");
            }
            stack.set(DataComponentTypes.MAX_DAMAGE, maxDamage);
        }
        if (currentDamage != null) stack.set(DataComponentTypes.DAMAGE, currentDamage);

        if (rule.fireResistant != null) setUnit(stack, DataComponentTypes.FIRE_RESISTANT, rule.fireResistant);
        if (rule.hideTooltip != null) setUnit(stack, DataComponentTypes.HIDE_TOOLTIP, rule.hideTooltip);
        if (rule.hideAdditionalTooltip != null) setUnit(stack, DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, rule.hideAdditionalTooltip);
        if (rule.customModelData != null) stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(rule.customModelData));
        if (rule.rarity != null || rule.rarityName != null) stack.set(DataComponentTypes.RARITY, parseRarity(rule));

        if (rule.food != null) applyFood(stack, rule.food, metadata);
        if (rule.enchantments != null) applyEnchantments(stack, DataComponentTypes.ENCHANTMENTS, rule.enchantments, lookup);
        if (rule.storedEnchantments != null) applyEnchantments(stack, DataComponentTypes.STORED_ENCHANTMENTS, rule.storedEnchantments, lookup);
        if (rule.attributes != null || rule.attackDamage != null || rule.attackDamageRange != null) applyAttributes(stack, rule, metadata);
        if (rule.tool != null) applyTool(stack, rule, metadata, lookup);
        metadata.projectileDamageMultiplier = rule.projectileDamageMultiplier == null ? 1
                : sampleDouble(metadata, "projectile_damage_multiplier", rule.projectileDamageMultiplier);
        if (metadata.projectileDamageMultiplier < 0) throw new IllegalArgumentException("projectile damage multiplier must be nonnegative");

        if (rule.setComponents != null) {
            for (Map.Entry<String, JsonElement> component : rule.setComponents.entrySet()) {
                ComponentCodecSupport.DecodedComponent decoded = ComponentCodecSupport.decode(component.getKey(), component.getValue(), lookup);
                setRaw(stack, decoded.type(), decoded.value());
            }
        }
        if (rule.removeComponents != null) {
            for (String componentId : rule.removeComponents) removeRaw(stack, componentType(componentId));
        }
    }

    private static void applyFood(ItemStack stack, FoodRule rule, Metadata metadata) {
        FoodComponent current = stack.get(DataComponentTypes.FOOD);
        Integer rolledNutrition = sampleInt(metadata, "food.nutrition", rule.nutrition != null ? new int[]{rule.nutrition, rule.nutrition} : rule.nutritionRange);
        int nutrition = rolledNutrition != null ? rolledNutrition : current == null ? 0 : current.nutrition();
        float saturation = rule.saturation != null ? rule.saturation : rule.saturationRange != null
                ? (float) sampleDouble(metadata, "food.saturation", rule.saturationRange) : current == null ? 0.0f : current.saturation();
        boolean always = rule.canAlwaysEat != null ? rule.canAlwaysEat : current != null && current.canAlwaysEat();
        float seconds = rule.eatSeconds != null ? rule.eatSeconds : rule.eatSecondsRange != null
                ? (float) sampleDouble(metadata, "food.eat_seconds", rule.eatSecondsRange) : current == null ? 1.6f : current.eatSeconds();
        if (nutrition < 0 || !Float.isFinite(saturation) || saturation < 0 || !Float.isFinite(seconds) || seconds <= 0) {
            throw new IllegalArgumentException("food nutrition and saturation must be nonnegative; use time must be positive");
        }
        Optional<ItemStack> converts = current == null ? Optional.empty() : current.usingConvertsTo();
        List<FoodComponent.StatusEffectEntry> effects = current == null ? List.of() : current.effects();
        if (rule.effects != null) {
            List<FoodComponent.StatusEffectEntry> configured = Boolean.TRUE.equals(rule.appendEffects) ? new ArrayList<>(effects) : new ArrayList<>();
            for (EffectRule effect : rule.effects) {
                if (effect == null) throw new IllegalArgumentException("food effect is null");
                Identifier id = Identifier.tryParse(effect.id);
                RegistryEntry.Reference<StatusEffect> type = id == null ? null : Registries.STATUS_EFFECT.getEntry(id).orElse(null);
                if (type == null) throw new IllegalArgumentException("unknown status effect: " + effect.id);
                if (!Float.isFinite(effect.probability) || effect.probability < 0 || effect.probability > 1) throw new IllegalArgumentException("effect probability must be between 0 and 1");
                if (effect.duration < 0 || effect.amplifier < 0 || effect.amplifier > 255) throw new IllegalArgumentException("invalid food effect duration or amplifier");
                StatusEffectInstance instance = new StatusEffectInstance(type, effect.duration, effect.amplifier,
                        effect.ambient, effect.showParticles, effect.showIcon);
                configured.add(new FoodComponent.StatusEffectEntry(instance, effect.probability));
            }
            effects = configured;
        }
        stack.set(DataComponentTypes.FOOD, new FoodComponent(nutrition, saturation, always, seconds, converts, effects));
    }

    private static void applyResidualEffects(ItemStack stack, SourceRule source, Metadata metadata) {
        FoodRule residual = new FoodRule();
        residual.appendEffects = true;
        residual.effects = new ArrayList<>();
        double retainedRisk = Math.max(metadata.ingredientRisk * source.processing.riskRetention, source.processing.riskFloor);
        for (EffectRule effect : source.processing.effects) {
            EffectRule copy = effect.copy();
            copy.probability *= (float) retainedRisk;
            residual.effects.add(copy);
        }
        applyFood(stack, residual, metadata);
    }

    private static void applyEnchantments(ItemStack stack, ComponentType<?> type, Map<String, Integer> values,
                                          RegistryWrapper.WrapperLookup lookup) {
        JsonObject levels = new JsonObject();
        for (Map.Entry<String, Integer> enchantment : values.entrySet()) levels.addProperty(enchantment.getKey(), enchantment.getValue());
        JsonObject json = new JsonObject();
        json.add("levels", levels);
        ComponentCodecSupport.DecodedComponent decoded = ComponentCodecSupport.decode(
                Registries.DATA_COMPONENT_TYPE.getId(type).toString(), json, lookup);
        setRaw(stack, decoded.type(), decoded.value());
    }

    private static void applyAttributes(ItemStack stack, ComponentRule rule, Metadata metadata) {
        AttributeModifiersComponent current = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        // ArmorItem supplies vanilla protection lazily when the component has no modifiers.
        AttributeModifiersComponent nativeAttributes = current.modifiers().isEmpty()
                ? stack.getItem().getAttributeModifiers() : current;
        List<AttributeModifiersComponent.Entry> entries = rule.attributes == null || !Boolean.FALSE.equals(rule.appendAttributes)
                ? new ArrayList<>(nativeAttributes.modifiers()) : new ArrayList<>();
        if (rule.attributes != null) {
            for (AttributeRule configured : rule.attributes) {
                if (configured == null) throw new IllegalArgumentException("attribute modifier is null");
                Identifier attributeId = Identifier.tryParse(configured.attribute);
                RegistryEntry.Reference<EntityAttribute> attribute = attributeId == null ? null : Registries.ATTRIBUTE.getEntry(attributeId).orElse(null);
                if (attribute == null) throw new IllegalArgumentException("unknown attribute: " + configured.attribute);
                String effectiveModifierId = configured.id;
                if ("originlore:preset_quality_armor".equals(effectiveModifierId)) {
                    effectiveModifierId += "." + parseSlot(configured.slot).asString();
                }
                Identifier modifierId = Identifier.tryParse(effectiveModifierId);
                if (modifierId == null) throw new IllegalArgumentException("invalid attribute modifier id: " + configured.id);
                EntityAttributeModifier.Operation operation;
                try {
                    operation = EntityAttributeModifier.Operation.valueOf(configured.operation.toUpperCase(Locale.ROOT));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("unknown attribute operation: " + configured.operation);
                }
                String randomKey = "attribute." + configured.id + "." + configured.slot;
                if (("originlore:preset_quality_armor." + configured.slot).equals(configured.id)) {
                    String oldKey = "attribute.originlore:preset_quality_armor." + configured.slot;
                    copyRandomAlias(metadata.randomPositions, oldKey, randomKey);
                    copyRandomAlias(metadata.randomValues, oldKey, randomKey);
                }
                NumberRange range = configured.amountRange == null
                        ? new NumberRange(configured.amount, configured.amount) : configured.amountRange;
                AttributeModifierSlot slot = parseSlot(configured.slot);
                if (Boolean.TRUE.equals(configured.total)) {
                    if (operation != EntityAttributeModifier.Operation.ADD_VALUE) {
                        throw new IllegalArgumentException("total attributes require add_value operation");
                    }
                    validateAttributeTotal(attribute, range);
                    migrateLegacyTotalPosition(metadata, randomKey, range, nativeAttributes, attribute, slot);
                    replaceAttributeTotal(entries, attribute, slot, modifierId, sampleDouble(metadata, randomKey, range));
                } else {
                    double amount = sampleDouble(metadata, randomKey, range);
                    entries.removeIf(entry -> entry.attribute().equals(attribute) && entry.modifier().idMatches(modifierId));
                    entries.add(new AttributeModifiersComponent.Entry(attribute,
                            new EntityAttributeModifier(modifierId, amount, operation), slot));
                }
            }
        }
        if (rule.attackDamage != null) {
            validateAttributeTotal(EntityAttributes.GENERIC_ATTACK_DAMAGE, rule.attackDamage);
            migrateLegacyTotalPosition(metadata, ATTACK_DAMAGE_RANDOM_KEY, rule.attackDamage,
                    nativeAttributes, EntityAttributes.GENERIC_ATTACK_DAMAGE, AttributeModifierSlot.MAINHAND);
            replaceAttributeTotal(entries, EntityAttributes.GENERIC_ATTACK_DAMAGE, AttributeModifierSlot.MAINHAND,
                    Item.BASE_ATTACK_DAMAGE_MODIFIER_ID, sampleDouble(metadata, ATTACK_DAMAGE_RANDOM_KEY, rule.attackDamage));
        } else if (rule.attackDamageRange != null) {
            entries.removeIf(entry -> entry.modifier().idMatches(ATTACK_DAMAGE_MODIFIER_ID));
            RegistryEntry.Reference<EntityAttribute> attackDamage = Registries.ATTRIBUTE
                    .getEntry(Identifier.ofVanilla("generic.attack_damage"))
                    .orElseThrow(() -> new IllegalArgumentException("attack damage attribute is unavailable"));
            double value = sampleDouble(metadata, ATTACK_DAMAGE_RANDOM_KEY, rule.attackDamageRange);
            entries.add(new AttributeModifiersComponent.Entry(attackDamage,
                    new EntityAttributeModifier(ATTACK_DAMAGE_MODIFIER_ID, value, EntityAttributeModifier.Operation.ADD_VALUE),
                    AttributeModifierSlot.MAINHAND));
        }
        stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                new AttributeModifiersComponent(List.copyOf(entries), current.showInTooltip()));
    }

    private static void validateAttributeTotal(RegistryEntry<EntityAttribute> attribute, NumberRange range) {
        double minimum = attribute.value() instanceof ClampedEntityAttribute clamped ? clamped.getMinValue() : 0;
        double maximum = attribute.value() instanceof ClampedEntityAttribute clamped ? clamped.getMaxValue() : Double.MAX_VALUE;
        if (!Double.isFinite(range.min) || !Double.isFinite(range.max) || range.min > range.max
                || range.min < minimum || range.max > maximum) {
            throw new IllegalArgumentException("total " + attribute.getIdAsString() + " must be between " + minimum + " and " + maximum);
        }
    }

    private static double playerAttributeBase(RegistryEntry<EntityAttribute> attribute) {
        DefaultAttributeContainer defaults = PlayerAttributeDefaults.VALUES;
        return defaults.has(attribute) ? defaults.getBaseValue(attribute) : attribute.value().getDefaultValue();
    }

    private static final class PlayerAttributeDefaults {
        private static final DefaultAttributeContainer VALUES = PlayerEntity.createPlayerAttributes().build();
    }

    private static void replaceAttributeTotal(List<AttributeModifiersComponent.Entry> entries,
                                              RegistryEntry<EntityAttribute> attribute, AttributeModifierSlot target,
                                              Identifier modifierId, double total) {
        List<AttributeModifiersComponent.Entry> retained = new ArrayList<>();
        for (AttributeModifiersComponent.Entry entry : entries) {
            boolean overlaps = false;
            if (entry.attribute().equals(attribute)) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (target.matches(slot) && entry.slot().matches(slot)) { overlaps = true; break; }
                }
            }
            if (!overlaps) retained.add(entry);
            else {
                // Shared HAND/ANY modifiers must retain their effects outside the edited slots.
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!target.matches(slot) && entry.slot().matches(slot)) {
                        retained.add(new AttributeModifiersComponent.Entry(attribute, entry.modifier(), AttributeModifierSlot.forEquipmentSlot(slot)));
                    }
                }
            }
        }
        entries.clear();
        entries.addAll(retained);
        Identifier effectiveId = modifierId;
        if (target == AttributeModifierSlot.MAINHAND) {
            if (attribute.equals(EntityAttributes.GENERIC_ATTACK_DAMAGE)) effectiveId = Item.BASE_ATTACK_DAMAGE_MODIFIER_ID;
            else if (attribute.equals(EntityAttributes.GENERIC_ATTACK_SPEED)) effectiveId = Item.BASE_ATTACK_SPEED_MODIFIER_ID;
        }
        entries.add(new AttributeModifiersComponent.Entry(attribute,
                new EntityAttributeModifier(effectiveId, total - playerAttributeBase(attribute), EntityAttributeModifier.Operation.ADD_VALUE), target));
    }

    private static void migrateLegacyTotalPosition(Metadata metadata, String key, NumberRange range,
                                                   AttributeModifiersComponent nativeAttributes,
                                                   RegistryEntry<EntityAttribute> attribute, AttributeModifierSlot target) {
        if (metadata.randomPositions.contains(key) || !metadata.randomValues.contains(key)) return;
        EquipmentSlot equipmentSlot = EquipmentSlot.MAINHAND;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (target.matches(slot)) { equipmentSlot = slot; break; }
        }
        double total = nativeAttributeTotal(nativeAttributes, attribute, equipmentSlot) + metadata.randomValues.getDouble(key);
        metadata.randomPositions.putDouble(key, RandomRanges.position(total, range.min, range.max));
    }

    /** Evaluates unenchanted item modifiers against the standard player's attribute baseline. */
    public static double nativeAttributeTotal(ItemStack stack, RegistryEntry<EntityAttribute> attribute, EquipmentSlot slot) {
        AttributeModifiersComponent modifiers = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        if (modifiers.modifiers().isEmpty()) modifiers = stack.getItem().getAttributeModifiers();
        return nativeAttributeTotal(modifiers, attribute, slot);
    }

    private static double nativeAttributeTotal(AttributeModifiersComponent nativeAttributes,
                                               RegistryEntry<EntityAttribute> attribute, EquipmentSlot equipmentSlot) {
        EntityAttributeInstance nativeValue = new EntityAttributeInstance(attribute, ignored -> {});
        nativeValue.setBaseValue(playerAttributeBase(attribute));
        nativeAttributes.applyModifiers(equipmentSlot, (candidate, modifier) -> {
            if (candidate.equals(attribute)) {
                nativeValue.removeModifier(modifier.id());
                nativeValue.addTemporaryModifier(modifier);
            }
        });
        return nativeValue.getValue();
    }

    private static void copyRandomAlias(NbtCompound values, String oldKey, String newKey) {
        NbtElement oldValue = values.get(oldKey);
        if (oldValue != null && !values.contains(newKey)) values.put(newKey, oldValue.copy());
    }

    private static void applyTool(ItemStack stack, ComponentRule rule, Metadata metadata,
                                  RegistryWrapper.WrapperLookup lookup) {
        ToolComponent original = stack.get(DataComponentTypes.TOOL);
        // Vanilla caches encoded components; never mutate the shared encoded value.
        JsonObject json = original == null ? new JsonObject()
                : ComponentCodecSupport.encode(DataComponentTypes.TOOL, original, lookup).getAsJsonObject().deepCopy();
        JsonArray rules = json.has("rules") ? json.getAsJsonArray("rules") : new JsonArray();
        if (rule.tool.rules != null) {
            JsonArray configuredRules = new JsonArray();
            int index = 0;
            for (ToolRuleEntry configured : rule.tool.rules) {
                if (configured == null || configured.blocks == null || configured.blocks.isEmpty()) {
                    throw new IllegalArgumentException("tool rule blocks are empty");
                }
                JsonObject toolRule = new JsonObject();
                JsonArray blocks = new JsonArray();
                for (String block : configured.blocks) blocks.add(block);
                toolRule.add("blocks", blocks);
                if (configured.speed != null) toolRule.addProperty("speed", configured.speed);
                else if (configured.speedRange != null) toolRule.addProperty("speed",
                        sampleDouble(metadata, "tool.rule." + index + ".speed", configured.speedRange));
                if (configured.correctForDrops != null) toolRule.addProperty("correct_for_drops", configured.correctForDrops);
                configuredRules.add(toolRule);
                index++;
            }
            // Speed-only overrides leave the native harvest-level rules available as fallbacks.
            configuredRules.addAll(rules);
            rules = configuredRules;
        }
        json.add("rules", rules);
        if (rule.tool.defaultMiningSpeed != null) json.addProperty("default_mining_speed", rule.tool.defaultMiningSpeed);
        else if (rule.tool.defaultMiningSpeedRange != null) json.addProperty("default_mining_speed",
                sampleDouble(metadata, "tool.default_mining_speed", rule.tool.defaultMiningSpeedRange));
        if (rule.tool.damagePerBlock != null) json.addProperty("damage_per_block", rule.tool.damagePerBlock);
        else if (rule.tool.damagePerBlockRange != null) json.addProperty("damage_per_block",
                sampleInt(metadata, "tool.damage_per_block", rule.tool.damagePerBlockRange));
        if (rule.tool.miningSpeedMultiplier != null) {
            double multiplier = sampleDouble(metadata, "tool.mining_speed_multiplier", rule.tool.miningSpeedMultiplier);
            for (JsonElement element : rules) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("speed")) entry.addProperty("speed", entry.get("speed").getAsDouble() * multiplier);
            }
            double speed = json.has("default_mining_speed") ? json.get("default_mining_speed").getAsDouble() : 1;
            json.addProperty("default_mining_speed", speed * multiplier);
        }
        ComponentCodecSupport.DecodedComponent decoded = ComponentCodecSupport.decode("minecraft:tool", json, lookup);
        setRaw(stack, decoded.type(), decoded.value());
    }

    private static AttributeModifierSlot parseSlot(String value) {
        String normalized = value == null ? "any" : value.toLowerCase(Locale.ROOT);
        for (AttributeModifierSlot slot : AttributeModifierSlot.values()) if (slot.asString().equals(normalized)) return slot;
        throw new IllegalArgumentException("unknown attribute slot: " + value);
    }

    private static Rarity parseRarity(ComponentRule rule) {
        if (rule.rarityName != null) {
            try {
                return Rarity.valueOf(rule.rarityName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown rarity: " + rule.rarityName);
            }
        }
        return switch (rule.rarity == null ? 0 : rule.rarity) {
            case 0 -> Rarity.COMMON;
            case 1 -> Rarity.UNCOMMON;
            case 2 -> Rarity.RARE;
            case 3 -> Rarity.EPIC;
            default -> throw new IllegalArgumentException("rarity must be between 0 and 3");
        };
    }

    private static Integer sampleInt(Metadata metadata, String key, int[] range) {
        if (range == null) return null;
        if (range.length != 2 || range[0] > range[1]) throw new IllegalArgumentException(key + " range is invalid");
        if (!metadata.randomPositions.contains(key)) {
            int initial = metadata.randomValues.contains(key) ? metadata.randomValues.getInt(key)
                    : range[0] == range[1] ? range[0]
                    : (int) ThreadLocalRandom.current().nextLong((long) range[0], (long) range[1] + 1L);
            metadata.randomPositions.putDouble(key, RandomRanges.position(initial, range[0], range[1]));
        }
        int value = (int) Math.round(RandomRanges.map(metadata.randomPositions.getDouble(key), range[0], range[1]));
        metadata.randomValues.putInt(key, value);
        return value;
    }

    private static double sampleDouble(Metadata metadata, String key, NumberRange range) {
        if (range == null || !Double.isFinite(range.min) || !Double.isFinite(range.max) || range.min > range.max) {
            throw new IllegalArgumentException(key + " range is invalid");
        }
        if (!metadata.randomPositions.contains(key)) {
            double position = metadata.randomValues.contains(key)
                    ? RandomRanges.position(metadata.randomValues.getDouble(key), range.min, range.max)
                    : range.min == range.max ? 0.5 : ThreadLocalRandom.current().nextDouble();
            metadata.randomPositions.putDouble(key, position);
        }
        double value = RandomRanges.map(metadata.randomPositions.getDouble(key), range.min, range.max);
        metadata.randomValues.putDouble(key, value);
        return value;
    }

    private static void validateCandidate(ItemStack candidate) {
        DataResult<Unit> validation = ItemStack.validateComponents(candidate.getComponents());
        String error = validation.error().map(result -> result.message()).orElse(null);
        if (error != null) throw new IllegalArgumentException(error);
        Integer damage = candidate.get(DataComponentTypes.DAMAGE);
        Integer maxDamage = candidate.get(DataComponentTypes.MAX_DAMAGE);
        if (damage != null && (damage < 0 || maxDamage == null || damage > maxDamage)) {
            throw new IllegalArgumentException("damage must be between 0 and max damage");
        }
    }

    private static void commit(ItemStack target, ItemStack candidate) {
        // Replacing the complete patch map preserves the distinction between an item default,
        // an explicit value equal to that default, and an explicit component removal.
        ((ItemStackAccessor) (Object) target).originlore$getComponents()
                .setChanges(candidate.getComponentChanges());
    }

    private static ComponentType<?> componentType(String componentId) {
        Identifier id = Identifier.tryParse(componentId);
        if (id == null) throw new IllegalArgumentException("invalid component id: " + componentId);
        ComponentType<?> type = Registries.DATA_COMPONENT_TYPE.get(id);
        if (type == null) throw new IllegalArgumentException("unknown component: " + componentId);
        try {
            type.getCodecOrThrow();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("component is not persistent: " + componentId);
        }
        return type;
    }

    private static void writeMetadata(ItemStack stack, Metadata metadata) {
        NbtCompound root = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        NbtCompound origin = new NbtCompound();
        origin.putInt("metadata_version", METADATA_VERSION);
        putNullable(origin, "managed_item_id", metadata.managedItemId);
        origin.putString("source_type", metadata.sourceType.name());
        putNullable(origin, "source_id", metadata.sourceId);
        putNullable(origin, "loot_table_id", metadata.lootTableId);
        putNullable(origin, "recipe_id", metadata.recipeId);
        origin.putBoolean("variant_selected", metadata.variantSelected);
        origin.putBoolean("player_named", metadata.playerNamed);
        putNullable(origin, "variant_id", metadata.variantId);
        origin.putLong("config_revision", metadata.revision);
        origin.put("originals", metadata.originals.copy());
        origin.put("random_values", metadata.randomValues.copy());
        origin.put("random_positions", metadata.randomPositions.copy());
        origin.putDouble("ingredient_quality", metadata.ingredientQuality);
        origin.putDouble("ingredient_risk", metadata.ingredientRisk);
        origin.putDouble("quality_score", metadata.qualityScore);
        origin.putDouble("spoilage_risk", metadata.spoilageRisk);
        origin.putDouble("projectile_damage_multiplier", metadata.projectileDamageMultiplier);
        if (metadata.cakeFood != null) origin.put("cake_food", metadata.cakeFood.copy());
        root.put(METADATA_KEY, origin);
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root);
    }

    private static void removeMetadata(ItemStack stack) {
        NbtCompound root = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        root.remove(METADATA_KEY);
        if (root.isEmpty()) stack.remove(DataComponentTypes.CUSTOM_DATA);
        else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root);
    }

    private static void putNullable(NbtCompound nbt, String key, String value) {
        if (value != null && !value.isBlank()) nbt.putString(key, value);
    }

    private static void setUnit(ItemStack stack, ComponentType<Unit> type, boolean present) {
        if (present) stack.set(type, Unit.INSTANCE);
        else stack.remove(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getRaw(ItemStack stack, ComponentType<?> type) {
        return stack.get((ComponentType) type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setRaw(ItemStack stack, ComponentType<?> type, Object value) {
        stack.set((ComponentType) type, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeRaw(ItemStack stack, ComponentType<?> type) {
        stack.remove((ComponentType) type);
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record ApplyResult(boolean changed, String error) {
        public static ApplyResult unchanged() { return new ApplyResult(false, null); }
        public static ApplyResult applied() { return new ApplyResult(true, null); }
        public static ApplyResult failure(String error) { return new ApplyResult(false, error); }
        public boolean success() { return error == null; }
    }

    private static final class Metadata {
        private boolean present;
        private int metadataVersion;
        private String managedItemId;
        private SourceType sourceType = SourceType.UNKNOWN;
        private String sourceId;
        private String lootTableId;
        private String recipeId;
        private boolean variantSelected;
        private boolean playerNamed;
        private String variantId = "";
        private long revision = -1;
        private NbtCompound originals = new NbtCompound();
        private NbtCompound randomValues = new NbtCompound();
        private NbtCompound randomPositions = new NbtCompound();
        private double ingredientQuality = 0.5;
        private double ingredientRisk;
        private double qualityScore = 0.5;
        private double spoilageRisk;
        private double projectileDamageMultiplier = 1;
        private NbtElement cakeFood;

        private void setSource(SourceContext source) {
            sourceType = source.type(); sourceId = source.sourceId();
            lootTableId = source.lootTableId(); recipeId = source.recipeId();
        }

        private SourceContext sourceContext() {
            return new SourceContext(sourceType, sourceId, lootTableId, recipeId);
        }

        private boolean matchesSource(SourceContext source) {
            return source != null && sourceType == source.type()
                    && Objects.equals(sourceId, source.sourceId())
                    && Objects.equals(lootTableId, source.lootTableId())
                    && Objects.equals(recipeId, source.recipeId());
        }

        private Metadata copy() {
            Metadata copy = new Metadata();
            copy.present = present;
            copy.metadataVersion = metadataVersion;
            copy.managedItemId = managedItemId;
            copy.sourceType = sourceType;
            copy.sourceId = sourceId;
            copy.lootTableId = lootTableId;
            copy.recipeId = recipeId;
            copy.variantSelected = variantSelected;
            copy.playerNamed = playerNamed;
            copy.variantId = variantId;
            copy.revision = revision;
            copy.originals = originals.copy();
            copy.randomValues = randomValues.copy();
            copy.randomPositions = randomPositions.copy();
            copy.ingredientQuality = ingredientQuality;
            copy.ingredientRisk = ingredientRisk;
            copy.qualityScore = qualityScore;
            copy.spoilageRisk = spoilageRisk;
            copy.projectileDamageMultiplier = projectileDamageMultiplier;
            copy.cakeFood = cakeFood == null ? null : cakeFood.copy();
            return copy;
        }

        private static Metadata read(ItemStack stack) {
            Metadata metadata = new Metadata();
            if (stack == null || stack.isEmpty()) return metadata;
            NbtCompound root = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
            if (!root.contains(METADATA_KEY, NbtElement.COMPOUND_TYPE)) return metadata;
            NbtCompound origin = root.getCompound(METADATA_KEY);
            metadata.present = true;
            metadata.metadataVersion = origin.getInt("metadata_version");
            metadata.managedItemId = nullable(origin.getString("managed_item_id"));
            metadata.sourceType = SourceType.parse(origin.getString("source_type"));
            metadata.sourceId = nullable(origin.getString("source_id"));
            metadata.lootTableId = nullable(origin.getString("loot_table_id"));
            metadata.recipeId = nullable(origin.getString("recipe_id"));
            metadata.variantSelected = origin.getBoolean("variant_selected");
            metadata.playerNamed = origin.getBoolean("player_named");
            metadata.variantId = origin.getString("variant_id");
            metadata.revision = origin.getLong("config_revision");
            if (origin.contains("originals", NbtElement.COMPOUND_TYPE)) metadata.originals = origin.getCompound("originals").copy();
            if (origin.contains("random_values", NbtElement.COMPOUND_TYPE)) metadata.randomValues = origin.getCompound("random_values").copy();
            if (origin.contains("random_positions", NbtElement.COMPOUND_TYPE)) metadata.randomPositions = origin.getCompound("random_positions").copy();
            if (origin.contains("ingredient_quality")) metadata.ingredientQuality = origin.getDouble("ingredient_quality");
            metadata.ingredientRisk = origin.getDouble("ingredient_risk");
            if (origin.contains("quality_score")) metadata.qualityScore = origin.getDouble("quality_score");
            metadata.spoilageRisk = origin.getDouble("spoilage_risk");
            if (origin.contains("projectile_damage_multiplier")) metadata.projectileDamageMultiplier = origin.getDouble("projectile_damage_multiplier");
            if (origin.contains("cake_food")) metadata.cakeFood = origin.get("cake_food").copy();
            return metadata;
        }

        private static String nullable(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
