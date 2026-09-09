package com.originlore.gameplay;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.component.ComponentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.MerchantInventory;
import net.minecraft.village.TradeOffer;

import java.util.ArrayList;
import java.util.List;

/** Server-owned production commits, shared by clicks, quick moves and machines. */
public final class Production {
    private static final ThreadLocal<Boolean> COOKING = ThreadLocal.withInitial(() -> false);
    private Production() { }

    public static void cooking(boolean active) { COOKING.set(active); }
    public static boolean cooking() { return COOKING.get(); }

    public static List<ItemStack> roll(ItemStack template, SourceContext source, List<ItemStack> ingredients) {
        if (template.isEmpty()) return List.of();
        ItemComponentManager manager = Originlore.getManager();
        if (manager == null) return List.of(template.copy());
        double quality = 0.0;
        double risk = 0.0;
        int count = 0;
        for (ItemStack ingredient : ingredients) {
            if (ingredient.isEmpty() || ingredient.isOf(Items.BOWL) || ingredient.isOf(Items.GLASS_BOTTLE)
                    || ingredient.isOf(Items.BUCKET)) continue;
            ItemStack remaining = ingredient.copy();
            while (!remaining.isEmpty()) {
                ItemStack consumed = remaining.split(1);
                if (!accepted(manager.refresh(consumed, Originlore.getServer().getRegistryManager()))) return List.of();
                quality += ItemComponentManager.getQualityScore(consumed);
                risk += ItemComponentManager.getSpoilageRisk(consumed);
                count++;
            }
        }
        quality = count == 0 ? 0.5 : quality / count;
        risk = count == 0 ? 0 : risk / count;
        List<ItemStack> outputs = new ArrayList<>();
        for (int i = 0; i < template.getCount(); i++) {
            ItemStack unit = template.copyWithCount(1);
            FoodUnits.clear(unit);
            if (!accepted(manager.applyProducedComponents(unit, source,
                    Originlore.getServer().getRegistryManager(), quality, risk))) return List.of();
            FoodUnits.initialize(unit);
            boolean merged = false;
            for (ItemStack output : outputs) {
                if (output.getCount() < output.getMaxCount() && FoodUnits.canCombine(unit, output)) {
                    FoodUnits.transfer(unit, output, 1);
                    merged = true;
                    break;
                }
            }
            if (!merged) outputs.add(unit);
        }
        return outputs;
    }

    private static boolean accepted(ItemComponentManager.ApplyResult result) {
        if (!result.success()) Originlore.LOGGER.warn("OriginLore cancelled production before consuming inputs: {}", result.error());
        return result.success();
    }

    public static List<ItemStack> craft(ItemStack template, SourceContext source, List<ItemStack> ingredients) {
        // Two-item repair is maintenance; the first input retains its identity in either crafting interface.
        if (Originlore.getManager() != null && ingredients.size() == 2 && template.isDamageable()
                && ingredients.stream().allMatch(input -> input.isOf(template.getItem()))) {
            ItemStack inherited = template.copy();
            if (!accepted(Originlore.getManager().applyInheritedComponents(inherited, ingredients.getFirst(), source,
                    Originlore.getServer().getRegistryManager()))) return List.of();
            return List.of(inherited);
        }
        return roll(template, source, ingredients);
    }

    public static void carryNativeCraftChanges(ItemStack before, ItemStack after, List<ItemStack> outputs) {
        java.util.Set<ComponentType<?>> types = new java.util.HashSet<>(before.getComponents().getTypes());
        types.addAll(after.getComponents().getTypes());
        for (ComponentType<?> type : types) carryChangedComponent(type, before, after, outputs);
    }

    private static <T> void carryChangedComponent(ComponentType<T> type, ItemStack before, ItemStack after, List<ItemStack> outputs) {
        T value = after.get(type);
        if (!java.util.Objects.equals(before.get(type), value)) {
            for (ItemStack output : outputs) output.set(type, value);
        }
    }

    public static int outputSlot(ScreenHandler handler) {
        if (handler instanceof CraftingScreenHandler || handler instanceof PlayerScreenHandler) return 0;
        if (handler instanceof MerchantScreenHandler) return 2;
        if (handler instanceof StonecutterScreenHandler) return 1;
        return -1;
    }

    public static boolean handles(ScreenHandler handler, int slot) {
        return Originlore.isOnServerThread() && Originlore.getManager() != null && slot >= 0
                && outputSlot(handler) == slot;
    }

