package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers custom SmithingRecipe implementations without targeting their classes. */
@Mixin(SmithingScreenHandler.class)
public abstract class SmithingScreenHandlerMixin {
    @Inject(method = "updateResult()V", at = @At("RETURN"))
    private void originlore$applySmithingFallback(CallbackInfo ci) {
        if (!Originlore.isOnServerThread()) return;
        Slot output = ((SmithingScreenHandler) (Object) this).getSlot(3);
        ItemStack stack = output.getStack();
        if (stack.isEmpty()) return;
        RecipeEntry<?> recipe = output.inventory instanceof CraftingResultInventory result
                ? result.getLastRecipe() : null;
        Originlore.applyCustomComponents(stack, SourceContext.recipe(SourceType.SMITHING,
                recipe == null ? null : recipe.id()));
    }
}
