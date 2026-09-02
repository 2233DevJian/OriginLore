package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies the crafting rule at the point where vanilla stores the result.
 *
 * The old implementation injected at method return, after vanilla had already
 * sent the result-slot packet.  That left the server-side result carrying
 * OriginLore components while the client still held the pre-modification
 * stack, which breaks normal and shift-click crafting.  Redirecting the
 * actual setStack call keeps the stored result, tracked slot and packet on
 * the same ItemStack.
 */
@Mixin(CraftingScreenHandler.class)
public abstract class CraftingScreenHandlerMixin {
    @Redirect(
            method = "updateResult(Lnet/minecraft/screen/ScreenHandler;Lnet/minecraft/world/World;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/inventory/RecipeInputInventory;Lnet/minecraft/inventory/CraftingResultInventory;Lnet/minecraft/recipe/RecipeEntry;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/inventory/CraftingResultInventory;setStack(ILnet/minecraft/item/ItemStack;)V")
    )
    private static void originlore$applyBeforeResultPacket(CraftingResultInventory result,
                                                            int slot, ItemStack stack) {
        if (Originlore.isOnServerThread() && !stack.isEmpty()) {
            RecipeEntry<?> recipe = result.getLastRecipe();
            Originlore.applyCustomComponents(stack, SourceContext.recipe(SourceType.CRAFTING,
                    recipe == null ? null : recipe.id()));
        }
        result.setStack(slot, stack);
    }
}
