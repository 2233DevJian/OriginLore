package com.originlore.mixin;

import com.originlore.gameplay.ProjectileWeapon;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    @Inject(method = "createArrowEntity", at = @At("RETURN"))
    private void originlore$weapon(World world, LivingEntity shooter, ItemStack weapon, ItemStack projectile,
                                    boolean critical, CallbackInfoReturnable<ProjectileEntity> cir) {
        if (!world.isClient && cir.getReturnValue() instanceof ProjectileWeapon tracked) tracked.originlore$setWeapon(weapon);
    }
}
