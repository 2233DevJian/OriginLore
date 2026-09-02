package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** UNKNOWN fallback for items granted directly by mods instead of a standard generation path. */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow @Final public PlayerEntity player;

    @Inject(
            method = {
                    "insertStack(Lnet/minecraft/item/ItemStack;)Z",
                    "insertStack(ILnet/minecraft/item/ItemStack;)Z"
            },
            at = @At("HEAD")
    )
    private void originlore$applyBeforeInsert(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        applyIfServerPlayer(stack);
    }

    @Inject(method = "setStack(ILnet/minecraft/item/ItemStack;)V", at = @At("HEAD"))
    private void originlore$applyBeforeSet(int slot, ItemStack stack, CallbackInfo ci) {
        applyIfServerPlayer(stack);
    }

    private void applyIfServerPlayer(ItemStack stack) {
        if (player instanceof ServerPlayerEntity) {
            Originlore.applyCustomComponents(stack, SourceContext.unknown());
        }
    }
}
