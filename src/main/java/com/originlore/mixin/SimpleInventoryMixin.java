package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.server.RefreshService;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queues transient server inventories while excluding integrated-client copies by thread. */
@Mixin(SimpleInventory.class)
public abstract class SimpleInventoryMixin {
    @Inject(method = "markDirty()V", at = @At("RETURN"))
    private void originlore$queueChangedInventory(CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        RefreshService service = Originlore.getRefreshService();
        if (service != null) service.queueInventory((Inventory) (Object) this);
    }
}
