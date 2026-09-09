package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradedItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantScreenHandler.class)
public abstract class MerchantScreenHandlerMixin {
    @Inject(method = "autofill", at = @At("HEAD"), cancellable = true)
    private void originlore$autofill(int targetSlot, TradedItem requested, CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        MerchantScreenHandler self = (MerchantScreenHandler) (Object) this;
        for (int slot = 3; slot < 39; slot++) {
            ItemStack source = self.getSlot(slot).getStack();
            if (source.isEmpty() || !requested.matches(source)) continue;
            ItemStack target = self.getSlot(targetSlot).getStack();
            if (target.isEmpty()) self.getSlot(targetSlot).setStack(source.split(source.getMaxCount()));
            else if (FoodUnits.canCombine(source, target)) FoodUnits.transfer(source, target, target.getMaxCount() - target.getCount());
            self.getSlot(slot).markDirty();
            self.getSlot(targetSlot).markDirty();
            ItemStack current = self.getSlot(targetSlot).getStack();
            if (current.getCount() >= current.getMaxCount()) break;
        }
        ci.cancel();
    }
}
