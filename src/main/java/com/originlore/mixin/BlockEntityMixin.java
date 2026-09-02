package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.server.RefreshService;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queues changed vanilla-compatible block inventories for lazy UNKNOWN refresh. */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "markDirty()V", at = @At("RETURN"))
    private void originlore$queueChangedInventory(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld) || !(self instanceof Inventory inventory)) return;
        RefreshService service = Originlore.getRefreshService();
        if (service != null) service.queueInventory(inventory);
    }
}
