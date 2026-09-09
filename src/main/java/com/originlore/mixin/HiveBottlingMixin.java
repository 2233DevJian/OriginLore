package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.gameplay.Production;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(BeehiveBlock.class)
public abstract class HiveBottlingMixin {
    @Unique private static final ThreadLocal<ItemStack> originlore$bottle = new ThreadLocal<>();

    @Inject(method = "onUseWithItem", at = @At("HEAD"), cancellable = true)
    private void originlore$prepare(ItemStack input, BlockState state, World world, BlockPos pos, PlayerEntity player,
                                      Hand hand, BlockHitResult hit, CallbackInfoReturnable<ItemActionResult> cir) {
        originlore$bottle.remove();
        if (!Originlore.isOnServerThread() || !input.isOf(Items.GLASS_BOTTLE) || state.get(BeehiveBlock.HONEY_LEVEL) < 5) return;
        List<ItemStack> outputs = Production.roll(new ItemStack(Items.HONEY_BOTTLE), new SourceContext(SourceType.HARVEST), List.of());
        if (outputs.isEmpty()) cir.setReturnValue(ItemActionResult.FAIL);
        else originlore$bottle.set(outputs.getFirst());
    }

    @Redirect(method = "onUseWithItem", at = @At(value = "NEW", target = "(Lnet/minecraft/item/ItemConvertible;)Lnet/minecraft/item/ItemStack;"))
    private ItemStack originlore$preparedBottle(ItemConvertible item) {
        ItemStack prepared = originlore$bottle.get();
        return prepared == null ? new ItemStack(item) : prepared.copy();
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void originlore$clear(ItemStack input, BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 Hand hand, BlockHitResult hit, CallbackInfoReturnable<ItemActionResult> cir) {
        originlore$bottle.remove();
    }
}
