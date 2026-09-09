package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.Hopper;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin {
    @Unique private static final ThreadLocal<java.util.Map<ItemStack, ItemStack>> originlore$beforeRemoval =
            ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    @Redirect(method = "insert", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/entity/HopperBlockEntity;removeStack(II)Lnet/minecraft/item/ItemStack;"))
    private static ItemStack originlore$saveInsert(HopperBlockEntity inventory, int slot, int count) {
        originlore$remember(inventory.getStack(slot));
        return inventory.removeStack(slot, count);
    }

    @Redirect(method = "extract(Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/inventory/Inventory;ILnet/minecraft/util/math/Direction;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/Inventory;removeStack(II)Lnet/minecraft/item/ItemStack;"))
    private static ItemStack originlore$saveExtract(Inventory inventory, int slot, int count) {
        originlore$remember(inventory.getStack(slot));
        return inventory.removeStack(slot, count);
    }

    @Unique
    private static void originlore$remember(ItemStack stack) {
        if (FoodUnits.hasQueue(stack)) originlore$beforeRemoval.get().put(stack, stack.copy());
    }

    @Redirect(method = {"insert", "extract(Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/inventory/Inventory;ILnet/minecraft/util/math/Direction;)Z"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;setCount(I)V"))
    private static void originlore$rollback(ItemStack stack, int count) {
        ItemStack original = originlore$beforeRemoval.get().remove(stack);
        if (original == null) stack.setCount(count);
        else {
            FoodUnits.withoutCountHooks(() -> stack.setCount(count));
            ((ItemStackAccessor) (Object) stack).originlore$getComponents().setChanges(original.getComponentChanges());
        }
    }

    @Inject(method = {"insert", "extract(Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/inventory/Inventory;ILnet/minecraft/util/math/Direction;)Z"}, at = @At("RETURN"))
    private static void originlore$finishTransfer(CallbackInfoReturnable<Boolean> cir) {
        originlore$beforeRemoval.remove();
    }

    @Inject(method = "transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/item/ItemStack;ILnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private static void originlore$merge(Inventory from, Inventory to, ItemStack source, int slot, Direction side,
                                          CallbackInfoReturnable<ItemStack> cir) {
        ItemStack target = to.getStack(slot);
        if (!Originlore.isOnServerThread() || target.isEmpty() || !FoodUnits.canCombine(source, target)) return;
        if (to.isValid(slot, source) && (!(to instanceof SidedInventory sided) || sided.canInsert(slot, source, side))) {
            if (FoodUnits.transfer(source, target, to.getMaxCount(target) - target.getCount()) > 0) to.markDirty();
        }
        cir.setReturnValue(source);
    }
}
