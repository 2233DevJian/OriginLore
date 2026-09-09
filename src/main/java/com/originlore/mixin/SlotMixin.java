package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method = "insertStack(Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void originlore$insert(ItemStack source, int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (!Originlore.isOnServerThread() || !ItemComponentManager.hasOriginLoreMetadata(source)) return;
        Slot self = (Slot) (Object) this;
        if (self.canInsert(source)) {
            ItemStack target = self.getStack();
            int moved = Math.max(0, Math.min(amount, self.getMaxItemCount(source) - target.getCount()));
            if (target.isEmpty()) self.setStack(source.split(moved));
            else if (FoodUnits.canCombine(source, target)) {
                FoodUnits.transfer(source, target, moved);
                self.setStack(target);
            }
        }
        cir.setReturnValue(source);
    }
}
