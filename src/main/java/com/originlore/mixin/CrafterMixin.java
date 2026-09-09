package com.originlore.mixin;

import com.originlore.gameplay.Production;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.block.BlockState;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.recipe.input.RecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

@Mixin(CrafterBlock.class)
public abstract class CrafterMixin {
    @Unique
    private List<ItemStack> originlore$preparedOutputs = List.of();
    @Unique
    private ItemStack originlore$preparedTemplate = ItemStack.EMPTY;

    @Shadow
    protected abstract void transferOrSpawnStack(ServerWorld world, BlockPos pos, CrafterBlockEntity blockEntity,
                                                 ItemStack stack, BlockState state, RecipeEntry<CraftingRecipe> recipe);

    @Redirect(method = "craft", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/recipe/CraftingRecipe;craft(Lnet/minecraft/recipe/input/RecipeInput;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;"))
    private ItemStack originlore$prepare(CraftingRecipe recipe, RecipeInput input, RegistryWrapper.WrapperLookup lookup) {
        ItemStack template = recipe.craft((CraftingRecipeInput) input, lookup);
        List<ItemStack> ingredients = new ArrayList<>();
        for (int slot = 0; slot < input.getSize(); slot++) {
            ItemStack stack = input.getStackInSlot(slot);
            if (!stack.isEmpty()) ingredients.add(stack.copyWithCount(1));
        }
        originlore$preparedOutputs = Production.craft(template, SourceContext.recipe(SourceType.CRAFTING,
                com.originlore.Originlore.resolveRecipeId(recipe)), ingredients);
        originlore$preparedTemplate = template.copy();
        return originlore$preparedOutputs.isEmpty() ? ItemStack.EMPTY : template;
    }

    @Redirect(method = "craft", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/block/CrafterBlock;transferOrSpawnStack(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/CrafterBlockEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/block/BlockState;Lnet/minecraft/recipe/RecipeEntry;)V"))
    private void originlore$produce(CrafterBlock self, ServerWorld world, BlockPos pos, CrafterBlockEntity blockEntity,
                                     ItemStack template, BlockState state, RecipeEntry<CraftingRecipe> recipe) {
        List<ItemStack> outputs = originlore$preparedOutputs;
        originlore$preparedOutputs = List.of();
        Production.carryNativeCraftChanges(originlore$preparedTemplate, template, outputs);
        originlore$preparedTemplate = ItemStack.EMPTY;
        for (ItemStack output : outputs) {
            transferOrSpawnStack(world, pos, blockEntity, output, state, recipe);
        }
    }
}
