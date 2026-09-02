package com.originlore.mixin;

import net.minecraft.component.ComponentMapImpl;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the component transaction code access to the stack's complete patch map. */
@Mixin(ItemStack.class)
public interface ItemStackAccessor {
    @Accessor("components")
    ComponentMapImpl originlore$getComponents();
}
