package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Allows equivalent OriginLore variants to stack across compatible inventories. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(
            method = "areItemsAndComponentsEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void originlore$compareBookkeeping(ItemStack left, ItemStack right,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && ItemComponentManager.canStackIgnoringBookkeeping(left, right)) {
            cir.setReturnValue(true);
        }
    }
}
