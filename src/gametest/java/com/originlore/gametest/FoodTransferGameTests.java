package com.originlore.gametest;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.gameplay.FoodUnits;
import com.originlore.gameplay.FoodTooltip;
import com.originlore.source.SourceContext;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class FoodTransferGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void servingTooltipFollowsTheQueueAndPreservesAuthoredLore(TestContext context) {
        ItemStack first = queue(2);
        ItemStack second = queue(8);
        Text authored = Text.literal("Hand-edited lore").styled(style -> style.withBold(true));
        first.set(DataComponentTypes.LORE, new LoreComponent(List.of(authored)));
        second.set(DataComponentTypes.LORE, new LoreComponent(List.of(authored)));
        var lookup = context.getWorld().getRegistryManager();
        FoodTooltip.refresh(first, lookup, "en_us");
        FoodTooltip.refresh(second, lookup, "en_us");
        check(first.get(DataComponentTypes.LORE).lines().getLast().getString().contains("Food 2"), "tooltip does not show the first serving");
        check(FoodUnits.canCombine(first, second), "display-only food numbers prevented a compatible merge");
        FoodUnits.transfer(second, first, 1);
        first.split(1);
        FoodTooltip.refresh(first, lookup, "en_us");
        var lore = first.get(DataComponentTypes.LORE).lines();
        check(lore.size() == 2 && lore.getFirst().equals(authored), "serving update altered manual text or duplicated its summary");
        check(lore.getLast().getString().contains("Food 8"), "tooltip did not advance to the next serving");
        FoodTooltip.refresh(first, lookup, "zh_cn");
        check(first.get(DataComponentTypes.LORE).lines().getFirst().equals(authored), "language change altered manual lore");
        first.remove(DataComponentTypes.CUSTOM_DATA);
        FoodTooltip.refresh(first, lookup, "en_us");
        check(first.get(DataComponentTypes.LORE).lines().equals(List.of(authored)), "rule removal left a generated summary");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void clientPredictionLeavesServerFoodRecordsUntouched(TestContext context) {
        ItemStack queue = queue(2, 5, 8);
        NbtComponent original = queue.get(DataComponentTypes.CUSTOM_DATA);
        CompletableFuture.runAsync(() -> {
            check(!Originlore.isOnServerThread(), "prediction test must run outside the server thread");
            ItemStack predicted = queue.copy();
            ItemStack copied = predicted.copyWithCount(1);
            check(original.equals(copied.get(DataComponentTypes.CUSTOM_DATA)), "client copy rewrote server records");
            ItemStack split = predicted.split(1);
            check(original.equals(split.get(DataComponentTypes.CUSTOM_DATA)), "client split rewrote server records");
            predicted.decrement(1);
            check(original.equals(predicted.get(DataComponentTypes.CUSTOM_DATA)), "client count prediction rewrote server records");
        }).join();
        check(FoodUnits.records(queue).size() == 3 && nutrition(queue) == 2, "prediction altered the authoritative stack");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void compatibleFoodStillHasStrictSyncAndSaveEquality(TestContext context) {
        ItemStack first = queue(2, 5);
        ItemStack second = queue(2, 8);
        check(FoodUnits.canCombine(first, second), "same quality food cannot merge");
        check(!ItemStack.areItemsAndComponentsEqual(first, second), "different tail records are invisible to synchronization");
        ItemStack renamed = second.copy();
        renamed.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Player food"));
        check(!FoodUnits.canCombine(first, renamed), "player names were ignored when merging");
        NbtCompound root = second.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        root.putString("another_mod", "preserve");
        second.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        check(!FoodUnits.canCombine(first, second), "unrelated custom data was ignored when merging");
        var ops = context.getWorld().getRegistryManager().getOps(NbtOps.INSTANCE);
        ItemStack loaded = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, first).getOrThrow()).getOrThrow();
        check(ItemStack.areItemsAndComponentsEqual(first, loaded), "save/load changed food records");
        ItemStack head = loaded.split(1);
        check(nutrition(head) == 2 && nutrition(loaded) == 5, "loaded food did not split from the front");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void uninitializedFoodPreservesNativeComponentCompatibility(TestContext context) {
        ItemComponentConfig config = new ItemComponentConfig(Path.of("build", "gametest", "food-merge-compatibility.json"));
        ItemComponentConfig.ItemEntry entry = new ItemComponentConfig.ItemEntry("minecraft:apple");
        entry.base.food = new ItemComponentConfig.FoodRule();
        entry.base.food.nutrition = 8;
        config.setItemConfig(entry.itemId, entry);
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemStack first = new ItemStack(Items.APPLE, 2);
        ItemStack second = new ItemStack(Items.APPLE, 3);
        first.set(DataComponentTypes.FOOD, new FoodComponent(1, 0.4F, false, 1.6F, Optional.empty(), List.of()));
        second.set(DataComponentTypes.FOOD, new FoodComponent(2, 0.4F, false, 1.6F, Optional.empty(), List.of()));
        var lookup = context.getWorld().getRegistryManager();
        check(manager.applyComponents(first, SourceContext.command(), lookup).success(), "first command food setup failed");
        check(manager.applyComponents(second, SourceContext.command(), lookup).success(), "second command food setup failed");
        check(!FoodUnits.hasQueue(first) && !FoodUnits.hasQueue(second), "test food already has a FIFO queue");
        check(first.get(DataComponentTypes.FOOD).equals(second.get(DataComponentTypes.FOOD)), "effective food values differ");
        check(ItemComponentManager.canStackIgnoringBookkeeping(first, second), "test does not exercise the bookkeeping shortcut");
        check(!FoodUnits.canCombine(first, second), "uninitialized food ignored incompatible native food values");
        check(FoodUnits.transfer(second, first, 3) == 0 && first.getCount() == 2 && second.getCount() == 3,
                "rejected native food merge changed quantities");
        FoodUnits.initialize(first);
        FoodUnits.initialize(second);
        check(!FoodUnits.canCombine(first, second), "initializing FIFO allowed incompatible native food to merge");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleClickCollectionAndOffhandSwapKeepFifo(TestContext context) {
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        try {
            SimpleInventory inventory = new SimpleInventory(27);
            GenericContainerScreenHandler handler = GenericContainerScreenHandler.createGeneric9x3(84, player.getInventory(), inventory);
            handler.setCursorStack(queue(1));
            inventory.setStack(0, queue(2, 3));
            inventory.setStack(1, queue(4, 5));
            handler.onSlotClick(2, 0, SlotActionType.PICKUP_ALL, player);
            check(handler.getCursorStack().getCount() == 5 && inventory.isEmpty(), "double-click lost or duplicated food");
            handler.onSlotClick(2, 0, SlotActionType.PICKUP, player);
            handler.onSlotClick(2, 40, SlotActionType.SWAP, player);
            ItemStack offhand = player.getOffHandStack();
            check(inventory.getStack(2).isEmpty() && offhand.getCount() == 5, "offhand swap lost food");
            for (int expected = 1; expected <= 5; expected++) {
                check(nutrition(offhand.split(1)) == expected, "collection changed serving order");
            }
            check(offhand.isEmpty(), "collection created extra servings");
        } finally {
            player.networkHandler.disconnect(Text.literal("OriginLore food transfer test complete"));
        }
        context.complete();
    }

    private static ItemStack queue(int... nutrition) {
        ItemStack result = ItemStack.EMPTY;
        for (int value : nutrition) {
            ItemStack unit = new ItemStack(Items.APPLE);
            unit.set(DataComponentTypes.FOOD, new FoodComponent(value, 0.4F, false, 1.6F, Optional.empty(), List.of()));
            NbtCompound identity = new NbtCompound();
            identity.putInt("metadata_version", 4);
            identity.putString("managed_item_id", "minecraft:apple");
            identity.putString("source_type", "UNKNOWN");
            identity.putString("variant_id", "test_quality");
            identity.putBoolean("variant_selected", true);
            identity.putLong("config_revision", Originlore.getRevision());
            NbtCompound root = new NbtCompound();
            root.put(ItemComponentManager.METADATA_KEY, identity);
            unit.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
            FoodUnits.initialize(unit);
            if (result.isEmpty()) result = unit;
            else check(FoodUnits.transfer(unit, result, 1) == 1, "failed to arrange the test queue");
        }
        return result;
    }

    private static int nutrition(ItemStack stack) { return stack.get(DataComponentTypes.FOOD).nutrition(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
