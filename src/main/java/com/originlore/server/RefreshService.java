package com.originlore.server;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Incrementally refreshes only online and currently loaded storage. */
public final class RefreshService {
    private static final int CHUNKS_PER_TICK = 2;
    private static final int ENTITIES_PER_TICK = 48;
    private static final int INVENTORIES_PER_TICK = 32;
    private static final int PLAYERS_PER_TICK = 4;

    private final MinecraftServer server;
    private final ItemComponentManager manager;
    private final Set<WorldChunk> loadedChunks = identitySet();
    private final Set<Entity> loadedEntities = identitySet();
    private final Set<WorldChunk> queuedChunks = identitySet();
    private final Set<Entity> queuedEntities = identitySet();
    private final Set<Inventory> queuedInventories = identitySet();
    private final Set<ServerPlayerEntity> queuedPlayers = identitySet();
    private final ArrayDeque<WorldChunk> chunkQueue = new ArrayDeque<>();
    private final ArrayDeque<Entity> entityQueue = new ArrayDeque<>();
    private final ArrayDeque<Inventory> inventoryQueue = new ArrayDeque<>();
    private final ArrayDeque<ServerPlayerEntity> playerQueue = new ArrayDeque<>();
    private final Set<String> reportedErrors = new java.util.HashSet<>();

    public RefreshService(MinecraftServer server, ItemComponentManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static void registerEvents() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.onChunkLoad(chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.onChunkUnload(chunk);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.onEntityLoad(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.onEntityUnload(entity);
        });
        ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, previous, current) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.refreshStack(current);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.queuePlayer(handler.player);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RefreshService service = Originlore.getRefreshService();
            if (service != null) service.tick();
        });
    }

    public void markAllDirty() {
        reportedErrors.clear();
        for (WorldChunk chunk : loadedChunks) queueChunk(chunk);
        for (Entity entity : loadedEntities) queueEntity(entity);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) queuePlayer(player);
    }

    public void onChunkLoad(WorldChunk chunk) {
        loadedChunks.add(chunk);
        queueChunk(chunk);
    }

    public void onChunkUnload(WorldChunk chunk) {
        loadedChunks.remove(chunk);
        queuedChunks.remove(chunk);
    }

    public void onEntityLoad(Entity entity) {
        loadedEntities.add(entity);
        queueEntity(entity);
    }

    public void onEntityUnload(Entity entity) {
        loadedEntities.remove(entity);
        queuedEntities.remove(entity);
    }

    public void queuePlayer(ServerPlayerEntity player) {
        if (player != null && queuedPlayers.add(player)) playerQueue.addLast(player);
    }

    /** Queues a changed server-side inventory without scanning it on the mutation call stack. */
    public void queueInventory(Inventory inventory) {
        if (inventory != null && queuedInventories.add(inventory)) inventoryQueue.addLast(inventory);
    }

    private void queueChunk(WorldChunk chunk) {
        if (chunk != null && queuedChunks.add(chunk)) chunkQueue.addLast(chunk);
    }

    private void queueEntity(Entity entity) {
        if (entity != null && queuedEntities.add(entity)) entityQueue.addLast(entity);
    }

    private void tick() {
        for (int index = 0; index < CHUNKS_PER_TICK && !chunkQueue.isEmpty(); index++) {
            WorldChunk chunk = chunkQueue.removeFirst();
            queuedChunks.remove(chunk);
            if (loadedChunks.contains(chunk)) refreshChunk(chunk);
        }
        for (int index = 0; index < ENTITIES_PER_TICK && !entityQueue.isEmpty(); index++) {
            Entity entity = entityQueue.removeFirst();
            queuedEntities.remove(entity);
            if (loadedEntities.contains(entity) && !entity.isRemoved()) refreshEntity(entity);
        }
        for (int index = 0; index < INVENTORIES_PER_TICK && !inventoryQueue.isEmpty(); index++) {
            Inventory inventory = inventoryQueue.removeFirst();
            queuedInventories.remove(inventory);
            refreshInventory(inventory);
        }
        for (int index = 0; index < PLAYERS_PER_TICK && !playerQueue.isEmpty(); index++) {
            ServerPlayerEntity player = playerQueue.removeFirst();
            queuedPlayers.remove(player);
            if (!player.isRemoved()) refreshPlayer(player);
        }
    }

    private void refreshChunk(WorldChunk chunk) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof Inventory inventory && refreshInventory(inventory)) {
                blockEntity.markDirty();
            }
        }
    }

    private void refreshEntity(Entity entity) {
        if (entity instanceof ServerPlayerEntity player) {
            refreshPlayer(player);
            return;
        }
        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getStack();
            if (refreshStack(stack)) itemEntity.setStack(stack);
        }
        if (entity instanceof Inventory inventory) refreshInventory(inventory);
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) refreshStack(living.getEquippedStack(slot));
        }
    }

    private void refreshPlayer(ServerPlayerEntity player) {
        refreshInventory(player.getInventory());
        refreshInventory(player.getEnderChestInventory());
        for (Slot slot : player.currentScreenHandler.slots) {
            if (refreshStack(slot.getStack())) slot.markDirty();
        }
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        if (refreshStack(cursor)) player.currentScreenHandler.setCursorStack(cursor);
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
    }

    private boolean refreshInventory(Inventory inventory) {
        boolean changed = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            changed |= refreshStack(inventory.getStack(slot));
        }
        if (changed) inventory.markDirty();
        return changed;
    }

    public boolean refreshStack(ItemStack stack) {
        ItemComponentManager.ApplyResult result = manager.refresh(stack, server.getRegistryManager());
        if (!result.success()) {
            if (reportedErrors.add(result.error())) {
                Originlore.LOGGER.warn("OriginLore could not refresh an item: {}", result.error());
            }
            return false;
        }
        return result.changed();
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
