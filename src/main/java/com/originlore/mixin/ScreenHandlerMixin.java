package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.FoodInteractions;
import com.originlore.gameplay.Production;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {
    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void originlore$click(int index, int button, SlotActionType action, PlayerEntity player, CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        ScreenHandler self = (ScreenHandler) (Object) this;
        if (Production.click(self, index, button, action, player)
                || FoodInteractions.click(self, index, button, action, player)) ci.cancel();
    }

    @Inject(method = "insertItem", at = @At("HEAD"), cancellable = true)
    private void originlore$insert(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> cir) {
        if (Originlore.isOnServerThread() && ItemComponentManager.hasOriginLoreMetadata(stack)) {
            cir.setReturnValue(FoodInteractions.insert((ScreenHandler) (Object) this, stack, start, end, reverse));
        }
    }
}