    public static boolean click(ScreenHandler handler, int slot, int button, SlotActionType action, PlayerEntity player) {
        if (!handles(handler, slot)) return false;
        if (action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE
                && action != SlotActionType.SWAP && action != SlotActionType.THROW) return false;
        if ((action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE || action == SlotActionType.THROW)
                && button != 0 && button != 1) return true;
        if (action == SlotActionType.SWAP && !((button >= 0 && button < 9) || button == 40)) return true;
        if (action == SlotActionType.THROW && !handler.getCursorStack().isEmpty()) return true;
        int remaining = action == SlotActionType.QUICK_MOVE ? 4096 : 1;
        while (remaining-- > 0) {
            Commit result = commit(handler, slot, button, action, player);
            if (result == null || result.overflow()) break;
        }
        handler.sendContentUpdates();
        return true;
    }

    public static ItemStack quickMove(ScreenHandler handler, int slot, PlayerEntity player) {
        Commit result = commit(handler, slot, 0, SlotActionType.QUICK_MOVE, player);
        return result == null || result.overflow() ? ItemStack.EMPTY : result.representative();
    }

    private static Commit commit(ScreenHandler handler, int index, int button, SlotActionType action, PlayerEntity player) {
        Slot slot = handler.getSlot(index);
        if (!slot.hasStack() || !slot.canTakeItems(player)) return null;
        ItemStack template = slot.getStack().copy();
        if (action == SlotActionType.PICKUP && !handler.getCursorStack().isEmpty()
                && (!handler.getCursorStack().isOf(template.getItem())
                || handler.getCursorStack().getCount() >= handler.getCursorStack().getMaxCount())) return null;
        if (slot.inventory instanceof MerchantInventory merchant) {
            TradeOffer offer = merchant.getTradeOffer();
            if (offer == null || offer.isDisabled() || !(offer.matchesBuyItems(merchant.getStack(0), merchant.getStack(1))
                    || offer.matchesBuyItems(merchant.getStack(1), merchant.getStack(0)))) return null;
        }

        SourceType type = handler instanceof MerchantScreenHandler ? SourceType.TRADING
                : handler instanceof StonecutterScreenHandler ? SourceType.CUTTING : SourceType.CRAFTING;
        RecipeEntry<?> recipe = slot.inventory instanceof CraftingResultInventory result ? result.getLastRecipe() : null;
        SourceContext source = SourceContext.recipe(type, recipe == null ? null : recipe.id());
        List<ItemStack> ingredients = new ArrayList<>();
        if (type != SourceType.TRADING) {
            int end = handler instanceof CraftingScreenHandler ? 10 : handler instanceof PlayerScreenHandler ? 5 : 1;
            int start = type == SourceType.CUTTING ? 0 : 1;
            for (int i = start; i < end; i++) {
                ItemStack input = handler.getSlot(i).getStack();
                if (!input.isEmpty()) ingredients.add(input.copyWithCount(1));
            }
        }
        List<ItemStack> outputs = type == SourceType.CRAFTING ? craft(template, source, ingredients) : roll(template, source, ingredients);
        if (outputs.isEmpty()) return null;

        ItemStack taken = slot.takeStack(template.getCount());
        if (taken.isEmpty()) return null;
        if (!slot.getStack().isEmpty()) slot.setStack(ItemStack.EMPTY);
        slot.onTakeItem(player, taken);
        carryNativeCraftChanges(template, taken, outputs);
        boolean overflow = false;
        ItemStack representative = outputs.getFirst().copy();
        ItemStack displaced = ItemStack.EMPTY;
        if (action == SlotActionType.SWAP) {
            displaced = player.getInventory().getStack(button);
            player.getInventory().setStack(button, ItemStack.EMPTY);
        }
        for (ItemStack output : outputs) {
            if (action == SlotActionType.THROW) {
                player.dropItem(output, false);
                continue;
            }
            if (action == SlotActionType.PICKUP) {
                ItemStack cursor = handler.getCursorStack();
                if (cursor.isEmpty()) {
                    handler.setCursorStack(output.copyAndEmpty());
                } else if (FoodUnits.canCombine(output, cursor)) {
                    FoodUnits.transfer(output, cursor, cursor.getMaxCount() - cursor.getCount());
                }
            } else if (action == SlotActionType.SWAP && player.getInventory().getStack(button).isEmpty()) {
                player.getInventory().setStack(button, output.copyAndEmpty());
            }
            if (!output.isEmpty()) {
                player.getInventory().insertStack(output);
                if (!output.isEmpty()) {
                    player.dropItem(output.copyAndEmpty(), false);
                    overflow = true;
                }
            }
        }
        if (!displaced.isEmpty()) {
            player.getInventory().insertStack(displaced);
            if (!displaced.isEmpty()) {
                player.dropItem(displaced.copyAndEmpty(), false);
                overflow = true;
            }
        }
        return new Commit(representative, overflow);
    }

    private record Commit(ItemStack representative, boolean overflow) { }
}
