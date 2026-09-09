package com.originlore.gametest;

import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.List;

public final class MapCraftingGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void workbenchDeliversScaledMapAfterNativeCraftCallback(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        try {
            installMapRule(config);
            ItemStack map = newMap(context);
            MapIdComponent originalId = map.get(DataComponentTypes.MAP_ID);
            CraftingScreenHandler handler = new CraftingScreenHandler(75, player.getInventory(),
                    ScreenHandlerContext.create(context.getWorld(), context.getAbsolutePos(BlockPos.ORIGIN)));
            for (int slot = 1; slot <= 9; slot++) {
                handler.getSlot(slot).setStack(slot == 5 ? map : new ItemStack(Items.PAPER, 2));
            }
            check(handler.getSlot(0).getStack().isOf(Items.FILLED_MAP), "map expansion preview missing");
            check(originalId.equals(handler.getSlot(0).getStack().get(DataComponentTypes.MAP_ID)),
                    "preview allocated the final map ID");
            handler.onSlotClick(0, 0, SlotActionType.PICKUP, player);
            assertScaledMap(context, handler.getCursorStack(), originalId);
            for (int slot = 1; slot <= 9; slot++) {
                ItemStack remaining = handler.getSlot(slot).getStack();
                check(slot == 5 ? remaining.isEmpty() : remaining.isOf(Items.PAPER) && remaining.getCount() == 1,
                        "workbench map expansion consumed the wrong input quantity");
            }
            check(player.getInventory().isEmpty(), "workbench map expansion produced an extra item");
        } finally {
            player.networkHandler.disconnect(Text.literal("OriginLore map crafting test complete"));
            check(config.replaceSnapshot(original, config.getRevision()).success(), "map config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void crafterDeliversScaledMapAfterNativeCraftCallback(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        ConfigSnapshot original = config.snapshot();
        try {
            installMapRule(config);
            ItemStack map = newMap(context);
            MapIdComponent originalId = map.get(DataComponentTypes.MAP_ID);
            BlockPos pos = context.getAbsolutePos(new BlockPos(1, 1, 1));
            BlockState state = Blocks.CRAFTER.getDefaultState();
            context.getWorld().setBlockState(pos, state);
            BlockPos target = pos.offset(state.get(Properties.ORIENTATION).getFacing());
            context.getWorld().setBlockState(target, Blocks.CHEST.getDefaultState());
            CrafterBlockEntity crafter = (CrafterBlockEntity) context.getWorld().getBlockEntity(pos);
            for (int slot = 0; slot < 9; slot++) crafter.setStack(slot, slot == 4 ? map : new ItemStack(Items.PAPER, 2));
            state.scheduledTick(context.getWorld(), pos, context.getWorld().random);
            Inventory chest = (Inventory) context.getWorld().getBlockEntity(target);
            assertScaledMap(context, chest.getStack(0), originalId);
            for (int slot = 1; slot < chest.size(); slot++) check(chest.getStack(slot).isEmpty(), "crafter produced an extra item");
            for (int slot = 0; slot < 9; slot++) {
                ItemStack remaining = crafter.getStack(slot);
                check(slot == 4 ? remaining.isEmpty() : remaining.isOf(Items.PAPER) && remaining.getCount() == 1,
                        "crafter map expansion consumed the wrong input quantity");
            }
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "map config restore failed");
        }
        context.complete();
    }

    private static void installMapRule(ItemComponentConfig config) {
        ItemEntry entry = new ItemEntry("minecraft:filled_map");
        entry.base.lore = List.of("Map crafting regression");
        config.setItemConfig(entry.itemId, entry);
        check(config.save().success(), "map config save failed");
    }

    private static ItemStack newMap(TestContext context) {
        BlockPos pos = context.getAbsolutePos(BlockPos.ORIGIN);
        return FilledMapItem.createMap(context.getWorld(), pos.getX(), pos.getZ(), (byte) 0, true, false);
    }

    private static void assertScaledMap(TestContext context, ItemStack output, MapIdComponent originalId) {
        check(output.isOf(Items.FILLED_MAP) && output.getCount() == 1, "map expansion lost its output");
        MapIdComponent outputId = output.get(DataComponentTypes.MAP_ID);
        check(outputId != null && outputId.id() == originalId.id() + 1, "native map callback did not allocate exactly one new ID");
        MapState scaled = FilledMapItem.getMapState(output, context.getWorld());
        MapState original = FilledMapItem.getMapState(originalId, context.getWorld());
        check(scaled != null && scaled.scale == 1 && original != null && original.scale == 0,
                "delivered map has the wrong scale or changed the original map state");
        check(!output.contains(DataComponentTypes.MAP_POST_PROCESSING), "delivered map retains an unapplied processing marker");
        check(output.get(DataComponentTypes.LORE) != null
                && output.get(DataComponentTypes.LORE).lines().stream().anyMatch(line -> line.getString().equals("Map crafting regression")),
                "native map callback erased managed lore");
        check(Originlore.getManager().refresh(output, context.getWorld().getRegistryManager()).success(), "map refresh failed");
        check(outputId.equals(output.get(DataComponentTypes.MAP_ID)) && !output.contains(DataComponentTypes.MAP_POST_PROCESSING),
                "refresh reverted the completed native map processing");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
