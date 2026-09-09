package com.originlore.gameplay;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryWrapper;

import java.util.List;

public final class Harvest {
    private Harvest() { }

    public static void prepareContainer(ItemStack output, RegistryWrapper.WrapperLookup lookup) {
        if (!Originlore.isOnServerThread() || Originlore.getManager() == null
                || com.originlore.ItemComponentManager.hasOriginLoreMetadata(output)) return;
        Originlore.getManager().applyProducedComponents(output, new SourceContext(SourceType.HARVEST), lookup, 0.5, 0);
        FoodUnits.initialize(output);
    }

    public static void drop(World world, BlockPos pos, ItemStack stack) {
        if (!world.isClient && Originlore.getManager() != null) {
            List<ItemStack> outputs = Production.roll(stack, new SourceContext(SourceType.HARVEST), List.of());
            if (!outputs.isEmpty()) {
                for (ItemStack output : outputs) Block.dropStack(world, pos, output);
                return;
            }
        }
        Block.dropStack(world, pos, stack);
    }
}
