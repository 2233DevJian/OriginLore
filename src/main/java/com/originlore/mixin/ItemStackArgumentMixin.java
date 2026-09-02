package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks stacks created by item-stack command arguments, including /give. */
@Mixin(ItemStackArgument.class)
public abstract class ItemStackArgumentMixin {
    @Inject(method = "createStack(IZ)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"))
    private void originlore$applyCommand(int amount, boolean checkOverstack,
                                         CallbackInfoReturnable<ItemStack> cir) {
        if (Originlore.isOnServerThread()) {
            Originlore.applyCustomComponents(cir.getReturnValue(), SourceContext.command());
        }
    }
}
