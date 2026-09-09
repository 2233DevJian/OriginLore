package com.originlore.gameplay;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.mixin.ItemStackAccessor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered, independently sampled food units carried in vanilla custom data. */
public final class FoodUnits {
    public static final String KEY = "originlore_food_units";
    private static final ThreadLocal<Integer> MUTATING = ThreadLocal.withInitial(() -> 0);

    private FoodUnits() { }

    public static boolean managed(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)
                && ItemComponentManager.hasOriginLoreMetadata(stack);
    }

    public static boolean hasQueue(ItemStack stack) {
        return stack != null && stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT)
                .copyNbt().contains(KEY);
    }

    public static void withoutCountHooks(Runnable action) {
        MUTATING.set(MUTATING.get() + 1);
        try { action.run(); } finally { MUTATING.set(MUTATING.get() - 1); }
    }

    private static NbtCompound root(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
    }

    private static NbtCompound snapshot(ItemStack stack) {
        NbtCompound unit = new NbtCompound();
        unit.put("identity", root(stack).getCompound(ItemComponentManager.METADATA_KEY).copy());
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food != null && Originlore.getServer() != null) {
            FoodComponent.CODEC.encodeStart(Originlore.getServer().getRegistryManager().getOps(NbtOps.INSTANCE), food)
                    .result().ifPresent(encoded -> unit.put("food", encoded));
        }
        return unit;
    }

    public static List<NbtCompound> records(ItemStack stack) {
        List<NbtCompound> records = new ArrayList<>();
        NbtList stored = root(stack).getList(KEY, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < Math.min(stored.size(), stack.getCount()); i++) records.add(stored.getCompound(i).copy());
        if (managed(stack)) {
            while (records.size() < stack.getCount()) records.add(snapshot(stack));
            if (!records.isEmpty()) records.set(0, snapshot(stack));
        }
        return records;
    }

    public static void write(ItemStack stack, List<NbtCompound> records) {
        if (stack == ItemStack.EMPTY) return;
        NbtCompound root = root(stack);
        root.remove(KEY);
        if (!records.isEmpty()) {
            NbtList list = new NbtList();
            for (NbtCompound record : records) list.add(record.copy());
            root.put(KEY, list);
            root.put(ItemComponentManager.METADATA_KEY, records.getFirst().getCompound("identity").copy());
            NbtElement food = records.getFirst().get("food");
            if (food != null && Originlore.getServer() != null) {
                FoodComponent.CODEC.parse(Originlore.getServer().getRegistryManager().getOps(NbtOps.INSTANCE), food)
                        .result().ifPresent(value -> stack.set(DataComponentTypes.FOOD, value));
            }
        }
        if (root.isEmpty()) stack.remove(DataComponentTypes.CUSTOM_DATA);
        else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root);
        FoodTooltip.refresh(stack);
    }

    public static void initialize(ItemStack stack) {
        if (managed(stack) && !hasQueue(stack)) write(stack, records(stack));
    }

    public static void clear(ItemStack stack) {
        NbtCompound root = root(stack);
        root.remove(KEY);
        if (root.isEmpty()) stack.remove(DataComponentTypes.CUSTOM_DATA);
        else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root);
    }

    public static ItemStack split(ItemStack stack, int amount) {
        int count = Math.max(0, Math.min(amount, stack.getCount()));
        if (count == 0) return ItemStack.EMPTY;
        List<NbtCompound> all = records(stack);
        ItemStack result = stack.copy();
        withoutCountHooks(() -> { result.setCount(count); stack.decrement(count); });
        write(result, all.subList(0, count));
        write(stack, all.subList(count, all.size()));
        return result;
    }

    public static void countChanging(ItemStack stack, int newCount) {
        if (MUTATING.get() != 0 || !hasQueue(stack) || newCount == stack.getCount()) return;
        List<NbtCompound> units = records(stack);
        if (newCount < stack.getCount()) {
            write(stack, units.subList(Math.min(units.size(), stack.getCount() - Math.max(0, newCount)), units.size()));
        } else if (!units.isEmpty()) {
            while (units.size() < newCount) units.add(units.getLast().copy());
            write(stack, units);
        }
    }

    public static void copied(ItemStack source, ItemStack result, int count) {
        if (MUTATING.get() != 0 || result == ItemStack.EMPTY || !hasQueue(source)) return;
        List<NbtCompound> units = records(source);
        while (!units.isEmpty() && units.size() < count) units.add(units.getLast().copy());
        write(result, units.subList(0, Math.max(0, Math.min(count, units.size()))));
    }

    public static boolean canCombine(ItemStack left, ItemStack right) {
        if (ItemStack.areItemsAndComponentsEqual(left, right)) return true;
        boolean leftManaged = managed(left);
        boolean rightManaged = managed(right);
        if (!leftManaged || !rightManaged) {
            return !leftManaged && !rightManaged && ItemComponentManager.canStackIgnoringBookkeeping(left, right);
        }
        if (!left.isOf(right.getItem())) return false;
        NbtCompound a = root(left).getCompound(ItemComponentManager.METADATA_KEY);
        NbtCompound b = root(right).getCompound(ItemComponentManager.METADATA_KEY);
        if (!a.getString("variant_id").equals(b.getString("variant_id"))) return false;
        if (!Objects.equals(a.getCompound("originals").get("minecraft:food"),
                b.getCompound("originals").get("minecraft:food"))) return false;
        return Objects.equals(normalized(left).getComponents(), normalized(right).getComponents());
    }

    private static ItemStack normalized(ItemStack stack) {
        ItemStack copy = stack.copy();
        FoodTooltip.strip(copy);
        copy.remove(DataComponentTypes.FOOD);
        NbtCompound root = root(copy);
        root.remove(KEY);
        root.remove(ItemComponentManager.METADATA_KEY);
        if (root.isEmpty()) copy.remove(DataComponentTypes.CUSTOM_DATA);
        else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, copy, root);
        return copy;
    }

    /** Moves a FIFO prefix; callers own slot replacement and dirty notifications. */
    public static int transfer(ItemStack source, ItemStack target, int limit) {
        if (source == target || source.isEmpty() || target.isEmpty() || !canCombine(source, target)) return 0;
        int moved = Math.max(0, Math.min(Math.min(limit, source.getCount()), target.getMaxCount() - target.getCount()));
        if (moved == 0) return 0;
        List<NbtCompound> from = records(source);
        List<NbtCompound> to = records(target);
        withoutCountHooks(() -> { source.decrement(moved); target.increment(moved); });
        if (!from.isEmpty() && !to.isEmpty()) {
            to.addAll(from.subList(0, moved));
            write(target, to);
            write(source, from.subList(moved, from.size()));
        }
        return moved;
    }

    /** Manager calls this before its normal refresh; unit stacks never contain a queue. */
    public static ItemComponentManager.ApplyResult refresh(ItemStack stack, ItemComponentManager manager,
                                                           RegistryWrapper.WrapperLookup lookup) {
        if (!hasQueue(stack) || stack.isEmpty()) return null;
        List<NbtCompound> refreshed = new ArrayList<>();
        ItemStack first = null;
        boolean anyManaged = false;
        for (NbtCompound record : records(stack)) {
            ItemStack unit = stack.copy();
            withoutCountHooks(() -> unit.setCount(1));
            NbtCompound root = root(unit);
            root.remove(KEY);
            root.put(ItemComponentManager.METADATA_KEY, record.getCompound("identity").copy());
            NbtComponent.set(DataComponentTypes.CUSTOM_DATA, unit, root);
            NbtElement food = record.get("food");
            if (food != null) FoodComponent.CODEC.parse(lookup.getOps(NbtOps.INSTANCE), food)
                    .result().ifPresent(value -> unit.set(DataComponentTypes.FOOD, value));
            ItemComponentManager.ApplyResult result = manager.refresh(unit, lookup);
            if (!result.success()) return result;
            if (first == null) first = unit;
            anyManaged |= managed(unit);
            refreshed.add(snapshot(unit));
        }
        ItemStack candidate = stack.copy();
        if (first != null) ((ItemStackAccessor) (Object) candidate).originlore$getComponents().setChanges(first.getComponentChanges());
        if (anyManaged) write(candidate, refreshed);
        else clear(candidate);
        if (ItemStack.areItemsAndComponentsEqual(stack, candidate)) return ItemComponentManager.ApplyResult.unchanged();
        ((ItemStackAccessor) (Object) stack).originlore$getComponents().setChanges(candidate.getComponentChanges());
        return ItemComponentManager.ApplyResult.applied();
    }
}
