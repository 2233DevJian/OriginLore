package com.originlore.mixin;

import com.originlore.gameplay.Harvest;
import net.minecraft.block.CaveVines;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CaveVines.class)
public interface CaveVinesMixin {
    @Redirect(method = "pickBerries", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/Block;dropStack(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/item/ItemStack;)V"))
    private static void originlore$harvest(World world, BlockPos pos, ItemStack stack) { Harvest.drop(world, pos, stack); }
}
