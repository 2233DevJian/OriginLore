package com.originlore.mixin;

import com.originlore.gameplay.Production;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CampfireBlockEntity.class)
public abstract class CampfireMixin {
    @Redirect(method = "litServerTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;isItemEnabled(Lnet/minecraft/resource/featuretoggle/FeatureSet;)Z"))
    private static boolean originlore$acceptPreparedOutput(ItemStack output, FeatureSet features) {
        return !output.isEmpty() && output.isItemEnabled(features);
    }

    @Inject(method = "litServerTick", at = @At("HEAD"))
    private static void originlore$begin(World world, BlockPos pos, BlockState state, CampfireBlockEntity campfire, CallbackInfo ci) {
        Production.cooking(true);
    }

    @Inject(method = "litServerTick", at = @At("RETURN"))
    private static void originlore$end(World world, BlockPos pos, BlockState state, CampfireBlockEntity campfire, CallbackInfo ci) {
        Production.cooking(false);
    }
}
