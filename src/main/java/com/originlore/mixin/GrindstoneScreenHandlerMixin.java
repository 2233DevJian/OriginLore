package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GrindstoneScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GrindstoneScreenHandler.class)
public abstract class GrindstoneScreenHandlerMixin {
    @Inject(method = "updateResult", at = @At("RETURN"))
    private void originlore$inherit(CallbackInfo ci) {
        if (!Originlore.isOnServerThread() || Originlore.getManager() == null) return;
        GrindstoneScreenHandler self = (GrindstoneScreenHandler) (Object) this;
        ItemStack primary = self.getSlot(0).getStack();
        if (primary.isEmpty()) primary = self.getSlot(1).getStack();
        Originlore.getManager().applyInheritedComponents(self.getSlot(2).getStack(), primary,
                new SourceContext(SourceType.SMITHING), Originlore.getServer().getRegistryManager());
    }
}
