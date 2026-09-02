package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies CUTTING after the screen has selected any compatible recipe implementation. */
@Mixin(StonecutterScreenHandler.class)
public abstract class StonecutterScreenHandlerMixin {
    @Inject(method = "populateResult()V", at = @At("RETURN"))
    private void originlore$applyCuttingFallback(CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        Slot output = ((StonecutterScreenHandler) (Object) this).getSlot(1);
        ItemStack stack = output.getStack();
        if (stack.isEmpty()) return;
        RecipeEntry<?> recipe = output.inventory instanceof CraftingResultInventory result
                ? result.getLastRecipe() : null;
        Originlore.applyCustomComponents(stack, SourceContext.recipe(SourceType.CUTTING,
                recipe == null ? null : recipe.id()));
    }
}
