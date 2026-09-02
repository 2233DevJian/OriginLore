package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.block.Block;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes cooking a one-result-at-a-time pipeline.  Vanilla has only one output
 * slot, so allowing the next recipe to start before that slot is emptied would
 * either reuse the first variant or consume an input that cannot be inserted.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceMixin {
    @org.spongepowered.asm.mixin.Shadow
    int burnTime;

    @org.spongepowered.asm.mixin.Shadow
    int fuelTime;

    @Unique
    private boolean originlore$waitingForOutput;

    @Unique
    private int originlore$pausedBurnTime;

    @Unique
    private int originlore$pausedFuelTime;

    @Unique
    private boolean originlore$trackingTick;

    @Unique
    private int originlore$inputCountBeforeTick;

    @Unique
    private int originlore$outputCountBeforeTick;

    @Inject(
            method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/AbstractFurnaceBlockEntity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void originlore$pauseBeforeTick(World world, BlockPos pos, BlockState state,
                                                    AbstractFurnaceBlockEntity blockEntity, CallbackInfo ci) {
        FurnaceMixin self = (FurnaceMixin) (Object) blockEntity;
        ItemStack output = blockEntity.getStack(2);

        // A persisted or newly completed OriginLore output blocks the next
        // cooking operation until the player/hopper takes it out.
        if (self.originlore$waitingForOutput) {
            if (!output.isEmpty()) {
                originlore$stopBurning(world, pos, state, blockEntity, self);
                ci.cancel();
                return;
            }
            self.originlore$waitingForOutput = false;
            blockEntity.markDirty();
            if (self.originlore$pausedBurnTime > 0) {
                self.burnTime = self.originlore$pausedBurnTime;
                self.fuelTime = self.originlore$pausedFuelTime;
                self.originlore$pausedBurnTime = 0;
                self.originlore$pausedFuelTime = 0;
            }
        } else if (!output.isEmpty() && Originlore.shouldPauseFurnace(output, blockEntity.getStack(0))) {
            // Recover the pause state for furnaces loaded from an older build,
            // or for a server restart before the custom flag was written.
            self.originlore$waitingForOutput = true;
            blockEntity.markDirty();
            originlore$stopBurning(world, pos, state, blockEntity, self);
            ci.cancel();
            return;
        }

        self.originlore$trackingTick = true;
        self.originlore$inputCountBeforeTick = blockEntity.getStack(0).getCount();
        self.originlore$outputCountBeforeTick = output.getCount();
    }

    @Inject(
            method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/AbstractFurnaceBlockEntity;)V",
            at = @At("RETURN")
    )
    private static void originlore$pauseAfterCompletedRecipe(World world, BlockPos pos, BlockState state,
                                                              AbstractFurnaceBlockEntity blockEntity,
                                                              CallbackInfo ci) {
        FurnaceMixin self = (FurnaceMixin) (Object) blockEntity;
        if (!self.originlore$trackingTick) return;
        self.originlore$trackingTick = false;

        int inputAfter = blockEntity.getStack(0).getCount();
        int outputAfter = blockEntity.getStack(2).getCount();
        if (inputAfter < self.originlore$inputCountBeforeTick
                && outputAfter > self.originlore$outputCountBeforeTick) {
            self.originlore$waitingForOutput = true;
            blockEntity.markDirty();
        }
    }

    @Unique
    private static void originlore$stopBurning(World world, BlockPos pos, BlockState state,
                                                AbstractFurnaceBlockEntity blockEntity, FurnaceMixin self) {
        if (self.burnTime <= 0) return;
        self.originlore$pausedBurnTime = self.burnTime;
        self.originlore$pausedFuelTime = self.fuelTime;
        self.burnTime = 0;
        self.fuelTime = 0;
        if (state.get(AbstractFurnaceBlock.LIT)) {
            world.setBlockState(pos, state.with(AbstractFurnaceBlock.LIT, false), Block.NOTIFY_ALL);
        }
    }

    @Inject(method = "readNbt", at = @At("RETURN"))
    private void originlore$readPauseState(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup, CallbackInfo ci) {
        originlore$waitingForOutput = nbt.getBoolean("OriginLoreWaitingForOutput");
        originlore$pausedBurnTime = nbt.getInt("OriginLorePausedBurnTime");
        originlore$pausedFuelTime = nbt.getInt("OriginLorePausedFuelTime");
    }

    @Inject(method = "writeNbt", at = @At("RETURN"))
    private void originlore$writePauseState(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup, CallbackInfo ci) {
        nbt.putBoolean("OriginLoreWaitingForOutput", originlore$waitingForOutput);
        nbt.putInt("OriginLorePausedBurnTime", originlore$pausedBurnTime);
        nbt.putInt("OriginLorePausedFuelTime", originlore$pausedFuelTime);
    }

    @Redirect(
            method = "craftRecipe(Lnet/minecraft/registry/DynamicRegistryManager;Lnet/minecraft/recipe/RecipeEntry;Lnet/minecraft/util/collection/DefaultedList;I)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/recipe/Recipe;getResult(Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)Lnet/minecraft/item/ItemStack;")
    )
    private static ItemStack originlore$prepareSmeltingResult(Recipe<?> recipeValue,
                                                               RegistryWrapper.WrapperLookup lookup,
                                                               DynamicRegistryManager registries,
                                                               RecipeEntry<?> recipe,
                                                               DefaultedList<ItemStack> slots,
                                                               int maxCount) {
        ItemStack result = recipeValue.getResult(lookup).copy();
        // This redirect only runs for craftRecipe's actual result lookup. The
        // canAcceptRecipeOutput lookup remains vanilla, avoiding a second roll
        // of the random variant during the same cooking operation.
        Originlore.applyCustomComponents(result,
                SourceContext.recipe(SourceType.SMELTING, recipe == null ? null : recipe.id()));
        return result;
    }
}
