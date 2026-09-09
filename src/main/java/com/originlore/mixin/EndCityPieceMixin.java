package com.originlore.mixin;

import com.originlore.gameplay.StructureItems;
import net.minecraft.item.ItemStack;
import net.minecraft.structure.EndCityGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(EndCityGenerator.Piece.class)
public abstract class EndCityPieceMixin {
    @ModifyArg(method = "handleMetadata", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/decoration/ItemFrameEntity;setHeldItemStack(Lnet/minecraft/item/ItemStack;Z)V"), index = 0)
    private ItemStack originlore$recordEndShip(ItemStack stack) {
        return StructureItems.markEndShip(stack);
    }
}
