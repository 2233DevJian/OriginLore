package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.FoodUnits;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemUsage.class)
public abstract class ItemUsageMixin {
    @Inject(method = "exchangeStack(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private static void originlore$harvest(ItemStack input, PlayerEntity player, ItemStack output, boolean creative,
                                            CallbackInfoReturnable<ItemStack> cir) {
        if (!Originlore.isOnServerThread() || Originlore.getManager() == null
                || ItemComponentManager.hasOriginLoreMetadata(output)) return;
        if (output.isOf(Items.HONEY_BOTTLE) || output.isOf(Items.MILK_BUCKET)
                || output.isOf(Items.MUSHROOM_STEW) || output.isOf(Items.SUSPICIOUS_STEW)) {
            com.originlore.gameplay.Harvest.prepareContainer(output, player.getRegistryManager());
        }
    }
}
