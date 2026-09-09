package com.originlore.mixin;

import com.originlore.gameplay.PlacedCakes;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
    @Shadow public abstract World getWorld();

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void originlore$removeCake(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous != null && PlacedCakes.isCake(previous) && !PlacedCakes.isCake(state)
                && getWorld() instanceof ServerWorld world) PlacedCakes.get(world).remove(pos);
    }
}
