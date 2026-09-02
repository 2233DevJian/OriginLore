package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Assigns SMITHING to anvil-created stacks that do not already carry provenance. */
@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin {
    @Inject(method = "updateResult()V", at = @At("RETURN"))
    private void originlore$applyAnvil(CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        ItemStack output = ((AnvilScreenHandler) (Object) this).getSlot(2).getStack();
        if (!output.isEmpty()) {
            Originlore.applyCustomComponents(output, new SourceContext(SourceType.SMITHING));
        }
    }
}
