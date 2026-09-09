package com.originlore.screen;

import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.NumberRange;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Consumer;

import static com.originlore.screen.NumericSettingsScreen.Setting.decimal;
import static com.originlore.screen.NumericSettingsScreen.Setting.integer;

final class RuleNumericSettings {
    private RuleNumericSettings() { }

    static Screen equipment(Screen parent, String itemId, ComponentRule rule, Consumer<ComponentRule> onApply) {
        ComponentRule copy = rule.copy();
        ItemStack original = vanillaStack(itemId);
        Integer vanillaStack = original == null ? null : original.getMaxCount();
        Integer vanillaDamage = original == null || original.getMaxDamage() <= 0 ? null : original.getMaxDamage();
        double vanillaAttack = vanillaAttackTotal(original);
        NumberRange attackInitial = copy.attackDamage != null ? copy.attackDamage
                : copy.attackDamageRange == null ? null
                : new NumberRange(vanillaAttack + copy.attackDamageRange.min, vanillaAttack + copy.attackDamageRange.max);
        return new NumericSettingsScreen(parent, "originlore.number.equipment", List.of(
                integer("originlore.number.stack", copy.maxStackSize, copy.maxStackSizeRange, 1, 99, value -> {
                    copy.maxStackSize = value.integer();
                    copy.maxStackSizeRange = value.integers();
                }).withDefault(vanillaStack),
                integer("originlore.number.durability", copy.maxDamage, copy.maxDamageRange, 1, Integer.MAX_VALUE, value -> {
                    copy.maxDamage = value.integer();
                    copy.maxDamageRange = value.integers();
                }).withDefault(vanillaDamage),
                decimal("originlore.number.attack", null, attackInitial, 0, 2048,
                        value -> {
                            copy.attackDamage = value.asRange();
                            copy.attackDamageRange = null;
                        }).withDefault(vanillaAttack),
                decimal("originlore.number.projectile", null, copy.projectileDamageMultiplier, 0, Double.MAX_VALUE,
                        value -> copy.projectileDamageMultiplier = value.asRange()).withDefault(1)
        ), () -> onApply.accept(copy));
    }

    private static ItemStack vanillaStack(String itemId) {
        Identifier id = Identifier.tryParse(itemId == null ? "" : itemId);
        return id == null || !Registries.ITEM.containsId(id) ? null : new ItemStack(Registries.ITEM.get(id));
    }

    private static double vanillaAttackTotal(ItemStack original) {
        if (original == null) return 1;
        EntityAttributeInstance attack = new EntityAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE, ignored -> { });
        attack.setBaseValue(1.0);
        for (AttributeModifiersComponent.Entry entry : original
                .getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT).modifiers()) {
            if (entry.attribute().equals(EntityAttributes.GENERIC_ATTACK_DAMAGE) && entry.slot().matches(EquipmentSlot.MAINHAND)) {
                attack.addTemporaryModifier(entry.modifier());
            }
        }
        double value = attack.getValue();
        return value;
    }

    static Screen food(Screen parent, String itemId, ComponentRule rule, Consumer<ComponentRule> onApply) {
        ComponentRule copy = rule.copy();
        FoodRule food = copy.food == null ? new FoodRule() : copy.food;
        ItemStack original = vanillaStack(itemId);
        FoodComponent vanilla = original == null ? null : original.get(DataComponentTypes.FOOD);
        return new NumericSettingsScreen(parent, "originlore.number.food", List.of(
                integer("originlore.number.nutrition", food.nutrition, food.nutritionRange, 0, Integer.MAX_VALUE, value -> {
                    food.nutrition = value.integer();
                    food.nutritionRange = value.integers();
                }).withDefault(vanilla == null ? null : vanilla.nutrition()),
                decimal("originlore.number.saturation", food.saturation, food.saturationRange, 0, Float.MAX_VALUE, value -> {
                    food.saturation = value.decimal();
                    food.saturationRange = value.range();
                }).withDefault(vanilla == null ? null : vanilla.saturation()),
                decimal("originlore.number.eat_seconds", food.eatSeconds, food.eatSecondsRange,
                        Float.MIN_VALUE, Integer.MAX_VALUE / 20.0, value -> {
                    food.eatSeconds = value.decimal();
                    food.eatSecondsRange = value.range();
                }).withDefault(vanilla == null ? null : vanilla.eatSeconds())
        ), () -> {
            copy.food = food.nutrition == null && food.nutritionRange == null && food.saturation == null
                    && food.saturationRange == null && food.eatSeconds == null && food.eatSecondsRange == null
                    && food.effects == null && food.canAlwaysEat == null && food.appendEffects == null ? null : food;
            onApply.accept(copy);
        });
    }
}
