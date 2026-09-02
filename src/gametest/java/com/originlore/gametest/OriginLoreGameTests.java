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
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
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
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKeys;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OriginLoreGameTests implements FabricGameTest {
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
            check(result.get(DataComponentTypes.LORE) != null
                            && !result.get(DataComponentTypes.LORE).lines().isEmpty(),
                    "crafting result did not receive Lore");
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
            check(ItemStack.areItemsAndComponentsEqual(furnace, smoker),
                    "equivalent variants from different machines did not stack");
            NbtCompound foreignRoot = smoker.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
            foreignRoot.putString("other_mod_data", "must-remain-distinct");
            smoker.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(foreignRoot));
            check(!ItemStack.areItemsAndComponentsEqual(furnace, smoker),
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
            check(!ItemStack.areItemsAndComponentsEqual(furnace, other),
                    "different variant stacks with equal Lore were incorrectly merged");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(),
                    "stacking test config restore failed");
        }
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
