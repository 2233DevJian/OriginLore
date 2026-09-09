package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "canMerge(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private static void originlore$canMerge(ItemStack target, ItemStack source, CallbackInfoReturnable<Boolean> cir) {
        if (Originlore.isOnServerThread() && ItemComponentManager.hasOriginLoreMetadata(source)) {
            cir.setReturnValue(source.getCount() + target.getCount() <= Math.min(64, target.getMaxCount())
                    && FoodUnits.canCombine(source, target));
        }
    }

    @Inject(method = "merge(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;I)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private static void originlore$merge(ItemStack target, ItemStack source, int limit, CallbackInfoReturnable<ItemStack> cir) {
        if (Originlore.isOnServerThread() && ItemComponentManager.hasOriginLoreMetadata(source)) {
            ItemStack combined = target.copy();
            FoodUnits.transfer(source, combined, Math.min(limit, target.getMaxCount()) - combined.getCount());
            cir.setReturnValue(combined);
        }
    }
}
