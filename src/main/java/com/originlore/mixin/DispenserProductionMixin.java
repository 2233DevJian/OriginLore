package com.originlore.mixin;

import com.originlore.gameplay.Harvest;
import net.minecraft.block.dispenser.ItemDispenserBehavior;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPointer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemDispenserBehavior.class)
public abstract class DispenserProductionMixin {
    @Inject(method = "decrementStackWithRemainder", at = @At("HEAD"))
    private void originlore$bottle(BlockPointer pointer, ItemStack input, ItemStack output,
                                    CallbackInfoReturnable<ItemStack> cir) {
        if (output.isOf(Items.HONEY_BOTTLE)) Harvest.prepareContainer(output, pointer.world().getRegistryManager());
    }
}
