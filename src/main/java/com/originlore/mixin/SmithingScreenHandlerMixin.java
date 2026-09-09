package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.SmithingTransformRecipe;
import net.minecraft.recipe.SmithingTrimRecipe;
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
        // Their craft hooks already inherited the primary item. Applying inheritance
        // again would back up the new quality's overrides as native components.
        if (recipe != null && (recipe.value() instanceof SmithingTransformRecipe
                || recipe.value() instanceof SmithingTrimRecipe)) return;
        if (Originlore.getManager() != null) Originlore.getManager().applyInheritedComponents(stack,
                ((SmithingScreenHandler) (Object) this).getSlot(1).getStack(),
                SourceContext.recipe(SourceType.SMITHING, recipe == null ? null : recipe.id()),
                Originlore.getServer().getRegistryManager());
    }
}
