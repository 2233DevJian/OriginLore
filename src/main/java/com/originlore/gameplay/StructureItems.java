package com.originlore.gameplay;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

public final class StructureItems {
    private static final String END_SHIP = "originlore_pending_end_ship";

    private StructureItems() { }

    public static ItemStack markEndShip(ItemStack stack) {
        // World generation can run off-thread and save the frame before its first entity-load event.
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putBoolean(END_SHIP, true));
        return stack;
    }

    public static void refresh(ItemFrameEntity frame) {
        if (!Originlore.isOnServerThread()) return;
        ItemStack original = frame.getHeldItemStack();
        if (original.isEmpty()) return;
        ItemStack candidate = original.copy();
        NbtCompound data = candidate.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        boolean generated = data.getBoolean(END_SHIP);
        if (generated) {
            data.remove(END_SHIP);
            if (data.isEmpty()) candidate.remove(DataComponentTypes.CUSTOM_DATA);
            else candidate.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        }
        SourceContext source = generated && !ItemComponentManager.hasOriginLoreMetadata(candidate)
                ? new SourceContext(SourceContext.SourceType.ENTITY_DROP, "minecraft:end_city", null, null)
                : SourceContext.unknown();
        if (Originlore.applyCustomComponents(candidate, source).success()
                && !ItemStack.areItemsAndComponentsEqual(original, candidate)) {
            frame.setHeldItemStack(candidate, false);
        }
    }
}
