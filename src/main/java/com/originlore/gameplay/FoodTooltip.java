package com.originlore.gameplay;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** A vanilla-readable serving summary, separate from authored lore and stacking identity. */
public final class FoodTooltip {
    private static final String KEY = "originlore.food.serving";

    private FoodTooltip() { }

    public static void refresh(ItemStack stack) {
        if (Originlore.getServer() != null && Originlore.getConfig() != null) {
            refresh(stack, Originlore.getServer().getRegistryManager(), Originlore.getConfig().getPresetLanguage());
        }
    }

    public static boolean refresh(ItemStack stack, RegistryWrapper.WrapperLookup lookup, String language) {
        LoreComponent previous = stack.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT);
        List<Text> lines = authoredLines(previous);
        if (ItemComponentManager.hasOriginLoreMetadata(stack)) {
            FoodComponent food = stack.isOf(Items.CAKE) ? ItemComponentManager.getCakeFood(stack, lookup)
                    : stack.get(DataComponentTypes.FOOD);
            if (food != null && lines.size() < 256) {
                String summary;
                if ("zh_cn".equals(language)) {
                    summary = (stack.isOf(Items.CAKE) ? "\u6bcf\u53e3" : "\u4e0b\u4e00\u4efd")
                            + "\uff1a\u9971\u98df\u5ea6 " + food.nutrition() + " | \u9971\u548c\u5ea6 " + number(food.saturation());
                    if (!stack.isOf(Items.CAKE)) summary += " | \u98df\u7528 " + number(food.eatSeconds()) + " \u79d2";
                } else {
                    summary = (stack.isOf(Items.CAKE) ? "Per bite: " : "Next serving: ")
                            + "Food " + food.nutrition() + " | Saturation " + number(food.saturation());
                    if (!stack.isOf(Items.CAKE)) summary += " | Time " + number(food.eatSeconds()) + "s";
                }
                lines.add(Text.translatableWithFallback(KEY, summary).formatted(Formatting.GRAY)
                        .styled(style -> style.withItalic(false)));
            }
        }
        if (previous.lines().equals(lines)) return false;
        if (lines.isEmpty()) stack.remove(DataComponentTypes.LORE);
        else stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return true;
    }

    public static void strip(ItemStack stack) {
        LoreComponent previous = stack.get(DataComponentTypes.LORE);
        if (previous == null) return;
        List<Text> lines = authoredLines(previous);
        if (previous.lines().equals(lines)) return;
        if (lines.isEmpty()) stack.remove(DataComponentTypes.LORE);
        else stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
    }

    private static List<Text> authoredLines(LoreComponent lore) {
        List<Text> lines = new ArrayList<>();
        for (Text line : lore.lines()) {
            if (!(line.getContent() instanceof TranslatableTextContent translated) || !KEY.equals(translated.getKey())) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String number(float value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
