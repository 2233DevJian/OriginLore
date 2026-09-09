package com.originlore.mixin;

import com.originlore.gameplay.Production;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({CraftingScreenHandler.class, PlayerScreenHandler.class, MerchantScreenHandler.class, StonecutterScreenHandler.class})
public abstract class ProductionQuickMoveMixin {
    @Inject(method = "quickMove", at = @At("HEAD"), cancellable = true)
    private void originlore$commit(PlayerEntity player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        ScreenHandler handler = (ScreenHandler) (Object) this;
        if (Production.handles(handler, slot)) cir.setReturnValue(Production.quickMove(handler, slot, player));
    }
}
