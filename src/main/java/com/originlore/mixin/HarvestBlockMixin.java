package com.originlore.mixin;

import com.originlore.gameplay.Harvest;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

public final class HarvestBlockMixin {
    @Mixin(SweetBerryBushBlock.class)
    public abstract static class SweetBerries {
        @Redirect(method = "onUse", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/block/SweetBerryBushBlock;dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/item/ItemStack;)V"))
        private void originlore$harvest(World world, BlockPos pos, ItemStack stack) { Harvest.drop(world, pos, stack); }
    }

    @Mixin(BeehiveBlock.class)
    public abstract static class Honeycomb {
        @Redirect(method = "dropHoneycomb", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/block/BeehiveBlock;dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/item/ItemStack;)V"))
        private static void originlore$harvest(World world, BlockPos pos, ItemStack stack) { Harvest.drop(world, pos, stack); }
    }
}
