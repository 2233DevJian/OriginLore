package com.originlore.mixin;

import com.originlore.gameplay.FoodUnits;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.HoneyBottleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HoneyBottleItem.class)
public abstract class HoneyBottleItemMixin {
    @Inject(method = "getMaxUseTime", at = @At("HEAD"), cancellable = true)
    private void originlore$time(ItemStack stack, LivingEntity user, CallbackInfoReturnable<Integer> cir) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (FoodUnits.managed(stack) && food != null) cir.setReturnValue(food.getEatTicks());
    }

    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void originlore$cureBeforeFood(ItemStack stack, World world, LivingEntity user,
                                           CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && FoodUnits.managed(stack)) user.removeStatusEffect(StatusEffects.POISON);
    }

    @Redirect(method = "finishUsing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/LivingEntity;removeStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"))
    private boolean originlore$retainConfiguredEffect(LivingEntity user, RegistryEntry<StatusEffect> effect,
                                                       ItemStack stack, World world, LivingEntity consumer) {
        // The native cure precedes this serving's effects so configured food risks remain effective.
        return ((ItemStackAccessor) (Object) stack).originlore$getComponents()
                .getOrDefault(DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.DEFAULT)
                .copyNbt().contains(com.originlore.ItemComponentManager.METADATA_KEY) ? false : user.removeStatusEffect(effect);
    }
}
