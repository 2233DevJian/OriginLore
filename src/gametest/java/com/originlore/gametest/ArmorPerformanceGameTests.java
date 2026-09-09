package com.originlore.gametest;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.AttributeRule;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.NumberRange;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.config.PresetLibrary;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class ArmorPerformanceGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerArmorRetainsNativeStatsAndIndependentSlotBonuses(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ItemEntry chest = refined(Items.NETHERITE_CHESTPLATE, 1);
        ItemEntry legs = refined(Items.NETHERITE_LEGGINGS, 2);
        ItemComponentManager manager = new ItemComponentManager(config);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        try {
            config.setItemConfig(chest.itemId, chest);
            config.setItemConfig(legs.itemId, legs);
            check(config.save().success(), "player armor configuration save failed");
            player.equipStack(EquipmentSlot.CHEST, produce(context, manager, chest));
            player.equipStack(EquipmentSlot.LEGS, produce(context, manager, legs));
            player.playerTick();
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 17, "two armor quality bonuses did not add");
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS), 6, "native toughness was replaced");
            close(player.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0.2, "native knockback resistance was replaced");
            player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.playerTick();
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 8, "removing chest armor removed the leggings bonus");
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS), 3, "remaining leggings lost their toughness");
            close(player.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0.1, "remaining leggings lost knockback resistance");
            player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.playerTick();
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 0, "unequipping armor left a quality modifier behind");
            chest.sources.getFirst().variants.getFirst().rule.attributes.getFirst().id = "originlore:preset_quality_armor";
            legs.sources.getFirst().variants.getFirst().rule.attributes.getFirst().id = "originlore:preset_quality_armor";
            config.setItemConfig(chest.itemId, chest);
            config.setItemConfig(legs.itemId, legs);
            check(config.save().success(), "legacy player armor configuration save failed");
            player.equipStack(EquipmentSlot.CHEST, produce(context, manager, chest));
            player.equipStack(EquipmentSlot.LEGS, produce(context, manager, legs));
            player.playerTick();
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 17, "legacy shared IDs did not receive independent effective slots");
            player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.playerTick();
            close(player.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 8, "legacy chest removal removed the leggings bonus");
        } finally {
            player.networkHandler.disconnect(Text.literal("OriginLore armor test complete"));
            check(config.replaceSnapshot(original, config.getRevision()).success(), "player armor configuration restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void allHorseArmorPreservesNativeProtectionAndChangesRealDamage(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ItemComponentManager manager = new ItemComponentManager(config);
        List<Item> items = List.of(Items.LEATHER_HORSE_ARMOR, Items.IRON_HORSE_ARMOR,
                Items.GOLDEN_HORSE_ARMOR, Items.DIAMOND_HORSE_ARMOR);
        int[] protection = {3, 5, 7, 11};
        double[] nativeHitDamage = {3.84, 3.52, 3.2, 2.496};
        double[] qualityHitDamage = {3.52, 3.2, 2.88, 2.176};
        ZombieEntity attacker = context.spawnEntity(EntityType.ZOMBIE, new BlockPos(2, 1, 1));
        attacker.setAiDisabled(true);
        attacker.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        DamageSource melee = context.getWorld().getDamageSources().mobAttack(attacker);
        try {
            check(!melee.isIn(DamageTypeTags.BYPASSES_ARMOR), "horse armor test requires physical damage that armor reduces");
            for (int index = 0; index < items.size(); index++) {
                Item item = items.get(index);
                ItemEntry entry = refined(item, 2);
                config.setItemConfig(entry.itemId, entry);
                check(config.save().success(), "horse armor configuration save failed");
                HorseEntity horse = context.spawnEntity(EntityType.HORSE, new BlockPos(1, 1, 1));
                horse.setAiDisabled(true);
                try {
                    horse.equipBodyArmor(new ItemStack(item));
                    horse.tick();
                    close(horse.getAttributeValue(EntityAttributes.GENERIC_ARMOR), protection[index], "unexpected vanilla horse armor");
                    float health = horse.getHealth();
                    check(horse.damage(melee, 4), entry.itemId + ": native horse armor damage was rejected");
                    float nativeDamage = health - horse.getHealth();
                    close(nativeDamage, nativeHitDamage[index], entry.itemId + ": unexpected native health damage");
                    horse.setHealth(health);
                    ItemStack quality = produce(context, manager, entry);
                    check(!quality.isDamageable(), "horse armor gained durability");
                    horse.equipBodyArmor(quality);
                    horse.tick();
                    close(horse.getAttributeValue(EntityAttributes.GENERIC_ARMOR), protection[index] + 2, "horse armor lost native protection");
                    close(horse.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS), item == Items.DIAMOND_HORSE_ARMOR ? 2 : 0,
                            "horse armor lost native toughness");
                    horse.timeUntilRegen = 0;
                    float beforeQualityHit = horse.getHealth();
                    check(horse.damage(melee, 4), entry.itemId + ": quality horse armor damage was rejected");
                    float qualityDamage = beforeQualityHit - horse.getHealth();
                    close(qualityDamage, qualityHitDamage[index], entry.itemId + ": unexpected quality health damage; native=" + nativeDamage);
                    check(qualityDamage < nativeDamage, entry.itemId + ": horse quality did not reduce damage; native="
                            + nativeDamage + ", quality=" + qualityDamage + ", armor=" + horse.getArmor());
                    horse.equipBodyArmor(ItemStack.EMPTY);
                    horse.tick();
                    close(horse.getAttributeValue(EntityAttributes.GENERIC_ARMOR), 0, "horse retained an unequipped armor modifier");
                } finally {
                    horse.discard();
                }
            }
        } finally {
            attacker.discard();
            check(config.replaceSnapshot(original, config.getRevision()).success(), "horse armor configuration restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void wolfQualityDurabilityAbsorbsDamageThroughTheNativeArmorPath(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry entry = refined(Items.WOLF_ARMOR, 0);
        Variant variant = entry.sources.getFirst().variants.getFirst();
        check(variant.rule.attributes == null, "wolf quality should not rely on generic armor modifiers");
        variant.rule.maxDamageRange = new int[]{128, 128};
        config.setItemConfig(entry.itemId, entry);
        ItemStack quality = produce(context, new ItemComponentManager(config), entry);
        WolfEntity wolf = context.spawnEntity(EntityType.WOLF, new BlockPos(1, 1, 1));
        wolf.setAiDisabled(true);
        try {
            wolf.equipBodyArmor(quality);
            float health = wolf.getHealth();
            check(quality.getMaxDamage() == 128, "wolf quality durability was not applied");
            check(wolf.damage(context.getWorld().getDamageSources().generic(), 20), "wolf armor damage was rejected");
            close(wolf.getHealth(), health, "wolf armor did not absorb the hit");
            check(wolf.getBodyArmor().getDamage() == 20 && wolf.getBodyArmor().getMaxDamage() == 128,
                    "wolf armor did not spend its quality durability");
        } finally {
            wolf.discard();
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void renamedPresetArmorModifierKeepsItsLegacyRandomPosition(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry entry = refined(Items.NETHERITE_CHESTPLATE, 1);
        AttributeRule attribute = entry.sources.getFirst().variants.getFirst().rule.attributes.getFirst();
        String newId = attribute.id;
        attribute.id = "originlore:preset_quality_armor";
        attribute.amountRange = new NumberRange(1, 2);
        config.setItemConfig(entry.itemId, entry);
        check(config.save().success(), "legacy armor configuration save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack stack = produce(context, manager, entry);
        String oldKey = "attribute.originlore:preset_quality_armor.chest";
        String newKey = "attribute." + newId + ".chest";
        NbtCompound data = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        NbtCompound metadata = data.getCompound(ItemComponentManager.METADATA_KEY);
        metadata.getCompound("random_positions").putDouble(oldKey, 0.4);
        metadata.getCompound("random_values").putDouble(oldKey, 1.4);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        attribute.id = newId;
        attribute.amountRange = new NumberRange(2, 4);
        config.setItemConfig(entry.itemId, entry);
        check(config.save().success(), "renamed armor configuration save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "renamed armor refresh failed");
        metadata = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt().getCompound(ItemComponentManager.METADATA_KEY);
        close(metadata.getCompound("random_positions").getDouble(oldKey), 0.4, "legacy position was removed");
        close(metadata.getCompound("random_positions").getDouble(newKey), 0.4, "renamed modifier rerolled its position");
        double amount = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .filter(modifier -> modifier.modifier().id().equals(Identifier.of(newId)))
                .findFirst().orElseThrow().modifier().value();
        close(amount, 2.8, "legacy armor position was not mapped into the new range");
        context.complete();
    }

    private static ItemEntry refined(Item item, double bonus) {
        String itemId = Registries.ITEM.getId(item).toString();
        ItemEntry original = PresetLibrary.load("vanilla_zh_cn").items().get(itemId);
        SourceRule source = original.sources.stream().filter(rule -> rule.variants.stream()
                .anyMatch(variant -> variant.id.equals("refined"))).findFirst().orElseThrow().copy();
        Variant variant = source.variants.stream().filter(value -> value.id.equals("refined")).findFirst().orElseThrow().copy();
        variant.weight = 1;
        if (variant.rule.attributes != null) {
            for (AttributeRule attribute : variant.rule.attributes) attribute.amountRange = new NumberRange(bonus, bonus);
        }
        source.variants = List.of(variant);
        ItemEntry result = new ItemEntry(itemId);
        result.base = original.base.copy();
        result.sources = List.of(source);
        return result;
    }

    private static ItemStack produce(TestContext context, ItemComponentManager manager, ItemEntry entry) {
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of(entry.itemId)));
        check(manager.applyComponents(stack, new SourceContext(SourceType.valueOf(entry.sources.getFirst().type)),
                context.getWorld().getRegistryManager()).success(), "armor quality application failed: " + entry.itemId);
        return stack;
    }

    private static ItemComponentConfig config() {
        return new ItemComponentConfig(Path.of("build", "gametest", "originlore-configs", UUID.randomUUID() + ".json"));
    }

    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 0.00001, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
