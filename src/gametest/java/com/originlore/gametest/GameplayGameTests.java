package com.originlore.gametest;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import com.originlore.gameplay.PlacedCakes;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.NumberRange;
import com.originlore.config.ItemComponentConfig.ProcessingRule;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.CrossbowItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.village.SimpleMerchant;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

public final class GameplayGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void foodDragRechecksCurrentSlotPermissionsAndMode(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ToggleInsertHandler handler = new ToggleInsertHandler();
            ItemStack cursor = food(3, "fresh");
            FoodUnits.transfer(food(7, "fresh"), cursor, 1);
            handler.setCursorStack(cursor);
            List<NbtCompound> before = FoodUnits.records(cursor);
            handler.onSlotClick(-999, 0, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(0, 1, SlotActionType.QUICK_CRAFT, player);
            handler.allowInsertion = false;
            handler.onSlotClick(-999, 2, SlotActionType.QUICK_CRAFT, player);
            check(handler.getSlot(0).getStack().isEmpty() && before.equals(FoodUnits.records(cursor)),
                    "drag ignored the handler's current insertion permission");
            handler.allowInsertion = true;
            handler.onSlotClick(-999, 0, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(0, 1, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(-999, 6, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(-999, 2, SlotActionType.QUICK_CRAFT, player);
            check(handler.getSlot(0).getStack().isEmpty() && before.equals(FoodUnits.records(cursor)),
                    "mismatched drag mode committed or retained a stale drag");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void craftingPickupOffhandAndThrowCommitEveryServing(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            config.setItemConfig("minecraft:cookie", randomFood("minecraft:cookie", "CRAFTING"));
            check(config.save().success(), "cookie click config save failed");
            SlotActionType[] actions = {SlotActionType.PICKUP, SlotActionType.PICKUP, SlotActionType.SWAP,
                    SlotActionType.THROW, SlotActionType.THROW};
            int[] buttons = {0, 1, 40, 0, 1};
            for (int scenario = 0; scenario < actions.length; scenario++) {
                player.getInventory().clear();
                CraftingScreenHandler handler = cookieRecipe(context, player, 2);
                ItemStack preview = handler.getSlot(0).getStack().copy();
                check(preview.getCount() == 8 && metadata(preview).getString("variant_id").isEmpty(),
                        "cookie preview selected a final quality");
                handler.onSlotClick(0, 3, SlotActionType.THROW, player);
                check(handler.getSlot(1).getStack().getCount() == 2
                                && ItemStack.areItemsAndComponentsEqual(preview, handler.getSlot(0).getStack()),
                        "invalid throw button consumed a recipe or changed its preview");
                if (actions[scenario] == SlotActionType.SWAP) player.getInventory().setStack(40, new ItemStack(Items.DIRT, 5));
                handler.onSlotClick(0, buttons[scenario], actions[scenario], player);
                for (int input = 1; input <= 3; input++) check(handler.getSlot(input).getStack().getCount() == 1,
                        "cookie click consumed more than one recipe");
                List<ItemStack> outputs = new ArrayList<>();
                outputs.add(handler.getCursorStack());
                for (int slot = 0; slot < player.getInventory().size(); slot++) outputs.add(player.getInventory().getStack(slot));
                List<ItemEntity> drops = context.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(3),
                        entity -> entity.getStack().isOf(Items.COOKIE));
                drops.forEach(entity -> outputs.add(entity.getStack()));
                assertIndependentServings(outputs, Items.COOKIE, "CRAFTING", 8);
                if (actions[scenario] == SlotActionType.SWAP) {
                    check(player.getOffHandStack().isOf(Items.COOKIE) && player.getInventory().count(Items.DIRT) == 5,
                            "offhand production lost the displaced stack");
                }
                if (actions[scenario] == SlotActionType.THROW) {
                    check(drops.stream().mapToInt(entity -> entity.getStack().getCount()).sum() == 8
                                    && handler.getCursorStack().isEmpty() && player.getInventory().count(Items.COOKIE) == 0,
                            "throw did not deliver exactly one recipe at the player's feet");
                }
                drops.forEach(ItemEntity::discard);
            }
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "cookie click config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void fullInventoryCraftingConservesEveryQualityStackAndStops(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ItemEntry entry = randomFood("minecraft:cookie", "CRAFTING");
            Variant other = entry.sources.getFirst().variants.getFirst().copy();
            other.id = "dry";
            other.rule.itemName = "Dry cookie";
            entry.sources.getFirst().variants.add(other);
            config.setItemConfig(entry.itemId, entry);
            check(config.save().success(), "quality overflow config save failed");
            for (int slot = 0; slot < 36; slot++) player.getInventory().setStack(slot, new ItemStack(Items.STONE, 64));
            CraftingScreenHandler handler = cookieRecipe(context, player, 3);
            handler.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, player);
            for (int input = 1; input <= 3; input++) check(handler.getSlot(input).getStack().getCount() == 2,
                    "quality overflow consumed another recipe after completing the current output");
            List<ItemStack> drops = context.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(3),
                    entity -> entity.getStack().isOf(Items.COOKIE)).stream().map(ItemEntity::getStack).toList();
            assertIndependentServings(drops, Items.COOKIE, "CRAFTING", 8);
            check(handler.getCursorStack().isEmpty() && player.getInventory().count(Items.STONE) == 36 * 64,
                    "quality overflow displaced an existing item");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "quality overflow config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void fullInventoryTradingFinishesOneTradeAndStops(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            config.setItemConfig("minecraft:bread", randomFood("minecraft:bread", "TRADING"));
            check(config.save().success(), "full inventory trade config save failed");
            for (int slot = 0; slot < 36; slot++) player.getInventory().setStack(slot, new ItemStack(Items.STONE, 64));
            SimpleMerchant merchant = new SimpleMerchant(player);
            TradeOffer offer = new TradeOffer(new TradedItem(Items.EMERALD, 3), new ItemStack(Items.BREAD, 2), 10, 1, 0);
            TradeOfferList offers = new TradeOfferList();
            offers.add(offer);
            merchant.setOffersFromServer(offers);
            MerchantScreenHandler handler = new MerchantScreenHandler(82, player.getInventory(), merchant);
            handler.getSlot(0).setStack(new ItemStack(Items.EMERALD, 9));
            handler.onSlotClick(2, 0, SlotActionType.QUICK_MOVE, player);
            check(handler.getSlot(0).getStack().getCount() == 6 && offer.getUses() == 1,
                    "full inventory trade consumed another payment after overflow");
            List<ItemStack> drops = context.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(3),
                    entity -> entity.getStack().isOf(Items.BREAD)).stream().map(ItemEntity::getStack).toList();
            assertIndependentServings(drops, Items.BREAD, "TRADING", 2);
            check(player.getInventory().count(Items.STONE) == 36 * 64 && player.getInventory().count(Items.BREAD) == 0,
                    "trade overflow displaced inventory contents");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "full inventory trade config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void failedProductionDoesNotConsumeCraftingOrTradingInputs(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            for (String item : List.of("minecraft:cookie", "minecraft:bread")) {
                ItemEntry entry = randomFood(item, item.endsWith("cookie") ? "CRAFTING" : "TRADING");
                entry.sources.getFirst().variants.getFirst().rule.setComponents = new java.util.LinkedHashMap<>();
                entry.sources.getFirst().variants.getFirst().rule.setComponents.put("minecraft:max_stack_size", new com.google.gson.JsonPrimitive("invalid"));
                config.setItemConfig(item, entry);
            }
            check(config.save().success(), "invalid production test config save failed");
            CraftingScreenHandler crafting = cookieRecipe(context, player, 2);
            ItemStack preview = crafting.getSlot(0).getStack().copy();
            crafting.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, player);
            for (int input = 1; input <= 3; input++) check(crafting.getSlot(input).getStack().getCount() == 2,
                    "failed quality application consumed a crafting input");
            check(crafting.getCursorStack().isEmpty() && player.getInventory().count(Items.COOKIE) == 0
                            && ItemStack.areItemsAndComponentsEqual(preview, crafting.getSlot(0).getStack()),
                    "failed crafting mutated the preview or granted output");
            SimpleMerchant merchant = new SimpleMerchant(player);
            TradeOffer offer = new TradeOffer(new TradedItem(Items.EMERALD, 3), new ItemStack(Items.BREAD, 2), 10, 1, 0);
            TradeOfferList offers = new TradeOfferList();
            offers.add(offer);
            merchant.setOffersFromServer(offers);
            MerchantScreenHandler trading = new MerchantScreenHandler(83, player.getInventory(), merchant);
            trading.getSlot(0).setStack(new ItemStack(Items.EMERALD, 9));
            trading.onSlotClick(2, 0, SlotActionType.PICKUP, player);
            check(trading.getSlot(0).getStack().getCount() == 9 && offer.getUses() == 0
                            && trading.getCursorStack().isEmpty() && player.getInventory().count(Items.BREAD) == 0,
                    "failed quality application charged or delivered a trade");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "invalid production test config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void campfireConsumesIndependentFoodHeadsAndRetainsRisk(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            ItemEntry cooked = randomFood("minecraft:cooked_beef", "SMELTING");
            cooked.sources.getFirst().processing = new ProcessingRule();
            cooked.sources.getFirst().processing.riskRetention = 0.25;
            config.setItemConfig(cooked.itemId, cooked);
            check(config.save().success(), "campfire config save failed");
            ItemStack input = food(Items.BEEF, 2, "fresh");
            setMetadataNumber(input, "quality_score", 0.2);
            setMetadataNumber(input, "spoilage_risk", 0.8);
            ItemStack second = food(Items.BEEF, 8, "fresh");
            setMetadataNumber(second, "quality_score", 0.8);
            setMetadataNumber(second, "spoilage_risk", 0.4);
            FoodUnits.transfer(second, input, 1);
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            context.getWorld().setBlockState(pos, Blocks.CAMPFIRE.getDefaultState());
            CampfireBlockEntity campfire = (CampfireBlockEntity) context.getWorld().getBlockEntity(pos);
            check(campfire.addItem(null, input, 1) && campfire.addItem(null, input, 1) && input.isEmpty(),
                    "campfire insertion did not consume exactly two food heads");
            CampfireBlockEntity.litServerTick(context.getWorld(), pos, context.getWorld().getBlockState(pos), campfire);
            List<ItemStack> outputs = context.getWorld().getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(pos).expand(2),
                    entity -> entity.getStack().isOf(Items.COOKED_BEEF)).stream().map(ItemEntity::getStack).toList();
            assertIndependentServings(outputs, Items.COOKED_BEEF, "SMELTING", 2);
            java.util.Set<Double> qualities = new java.util.HashSet<>();
            for (ItemStack output : outputs) for (NbtCompound record : FoodUnits.records(output)) {
                NbtCompound identity = record.getCompound("identity");
                qualities.add(identity.getDouble("ingredient_quality"));
                check(Math.abs(identity.getDouble("spoilage_risk") - identity.getDouble("ingredient_risk") * 0.25) < 0.0001,
                        "campfire lost its consumed ingredient's processing risk");
            }
            check(qualities.equals(java.util.Set.of(0.2, 0.8)) && campfire.getItemsBeingCooked().stream().allMatch(ItemStack::isEmpty),
                    "campfire reused an ingredient's quality or retained a consumed input");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "campfire config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crossbowFireworkRetainsWeaponAcrossSaveLoadAndActualExplosion(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ItemStack weapon = identified(new ItemStack(Items.CROSSBOW), "quality");
            setMetadataNumber(weapon, "projectile_damage_multiplier", 2);
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1, List.of(FireworkExplosionComponent.DEFAULT)));
            weapon.set(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectilesComponent.of(rocket));
            player.setStackInHand(Hand.MAIN_HAND, weapon);
            ((CrossbowItem) Items.CROSSBOW).shootAll(context.getWorld(), player, Hand.MAIN_HAND, weapon, 1, 0, null);
            FireworkRocketEntity fired = context.getWorld().getEntitiesByClass(FireworkRocketEntity.class,
                    player.getBoundingBox().expand(3), entity -> entity.getOwner() == player).stream().findFirst().orElseThrow();
            NbtCompound saved = new NbtCompound();
            fired.writeCustomDataToNbt(saved);
            check(saved.contains("OriginLoreWeapon") && weapon.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty()
                            && weapon.getDamage() == 3, "crossbow did not capture its weapon or preserve native firing costs");
            fired.discard();
            setMetadataNumber(weapon, "projectile_damage_multiplier", 0.5);
            TestFirework loaded = new TestFirework(context.getWorld());
            loaded.readCustomDataFromNbt(saved);
            LivingEntity target = context.spawnEntity(EntityType.COW, new BlockPos(1, 1, 1));
            target.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(40);
            target.setHealth(40);
            loaded.setPosition(target.getPos());
            float before = target.getHealth();
            loaded.hit(target);
            check(Math.abs(before - target.getHealth() - 14) < 0.01,
                    "loaded crossbow firework lost the firing weapon's multiplier at explosion");
            NbtCompound ordinary = saved.copy();
            ordinary.remove("OriginLoreWeapon");
            loaded.readCustomDataFromNbt(ordinary);
            NbtCompound rewritten = new NbtCompound();
            loaded.writeCustomDataToNbt(rewritten);
            check(!rewritten.contains("OriginLoreWeapon"), "loading an ordinary rocket retained another rocket's weapon");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void blockedHopperRestoresFifoBeforeRetry(TestContext context) {
        BlockPos pos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        context.getWorld().setBlockState(pos.down(), Blocks.CHEST.getDefaultState());
        context.getWorld().setBlockState(pos, Blocks.HOPPER.getDefaultState().with(HopperBlock.FACING, Direction.DOWN));
        HopperBlockEntity hopper = (HopperBlockEntity) context.getWorld().getBlockEntity(pos);
        Inventory chest = (Inventory) context.getWorld().getBlockEntity(pos.down());
        for (int slot = 0; slot < chest.size(); slot++) chest.setStack(slot, new ItemStack(Items.STONE, 63));
        ItemStack queue = food(2, "fresh");
        FoodUnits.transfer(food(8, "fresh"), queue, 1);
        hopper.setStack(0, queue);
        List<NbtCompound> before = FoodUnits.records(queue);
        HopperBlockEntity.serverTick(context.getWorld(), pos, context.getWorld().getBlockState(pos), hopper);
        check(hopper.getStack(0).getCount() == 2 && before.equals(FoodUnits.records(hopper.getStack(0))),
                "failed hopper insertion replaced the oldest serving");
        chest.setStack(0, ItemStack.EMPTY);
        HopperBlockEntity.serverTick(context.getWorld(), pos, context.getWorld().getBlockState(pos), hopper);
        check(chest.getStack(0).getCount() == 1 && nutrition(chest.getStack(0)) == 2, "hopper retry lost the original head");
        check(hopper.getStack(0).getCount() == 1 && nutrition(hopper.getStack(0)) == 8, "hopper retry consumed the wrong serving");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void blockedHopperExtractionRetainsSourceFifo(TestContext context) {
        BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
        context.getWorld().setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        context.getWorld().setBlockState(pos, Blocks.HOPPER.getDefaultState().with(HopperBlock.FACING, Direction.DOWN));
        context.getWorld().setBlockState(pos.up(), Blocks.CHEST.getDefaultState());
        HopperBlockEntity hopper = (HopperBlockEntity) context.getWorld().getBlockEntity(pos);
        Inventory chest = (Inventory) context.getWorld().getBlockEntity(pos.up());
        for (int slot = 0; slot < hopper.size(); slot++) hopper.setStack(slot, new ItemStack(Items.STONE, 63));
        ItemStack queue = food(2, "fresh");
        FoodUnits.transfer(food(8, "fresh"), queue, 1);
        chest.setStack(0, queue);
        List<NbtCompound> before = FoodUnits.records(queue);
        HopperBlockEntity.serverTick(context.getWorld(), pos, context.getWorld().getBlockState(pos), hopper);
        check(chest.getStack(0).getCount() == 2 && before.equals(FoodUnits.records(chest.getStack(0))),
                "failed hopper extraction replaced the oldest serving");
        hopper.setStack(0, ItemStack.EMPTY);
        HopperBlockEntity.serverTick(context.getWorld(), pos, context.getWorld().getBlockState(pos), hopper);
        check(hopper.getStack(0).getCount() == 1 && nutrition(hopper.getStack(0)) == 2,
                "hopper extraction retry lost the original head");
        check(chest.getStack(0).getCount() == 1 && nutrition(chest.getStack(0)) == 8,
                "hopper extraction retry consumed the wrong serving");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void containerClicksAndDragConserveFoodFifo(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            SimpleInventory inventory = new SimpleInventory(27);
            GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x3(73, player.getInventory(), inventory);
            ItemStack queue = food(1, "fresh");
            for (int value = 2; value <= 6; value++) FoodUnits.transfer(food(value, "fresh"), queue, 1);
            inventory.setStack(0, queue);
            handler.onSlotClick(0, 1, SlotActionType.PICKUP, player);
            check(handler.getCursorStack().getCount() == 3 && nutrition(handler.getCursorStack()) == 1
                    && inventory.getStack(0).getCount() == 3 && nutrition(inventory.getStack(0)) == 4,
                    "right-click pickup did not take the FIFO prefix");
            handler.onSlotClick(-999, 0, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(1, 1, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(2, 1, SlotActionType.QUICK_CRAFT, player);
            handler.onSlotClick(-999, 2, SlotActionType.QUICK_CRAFT, player);
            check(nutrition(inventory.getStack(1)) == 1 && nutrition(inventory.getStack(2)) == 2
                    && handler.getCursorStack().getCount() == 1 && nutrition(handler.getCursorStack()) == 3,
                    "drag distribution lost food order or cursor remainder");
            handler.onSlotClick(0, 0, SlotActionType.PICKUP, player);
            check(handler.getCursorStack().isEmpty() && inventory.getStack(0).getCount() == 4,
                    "cursor merge lost its remaining serving");
            handler.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, player);
            ItemStack moved = player.getInventory().main.stream().filter(stack -> stack.isOf(Items.APPLE)).findFirst().orElseThrow();
            check(moved.getCount() == 4 && inventory.getStack(0).isEmpty(), "shift transfer lost quantity");
            for (int value : new int[]{4, 5, 6, 3}) check(nutrition(moved.split(1)) == value, "shift transfer reordered food");
            check(inventory.getStack(1).getCount() == 1 && inventory.getStack(2).getCount() == 1,
                    "shift transfer changed unrelated drag outputs");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void harvestingUsesRealBlockAndContainerEntrypoints(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            for (String item : List.of("minecraft:sweet_berries", "minecraft:honey_bottle", "minecraft:mushroom_stew")) {
                config.setItemConfig(item, randomFood(item, "HARVEST"));
            }
            ItemEntry milk = new ItemEntry("minecraft:milk_bucket");
            milk.base.lore = List.of("harvest test");
            config.setItemConfig(milk.itemId, milk);
            check(config.save().success(), "harvest config save failed");
            BlockPos berriesPos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            context.getWorld().setBlockState(berriesPos.down(), Blocks.DIRT.getDefaultState());
            BlockState berries = Blocks.SWEET_BERRY_BUSH.getDefaultState().with(SweetBerryBushBlock.AGE, 3);
            context.getWorld().setBlockState(berriesPos, berries);
            berries.onUse(context.getWorld(), player, new BlockHitResult(Vec3d.ofCenter(berriesPos), Direction.UP, berriesPos, false));
            List<ItemEntity> drops = context.getWorld().getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(berriesPos).expand(1),
                    entity -> entity.getStack().isOf(Items.SWEET_BERRIES));
            int servings = 0;
            for (ItemEntity drop : drops) {
                for (NbtCompound record : FoodUnits.records(drop.getStack())) {
                    check(record.getCompound("identity").getString("source_type").equals("HARVEST"), "berry harvest lost its source");
                    servings++;
                }
            }
            check(servings >= 2 && servings <= 3 && context.getWorld().getBlockState(berriesPos).get(SweetBerryBushBlock.AGE) == 1,
                    "berry harvest lost output or failed to reset growth");
            BlockPos hivePos = context.getAbsolutePos(new BlockPos(2, 1, 1));
            BlockState hive = Blocks.BEEHIVE.getDefaultState().with(BeehiveBlock.HONEY_LEVEL, 5);
            context.getWorld().setBlockState(hivePos, hive);
            player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE, 2));
            check(hive.onUseWithItem(player.getMainHandStack(), context.getWorld(), player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(hivePos), Direction.UP, hivePos, false)).isAccepted(), "hive bottling failed");
            ItemStack honey = player.getInventory().main.stream().filter(stack -> stack.isOf(Items.HONEY_BOTTLE)).findFirst().orElseThrow();
            check(player.getMainHandStack().isOf(Items.GLASS_BOTTLE) && player.getMainHandStack().getCount() == 1
                    && honey.getCount() == 1 && FoodUnits.hasQueue(honey) && metadata(honey).getString("source_type").equals("HARVEST"),
                    "hive bottling lost source, quality, or bottle quantity");
            check(context.getWorld().getBlockState(hivePos).get(BeehiveBlock.HONEY_LEVEL) == 0, "hive bottling did not consume honey");
            player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.BUCKET));
            check(context.spawnEntity(EntityType.COW, new BlockPos(1, 1, 2)).interactMob(player, Hand.MAIN_HAND).isAccepted(), "milk harvest failed");
            check(player.getMainHandStack().isOf(Items.MILK_BUCKET)
                    && metadata(player.getMainHandStack()).getString("source_type").equals("HARVEST")
                    && metadata(player.getMainHandStack()).getString("variant_id").isEmpty(), "milk acquired an invalid quality or source");
            player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.BOWL));
            check(context.spawnEntity(EntityType.MOOSHROOM, new BlockPos(2, 1, 2)).interactMob(player, Hand.MAIN_HAND).isAccepted(), "stew harvest failed");
            ItemStack stew = player.getMainHandStack();
            check(stew.isOf(Items.MUSHROOM_STEW) && FoodUnits.hasQueue(stew)
                    && metadata(stew).getString("source_type").equals("HARVEST"), "harvested stew lost its food identity");
            check(stew.finishUsing(context.getWorld(), player).isOf(Items.BOWL), "quality stew did not return the native bowl");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "harvest config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void furnaceUsesConsumedFoodQualityAndResidualRisk(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            ItemEntry cooked = randomFood("minecraft:cooked_beef", "SMELTING");
            cooked.sources.getFirst().processing = new ProcessingRule();
            cooked.sources.getFirst().processing.riskRetention = 0.25;
            cooked.sources.getFirst().processing.riskFloor = 0.1;
            config.setItemConfig(cooked.itemId, cooked);
            check(config.save().success(), "processing config save failed");
            ItemStack first = food(Items.BEEF, 2, "fresh");
            setMetadataNumber(first, "quality_score", 0.2);
            setMetadataNumber(first, "spoilage_risk", 0.8);
            ItemStack second = food(Items.BEEF, 8, "fresh");
            setMetadataNumber(second, "quality_score", 0.8);
            FoodUnits.transfer(second, first, 1);
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            context.getWorld().setBlockState(pos, Blocks.FURNACE.getDefaultState());
            FurnaceBlockEntity furnace = (FurnaceBlockEntity) context.getWorld().getBlockEntity(pos);
            furnace.setStack(0, first);
            furnace.setStack(1, new ItemStack(Items.COAL));
            cook(context, pos, furnace);
            ItemStack output = furnace.removeStack(2);
            check(Math.abs(metadata(output).getDouble("ingredient_quality") - 0.2) < 0.0001,
                    "furnace sampled a serving other than the actual consumed head");
            check(Math.abs(metadata(output).getDouble("ingredient_risk") - 0.8) < 0.0001,
                    "furnace lost the raw ingredient risk");
            check(Math.abs(ItemComponentManager.getSpoilageRisk(output) - 0.2) < 0.0001,
                    "processing residual did not follow configured retention");
            cook(context, pos, furnace);
            output = furnace.getStack(2);
            check(Math.abs(metadata(output).getDouble("ingredient_quality") - 0.8) < 0.0001,
                    "second cooking operation reused the first serving's quality");
            check(furnace.getStack(0).isEmpty() && output.getCount() == 1, "processing quantity mismatch");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "processing config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void soupProcessingExcludesEmptyBowlFromIngredientAverage(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            config.setItemConfig("minecraft:beetroot_soup", randomFood("minecraft:beetroot_soup", "CRAFTING"));
            check(config.save().success(), "soup processing config save failed");
            CraftingScreenHandler handler = new CraftingScreenHandler(74, player.getInventory(),
                    ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
            for (int slot = 1; slot <= 6; slot++) {
                ItemStack beetroot = food(Items.BEETROOT, 2, "fresh");
                setMetadataNumber(beetroot, "quality_score", 0.8);
                handler.getSlot(slot).setStack(beetroot);
            }
            handler.getSlot(7).setStack(new ItemStack(Items.BOWL));
            check(handler.getSlot(0).getStack().isOf(Items.BEETROOT_SOUP), "soup recipe setup failed");
            handler.onSlotClick(0, 0, SlotActionType.PICKUP, player);
            ItemStack soup = handler.getCursorStack();
            check(soup.isOf(Items.BEETROOT_SOUP) && soup.getCount() == 1, "soup production lost output");
            check(Math.abs(metadata(soup).getDouble("ingredient_quality") - 0.8) < 0.0001,
                    "empty bowl diluted the consumed food quality");
            for (int slot = 1; slot <= 7; slot++) check(handler.getSlot(slot).getStack().isEmpty(), "soup production failed to consume its recipe");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "soup processing config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crafterCommitsEveryCookieAndPreservesIndependentDraws(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            config.setItemConfig("minecraft:cookie", randomFood("minecraft:cookie", "CRAFTING"));
            check(config.save().success(), "crafter config save failed");
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            BlockState state = Blocks.CRAFTER.getDefaultState();
            context.getWorld().setBlockState(pos, state);
            BlockPos target = pos.offset(state.get(net.minecraft.state.property.Properties.ORIENTATION).getFacing());
            context.getWorld().setBlockState(target, Blocks.CHEST.getDefaultState());
            CrafterBlockEntity crafter = (CrafterBlockEntity) context.getWorld().getBlockEntity(pos);
            crafter.setStack(0, new ItemStack(Items.WHEAT, 2));
            crafter.setStack(1, new ItemStack(Items.COCOA_BEANS, 2));
            crafter.setStack(2, new ItemStack(Items.WHEAT, 2));
            state.scheduledTick(context.getWorld(), pos, context.getWorld().random);
            Inventory chest = (Inventory) context.getWorld().getBlockEntity(target);
            int count = 0;
            java.util.Set<Double> positions = new java.util.HashSet<>();
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.getStack(slot);
                if (!stack.isOf(Items.COOKIE)) continue;
                count += stack.getCount();
                for (NbtCompound record : FoodUnits.records(stack)) {
                    NbtCompound identity = record.getCompound("identity");
                    check(identity.getString("source_type").equals("CRAFTING"), "crafter output lost its source");
                    positions.add(identity.getCompound("random_positions").getDouble("food.eat_seconds"));
                }
            }
            check(count == 8 && positions.size() == 8, "crafter lost or reused an independent output serving");
            check(crafter.getStack(0).getCount() == 1 && crafter.getStack(1).getCount() == 1
                    && crafter.getStack(2).getCount() == 1, "crafter did not consume exactly one recipe");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "crafter config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crafterRepairRetainsFirstItemRandomPositions(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            ItemEntry entry = new ItemEntry("minecraft:iron_sword");
            SourceRule source = new SourceRule("CRAFTING");
            Variant variant = new Variant("handmade", 1);
            variant.rule.maxDamageRange = new int[]{150, 200};
            variant.rule.attackDamageRange = new NumberRange(-2, -1);
            source.variants.add(variant);
            entry.sources.add(source);
            config.setItemConfig(entry.itemId, entry);
            check(config.save().success(), "crafter repair config save failed");
            SourceContext origin = new SourceContext(SourceType.CRAFTING);
            ItemStack first = com.originlore.gameplay.Production.roll(new ItemStack(Items.IRON_SWORD), origin, List.of()).getFirst();
            ItemStack second = com.originlore.gameplay.Production.roll(new ItemStack(Items.IRON_SWORD), origin, List.of()).getFirst();
            first.setDamage(first.getMaxDamage() - 20);
            second.setDamage(second.getMaxDamage() - 30);
            NbtCompound positions = metadata(first).getCompound("random_positions").copy();
            int originalMaximum = first.getMaxDamage();
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            BlockState state = Blocks.CRAFTER.getDefaultState();
            context.getWorld().setBlockState(pos, state);
            BlockPos target = pos.offset(state.get(net.minecraft.state.property.Properties.ORIENTATION).getFacing());
            context.getWorld().setBlockState(target, Blocks.CHEST.getDefaultState());
            CrafterBlockEntity crafter = (CrafterBlockEntity) context.getWorld().getBlockEntity(pos);
            crafter.setStack(0, first);
            crafter.setStack(1, second);
            state.scheduledTick(context.getWorld(), pos, context.getWorld().random);
            Inventory chest = (Inventory) context.getWorld().getBlockEntity(target);
            ItemStack repaired = chest.getStack(0);
            check(repaired.isOf(Items.IRON_SWORD) && repaired.getCount() == 1
                    && crafter.getStack(0).isEmpty() && crafter.getStack(1).isEmpty(), "crafter repair lost input or output quantity");
            check(metadata(repaired).getString("variant_id").equals("handmade")
                    && positions.equals(metadata(repaired).getCompound("random_positions"))
                    && repaired.getMaxDamage() == originalMaximum, "crafter repair rerolled the primary item");
            check(repaired.getMaxDamage() - repaired.getDamage() > 20, "crafter repair did not retain the repair gain");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "crafter repair config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void foodRuleRemovalClearsQueueAndRestoresNativeFood(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            config.setItemConfig("minecraft:apple", randomFood("minecraft:apple", "HARVEST"));
            check(config.save().success(), "food removal setup failed");
            ItemStack first = com.originlore.gameplay.Production.roll(new ItemStack(Items.APPLE, 3),
                    new SourceContext(SourceType.HARVEST), List.of()).getFirst();
            check(first.getCount() == 3 && FoodUnits.hasQueue(first), "food removal setup lost servings");
            config.removeItemConfig("minecraft:apple");
            check(config.save().success(), "food removal save failed");
            check(Originlore.applyCustomComponents(first).success(), "food rule removal refresh failed");
            check(!FoodUnits.hasQueue(first) && !ItemComponentManager.hasOriginLoreMetadata(first),
                    "removed food rule left a phantom identity or queue");
            check(first.getCount() == 3 && first.get(DataComponentTypes.FOOD).equals(Items.APPLE.getDefaultStack().get(DataComponentTypes.FOOD)),
                    "removed food rule failed to restore native food or quantity");
            config.setItemConfig("minecraft:apple", randomFood("minecraft:apple", "HARVEST"));
            check(config.save().success(), "food restoration save failed");
            check(Originlore.applyCustomComponents(first).success(), "food rule restoration refresh failed");
            check(metadata(first).getString("variant_id").isEmpty(), "restored rule invented a new historical harvest quality");
            check(first.getCount() == 3 && nutrition(first) == 4, "restored base rule altered food quantity or nutrition");
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "food removal config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void cakePlacementCandleSaveLoadAndHotUpdateRetainIdentity(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ItemEntry entry = randomFood("minecraft:cake", "CRAFTING");
            entry.sources.getFirst().variants.getFirst().rule.food.nutritionRange = new int[]{3, 3};
            config.setItemConfig(entry.itemId, entry);
            check(config.save().success(), "cake config save failed");
            ItemStack cake = com.originlore.gameplay.Production.roll(new ItemStack(Items.CAKE),
                    new SourceContext(SourceType.CRAFTING), List.of()).getFirst();
            check(!cake.contains(DataComponentTypes.FOOD) && !FoodUnits.hasQueue(cake)
                    && ItemComponentManager.getCakeFood(cake, context.getWorld().getRegistryManager()).nutrition() == 3,
                    "cake slice data leaked into a handheld food component");
            ItemStack legacyCake = cake.copy();
            legacyCake.set(DataComponentTypes.FOOD, ItemComponentManager.getCakeFood(cake, context.getWorld().getRegistryManager()));
            FoodUnits.initialize(legacyCake);
            check(Originlore.applyCustomComponents(legacyCake).success() && !legacyCake.contains(DataComponentTypes.FOOD)
                    && !FoodUnits.hasQueue(legacyCake)
                    && metadata(cake).getCompound("random_positions").equals(metadata(legacyCake).getCompound("random_positions")),
                    "legacy cake queue retained handheld food or changed its random positions");
            ItemStack spare = cake.copy();
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            context.getWorld().setBlockState(pos.down(), Blocks.STONE.getDefaultState());
            player.setStackInHand(Hand.MAIN_HAND, cake);
            player.getHungerManager().setFoodLevel(0);
            check(!cake.use(context.getWorld(), player, Hand.MAIN_HAND).getResult().isAccepted()
                    && !player.isUsingItem() && cake.getCount() == 1, "quality cake started handheld food consumption");
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos.down()).add(0, 0.5, 0), Direction.UP, pos.down(), false);
            check(cake.useOnBlock(new ItemUsageContext(player, Hand.MAIN_HAND, hit)).isAccepted(), "quality cake placement failed");
            PlacedCakes placed = PlacedCakes.get(context.getWorld());
            NbtCompound saved = placed.writeNbt(new NbtCompound(), context.getWorld().getRegistryManager());
            check(saved.getList("cakes", 10).size() > 0, "placed cake was not persisted");
            context.getWorld().setBlockState(pos, Blocks.CANDLE_CAKE.getDefaultState());
            player.getHungerManager().setFoodLevel(0);
            context.getWorld().getBlockState(pos).onUse(context.getWorld(), player,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false));
            check(player.getHungerManager().getFoodLevel() == 3
                    && context.getWorld().getBlockState(pos).get(CakeBlock.BITES) == 1, "candle cake lost quality on its first bite");
            PlacedCakes loaded = PlacedCakes.read(placed.writeNbt(new NbtCompound(), context.getWorld().getRegistryManager()),
                    context.getWorld().getRegistryManager());
            entry.sources.getFirst().variants.getFirst().rule.food.nutritionRange = new int[]{5, 5};
            config.setItemConfig(entry.itemId, entry);
            check(config.save().success(), "cake hot update failed");
            check(Originlore.applyCustomComponents(spare).success() && !spare.contains(DataComponentTypes.FOOD)
                    && ItemComponentManager.getCakeFood(spare, context.getWorld().getRegistryManager()).nutrition() == 5,
                    "cake hot update lost slice data or restored handheld eating");
            player.getHungerManager().setFoodLevel(0);
            check(loaded.eat(context.getWorld(), pos, context.getWorld().getBlockState(pos), player).isAccepted(), "loaded cake bite failed");
            check(player.getHungerManager().getFoodLevel() == 5, "loaded cake ignored the updated quality rule");
            config.removeItemConfig(entry.itemId);
            check(config.save().success() && Originlore.applyCustomComponents(spare).success(), "cake rule removal failed");
            check(!spare.contains(DataComponentTypes.FOOD) && ItemComponentManager.getCakeFood(spare, context.getWorld().getRegistryManager()) == null
                    && !ItemComponentManager.hasOriginLoreMetadata(spare), "cake rule removal left cached slice data");
            context.getWorld().removeBlock(pos, false);
            context.getWorld().setBlockState(pos, Blocks.CAKE.getDefaultState());
            check(placed.eat(context.getWorld(), pos, context.getWorld().getBlockState(pos), player) == null,
                    "replaced vanilla cake inherited a destroyed cake's identity");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "cake config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void foodTransferRespectsCapacityAndCannotTransferToItself(TestContext context) {
        ItemStack full = food(2, "fresh").copyWithCount(63);
        ItemStack incoming = food(9, "fresh").copyWithCount(3);
        check(FoodUnits.transfer(incoming, full, 100) == 1, "transfer exceeded vanilla capacity");
        check(full.getCount() == 64 && incoming.getCount() == 2, "bounded transfer lost quantity");
        List<NbtCompound> before = FoodUnits.records(full);
        check(FoodUnits.transfer(full, full, 1) == 0, "self transfer was accepted");
        check(before.equals(FoodUnits.records(full)), "self transfer duplicated food records");
        full.split(63);
        check(nutrition(full) == 9, "capacity boundary lost the appended serving");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void realConsumptionAndSaveLoadAdvanceExactlyOneFoodServing(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ItemStack stack = food(2, "fresh");
            FoodUnits.transfer(food(7, "fresh"), stack, 1);
            stack = ItemStack.fromNbt(context.getWorld().getRegistryManager(), stack.encode(context.getWorld().getRegistryManager())).orElseThrow();
            List<NbtCompound> before = FoodUnits.records(stack);
            stack.onStoppedUsing(context.getWorld(), player, 10);
            check(before.equals(FoodUnits.records(stack)), "cancelled use changed serving order");
            player.getHungerManager().setFoodLevel(0);
            ItemStack remainder = stack.finishUsing(context.getWorld(), player);
            check(player.getHungerManager().getFoodLevel() == 2, "first consumption used the next serving");
            check(remainder.getCount() == 1 && nutrition(remainder) == 7, "successful use failed to advance FIFO");
            player.getHungerManager().setFoodLevel(0);
            check(remainder.finishUsing(context.getWorld(), player).isEmpty(), "last serving was not consumed");
            check(player.getHungerManager().getFoodLevel() == 7, "second consumption lost its independent nutrition");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void honeyUsesQualityDurationAndRetainsBottleAndNativeCure(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            ItemStack honey = food(Items.HONEY_BOTTLE, 3, "fresh");
            honey.set(DataComponentTypes.FOOD, new FoodComponent(3, 0.4F, false, 0.75F, Optional.empty(),
                    List.of(new FoodComponent.StatusEffectEntry(new StatusEffectInstance(StatusEffects.POISON, 100, 0), 1))));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 600, 2));
            check(honey.getMaxUseTime(player) == 15, "honey ignored configured eating duration");
            player.getHungerManager().setFoodLevel(0);
            ItemStack bottle = honey.finishUsing(context.getWorld(), player);
            check(bottle.isOf(Items.GLASS_BOTTLE), "honey did not return its native bottle");
            check(player.getHungerManager().getFoodLevel() == 3, "honey ignored its food component");
            check(player.getStatusEffect(StatusEffects.POISON) != null
                    && player.getStatusEffect(StatusEffects.POISON).getAmplifier() == 0,
                    "honey failed to clear old poison before applying this serving's risk");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void craftingClicksConserveOutputsAndStopWhenFull(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            CraftingScreenHandler handler = new CraftingScreenHandler(71, player.getInventory(),
                    ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
            player.getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
            handler.getSlot(1).setStack(new ItemStack(Items.OAK_LOG, 2));
            handler.onSlotClick(0, 0, SlotActionType.SWAP, player);
            check(player.getInventory().getStack(0).isOf(Items.OAK_PLANKS)
                    && player.getInventory().getStack(0).getCount() == 4, "number-key production did not fill the chosen hotbar slot");
            check(player.getInventory().count(Items.DIRT) == 5, "number-key production lost the displaced stack");
            check(handler.getSlot(1).getStack().getCount() == 1, "number-key production consumed more than one recipe");
            player.getInventory().clear();
            for (int slot = 0; slot < 36; slot++) player.getInventory().setStack(slot, new ItemStack(Items.STONE, 64));
            handler.getSlot(1).setStack(new ItemStack(Items.OAK_LOG, 3));
            int before = context.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(2),
                    entity -> entity.getStack().isOf(Items.OAK_PLANKS)).stream().mapToInt(entity -> entity.getStack().getCount()).sum();
            handler.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, player);
            check(handler.getSlot(1).getStack().getCount() == 2, "full inventory did not stop after one completed recipe");
            int dropped = context.getWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(2),
                    entity -> entity.getStack().isOf(Items.OAK_PLANKS)).stream().mapToInt(entity -> entity.getStack().getCount()).sum();
            check(dropped - before == 4, "full inventory lost or duplicated crafted output");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void tradeCommitSamplesEveryServingAndChargesEachTrade(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            config.setItemConfig("minecraft:bread", randomFood("minecraft:bread", "TRADING"));
            check(config.save().success(), "trade config failed");
            SimpleMerchant merchant = new SimpleMerchant(player);
            TradeOffer offer = new TradeOffer(new TradedItem(Items.EMERALD, 3), new ItemStack(Items.BREAD, 2), 10, 1, 0);
            TradeOfferList offers = new TradeOfferList();
            offers.add(offer);
            merchant.setOffersFromServer(offers);
            MerchantScreenHandler handler = new MerchantScreenHandler(72, player.getInventory(), merchant);
            handler.getSlot(0).setStack(new ItemStack(Items.EMERALD, 9));
            ItemStack preview = handler.getSlot(2).getStack();
            check(!preview.isEmpty() && metadata(preview).getString("variant_id").isEmpty(), "trade preview selected a final quality");
            handler.onSlotClick(2, 0, SlotActionType.QUICK_MOVE, player);
            check(handler.getSlot(0).getStack().isEmpty() && offer.getUses() == 3, "trade did not charge exactly three payments");
            check(player.getInventory().count(Items.BREAD) == 6, "trade output quantity mismatch");
            java.util.Set<NbtCompound> positions = new java.util.HashSet<>();
            int servings = 0;
            for (ItemStack stack : player.getInventory().main) {
                if (!stack.isOf(Items.BREAD)) continue;
                for (NbtCompound record : FoodUnits.records(stack)) {
                    NbtCompound identity = record.getCompound("identity");
                    check(identity.getString("source_type").equals("TRADING"), "trade serving lost its source");
                    positions.add(identity.getCompound("random_positions"));
                    servings++;
                }
            }
            check(servings == 6 && positions.size() == 6, "trade reused another serving's random draw");
        } finally {
            disconnect(player);
            check(config.replaceSnapshot(original, config.getRevision()).success(), "trade config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void projectilesApplyWeaponMultiplierAtActualHit(TestContext context) {
        ServerPlayerEntity player = survivalPlayer(context);
        try {
            LivingEntity arrowTarget = context.spawnEntity(EntityType.COW, new BlockPos(1, 1, 1));
            ItemStack bow = identified(new ItemStack(Items.BOW), "quality");
            setMetadataNumber(bow, "projectile_damage_multiplier", 2);
            TestArrow arrow = new TestArrow(context.getWorld(), player, bow);
            arrow.setVelocity(0, 0, 2);
            float before = arrowTarget.getHealth();
            arrow.hit(arrowTarget);
            check(Math.abs(before - arrowTarget.getHealth() - 8) < 0.01, "arrow did not apply weapon multiplier on hit");
            LivingEntity tridentTarget = context.spawnEntity(EntityType.COW, new BlockPos(2, 1, 1));
            ItemStack weapon = identified(new ItemStack(Items.TRIDENT), "quality");
            setMetadataNumber(weapon, "projectile_damage_multiplier", 0.5);
            TestTrident trident = new TestTrident(context.getWorld(), player, weapon);
            before = tridentTarget.getHealth();
            trident.hit(tridentTarget);
            check(Math.abs(before - tridentTarget.getHealth() - 4) < 0.01, "trident did not apply its weapon multiplier on hit");
        } finally { disconnect(player); }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void foodUnitsConserveOrderAcrossSplitMergeAndConsume(TestContext context) {
        ItemStack first = food(2, "fresh");
        ItemStack second = food(7, "fresh");
        check(FoodUnits.canCombine(first, second), "same-quality values should combine");
        check(!ItemStack.areItemsAndComponentsEqual(first, second), "sync equality must remain strict");
        FoodUnits.transfer(second, first, 1);
        check(first.getCount() == 2 && second.isEmpty(), "merge count mismatch");
        check(FoodUnits.records(first).size() == 2, "merge lost an independent food unit");
        ItemStack beforeRefresh = first.copy();
        for (int refresh = 0; refresh < 2; refresh++) {
            ItemComponentManager.ApplyResult result = Originlore.applyCustomComponents(first);
            check(result.success() && !result.changed(), "unchanged food queue reported a refresh mutation");
            check(first.getCount() == 2 && ItemStack.areItemsAndComponentsEqual(first, beforeRefresh)
                    && FoodUnits.records(first).equals(FoodUnits.records(beforeRefresh)), "repeated refresh changed FIFO contents");
        }
        ItemStack taken = first.split(1);
        check(nutrition(taken) == 2 && nutrition(first) == 7, "split did not take the FIFO prefix");
        FoodUnits.transfer(first, taken, 1);
        taken.decrement(1);
        check(taken.getCount() == 1 && nutrition(taken) == 7, "consumption did not advance the food component");
        check(FoodUnits.records(taken).size() == 1, "consumption queue size differs from count");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void foodInventoryAndGroundMergesRetainValues(TestContext context) {
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, food(3, "fresh"));
        check(inventory.addStack(food(8, "fresh")).isEmpty(), "SimpleInventory refused same-quality food");
        check(nutrition(inventory.getStack(0).split(1)) == 3, "SimpleInventory changed the head");
        check(nutrition(inventory.getStack(0)) == 8, "SimpleInventory lost the appended value");
        ItemStack source = food(11, "fresh");
        check(HopperBlockEntity.transfer(null, inventory, source, null).isEmpty(), "hopper failed to merge food");
        check(nutrition(inventory.getStack(0).split(1)) == 8 && nutrition(inventory.getStack(0)) == 11,
                "hopper changed food order");
        ItemStack ground = ItemEntity.merge(food(1, "fresh"), food(9, "fresh"), 64);
        check(nutrition(ground.split(1)) == 1 && nutrition(ground) == 9, "ground merge duplicated the first value");
        check(!FoodUnits.canCombine(food(1, "fresh"), food(1, "spoiled")), "different quality variants combined");
        ItemStack renamed = food(1, "fresh");
        renamed.set(DataComponentTypes.CUSTOM_NAME, Text.literal("External name"));
        check(!FoodUnits.canCombine(renamed, food(1, "fresh")), "foreign component differences were ignored");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void placedCakeKeepsItsIdentityForSevenBites(TestContext context) {
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
        context.getWorld().setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        context.getWorld().setBlockState(pos, Blocks.CAKE.getDefaultState());
        ItemStack cake = food(Items.CAKE, 3, "fresh");
        NbtCompound identity = metadata(cake);
        identity.put("cake_food", com.originlore.component.ComponentCodecSupport.encodeNbt(DataComponentTypes.FOOD,
                cake.get(DataComponentTypes.FOOD), context.getWorld().getRegistryManager()));
        NbtCompound root = new NbtCompound();
        root.put(ItemComponentManager.METADATA_KEY, identity);
        cake.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        cake.remove(DataComponentTypes.FOOD);
        PlacedCakes state = PlacedCakes.get(context.getWorld());
        state.put(pos, cake);
        for (int bite = 0; bite < 7; bite++) {
            player.getHungerManager().setFoodLevel(0);
            check(state.eat(context.getWorld(), pos, context.getWorld().getBlockState(pos), player).isAccepted(), "cake bite failed");
            check(player.getHungerManager().getFoodLevel() == 3, "cake ignored its food unit");
            if (bite < 6) check(context.getWorld().getBlockState(pos).get(CakeBlock.BITES) == bite + 1, "wrong cake bite count");
        }
        check(context.getWorld().getBlockState(pos).isAir(), "cake survived its seventh bite");
        player.networkHandler.disconnect(Text.literal("OriginLore gameplay test complete"));
        context.complete();
    }

    private static CraftingScreenHandler cookieRecipe(TestContext context, ServerPlayerEntity player, int count) {
        CraftingScreenHandler handler = new CraftingScreenHandler(84, player.getInventory(),
                ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
        handler.getSlot(1).setStack(new ItemStack(Items.WHEAT, count));
        handler.getSlot(2).setStack(new ItemStack(Items.COCOA_BEANS, count));
        handler.getSlot(3).setStack(new ItemStack(Items.WHEAT, count));
        return handler;
    }

    private static void assertIndependentServings(List<ItemStack> stacks, Item item, String source, int expected) {
        int count = 0;
        java.util.Set<NbtCompound> positions = new java.util.HashSet<>();
        for (ItemStack stack : stacks) {
            if (!stack.isOf(item)) continue;
            check(stack.getCount() <= stack.getMaxCount() && FoodUnits.records(stack).size() == stack.getCount(),
                    "food output exceeded its native capacity or lost serving records");
            count += stack.getCount();
            for (NbtCompound record : FoodUnits.records(stack)) {
                NbtCompound identity = record.getCompound("identity");
                check(source.equals(identity.getString("source_type")) && !identity.getString("variant_id").isEmpty(),
                        "produced serving lost its final quality or acquisition source");
                check(identity.getString("variant_id").equals(metadata(stack).getString("variant_id")),
                        "different food qualities were merged into the same output stack");
                positions.add(identity.getCompound("random_positions"));
            }
        }
        check(count == expected && positions.size() == expected, "production lost, duplicated, or reused a serving's random draw");
    }

    private static ItemStack food(int nutrition, String variant) {
        return food(Items.APPLE, nutrition, variant);
    }

    private static ItemStack food(Item item, int nutrition, String variant) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.FOOD, new FoodComponent(nutrition, 0.4F, false, 1.6F, Optional.empty(), List.of()));
        identified(stack, variant);
        FoodUnits.initialize(stack);
        return stack;
    }

    private static ItemStack identified(ItemStack stack, String variant) {
        NbtCompound metadata = new NbtCompound();
        metadata.putInt("metadata_version", 4);
        metadata.putString("managed_item_id", net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString());
        metadata.putString("source_type", "UNKNOWN");
        metadata.putString("variant_id", variant);
        metadata.putBoolean("variant_selected", true);
        metadata.putLong("config_revision", Originlore.getRevision());
        metadata.putDouble("quality_score", 0.5);
        NbtCompound root = new NbtCompound();
        root.put(ItemComponentManager.METADATA_KEY, metadata);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        return stack;
    }

    private static ItemEntry randomFood(String id, String sourceType) {
        ItemEntry entry = new ItemEntry(id);
        entry.base.lore = List.of("production test");
        SourceRule source = new SourceRule(sourceType);
        Variant variant = new Variant("fresh", 1);
        variant.rule.food = new FoodRule();
        variant.rule.food.nutritionRange = new int[]{1, 9};
        variant.rule.food.eatSecondsRange = new NumberRange(0.6, 2.4);
        source.variants.add(variant);
        entry.sources.add(source);
        return entry;
    }

    private static NbtCompound metadata(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt()
                .getCompound(ItemComponentManager.METADATA_KEY);
    }

    private static void setMetadataNumber(ItemStack stack, String key, double value) {
        NbtCompound root = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        root.getCompound(ItemComponentManager.METADATA_KEY).putDouble(key, value);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
    }

    private static ServerPlayerEntity survivalPlayer(TestContext context) {
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        // Mock players otherwise share random positions around the world spawn across all tests.
        player.setPosition(context.getAbsolutePos(new BlockPos(1, 2, 1)).toCenterPos());
        return player;
    }

    private static void disconnect(ServerPlayerEntity player) {
        player.getServerWorld().getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(3), entity -> true)
                .forEach(ItemEntity::discard);
        player.networkHandler.disconnect(Text.literal("OriginLore gameplay test complete"));
    }

    private static void cook(TestContext context, BlockPos pos, FurnaceBlockEntity furnace) {
        for (int tick = 0; tick < 220; tick++) {
            AbstractFurnaceBlockEntity.tick(context.getWorld(), pos, context.getWorld().getBlockState(pos), furnace);
        }
        check(!furnace.getStack(2).isEmpty(), "furnace did not complete production");
    }

    private static final class TestArrow extends ArrowEntity {
        private TestArrow(World world, LivingEntity owner, ItemStack weapon) { super(world, owner, new ItemStack(Items.ARROW), weapon); }
        private void hit(LivingEntity target) { super.onEntityHit(new EntityHitResult(target)); }
    }

    private static final class TestTrident extends TridentEntity {
        private TestTrident(World world, LivingEntity owner, ItemStack weapon) { super(world, owner, weapon); }
        private void hit(LivingEntity target) { super.onEntityHit(new EntityHitResult(target)); }
    }

    private static final class TestFirework extends FireworkRocketEntity {
        private TestFirework(World world) { super(EntityType.FIREWORK_ROCKET, world); }
        private void hit(LivingEntity target) { super.onEntityHit(new EntityHitResult(target)); }
    }

    private static final class ToggleInsertHandler extends ScreenHandler {
        private boolean allowInsertion = true;
        private ToggleInsertHandler() {
            super(null, 85);
            addSlot(new Slot(new SimpleInventory(1), 0, 0, 0));
        }
        @Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player) { return true; }
        @Override public boolean canInsertIntoSlot(Slot slot) { return allowInsertion; }
        @Override public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }

    private static int nutrition(ItemStack stack) { return stack.get(DataComponentTypes.FOOD).nutrition(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
