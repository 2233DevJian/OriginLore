package com.originlore.mixin;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.gameplay.NaturalEquipment;
import com.originlore.source.SourceContext;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin implements NaturalEquipment {
    @Unique private boolean originlore$pendingEquipment;

    @Inject(method = "initialize", at = @At("HEAD"))
    private void originlore$recordSpawn(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason,
                                        EntityData data, CallbackInfoReturnable<EntityData> cir) {
        originlore$pendingEquipment = switch (reason) {
            case NATURAL, CHUNK_GENERATION, SPAWNER, STRUCTURE, BREEDING, MOB_SUMMONED, JOCKEY,
                    EVENT, REINFORCEMENT, TRIGGERED, PATROL, TRIAL_SPAWNER -> true;
            default -> false;
        };
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void originlore$writePending(NbtCompound nbt, CallbackInfo ci) {
        // Chunk generation may serialize the entity before its first server entity-load event.
        if (originlore$pendingEquipment) nbt.putBoolean("originlore_pending_equipment", true);
        else nbt.remove("originlore_pending_equipment");
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void originlore$readPending(NbtCompound nbt, CallbackInfo ci) {
        originlore$pendingEquipment = nbt.getBoolean("originlore_pending_equipment");
    }

    @Override
    public void originlore$finishEquipmentGeneration() {
        if (!originlore$pendingEquipment || !Originlore.isOnServerThread()) return;
        originlore$pendingEquipment = false;
        MobEntity mob = (MobEntity) (Object) this;
        SourceContext source = new SourceContext(SourceContext.SourceType.ENTITY_DROP,
                Registries.ENTITY_TYPE.getId(mob.getType()).toString(), null, null);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = mob.getEquippedStack(slot);
            if (!stack.isEmpty() && !ItemComponentManager.hasOriginLoreMetadata(stack)) {
                Originlore.applyCustomComponents(stack, source);
            }
        }
    }
}
