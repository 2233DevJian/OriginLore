package com.originlore.gameplay;

import com.originlore.Originlore;
import com.originlore.ItemComponentManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.event.GameEvent;

import java.util.HashMap;
import java.util.Map;

/** Vanilla cake has no block entity, so its item identity lives in dimension state. */
public final class PlacedCakes extends PersistentState {
    private static final Type<PlacedCakes> TYPE = new Type<>(PlacedCakes::new, PlacedCakes::read, null);
    private final Map<Long, ItemStack> cakes = new HashMap<>();

    public static PlacedCakes get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE, "originlore_cakes");
    }

    public static PlacedCakes read(NbtCompound root, RegistryWrapper.WrapperLookup lookup) {
        PlacedCakes state = new PlacedCakes();
        NbtList list = root.getList("cakes", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            ItemStack.fromNbt(lookup, entry.getCompound("item")).ifPresent(stack -> state.cakes.put(entry.getLong("pos"), stack));
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound root, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        cakes.forEach((position, stack) -> {
            NbtCompound entry = new NbtCompound();
            entry.putLong("pos", position);
            entry.put("item", stack.encode(lookup));
            list.add(entry);
        });
        root.put("cakes", list);
        return root;
    }

    public void put(BlockPos pos, ItemStack item) {
        ItemStack unit = item.copyWithCount(1);
        FoodUnits.clear(unit);
        cakes.put(pos.asLong(), unit);
        markDirty();
    }

    public void remove(BlockPos pos) {
        if (cakes.remove(pos.asLong()) != null) markDirty();
    }

    public static boolean isCake(BlockState state) {
        return state.getBlock() instanceof CakeBlock || state.getBlock() instanceof CandleCakeBlock;
    }

    public ActionResult eat(ServerWorld world, BlockPos pos, BlockState state, PlayerEntity player) {
        ItemStack item = cakes.get(pos.asLong());
        if (item == null) return null;
        Originlore.applyCustomComponents(item);
        FoodComponent food = ItemComponentManager.getCakeFood(item, world.getRegistryManager());
        if (food == null) return null;
        if (!player.canConsume(food.canAlwaysEat())) return ActionResult.PASS;
        player.incrementStat(Stats.EAT_CAKE_SLICE);
        player.getHungerManager().add(food.nutrition(), food.nutrition() == 0 ? 0.0F
                : food.saturation() / (2.0F * food.nutrition()));
        for (FoodComponent.StatusEffectEntry effect : food.effects()) {
            if (world.random.nextFloat() < effect.probability()) player.addStatusEffect(new StatusEffectInstance(effect.effect()));
        }
        int bites = state.get(CakeBlock.BITES);
        world.emitGameEvent(player, GameEvent.EAT, pos);
        if (bites < CakeBlock.MAX_BITES) world.setBlockState(pos, state.with(CakeBlock.BITES, bites + 1), Block.NOTIFY_ALL);
        else {
            remove(pos);
            world.removeBlock(pos, false);
            world.emitGameEvent(player, GameEvent.BLOCK_DESTROY, pos);
        }
        markDirty();
        return ActionResult.SUCCESS;
    }
}
