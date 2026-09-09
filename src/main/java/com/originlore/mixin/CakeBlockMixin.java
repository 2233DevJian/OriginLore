package com.originlore.mixin;

import com.originlore.gameplay.PlacedCakes;
import net.minecraft.block.BlockState;
import net.minecraft.block.CakeBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CakeBlock.class)
public abstract class CakeBlockMixin {
    @Inject(method = "tryEat", at = @At("HEAD"), cancellable = true)
    private static void originlore$eat(WorldAccess world, BlockPos pos, BlockState state, PlayerEntity player,
                                        CallbackInfoReturnable<ActionResult> cir) {
        if (world instanceof ServerWorld serverWorld) {
            ActionResult result = PlacedCakes.get(serverWorld).eat(serverWorld, pos, state, player);
            if (result != null) cir.setReturnValue(result);
        }
    }
}
