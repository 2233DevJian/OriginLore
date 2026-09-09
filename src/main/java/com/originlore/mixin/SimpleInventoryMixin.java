package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.ItemComponentManager;
import com.originlore.gameplay.FoodUnits;
import com.originlore.server.RefreshService;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queues transient server inventories while excluding integrated-client copies by thread. */
@Mixin(SimpleInventory.class)
public abstract class SimpleInventoryMixin {
    @Redirect(method = {"addToExistingSlot", "canInsert"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;areItemsAndComponentsEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"))
    private boolean originlore$compatible(ItemStack left, ItemStack right) {
        return Originlore.isOnServerThread() ? FoodUnits.canCombine(left, right) : ItemStack.areItemsAndComponentsEqual(left, right);
    }

    @Inject(method = "transfer", at = @At("HEAD"), cancellable = true)
    private void originlore$transfer(ItemStack source, ItemStack target, CallbackInfo ci) {
        if (!Originlore.isOnServerThread() || !ItemComponentManager.hasOriginLoreMetadata(source)) return;
        SimpleInventory self = (SimpleInventory) (Object) this;
        if (FoodUnits.transfer(source, target, self.getMaxCount(target) - target.getCount()) > 0) self.markDirty();
        ci.cancel();
    }

    @Inject(method = "markDirty()V", at = @At("RETURN"))
    private void originlore$queueChangedInventory(CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        RefreshService service = Originlore.getRefreshService();
        if (service != null) service.queueInventory((Inventory) (Object) this);
    }
}
