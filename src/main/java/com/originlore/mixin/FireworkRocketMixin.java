package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.ProjectileWeapon;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketMixin implements ProjectileWeapon {
    @Unique private ItemStack originlore$weapon = ItemStack.EMPTY;

    @Override
    public void originlore$setWeapon(ItemStack stack) { originlore$weapon = stack.copyWithCount(1); }

    @Redirect(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/LivingEntity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"))
    private boolean originlore$damage(LivingEntity target, DamageSource source, float amount) {
        Originlore.applyCustomComponents(originlore$weapon);
        return target.damage(source, (float) (amount * ItemComponentManager.getProjectileDamageMultiplier(originlore$weapon)));
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("RETURN"))
    private void originlore$write(NbtCompound nbt, CallbackInfo ci) {
        if (!originlore$weapon.isEmpty()) nbt.put("OriginLoreWeapon", originlore$weapon.encode(((FireworkRocketEntity) (Object) this).getRegistryManager()));
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("RETURN"))
    private void originlore$read(NbtCompound nbt, CallbackInfo ci) {
        originlore$weapon = nbt.contains("OriginLoreWeapon") ? ItemStack.fromNbt(
                ((FireworkRocketEntity) (Object) this).getRegistryManager(), nbt.getCompound("OriginLoreWeapon")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }
}
