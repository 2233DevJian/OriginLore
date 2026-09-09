package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({PersistentProjectileEntity.class, TridentEntity.class})
public abstract class ProjectileDamageMixin {
    @Redirect(method = "onEntityHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"))
    private boolean originlore$damage(Entity target, DamageSource source, float damage) {
        PersistentProjectileEntity projectile = (PersistentProjectileEntity) (Object) this;
        ItemStack weapon = projectile.getWeaponStack();
        if (weapon != null && Originlore.isOnServerThread()) Originlore.applyCustomComponents(weapon);
        return target.damage(source, (float) (damage * ItemComponentManager.getProjectileDamageMultiplier(weapon)));
    }
}
