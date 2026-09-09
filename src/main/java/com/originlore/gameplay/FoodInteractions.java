package com.originlore.gameplay;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-side cursor operations that retain FIFO order across vanilla clients. */
public final class FoodInteractions {
    private static final Map<ScreenHandler, Drag> DRAGS = new WeakHashMap<>();
    private FoodInteractions() { }

    public static boolean click(ScreenHandler handler, int index, int button, SlotActionType action, PlayerEntity player) {
        ItemStack cursor = handler.getCursorStack();
        if (action == SlotActionType.QUICK_CRAFT && (FoodUnits.managed(cursor) || DRAGS.containsKey(handler))) {
            int stage = ScreenHandler.unpackQuickCraftStage(button);
            int mode = ScreenHandler.unpackQuickCraftButton(button);
            if (stage == 0) {
                DRAGS.remove(handler);
                if (mode < 0 || mode > 2 || mode == 2 && !player.isInCreativeMode()) return true;
                DRAGS.put(handler, new Drag(mode, new LinkedHashSet<>()));
            } else {
                Drag drag = DRAGS.get(handler);
                if (drag == null) return true;
                if (mode != drag.mode() || cursor.isEmpty() || drag.mode() == 2 && !player.isInCreativeMode()) {
                    DRAGS.remove(handler);
                    return true;
                }
                if (stage == 1 && index >= 0 && index < handler.slots.size()) {
                    Slot slot = handler.getSlot(index);
                    if (handler.canInsertIntoSlot(slot) && slot.canInsert(cursor)
                            && (drag.mode() == 2 || cursor.getCount() > drag.slots().size())
                            && (slot.getStack().isEmpty() || FoodUnits.canCombine(cursor, slot.getStack()))) {
                        drag.slots().add(slot);
                    }
                } else if (stage == 2) {
                    int each = drag.mode() == 1 ? 1 : cursor.getCount() / Math.max(1, drag.slots().size());
                    for (Slot slot : drag.slots()) {
                        if (cursor.isEmpty()) break;
                        if (!handler.canInsertIntoSlot(slot) || !slot.canInsert(cursor)
                                || !slot.getStack().isEmpty() && !FoodUnits.canCombine(cursor, slot.getStack())) continue;
                        if (drag.mode() == 2 && player.isInCreativeMode()) slot.setStack(cursor.copyWithCount(slot.getMaxItemCount(cursor)));
                        else slot.insertStack(cursor, each);
                    }
                    DRAGS.remove(handler);
                } else if (stage != 1) DRAGS.remove(handler);
            }
            handler.sendContentUpdates();
            return true;
        }
        if (action != SlotActionType.QUICK_CRAFT) DRAGS.remove(handler);
        if (index < 0 || index >= handler.slots.size()) return false;
        Slot slot = handler.getSlot(index);
        ItemStack stack = slot.getStack();
        if (action == SlotActionType.PICKUP && (button == 0 || button == 1) && !cursor.isEmpty()
                && !stack.isEmpty() && (FoodUnits.managed(cursor) || FoodUnits.managed(stack))
                && FoodUnits.canCombine(cursor, stack) && slot.canTakeItems(player)) {
            if (slot.canInsert(cursor)) slot.insertStack(cursor, button == 0 ? cursor.getCount() : 1);
            else {
                int amount = Math.min(stack.getCount(), cursor.getMaxCount() - cursor.getCount());
                ItemStack taken = slot.takeStackRange(amount, amount, player);
                FoodUnits.transfer(taken, cursor, taken.getCount());
            }
            handler.sendContentUpdates();
            return true;
        }
        if (action == SlotActionType.PICKUP_ALL && FoodUnits.managed(cursor)) {
            if (slot.hasStack() && slot.canTakeItems(player)) return true;
            for (int pass = 0; pass < 2; pass++) {
                for (int n = 0; n < handler.slots.size() && cursor.getCount() < cursor.getMaxCount(); n++) {
                    Slot candidate = handler.getSlot(button == 0 ? n : handler.slots.size() - 1 - n);
                    ItemStack other = candidate.getStack();
                    if (!other.isEmpty() && candidate.canTakeItems(player) && handler.canInsertIntoSlot(cursor, candidate)
                            && FoodUnits.canCombine(cursor, other) && (pass != 0 || other.getCount() < other.getMaxCount())) {
                        int amount = Math.min(other.getCount(), cursor.getMaxCount() - cursor.getCount());
                        ItemStack taken = candidate.takeStackRange(amount, amount, player);
                        FoodUnits.transfer(taken, cursor, taken.getCount());
                    }
                }
            }
            handler.sendContentUpdates();
            return true;
        }
        return false;
    }

    public static boolean insert(ScreenHandler handler, ItemStack stack, int start, int end, boolean reverse) {
        int before = stack.getCount();
        for (int pass = 0; pass < 2; pass++) {
            for (int n = 0; n < end - start && !stack.isEmpty(); n++) {
                Slot slot = handler.getSlot(reverse ? end - n - 1 : start + n);
                ItemStack existing = slot.getStack();
                if (!slot.canInsert(stack)) continue;
                if (pass == 0 && !existing.isEmpty() && FoodUnits.canCombine(stack, existing)) {
                    FoodUnits.transfer(stack, existing, slot.getMaxItemCount(stack) - existing.getCount());
                    slot.markDirty();
                } else if (pass == 1 && existing.isEmpty()) {
                    slot.setStack(stack.split(Math.min(stack.getCount(), slot.getMaxItemCount(stack))));
                }
            }
        }
        return before != stack.getCount();
    }

    private record Drag(int mode, Set<Slot> slots) { }
}
