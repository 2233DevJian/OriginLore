package com.originlore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.originlore.component.ComponentCodecSupport;
import com.originlore.config.ItemComponentConfig;
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
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
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
    private static final int METADATA_VERSION = 2;
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

        Metadata metadata = Metadata.read(stack);
        Identifier currentItemId = Registries.ITEM.getId(stack.getItem());
        SourceContext requestedSource = source == null ? SourceContext.unknown() : source;
        boolean itemChanged = metadata.present && metadata.managedItemId != null
                && !metadata.managedItemId.equals(currentItemId.toString());
        boolean sourceChanged = metadata.present && requestedSource.type() != SourceType.UNKNOWN
                && !metadata.matchesSource(requestedSource);
        boolean reclassify = itemChanged || sourceChanged;
        if (metadata.present && metadata.metadataVersion == METADATA_VERSION
                && metadata.revision == config.getRevision() && !reclassify) {
            return ApplyResult.unchanged();
        }

        ItemEntry currentEntry = config.getItemConfig(currentItemId.toString());
        if (!metadata.present && currentEntry == null) return ApplyResult.unchanged();

        ItemStack candidate = stack.copy();
        try {
            restoreOriginals(candidate, metadata, lookup);
            if (itemChanged) metadata.originals = new NbtCompound();
            Identifier itemId = Registries.ITEM.getId(candidate.getItem());
            ItemEntry entry = config.getItemConfig(itemId.toString());
            if (entry == null) {
                removeMetadata(candidate);
                validateCandidate(candidate);
                commit(stack, candidate);
                return ApplyResult.applied();
            }

            if (reclassify) {
                metadata.variantSelected = false;
                metadata.variantId = "";
                metadata.randomValues = new NbtCompound();
            }
            SourceContext effectiveSource = metadata.present && !reclassify
                    ? metadata.sourceContext() : requestedSource;
            SourceRule sourceRule = config.findSourceRule(entry, effectiveSource.ruleType(),
                    effectiveSource.lootTableId(), effectiveSource.recipeId());
            String variantId = metadata.present && metadata.variantSelected
                    ? metadata.variantId : selectVariant(sourceRule);
            ComponentRule merged = merge(entry, sourceRule, variantId);
            Set<String> managed = merged.controlledComponents();
            for (String componentId : managed) {
                ComponentType<?> type = componentType(componentId);
                if (!metadata.originals.contains(componentId)) {
                    backupOriginal(candidate, metadata, componentId, type, lookup);
                }
            }
            retainOriginals(metadata, managed);
            applyRule(candidate, merged, metadata, lookup);
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
        if (rule.attackDamageRange != null) {
            NumberRange range = rule.attackDamageRange;
            if (!Double.isFinite(range.min) || !Double.isFinite(range.max) || range.min > range.max) {
                throw new IllegalArgumentException("attack_damage range is invalid");
            }
            ranges.add(new RangeSeed(ATTACK_DAMAGE_RANDOM_KEY, range.min, range.max));
        }

        int combinations = 1 << ranges.size();
        List<Metadata> samples = new ArrayList<>(combinations);
        for (int mask = 0; mask < combinations; mask++) {
            Metadata metadata = new Metadata();
            for (int index = 0; index < ranges.size(); index++) {
                RangeSeed range = ranges.get(index);
                double value = (mask & (1 << index)) == 0 ? range.min : range.max;
                if (range.integral) metadata.randomValues.putInt(range.key, (int) value);
                else metadata.randomValues.putDouble(range.key, value);
            }
            samples.add(metadata);
        }
        return samples;
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
        if (sourceRule == null || sourceRule.variants == null || sourceRule.variants.isEmpty()) return "";
        double total = 0;
        Variant lastPositive = null;
        for (Variant variant : sourceRule.variants) {
            if (variant == null || variant.weight <= 0) continue;
            total += variant.weight;
            lastPositive = variant;
        }
        if (!(total > 0)) return "";
        if (!Double.isFinite(total)) throw new IllegalArgumentException("variant weight total is not finite");
        double fraction = randomFraction == null ? ThreadLocalRandom.current().nextDouble() : randomFraction.getAsDouble();
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("variant random value is not finite");
        double selected = Math.max(0.0, Math.min(Math.nextDown(1.0), fraction)) * total;
        for (Variant variant : sourceRule.variants) {
            if (variant == null || variant.weight <= 0) continue;
            selected -= variant.weight;
            if (selected < 0) return variant.id;
        }
        return lastPositive == null ? "" : lastPositive.id;
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
        if (rule.customNameJson != null) {
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.Serialization.fromJsonTree(rule.customNameJson, lookup));
        } else if (rule.customName != null) {
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

        Integer maxStack = rule.maxStackSize != null ? rule.maxStackSize : sampleInt(metadata, "max_stack_size", rule.maxStackSizeRange);
        Integer maxDamage = rule.maxDamage != null ? rule.maxDamage : sampleInt(metadata, "max_damage", rule.maxDamageRange);
        Integer currentDamage = rule.currentDamage;
        if (maxStack != null) stack.set(DataComponentTypes.MAX_STACK_SIZE, maxStack);
        if (maxDamage != null) stack.set(DataComponentTypes.MAX_DAMAGE, maxDamage);
        if (currentDamage != null) stack.set(DataComponentTypes.DAMAGE, currentDamage);

        if (rule.fireResistant != null) setUnit(stack, DataComponentTypes.FIRE_RESISTANT, rule.fireResistant);
        if (rule.hideTooltip != null) setUnit(stack, DataComponentTypes.HIDE_TOOLTIP, rule.hideTooltip);
        if (rule.hideAdditionalTooltip != null) setUnit(stack, DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, rule.hideAdditionalTooltip);
        if (rule.customModelData != null) stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(rule.customModelData));
        if (rule.rarity != null || rule.rarityName != null) stack.set(DataComponentTypes.RARITY, parseRarity(rule));

        if (rule.food != null) applyFood(stack, rule.food);
        if (rule.enchantments != null) applyEnchantments(stack, DataComponentTypes.ENCHANTMENTS, rule.enchantments, lookup);
        if (rule.storedEnchantments != null) applyEnchantments(stack, DataComponentTypes.STORED_ENCHANTMENTS, rule.storedEnchantments, lookup);
        if (rule.attributes != null || rule.attackDamageRange != null) applyAttributes(stack, rule, metadata);
        if (rule.tool != null) applyTool(stack, rule, lookup);

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

    private static void applyFood(ItemStack stack, FoodRule rule) {
        FoodComponent current = stack.get(DataComponentTypes.FOOD);
        int nutrition = rule.nutrition != null ? rule.nutrition : current == null ? 0 : current.nutrition();
        float saturation = rule.saturation != null ? rule.saturation : current == null ? 0.0f : current.saturation();
        boolean always = rule.canAlwaysEat != null ? rule.canAlwaysEat : current != null && current.canAlwaysEat();
        float seconds = rule.eatSeconds != null ? rule.eatSeconds : current == null ? 1.6f : current.eatSeconds();
        Optional<ItemStack> converts = current == null ? Optional.empty() : current.usingConvertsTo();
        List<FoodComponent.StatusEffectEntry> effects = current == null ? List.of() : current.effects();
        if (rule.effects != null) {
            List<FoodComponent.StatusEffectEntry> configured = new ArrayList<>();
            for (EffectRule effect : rule.effects) {
                if (effect == null) throw new IllegalArgumentException("food effect is null");
                Identifier id = Identifier.tryParse(effect.id);
                RegistryEntry.Reference<StatusEffect> type = id == null ? null : Registries.STATUS_EFFECT.getEntry(id).orElse(null);
                if (type == null) throw new IllegalArgumentException("unknown status effect: " + effect.id);
                if (effect.probability < 0 || effect.probability > 1) throw new IllegalArgumentException("effect probability must be between 0 and 1");
                StatusEffectInstance instance = new StatusEffectInstance(type, effect.duration, effect.amplifier,
                        effect.ambient, effect.showParticles, effect.showIcon);
                configured.add(new FoodComponent.StatusEffectEntry(instance, effect.probability));
            }
            effects = configured;
        }
        stack.set(DataComponentTypes.FOOD, new FoodComponent(nutrition, saturation, always, seconds, converts, effects));
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
        List<AttributeModifiersComponent.Entry> entries = rule.attributes == null
                ? new ArrayList<>(current.modifiers()) : new ArrayList<>();
        if (rule.attributes != null) {
            for (AttributeRule configured : rule.attributes) {
                if (configured == null) throw new IllegalArgumentException("attribute modifier is null");
                Identifier attributeId = Identifier.tryParse(configured.attribute);
                RegistryEntry.Reference<EntityAttribute> attribute = attributeId == null ? null : Registries.ATTRIBUTE.getEntry(attributeId).orElse(null);
                if (attribute == null) throw new IllegalArgumentException("unknown attribute: " + configured.attribute);
                Identifier modifierId = Identifier.tryParse(configured.id);
                if (modifierId == null) throw new IllegalArgumentException("invalid attribute modifier id: " + configured.id);
                EntityAttributeModifier.Operation operation;
                try {
                    operation = EntityAttributeModifier.Operation.valueOf(configured.operation.toUpperCase(Locale.ROOT));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("unknown attribute operation: " + configured.operation);
                }
                entries.add(new AttributeModifiersComponent.Entry(attribute,
                        new EntityAttributeModifier(modifierId, configured.amount, operation), parseSlot(configured.slot)));
            }
        }
        if (rule.attackDamageRange != null) {
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

    private static void applyTool(ItemStack stack, ComponentRule rule, RegistryWrapper.WrapperLookup lookup) {
        JsonObject json = new JsonObject();
        JsonArray rules = new JsonArray();
        if (rule.tool.rules != null) {
            for (ToolRuleEntry configured : rule.tool.rules) {
                if (configured == null || configured.blocks == null || configured.blocks.isEmpty()) {
                    throw new IllegalArgumentException("tool rule blocks are empty");
                }
                JsonObject toolRule = new JsonObject();
                JsonArray blocks = new JsonArray();
                for (String block : configured.blocks) blocks.add(block);
                toolRule.add("blocks", blocks);
                if (configured.speed != null) toolRule.addProperty("speed", configured.speed);
                if (configured.correctForDrops != null) toolRule.addProperty("correct_for_drops", configured.correctForDrops);
                rules.add(toolRule);
            }
        }
        json.add("rules", rules);
        if (rule.tool.defaultMiningSpeed != null) json.addProperty("default_mining_speed", rule.tool.defaultMiningSpeed);
        if (rule.tool.damagePerBlock != null) json.addProperty("damage_per_block", rule.tool.damagePerBlock);
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
        if (metadata.randomValues.contains(key)) return metadata.randomValues.getInt(key);
        int value = range[0] == range[1] ? range[0]
                : (int) ThreadLocalRandom.current().nextLong((long) range[0], (long) range[1] + 1L);
        metadata.randomValues.putInt(key, value);
        return value;
    }

    private static double sampleDouble(Metadata metadata, String key, NumberRange range) {
        if (range == null || !Double.isFinite(range.min) || !Double.isFinite(range.max) || range.min > range.max) {
            throw new IllegalArgumentException(key + " range is invalid");
        }
        if (metadata.randomValues.contains(key)) return metadata.randomValues.getDouble(key);
        double value = range.min == range.max ? range.min : ThreadLocalRandom.current().nextDouble(range.min, range.max);
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
        putNullable(origin, "variant_id", metadata.variantId);
        origin.putLong("config_revision", metadata.revision);
        origin.put("originals", metadata.originals.copy());
        origin.put("random_values", metadata.randomValues.copy());
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
        private String variantId = "";
        private long revision = -1;
        private NbtCompound originals = new NbtCompound();
        private NbtCompound randomValues = new NbtCompound();

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
            copy.variantId = variantId;
            copy.revision = revision;
            copy.originals = originals.copy();
            copy.randomValues = randomValues.copy();
            return copy;
        }

        private static Metadata read(ItemStack stack) {
            Metadata metadata = new Metadata();
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
            metadata.variantId = origin.getString("variant_id");
            metadata.revision = origin.getLong("config_revision");
            if (origin.contains("originals", NbtElement.COMPOUND_TYPE)) metadata.originals = origin.getCompound("originals").copy();
            if (origin.contains("random_values", NbtElement.COMPOUND_TYPE)) metadata.randomValues = origin.getCompound("random_values").copy();
            return metadata;
        }

        private static String nullable(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
