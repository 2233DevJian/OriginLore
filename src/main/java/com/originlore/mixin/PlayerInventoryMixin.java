package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.ItemComponentManager;
import com.originlore.gameplay.FoodUnits;
import com.originlore.source.SourceContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** UNKNOWN fallback for items granted directly by mods instead of a standard generation path. */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow @Final public PlayerEntity player;

    @Inject(method = "canStackAddMore", at = @At("HEAD"), cancellable = true)
    private void originlore$canMerge(ItemStack existing, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayerEntity && ItemComponentManager.hasOriginLoreMetadata(stack)) {
            PlayerInventory inventory = (PlayerInventory) (Object) this;
            cir.setReturnValue(!existing.isEmpty() && FoodUnits.canCombine(existing, stack)
                    && existing.isStackable() && existing.getCount() < inventory.getMaxCount(existing));
        }
    }

    @Inject(method = "addStack(ILnet/minecraft/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void originlore$moveUnits(int slot, ItemStack source, CallbackInfoReturnable<Integer> cir) {
        if (!(player instanceof ServerPlayerEntity) || !ItemComponentManager.hasOriginLoreMetadata(source)) return;
        PlayerInventory inventory = (PlayerInventory) (Object) this;
        ItemStack target = inventory.getStack(slot);
        if (target.isEmpty()) {
            ItemStack moved = source.split(Math.min(source.getCount(), inventory.getMaxCount(source)));
            moved.setBobbingAnimationTime(5);
            inventory.setStack(slot, moved);
        } else if (FoodUnits.canCombine(source, target)) {
            FoodUnits.transfer(source, target, inventory.getMaxCount(target) - target.getCount());
            target.setBobbingAnimationTime(5);
        }
        cir.setReturnValue(source.getCount());
    }

    // One handler cannot cover both overloads: the (int, ItemStack) target needs a matching
    // parameter list, and a mismatch there is silent once the other overload satisfies require.
    @Inject(method = "insertStack(Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"))
    private void originlore$applyBeforeInsert(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        applyIfServerPlayer(stack);
    }

    @Inject(method = "insertStack(ILnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"))
    private void originlore$applyBeforeInsertIntoSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        applyIfServerPlayer(stack);
    }

    @Inject(method = "setStack(ILnet/minecraft/item/ItemStack;)V", at = @At("HEAD"))
    private void originlore$applyBeforeSet(int slot, ItemStack stack, CallbackInfo ci) {
        applyIfServerPlayer(stack);
    }

    private void applyIfServerPlayer(ItemStack stack) {
        if (player instanceof ServerPlayerEntity) {
            Originlore.applyCustomComponents(stack, SourceContext.unknown());
        }
    }
}
