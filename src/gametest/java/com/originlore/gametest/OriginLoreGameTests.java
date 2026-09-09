package com.originlore.gametest;

import com.google.gson.JsonPrimitive;
import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.AttributeRule;
import com.originlore.config.ItemComponentConfig.EffectRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.ToolRule;
import com.originlore.config.ItemComponentConfig.ToolRuleEntry;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.config.PresetLibrary;
import com.originlore.config.PresetMerger;
import com.originlore.network.RegistryCatalog;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RepairItemRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OriginLoreGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void processingOnlyEffectsRefreshAndRevertWithoutAccumulating(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry bread = new ItemEntry("minecraft:bread");
        SourceRule crafting = new SourceRule("CRAFTING");
        crafting.processing = new ItemComponentConfig.ProcessingRule();
        crafting.processing.riskRetention = 0.5;
        EffectRule effect = new EffectRule();
        effect.id = "minecraft:hunger";
        effect.duration = 100;
        effect.probability = 1;
        crafting.processing.effects = List.of(effect);
        bread.sources.add(crafting);
        config.setItemConfig(bread.itemId, bread);
        check(config.save().success(), "processing save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack stack = new ItemStack(Items.BREAD);
        FoodComponent original = stack.get(DataComponentTypes.FOOD);
        check(manager.applyProducedComponents(stack, new SourceContext(SourceType.CRAFTING),
                context.getWorld().getRegistryManager(), 0.5, 0.8).success(), "processing apply failed");
        for (int update = 0; update < 3; update++) {
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            check(food.effects().size() == original.effects().size() + 1, "residual effects accumulated on refresh");
            check(Math.abs(food.effects().getLast().probability() - 0.4) < 0.0001, "residual risk was not retained");
            check(config.save().success(), "processing refresh save failed");
            check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "processing refresh failed");
        }
        crafting.processing = null;
        config.setItemConfig(bread.itemId, bread);
        check(config.save().success(), "processing removal save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "processing removal refresh failed");
        check(original.equals(stack.get(DataComponentTypes.FOOD)), "removing processing did not restore native food");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void relativeRollAndWearSurviveRangeAndVariantRemoval(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.lore = List.of("base");
        SourceRule crafting = new SourceRule("CRAFTING");
        Variant handmade = new Variant("handmade", 1);
        handmade.rule.maxDamageRange = new int[]{150, 200};
        crafting.variants.add(handmade);
        sword.sources.add(crafting);
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        check(manager.applyComponents(stack, new SourceContext(SourceType.CRAFTING), context.getWorld().getRegistryManager()).success(), "initial apply failed");
        NbtCompound root = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        NbtCompound identity = root.getCompound(ItemComponentManager.METADATA_KEY);
        identity.getCompound("random_positions").putDouble("max_damage", 0.4);
        identity.getCompound("random_values").putInt("max_damage", 170);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        stack.set(DataComponentTypes.MAX_DAMAGE, 170);
        stack.setDamage(85);

        handmade.rule.maxDamageRange = new int[]{200, 300};
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "range save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "range refresh failed");
        check(stack.getMaxDamage() == 240 && stack.getDamage() == 120, "170 did not map to 240 at half durability");
        handmade.rule.maxDamageRange = new int[]{80, 130};
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "lower range save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "lower range refresh failed");
        check(stack.getMaxDamage() == 100 && stack.getDamage() == 50, "lower maximum lost remaining ratio");

        crafting.variants.clear();
        crafting.variants.add(new Variant("replacement", 100));
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "variant removal save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "variant removal refresh failed");
        check(stack.getMaxDamage() == 250 && stack.getDamage() == 125, "removing durability did not restore half of vanilla maximum");
        check(originMetadata(stack).getString("variant_id").equals("handmade"), "deleted variant was rerolled");
        check(originMetadata(stack).getCompound("random_positions").getDouble("max_damage") == 0.4, "random position was discarded");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void maintenanceAndUpgradePreserveIdentityAndExplicitDamage(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry diamond = new ItemEntry("minecraft:diamond_sword");
        SourceRule crafting = new SourceRule("CRAFTING");
        Variant handmade = new Variant("handmade", 1);
        handmade.rule.maxDamage = 1000;
        handmade.rule.itemName = "Quality sword";
        crafting.variants.add(handmade);
        diamond.sources.add(crafting);
        ItemEntry netherite = new ItemEntry("minecraft:netherite_sword");
        SourceRule smithing = new SourceRule("SMITHING");
        Variant upgradedQuality = new Variant("handmade", 1);
        upgradedQuality.rule.maxDamage = 1600;
        smithing.variants.add(upgradedQuality);
        netherite.sources.add(smithing);
        config.setItemConfig(diamond.itemId, diamond);
        config.setItemConfig(netherite.itemId, netherite);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack original = new ItemStack(Items.DIAMOND_SWORD);
        check(manager.applyComponents(original, new SourceContext(SourceType.CRAFTING), context.getWorld().getRegistryManager()).success(), "initial apply failed");
        original.setDamage(250);
        ItemStack repaired = original.copy();
        repaired.setDamage(100);
        repaired.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Player's sword"));
        check(manager.applyInheritedComponents(repaired, original, new SourceContext(SourceType.SMITHING), context.getWorld().getRegistryManager()).success(), "repair failed");
        check(repaired.getDamage() == 100, "repair damage was remapped twice");
        check(repaired.getName().getString().equals("Player's sword"), "anvil name was replaced");

        ItemStack upgraded = original.copyComponentsToNewStack(Items.NETHERITE_SWORD, 1);
        check(manager.applyInheritedComponents(upgraded, original, new SourceContext(SourceType.SMITHING), context.getWorld().getRegistryManager()).success(), "upgrade failed");
        check(upgraded.getMaxDamage() == 1600 && upgraded.getDamage() == 400, "upgrade did not use primary item's remaining ratio");
        check(originMetadata(upgraded).getString("variant_id").equals("handmade"), "upgrade lost quality");
        check(originMetadata(upgraded).getCompound("random_positions").equals(originMetadata(original).getCompound("random_positions")), "upgrade changed random positions");

        upgradedQuality.rule.currentDamage = 17;
        config.setItemConfig(netherite.itemId, netherite);
        check(config.save().success(), "explicit damage save failed");
        ItemStack explicit = original.copyComponentsToNewStack(Items.NETHERITE_SWORD, 1);
        check(manager.applyInheritedComponents(explicit, original, new SourceContext(SourceType.SMITHING), context.getWorld().getRegistryManager()).success(), "explicit upgrade failed");
        check(explicit.getDamage() == 17, "explicit administrator damage was overwritten");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void legacyRandomValuesMigrateOnlyOnce(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.maxDamageRange = new int[]{150, 200};
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "initial apply failed");
        NbtCompound root = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        NbtCompound identity = root.getCompound(ItemComponentManager.METADATA_KEY);
        identity.putInt("metadata_version", 2);
        identity.remove("random_positions");
        identity.getCompound("random_values").putInt("max_damage", 170);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        stack.set(DataComponentTypes.MAX_DAMAGE, 170);
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "legacy migration failed");
        check(originMetadata(stack).getCompound("random_positions").getDouble("max_damage") == 0.4, "legacy position was not recovered");
        sword.base.maxDamageRange = new int[]{200, 300};
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "new range save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "new range refresh failed");
        check(stack.getMaxDamage() == 240, "legacy position was inferred again after migration");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void repairMaximumDoesNotReplacePrimaryNativeDurability(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.lore = List.of("base");
        SourceRule crafting = new SourceRule("CRAFTING");
        Variant handmade = new Variant("handmade", 1);
        handmade.rule.maxDamage = 100;
        crafting.variants.add(handmade);
        sword.sources.add(crafting);
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "repair config save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack first = new ItemStack(Items.IRON_SWORD);
        check(manager.applyComponents(first, new SourceContext(SourceType.CRAFTING),
                context.getWorld().getRegistryManager()).success(), "primary quality failed");
        first.setDamage(75);
        ItemStack second = new ItemStack(Items.IRON_SWORD);
        second.set(DataComponentTypes.MAX_DAMAGE, 200);
        second.setDamage(150);
        ItemStack repaired = new RepairItemRecipe(CraftingRecipeCategory.MISC).craft(
                CraftingRecipeInput.create(2, 1, List.of(first, second)), context.getWorld().getRegistryManager());
        check(repaired.getMaxDamage() == 200 && repaired.getDamage() == 115,
                "vanilla repair did not produce its combined durability");
        check(manager.applyInheritedComponents(repaired, first, new SourceContext(SourceType.CRAFTING),
                context.getWorld().getRegistryManager()).success(), "repair inheritance failed");
        check(repaired.getMaxDamage() == 100 && repaired.getDamage() == 57,
                "repair did not retain primary quality and combined remaining ratio");
        check(originMetadata(repaired).getCompound("originals").getCompound("minecraft:max_damage")
                .getString("patch_state").equals("default"), "secondary maximum replaced the primary native backup");
        crafting.variants.clear();
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "repair variant removal save failed");
        check(manager.refresh(repaired, context.getWorld().getRegistryManager()).success(), "repair removal refresh failed");
        check(repaired.getMaxDamage() == 250 && repaired.getDamage() == 142,
                "repair quality removal failed to restore primary native durability and remaining ratio");
        check(originMetadata(repaired).getString("variant_id").equals("handmade"), "repair removal discarded identity");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void smithingScreenInheritsOnceAndRetainsRecipeSpecificQuality(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot originalConfig = config.snapshot();
        ServerPlayerEntity player = null;
        try {
            ItemEntry diamond = new ItemEntry("minecraft:diamond_sword");
            SourceRule crafting = new SourceRule("CRAFTING");
            Variant handmade = new Variant("handmade", 1);
            handmade.rule.maxDamage = 1000;
            handmade.rule.attackDamageRange = new ItemComponentConfig.NumberRange(-2, -2);
            crafting.variants.add(handmade);
            diamond.sources.add(crafting);
            ItemEntry netherite = new ItemEntry("minecraft:netherite_sword");
            netherite.base.lore = List.of("base");
            SourceRule smithing = new SourceRule("SMITHING");
            smithing.recipeId = "minecraft:netherite_sword_smithing";
            Variant upgradedQuality = new Variant("handmade", 1);
            upgradedQuality.rule.maxDamage = 1600;
            upgradedQuality.rule.attackDamageRange = new ItemComponentConfig.NumberRange(1, 1);
            smithing.variants.add(upgradedQuality);
            netherite.sources.add(smithing);
            config.setItemConfig(diamond.itemId, diamond);
            config.setItemConfig(netherite.itemId, netherite);
            check(config.save().success(), "smithing screen config save failed");
            ItemComponentManager manager = Originlore.getManager();
            ItemStack primary = new ItemStack(Items.DIAMOND_SWORD);
            primary.addEnchantment(context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT)
                    .getEntry(Identifier.ofVanilla("unbreaking")).orElseThrow(), 1);
            check(manager.applyComponents(primary, new SourceContext(SourceType.CRAFTING),
                    context.getWorld().getRegistryManager()).success(), "smithing primary quality failed");
            primary.setDamage(250);
            player = context.createMockCreativeServerPlayerInWorld();
            SmithingScreenHandler handler = new SmithingScreenHandler(49, player.getInventory(),
                    ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
            handler.getSlot(0).setStack(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            handler.getSlot(1).setStack(primary);
            handler.getSlot(2).setStack(new ItemStack(Items.NETHERITE_INGOT));
            ItemStack upgraded = handler.getSlot(3).getStack().copy();
            check(upgraded.isOf(Items.NETHERITE_SWORD) && upgraded.getMaxDamage() == 1600
                    && upgraded.getDamage() == 400, "smithing screen lost the recipe-specific quality or wear ratio");
            check(mainhandAttackDamage(upgraded) == 9, "smithing screen lost native netherite attack modifiers");
            check(upgraded.get(DataComponentTypes.ENCHANTMENTS).equals(primary.get(DataComponentTypes.ENCHANTMENTS)),
                    "smithing screen lost native enchantments");
            check(originMetadata(upgraded).getString("recipe_id").equals(smithing.recipeId), "smithing recipe identity was not recorded");
            check(originMetadata(upgraded).getCompound("random_positions").equals(originMetadata(primary).getCompound("random_positions")),
                    "smithing screen changed primary random positions");
            smithing.variants.clear();
            config.setItemConfig(netherite.itemId, netherite);
            check(config.save().success(), "smithing variant removal save failed");
            check(manager.refresh(upgraded, context.getWorld().getRegistryManager()).success(), "smithing variant removal failed");
            check(upgraded.getMaxDamage() == 2031 && upgraded.getDamage() == 508,
                    "smithing quality was backed up twice instead of restoring native netherite durability");
            check(mainhandAttackDamage(upgraded) == 8, "smithing quality removal left an attack modifier behind");
            check(originMetadata(upgraded).getString("variant_id").equals("handmade"), "smithing removal discarded quality identity");
        } finally {
            if (player != null) player.networkHandler.disconnect(Text.literal("OriginLore GameTest complete"));
            check(config.replaceSnapshot(originalConfig, config.getRevision()).success(), "smithing screen config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void toolRangesKeepNativeHarvestRules(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry pickaxe = new ItemEntry("minecraft:iron_pickaxe");
        pickaxe.base.tool = new ToolRule();
        pickaxe.base.tool.miningSpeedMultiplier = new ItemComponentConfig.NumberRange(0.5, 0.5);
        config.setItemConfig(pickaxe.itemId, pickaxe);
        check(config.save().success(), "tool save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack original = new ItemStack(Items.IRON_PICKAXE);
        for (int application = 0; application < 3; application++) {
            ItemStack tuned = original.copy();
            check(manager.refresh(tuned, context.getWorld().getRegistryManager()).success(), "tool apply failed");
            check(config.save().success(), "tool refresh save failed");
            check(manager.refresh(tuned, context.getWorld().getRegistryManager()).success(), "tool refresh failed");
            for (var block : List.of(Blocks.STONE, Blocks.DIAMOND_ORE, Blocks.OBSIDIAN, Blocks.DIRT)) {
                check(original.isSuitableFor(block.getDefaultState()) == tuned.isSuitableFor(block.getDefaultState()), "tool changed native harvest level");
                check(Math.abs(tuned.getMiningSpeedMultiplier(block.getDefaultState()) - original.getMiningSpeedMultiplier(block.getDefaultState()) * 0.5) < 0.0001,
                        "tool multiplier drifted for " + block + " after application " + application);
            }
            check(tuned.getMaxDamage() == original.getMaxDamage(), "speed tuning changed durability");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void reversibleComponentsAndForeignData(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry stone = new ItemEntry("minecraft:stone");
        stone.base.maxStackSize = 16;
        config.setItemConfig(stone.itemId, stone);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);

        ItemStack stack = new ItemStack(Items.STONE);
        NbtCompound foreign = new NbtCompound();
        foreign.putString("other_mod", "keep-me");
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(foreign));
        check(manager.applyComponents(stack, SourceContext.command(), context.getWorld().getRegistryManager()).success(),
                "initial apply failed");
        check(stack.getMaxCount() == 16, "configured max stack was not applied");
        NbtCompound metadata = originMetadata(stack);
        check(metadata.getString("source_type").equals("COMMAND"), "command source was not persisted");
        check(metadata.getString("managed_item_id").equals("minecraft:stone"), "managed item id was not persisted");

        stone.base.maxStackSize = null;
        config.setItemConfig(stone.itemId, stone);
        check(config.save().success(), "field-removal save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "field-removal refresh failed");
        check(stack.getMaxCount() == 64, "removed field did not restore its original value");
        check(stack.getComponentChanges().get(DataComponentTypes.MAX_STACK_SIZE) == null,
                "removed field restored the default value as an explicit patch");

        config.removeItem(stone.itemId);
        check(config.save().success(), "rule-removal save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "rule-removal refresh failed");
        NbtCompound remaining = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        check(remaining.getString("other_mod").equals("keep-me"), "foreign custom_data was lost");
        check(!remaining.contains(ItemComponentManager.METADATA_KEY), "OriginLore metadata was not removed");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void stableVariantAcrossRefreshAndCopy(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry apple = new ItemEntry("minecraft:apple");
        SourceRule chest = new SourceRule("CHEST_LOOT");
        Variant fresh = new Variant("fresh", 1);
        fresh.rule.maxStackSize = 8;
        Variant old = new Variant("old", 1);
        old.rule.maxStackSize = 4;
        chest.variants.add(fresh);
        chest.variants.add(old);
        apple.sources.add(chest);
        config.setItemConfig(apple.itemId, apple);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);
        SourceContext source = SourceContext.loot(SourceType.CHEST_LOOT,
                Identifier.ofVanilla("chests/simple_dungeon"));

        ItemStack stack = new ItemStack(Items.APPLE);
        check(manager.applyComponents(stack, source, context.getWorld().getRegistryManager()).success(),
                "variant apply failed");
        String selected = originMetadata(stack).getString("variant_id");
        check(selected.equals("fresh") || selected.equals("old"), "no valid variant was selected");

        (selected.equals("fresh") ? fresh : old).rule.maxStackSize = 2;
        config.setItemConfig(apple.itemId, apple);
        check(config.save().success(), "hot save failed");
        check(manager.refresh(stack, context.getWorld().getRegistryManager()).success(), "hot refresh failed");
        check(originMetadata(stack).getString("variant_id").equals(selected), "hot refresh rerolled the variant");
        check(stack.getMaxCount() == 2, "hot refresh did not apply the selected variant's new rule");

        ItemStack copy = stack.copy();
        check(manager.refresh(copy, context.getWorld().getRegistryManager()).success(), "copied stack refresh failed");
        check(originMetadata(copy).getString("variant_id").equals(selected), "copy lost its variant identity");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crossItemTransformationUsesPatchSemantics(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.maxDamage = 500;
        config.setItemConfig(sword.itemId, sword);
        check(config.save().success(), "initial save failed");
        ItemComponentManager manager = new ItemComponentManager(config);

        ItemStack defaultSword = new ItemStack(Items.IRON_SWORD);
        check(manager.applyComponents(defaultSword, SourceContext.command(), context.getWorld().getRegistryManager()).success(),
                "default sword apply failed");
        ItemStack upgradedDefault = defaultSword.copyComponentsToNewStack(Items.NETHERITE_SWORD, 1);
        check(manager.applyComponents(upgradedDefault, new SourceContext(SourceType.SMITHING),
                context.getWorld().getRegistryManager()).success(), "default sword upgrade failed");
        check(upgradedDefault.getMaxDamage() == new ItemStack(Items.NETHERITE_SWORD).getMaxDamage(),
                "iron sword default durability leaked into upgraded sword");
        check(upgradedDefault.getComponentChanges().get(DataComponentTypes.MAX_DAMAGE) == null,
                "default durability became an explicit patch after upgrade");
        check(!hasOriginMetadata(upgradedDefault), "unconfigured upgraded item stayed managed");

        ItemStack patchedSword = new ItemStack(Items.IRON_SWORD);
        patchedSword.set(DataComponentTypes.MAX_DAMAGE, 300);
        check(manager.applyComponents(patchedSword, SourceContext.command(), context.getWorld().getRegistryManager()).success(),
                "patched sword apply failed");
        ItemStack upgradedPatched = patchedSword.copyComponentsToNewStack(Items.NETHERITE_SWORD, 1);
        check(manager.applyComponents(upgradedPatched, new SourceContext(SourceType.SMITHING),
                context.getWorld().getRegistryManager()).success(), "patched sword upgrade failed");
        check(upgradedPatched.getMaxDamage() == 300, "explicit foreign durability patch was not preserved");
        check(upgradedPatched.getComponentChanges().get(DataComponentTypes.MAX_DAMAGE) != null
                        && upgradedPatched.getComponentChanges().get(DataComponentTypes.MAX_DAMAGE).orElseThrow() == 300,
                "explicit foreign durability patch lost its patch identity");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void codecValidationRejectsInvalidAdvancedValue(TestContext context) {
        ItemComponentConfig config = config();
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemEntry stone = new ItemEntry("minecraft:stone");
        stone.base.setComponents = new LinkedHashMap<>();
        stone.base.setComponents.put("minecraft:max_stack_size", new JsonPrimitive("not-an-integer"));
        ConfigSnapshot snapshot = new ConfigSnapshot(0, Map.of(stone.itemId, stone));

        List<String> errors = manager.validateConfiguration(snapshot, context.getWorld().getRegistryManager());
        check(!errors.isEmpty(), "invalid advanced component unexpectedly passed Codec validation");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void validationChecksEveryRandomRangeEndpoint(TestContext context) {
        ItemComponentConfig config = config();
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemEntry sword = new ItemEntry("minecraft:iron_sword");
        sword.base.maxStackSizeRange = new int[]{1, 64};
        ConfigSnapshot snapshot = new ConfigSnapshot(0, Map.of(sword.itemId, sword));

        List<String> errors = manager.validateConfiguration(snapshot, context.getWorld().getRegistryManager());
        check(!errors.isEmpty(), "range with an invalid endpoint unexpectedly passed validation");
        check(errors.getFirst().startsWith("minecraft:iron_sword.base:"),
                "range validation did not identify the failing rule: " + errors);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void identityApplicationFailureDoesNotMutateTarget(TestContext context) {
        ItemComponentConfig config = config();
        check(config.load().success(), "initial load failed");

        ItemEntry valid = new ItemEntry("minecraft:apple");
        valid.base.lore = List.of("managed");
        check(config.replaceSnapshot(new ConfigSnapshot(config.getRevision(), Map.of(valid.itemId, valid)),
                config.getRevision()).success(), "valid config save failed");

        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack identity = new ItemStack(Items.APPLE);
        check(manager.applyComponents(identity, SourceContext.command(),
                context.getWorld().getRegistryManager()).success(), "identity setup failed");

        ItemEntry invalid = new ItemEntry("minecraft:apple");
        invalid.base.setComponents = new LinkedHashMap<>();
        invalid.base.setComponents.put("minecraft:max_stack_size", new JsonPrimitive("not-an-integer"));
        check(config.replaceSnapshot(new ConfigSnapshot(config.getRevision(), Map.of(invalid.itemId, invalid)),
                config.getRevision()).success(), "invalid test config save failed");

        ItemStack target = new ItemStack(Items.APPLE);
        var before = target.getComponentChanges();
        ItemComponentManager.ApplyResult result = manager.applyComponentsUsingIdentity(target,
                SourceContext.command(), identity, context.getWorld().getRegistryManager());
        check(!result.success(), "invalid identity application unexpectedly succeeded");
        check(target.getComponentChanges().equals(before), "failed identity application mutated the target");
        check(!hasOriginMetadata(target), "failed identity application leaked OriginLore metadata");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void structuredComponentsApplyThroughVanillaCodecs(TestContext context) {
        ItemComponentConfig config = config();
        ItemEntry apple = new ItemEntry("minecraft:apple");
        FoodRule food = new FoodRule();
        food.nutrition = 2;
        food.saturation = 0.25f;
        food.canAlwaysEat = true;
        food.eatSeconds = 0.8f;
        food.effects = List.of(new EffectRule("minecraft:nausea", 120, 1, 0.5f));
        apple.base.food = food;
        apple.base.enchantments = new LinkedHashMap<>(Map.of("minecraft:sharpness", 2));
        apple.base.storedEnchantments = new LinkedHashMap<>(Map.of("minecraft:unbreaking", 1));
        AttributeRule attribute = new AttributeRule();
        attribute.attribute = "minecraft:generic.attack_damage";
        attribute.id = "originlore:test_damage";
        attribute.amount = 2.5;
        attribute.operation = "add_value";
        attribute.slot = "mainhand";
        apple.base.attributes = List.of(attribute);

        ItemEntry stick = new ItemEntry("minecraft:stick");
        ToolRule tool = new ToolRule();
        tool.defaultMiningSpeed = 0.5f;
        tool.damagePerBlock = 2;
        ToolRuleEntry stoneRule = new ToolRuleEntry();
        stoneRule.blocks = List.of("minecraft:stone");
        stoneRule.speed = 8.0f;
        stoneRule.correctForDrops = true;
        tool.rules = List.of(stoneRule);
        stick.base.tool = tool;

        ConfigSnapshot snapshot = new ConfigSnapshot(0, Map.of(apple.itemId, apple, stick.itemId, stick));
        ItemComponentManager manager = new ItemComponentManager(config);
        List<String> errors = manager.validateConfiguration(snapshot, context.getWorld().getRegistryManager());
        check(errors.isEmpty(), "structured component validation failed: " + errors);
        check(config.replaceSnapshot(snapshot, config.getRevision()).success(), "structured config save failed");

        ItemStack appleStack = new ItemStack(Items.APPLE);
        check(manager.applyComponents(appleStack, SourceContext.command(),
                context.getWorld().getRegistryManager()).success(), "structured apple apply failed");
        FoodComponent appliedFood = appleStack.get(DataComponentTypes.FOOD);
        check(appliedFood != null && appliedFood.nutrition() == 2, "food nutrition was not applied");
        check(appliedFood.effects().size() == 1, "food effects were not applied");
        ItemEnchantmentsComponent enchantments = appleStack.get(DataComponentTypes.ENCHANTMENTS);
        ItemEnchantmentsComponent storedEnchantments = appleStack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        var enchantmentRegistry = context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        var sharpness = enchantmentRegistry.getEntry(Identifier.ofVanilla("sharpness")).orElseThrow();
        var unbreaking = enchantmentRegistry.getEntry(Identifier.ofVanilla("unbreaking")).orElseThrow();
        check(enchantments != null && enchantments.getLevel(sharpness) == 2, "direct enchantment was not applied");
        check(storedEnchantments != null && storedEnchantments.getLevel(unbreaking) == 1,
                "stored enchantment was not applied");
        AttributeModifiersComponent attributes = appleStack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        check(attributes != null && attributes.modifiers().size() == 1, "attribute modifier was not applied");
        check(attributes.modifiers().getFirst().modifier().value() == 2.5, "attribute amount changed");

        ItemStack stickStack = new ItemStack(Items.STICK);
        check(manager.applyComponents(stickStack, SourceContext.command(),
                context.getWorld().getRegistryManager()).success(), "tool apply failed");
        ToolComponent appliedTool = stickStack.get(DataComponentTypes.TOOL);
        check(appliedTool != null && appliedTool.damagePerBlock() == 2, "tool durability cost was not applied");
        check(appliedTool.getSpeed(Blocks.STONE.getDefaultState()) == 8.0f, "tool block speed was not applied");
        check(appliedTool.isCorrectForDrops(Blocks.STONE.getDefaultState()), "tool drop rule was not applied");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void craftingResultCanBeTakenWithoutDuplicatingInputs(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        check(config != null, "server configuration is unavailable");
        ConfigSnapshot original = config.snapshot();
        ItemEntry planks = new ItemEntry("minecraft:oak_planks");
        SourceRule crafting = new SourceRule("CRAFTING");
        crafting.rule.lore = List.of("crafted by OriginLore");
        planks.sources.add(crafting);
        config.setItemConfig(planks.itemId, planks);
        check(config.save().success(), "crafting test config save failed");

        try {
            ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
            CraftingScreenHandler handler = new CraftingScreenHandler(42, player.getInventory(),
                    ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
            // Oak planks from one log: the output is also oak planks, which exercises
            // component equality and the result-slot consume path without relying on a
            // custom recipe.
            handler.getSlot(1).setStack(new ItemStack(Items.OAK_LOG));
            ItemStack result = handler.getSlot(0).getStack();
            check(!result.isEmpty() && result.isOf(Items.OAK_PLANKS), "crafting result was not produced");
            check(!hasOriginMetadata(result) || originMetadata(result).getString("variant_id").isEmpty(),
                    "crafting preview selected a final quality");
            int inputBefore = handler.getSlot(1).getStack().getCount();

            handler.onSlotClick(0, 0, SlotActionType.PICKUP, player);

            check(handler.getSlot(1).getStack().getCount() == inputBefore - 1,
                    "taking the crafting result consumed the wrong number of inputs");
            check(handler.getCursorStack().isOf(Items.OAK_PLANKS), "picked-up crafting result is missing");
            check(handler.getCursorStack().get(DataComponentTypes.LORE) != null
                            && !handler.getCursorStack().get(DataComponentTypes.LORE).lines().isEmpty(),
                    "picked-up crafting result lost Lore");
            check(handler.getSlot(0).getStack().isEmpty(), "result slot was not cleared after pickup");
            player.networkHandler.disconnect(Text.literal("OriginLore GameTest complete"));
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "crafting test config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void generatedLootKeepsChestSourceWhenSuppliedToInventory(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        check(config != null, "server configuration is unavailable");
        ConfigSnapshot original = config.snapshot();
        ItemEntry apple = new ItemEntry("minecraft:apple");
        SourceRule chest = new SourceRule("CHEST_LOOT");
        chest.rule.lore = List.of("from a chest");
        apple.sources.add(chest);
        config.setItemConfig(apple.itemId, apple);
        check(config.save().success(), "loot test config save failed");

        try {
            LootTable table = LootTable.builder()
                    .type(LootContextTypes.CHEST)
                    .pool(LootPool.builder().with(net.minecraft.loot.entry.ItemEntry.builder(Items.APPLE)))
                    .build();
            LootContextParameterSet parameters = new LootContextParameterSet.Builder(context.getWorld())
                    .add(LootContextParameters.ORIGIN, Vec3d.ofCenter(context.getAbsolutePos(BlockPos.ORIGIN)))
                    .build(LootContextTypes.CHEST);
            SimpleInventory inventory = new SimpleInventory(9);
            table.supplyInventory(inventory, parameters, 1234L);

            ItemStack generated = ItemStack.EMPTY;
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.getStack(slot).isEmpty()) {
                    generated = inventory.getStack(slot);
                    break;
                }
            }
            check(!generated.isEmpty() && generated.isOf(Items.APPLE), "loot table did not fill inventory");
            check(generated.get(DataComponentTypes.LORE) != null
                            && !generated.get(DataComponentTypes.LORE).lines().isEmpty(),
                    "loot table output was not assigned CHEST_LOOT Lore");
            check(originMetadata(generated).getString("source_type").equals("CHEST_LOOT"),
                    "loot table output source metadata is incorrect");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "loot test config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playerInventoryInsertionAppliesUnknownFallback(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        check(config != null, "server configuration is unavailable");
        ConfigSnapshot original = config.snapshot();
        ItemEntry carrot = new ItemEntry("minecraft:carrot");
        carrot.base.lore = List.of("granted straight into the inventory");
        SourceRule unknown = new SourceRule("UNKNOWN");
        unknown.rule.customName = "Carrot Of Unknown Origin";
        carrot.sources.add(unknown);
        config.setItemConfig(carrot.itemId, carrot);
        check(config.save().success(), "inventory fallback config save failed");

        try {
            ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
            PlayerInventory inventory = player.getInventory();

            // Each overload starts from an empty inventory so a failure names the exact
            // entry point that never received the injection.
            inventory.clear();
            ItemStack loose = new ItemStack(Items.CARROT);
            check(inventory.insertStack(loose), "insertStack(ItemStack) reported failure");
            assertUnknownFallback(inventory, "insertStack(ItemStack)");

            inventory.clear();
            ItemStack slotted = new ItemStack(Items.CARROT);
            check(inventory.insertStack(3, slotted), "insertStack(int, ItemStack) reported failure");
            assertUnknownFallback(inventory, "insertStack(int, ItemStack)");

            inventory.clear();
            inventory.setStack(5, new ItemStack(Items.CARROT));
            assertUnknownFallback(inventory, "setStack(int, ItemStack)");

            player.networkHandler.disconnect(Text.literal("OriginLore GameTest complete"));
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "inventory fallback config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void furnacePausesAfterEveryOriginLoreResultAndRerolls(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        check(config != null, "server configuration is unavailable");
        ConfigSnapshot original = config.snapshot();

        ItemEntry cookedBeef = new ItemEntry("minecraft:cooked_beef");
        SourceRule smelting = new SourceRule("SMELTING");
        Variant hand = new Variant("hand", 1.0);
        hand.rule.lore = List.of("手工烤制");
        Variant burnt = new Variant("burnt", 0.0);
        burnt.rule.lore = List.of("烤焦");
        smelting.variants.add(hand);
        smelting.variants.add(burnt);
        cookedBeef.sources.add(smelting);
        config.setItemConfig(cookedBeef.itemId, cookedBeef);
        check(config.save().success(), "furnace test config save failed");

        try {
            BlockPos pos = context.getAbsolutePos(BlockPos.ORIGIN);
            context.getWorld().setBlockState(pos, Blocks.FURNACE.getDefaultState());
            FurnaceBlockEntity furnace = (FurnaceBlockEntity) context.getWorld().getBlockEntity(pos);
            check(furnace != null, "furnace block entity was not created");
            furnace.setStack(0, new ItemStack(Items.BEEF, 2));
            furnace.setStack(1, new ItemStack(Items.COAL));
            for (int tick = 0; tick < 230; tick++) {
                net.minecraft.block.entity.AbstractFurnaceBlockEntity.tick(
                        context.getWorld(), pos, context.getWorld().getBlockState(pos), furnace);
            }
            ItemStack output = furnace.getStack(2);
            check(!output.isEmpty() && output.isOf(Items.COOKED_BEEF), "furnace did not produce beef");
            check(originMetadata(output).getString("variant_id").equals("hand"),
                    "first furnace result did not use the configured variant");
            check(furnace.getStack(0).getCount() == 1,
                    "furnace did not consume exactly one input for the first result");

            int inputWhilePaused = furnace.getStack(0).getCount();
            for (int tick = 0; tick < 40; tick++) {
                net.minecraft.block.entity.AbstractFurnaceBlockEntity.tick(
                        context.getWorld(), pos, context.getWorld().getBlockState(pos), furnace);
            }
            check(furnace.getStack(0).getCount() == inputWhilePaused,
                    "furnace continued cooking while its output was present");
            furnace.removeStack(2);

            // Change the weights before the next completion. Existing output
            // keeps its identity; the next one must reroll.
            ItemEntry liveConfig = config.getItemConfig(cookedBeef.itemId);
            check(liveConfig != null && !liveConfig.sources.isEmpty(), "live furnace config disappeared");
            liveConfig.sources.getFirst().variants.get(0).weight = 0.0;
            liveConfig.sources.getFirst().variants.get(1).weight = 1.0;
            check(config.save().success(), "furnace weight update failed");
            for (int tick = 0; tick < 230; tick++) {
                net.minecraft.block.entity.AbstractFurnaceBlockEntity.tick(
                        context.getWorld(), pos, context.getWorld().getBlockState(pos), furnace);
            }
            ItemStack next = furnace.getStack(2);
            check(!next.isEmpty(), "furnace did not resume after output removal");
            check(originMetadata(next).getString("variant_id").equals("burnt"),
                    "furnace reused the previous variant instead of rerolling");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "furnace test config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void managedBookkeepingDoesNotBlockEquivalentStacking(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        check(config != null, "server configuration is unavailable");
        ConfigSnapshot original = config.snapshot();
        ItemEntry beef = new ItemEntry("minecraft:cooked_beef");
        SourceRule smelting = new SourceRule("SMELTING");
        Variant first = new Variant("same", 1.0);
        first.rule.lore = List.of("同一变体");
        smelting.variants.add(first);
        beef.sources.add(smelting);
        config.setItemConfig(beef.itemId, beef);
        check(config.save().success(), "stacking test config save failed");

        try {
            ItemStack furnace = new ItemStack(Items.COOKED_BEEF);
            ItemStack smoker = new ItemStack(Items.COOKED_BEEF);
            check(Originlore.applyCustomComponents(furnace,
                    SourceContext.recipe(SourceType.SMELTING, Identifier.ofVanilla("beef"))).success(),
                    "furnace stack setup failed");
            check(Originlore.applyCustomComponents(smoker,
                    SourceContext.recipe(SourceType.SMELTING, Identifier.of("test", "beef"))).success(),
                    "smoker stack setup failed");
            check(com.originlore.gameplay.FoodUnits.canCombine(furnace, smoker),
                    "equivalent variants from different machines did not stack");
            check(!ItemStack.areItemsAndComponentsEqual(furnace, smoker),
                    "merge compatibility incorrectly replaced exact component equality");
            NbtCompound foreignRoot = smoker.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
            foreignRoot.putString("other_mod_data", "must-remain-distinct");
            smoker.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(foreignRoot));
            check(!com.originlore.gameplay.FoodUnits.canCombine(furnace, smoker),
                    "foreign custom_data was incorrectly ignored by stack comparison");

            ItemEntry different = new ItemEntry("minecraft:cooked_beef");
            SourceRule differentSource = new SourceRule("SMELTING");
            Variant differentVariant = new Variant("different", 1.0);
            differentVariant.rule.lore = List.of("同一变体");
            differentSource.variants.add(differentVariant);
            different.sources.add(differentSource);
            config.setItemConfig(different.itemId, different);
            check(config.save().success(), "different variant config save failed");
            ItemStack other = new ItemStack(Items.COOKED_BEEF);
            check(Originlore.applyCustomComponents(other,
                    SourceContext.recipe(SourceType.SMELTING, Identifier.ofVanilla("beef"))).success(),
                    "different variant stack setup failed");
            check(!com.originlore.gameplay.FoodUnits.canCombine(furnace, other),
                    "different variant stacks with equal Lore were incorrectly merged");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "stacking test config restore failed");
        }
        context.complete();
    }

    /**
     * Refreshes the committed JUnit reference list after a Minecraft upgrade. Unit tests cannot bootstrap the
     * registry, so the authoritative id set is exported here and copied into
     * src/test/resources/reference/item_ids_1_21_1.txt by hand.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void exportsTheAuthoritativeItemIdReference(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        check(server != null, "gametest world has no server");
        List<String> itemIds = RegistryCatalog.fromServer(server).itemIds();
        check(!itemIds.isEmpty(), "item registry is empty");

        // The run directory already is build/gametest, so a bare name lands next to the server's own files.
        Path target = Path.of("item_ids_1_21_1.txt");
        try {
            Files.write(target, itemIds);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write " + target.toAbsolutePath(), exception);
        }
        context.complete();
    }

    /**
     * Preset copy says things like "60% of a vanilla iron sword" and "1~2 damage below vanilla", but every rule field
     * is an absolute number, so the percentages need the real values behind them. Exporting them beats trusting
     * memory across a thousand durabilities.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void exportsVanillaItemStatsForPresetAuthors(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        check(server != null, "gametest world has no server");
        List<String> itemIds = RegistryCatalog.fromServer(server).itemIds();

        List<String> lines = new ArrayList<>(itemIds.size());
        for (String itemId : itemIds) {
            ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.tryParse(itemId)));
            StringBuilder line = new StringBuilder(itemId);
            if (stack.getMaxDamage() > 0) line.append(" durability=").append(stack.getMaxDamage());
            double attack = mainhandAttackDamage(stack);
            if (attack > 1.0) line.append(" attack=").append(attack);
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            if (food != null) {
                line.append(" nutrition=").append(food.nutrition())
                        .append(" saturation=").append(food.saturation())
                        .append(" eatSeconds=").append(food.eatSeconds())
                        .append(" canAlwaysEat=").append(food.canAlwaysEat());
                for (FoodComponent.StatusEffectEntry effect : food.effects()) {
                    line.append(" effect=").append(Registries.STATUS_EFFECT.getId(effect.effect().getEffectType().value()))
                            .append(" amplifier=").append(effect.effect().getAmplifier())
                            .append(" duration=").append(effect.effect().getDuration())
                            .append(" probability=").append(effect.probability());
                }
            }
            lines.add(line.toString());
        }

        Path target = Path.of("item_stats_1_21_1.txt");
        try {
            Files.write(target, lines);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write " + target.toAbsolutePath(), exception);
        }
        context.complete();
    }

    /** A player swings for 1.0 without a modifier, so anything above that is the item's own contribution. */
    private static double mainhandAttackDamage(ItemStack stack) {
        AttributeModifiersComponent modifiers =
                stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        double total = 1.0;
        for (AttributeModifiersComponent.Entry entry : modifiers.modifiers()) {
            if (entry.slot() == AttributeModifierSlot.MAINHAND
                    && entry.attribute().matchesId(Identifier.ofVanilla("generic.attack_damage"))) {
                total += entry.modifier().value();
            }
        }
        return total;
    }

    /**
     * The JUnit pipeline can only compare id strings, because it cannot bootstrap the registry. This is the check that
     * catches a preset naming an item that does not exist in the running game, or setting a component value vanilla
     * refuses.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bundledPresetsValidateAgainstTheRealRegistry(TestContext context) {
        List<PresetLibrary.PresetInfo> presets = PresetLibrary.discover();
        check(!presets.isEmpty(), "no presets were found inside the mod jar");
        Map<String, String> categories = PresetLibrary.categories();
        check(!categories.isEmpty(), "no category table was found inside the mod jar");

        ItemComponentManager manager = new ItemComponentManager(config());
        MinecraftServer server = context.getWorld().getServer();
        Registry<LootTable> lootTables = server.getReloadableRegistries().getRegistryManager().get(RegistryKeys.LOOT_TABLE);
        Set<Identifier> recipeIds = new HashSet<>();
        for (RecipeEntry<?> entry : server.getRecipeManager().values()) recipeIds.add(entry.id());
        for (PresetLibrary.PresetInfo preset : presets) {
            ConfigSnapshot snapshot = PresetLibrary.load(preset.id());
            check(snapshot.items().size() == preset.itemCount(), preset.id() + " advertises " + preset.itemCount()
                    + " items but holds " + snapshot.items().size());
            List<String> errors = manager.validateConfiguration(snapshot, context.getWorld().getRegistryManager());
            check(errors.isEmpty(), preset.id() + " failed registry validation (" + errors.size() + " errors): "
                    + errors.subList(0, Math.min(8, errors.size())));
            for (String itemId : snapshot.items().keySet()) {
                check(categories.containsKey(itemId), preset.id() + " covers " + itemId + ", which has no category");
            }
            for (ItemEntry entry : snapshot.items().values()) {
                for (SourceRule source : entry.sources) {
                    if (source.lootTableId != null) {
                        check(lootTables.containsId(Identifier.tryParse(source.lootTableId)),
                                preset.id() + ": " + entry.itemId + " points at loot table " + source.lootTableId
                                        + ", which does not exist, so that rule can never fire");
                    }
                    if (source.recipeId != null) {
                        check(recipeIds.contains(Identifier.tryParse(source.recipeId)),
                                preset.id() + ": " + entry.itemId + " points at recipe " + source.recipeId
                                        + ", which does not exist, so that rule can never fire");
                    }
                }
            }
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void presetImportAppliesComponentsAndRevertsCleanly(TestContext context) {
        List<PresetLibrary.PresetInfo> presets = PresetLibrary.discover();
        check(!presets.isEmpty(), "no presets were found inside the mod jar");
        PresetLibrary.PresetInfo preset = presets.get(0);
        ConfigSnapshot bundled = PresetLibrary.load(preset.id());
        String itemId = bundled.items().keySet().iterator().next();

        ItemComponentConfig config = config();
        ItemComponentManager manager = new ItemComponentManager(config);
        PresetMerger.MergeResult merged = PresetMerger.merge(config.snapshot(), bundled, false);
        check(merged.added() == bundled.items().size() && merged.skipped() == 0,
                "a fresh configuration should take the whole preset");
        check(config.replaceSnapshot(new ConfigSnapshot(0, merged.items()), 0).success(), "importing the preset failed");

        ItemStack imported = new ItemStack(Registries.ITEM.get(Identifier.tryParse(itemId)));
        check(manager.applyComponents(imported, SourceContext.command(), context.getWorld().getRegistryManager()).success(),
                "applying the imported rule failed");
        check(hasOriginMetadata(imported), itemId + " was not marked as managed after the preset import");
        check(PresetMerger.status(config.snapshot(), bundled).state() == PresetMerger.PresetState.APPLIED,
                "the freshly imported preset was not reported as applied");

        PresetMerger.RevertResult reverted = PresetMerger.revert(config.snapshot(), bundled);
        check(reverted.removed() == bundled.items().size() && reverted.keptModified() == 0,
                "revert removed " + reverted.removed() + " of " + bundled.items().size() + " preset rules");
        check(config.replaceSnapshot(new ConfigSnapshot(1, reverted.items()), 1).success(), "reverting the preset failed");
        check(config.snapshot().items().isEmpty(), "revert did not clear the configuration");

        ItemStack afterRevert = new ItemStack(Registries.ITEM.get(Identifier.tryParse(itemId)));
        check(manager.applyComponents(afterRevert, SourceContext.command(), context.getWorld().getRegistryManager()).success(),
                "applying components after the revert failed");
        check(!hasOriginMetadata(afterRevert), itemId + " stayed managed after the preset was reverted");
        context.complete();
    }

    private static ItemComponentConfig config() {
        Path path = Path.of("build", "gametest", "originlore-configs", UUID.randomUUID() + ".json");
        return new ItemComponentConfig(path);
    }

    private static boolean hasOriginMetadata(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().contains(ItemComponentManager.METADATA_KEY);
    }

    private static NbtCompound originMetadata(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        check(data != null, "stack has no custom_data");
        NbtCompound root = data.copyNbt();
        check(root.contains(ItemComponentManager.METADATA_KEY), "stack has no OriginLore metadata");
        return root.getCompound(ItemComponentManager.METADATA_KEY);
    }

    private static void assertUnknownFallback(PlayerInventory inventory, String entryPoint) {
        ItemStack managed = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.CARROT)) {
                managed = stack;
                break;
            }
        }
        check(!managed.isEmpty(), entryPoint + " did not place the item in the inventory");
        check(managed.get(DataComponentTypes.LORE) != null
                        && !managed.get(DataComponentTypes.LORE).lines().isEmpty(),
                entryPoint + " did not apply the base Lore");
        Text name = managed.get(DataComponentTypes.CUSTOM_NAME);
        check(name != null && name.getString().equals("Carrot Of Unknown Origin"),
                entryPoint + " did not match the UNKNOWN source rule");
        check(originMetadata(managed).getString("source_type").equals("UNKNOWN"),
                entryPoint + " did not record the UNKNOWN source");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
