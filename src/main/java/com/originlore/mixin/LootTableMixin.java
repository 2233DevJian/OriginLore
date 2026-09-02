package com.originlore.mixin;

import com.originlore.Originlore;
import com.originlore.source.SourceContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.function.LootFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Applies provenance to the final loot stack, including the Consumer-based
 * generation APIs used by containers and by many modded loot callers.
 *
 * The previous list-return hook only covered LootTable's private list helper.
 * Direct generateLoot(..., Consumer) calls bypassed it, so container contents
 * could later enter a player's inventory as UNKNOWN and miss CHEST_LOOT rules.
 * Wrapping the consumer passed to LootFunction.apply covers every generation
 * path after loot functions have produced the final stack and before the
 * caller receives it (including processStacks splitting).
 */
@Mixin(LootTable.class)
public abstract class LootTableMixin {
    @Redirect(
            method = "generateUnprocessedLoot(Lnet/minecraft/loot/context/LootContext;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/loot/function/LootFunction;apply(Ljava/util/function/BiFunction;Ljava/util/function/Consumer;Lnet/minecraft/loot/context/LootContext;)Ljava/util/function/Consumer;")
    )
    private Consumer<ItemStack> originlore$wrapLootConsumer(
            BiFunction<ItemStack, LootContext, ItemStack> combinedFunction,
            Consumer<ItemStack> consumer,
            LootContext context) {
        LootTable table = (LootTable) (Object) this;
        SourceContext source = SourceContext.fromLootContext(context, table.getType(),
                Originlore.resolveLootTableId(table));
        Consumer<ItemStack> managed = stack -> {
            if (Originlore.isOnServerThread()) {
                Originlore.applyCustomComponents(stack, source);
            }
            consumer.accept(stack);
        };
        return LootFunction.apply(combinedFunction, managed, context);
    }
}
