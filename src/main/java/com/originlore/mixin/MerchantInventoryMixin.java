package com.originlore.mixin;

import com.originlore.Originlore;
import net.minecraft.village.MerchantInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantInventory.class)
public abstract class MerchantInventoryMixin {
    @Inject(method = "updateOffers", at = @At("RETURN"))
    private void originlore$preview(CallbackInfo ci) {
        if (Originlore.isOnServerThread()) Originlore.applyCustomComponents(((MerchantInventory) (Object) this).getStack(2));
    }
}
