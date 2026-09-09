package com.originlore.mixin;

import com.originlore.gameplay.FoodUnits;
import com.originlore.Originlore;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Count operations conserve per-unit records; synchronization equality stays strict. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "split", at = @At("HEAD"), cancellable = true)
    private void originlore$split(int amount, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (Originlore.isOnServerThread() && FoodUnits.hasQueue(self)) cir.setReturnValue(FoodUnits.split(self, amount));
    }

    @Inject(method = "setCount", at = @At("HEAD"))
    private void originlore$count(int count, CallbackInfo ci) {
        if (Originlore.isOnServerThread()) FoodUnits.countChanging((ItemStack) (Object) this, count);
    }

    @Inject(method = "copyWithCount", at = @At("RETURN"))
    private void originlore$copy(int count, CallbackInfoReturnable<ItemStack> cir) {
        if (Originlore.isOnServerThread()) FoodUnits.copied((ItemStack) (Object) this, cir.getReturnValue(), count);
    }
}
