package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.gameplay.Production;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CuttingRecipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.SmithingTransformRecipe;
import net.minecraft.recipe.SmithingTrimRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.recipe.input.SmithingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Uses the concrete 1.21.1 input signatures. Custom recipes inheriting these
 * vanilla implementations are covered; unrelated implementations fall back to UNKNOWN.
 */
public class RecipeMixin {

    /** 熔炉/高炉/烟熏炉（抽象类，子类 FurnaceRecipe 等继承） */
    @Mixin(AbstractCookingRecipe.class)
    public static class AbstractCookingRecipeMixin {
        @Inject(
            method = "craft(Lnet/minecraft/recipe/input/SingleStackRecipeInput;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;",
            at = @At("RETURN")
        )
        private void originlore$applyCrafted(SingleStackRecipeInput input, RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<ItemStack> cir) {
            if (Originlore.isOnServerThread() && Production.cooking() && !cir.getReturnValue().isEmpty()) {
                java.util.List<ItemStack> outputs = Production.roll(cir.getReturnValue(), SourceContext.recipe(SourceType.SMELTING,
                        Originlore.resolveRecipeId(this)), java.util.List.of(input.getStackInSlot(0).copyWithCount(1)));
                if (outputs.size() != 1) {
                    cir.getReturnValue().setCount(0);
                    return;
                }
                ItemStack produced = outputs.getFirst();
                ((ItemStackAccessor) (Object) cir.getReturnValue()).originlore$getComponents().setChanges(produced.getComponentChanges());
            }
        }
    }

    /** 切石机（抽象类，StonecuttingRecipe 父类） */
    @Mixin(CuttingRecipe.class)
    public static class CuttingRecipeMixin {
        @Inject(
            method = "craft(Lnet/minecraft/recipe/input/SingleStackRecipeInput;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;",
            at = @At("RETURN")
        )
        private void originlore$applyCrafted(SingleStackRecipeInput input, RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<ItemStack> cir) {
            if (Originlore.isOnServerThread()) Originlore.applyCustomComponents(cir.getReturnValue(), SourceContext.unknown());
        }
    }

    /** 铁匠铺转化 */
    @Mixin(SmithingTransformRecipe.class)
    public static class SmithingTransformRecipeMixin {
        @Inject(
            method = "craft(Lnet/minecraft/recipe/input/SmithingRecipeInput;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;",
            at = @At("RETURN")
        )
        private void originlore$applyCrafted(SmithingRecipeInput input, RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<ItemStack> cir) {
            if (Originlore.isOnServerThread() && !cir.getReturnValue().isEmpty() && Originlore.getManager() != null) {
                Originlore.getManager().applyInheritedComponents(cir.getReturnValue(), input.base(),
                        SourceContext.recipe(SourceType.SMITHING, Originlore.resolveRecipeId(this)), registries);
            }
        }
    }

    @Mixin(SmithingTrimRecipe.class)
    public static class SmithingTrimRecipeMixin {
        @Inject(
            method = "craft(Lnet/minecraft/recipe/input/SmithingRecipeInput;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;",
            at = @At("RETURN")
        )
        private void originlore$applyCrafted(SmithingRecipeInput input, RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<ItemStack> cir) {
            if (Originlore.isOnServerThread() && !cir.getReturnValue().isEmpty() && Originlore.getManager() != null) {
                Originlore.getManager().applyInheritedComponents(cir.getReturnValue(), input.base(),
                        SourceContext.recipe(SourceType.SMITHING, Originlore.resolveRecipeId(this)), registries);
            }
        }
    }

}
