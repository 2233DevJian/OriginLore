package com.originlore;

import com.originlore.command.OriginLoreCommand;
import com.originlore.config.ItemComponentConfig;
import com.originlore.network.OriginLoreNetworking;
import com.originlore.network.OriginLorePayloads;
import com.originlore.server.RefreshService;
import com.originlore.source.SourceContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/** Common entry point. All mutable configuration state belongs to the logical server. */
public final class Originlore implements ModInitializer {
    public static final String MOD_ID = "originlore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ServerRuntime runtime;

    @Override
    public void onInitialize() {
        OriginLorePayloads.register();
        OriginLoreNetworking.registerServerReceivers();
        RefreshService.registerEvents();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                OriginLoreCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(Originlore::startServer);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerRuntime active = runtime;
            if (active != null && active.server == server) {
                active.rebuildRecipeIndex();
                active.refreshService.markAllDirty();
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            ServerRuntime active = runtime;
            if (success && active != null && active.server == server) {
                active.rebuildRecipeIndex();
                active.refreshService.markAllDirty();
                OriginLoreNetworking.broadcastSnapshot(server, "REGISTRIES_RELOADED",
                        "数据包已重载，补全目录已更新", true);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (runtime != null && runtime.server == server) runtime = null;
        });

        LOGGER.info("OriginLore common services initialized");
    }

    private static void startServer(MinecraftServer server) {
        ItemComponentConfig config = new ItemComponentConfig();
        ItemComponentManager manager = new ItemComponentManager(config);
        ItemComponentConfig.LoadResult loaded = config.load(snapshot ->
                manager.validateConfiguration(snapshot, server.getRegistryManager()));
        if (!loaded.success()) {
            LOGGER.error("OriginLore configuration load failed; continuing with the last valid in-memory snapshot: {}",
                    loaded.message());
        } else {
            LOGGER.info("OriginLore configuration revision {} loaded from {}",
                    loaded.revision(), config.getConfigFile());
        }
        RefreshService refreshService = new RefreshService(server, manager);
        runtime = new ServerRuntime(server, config, manager, refreshService);
    }

    public static ItemComponentManager.ApplyResult applyCustomComponents(ItemStack stack, SourceContext source) {
        ServerRuntime active = runtime;
        if (active == null) return ItemComponentManager.ApplyResult.unchanged();
        ItemComponentManager.ApplyResult result = active.manager.applyComponents(stack, source,
                active.server.getRegistryManager());
        if (!result.success()) {
            LOGGER.warn("OriginLore rejected generated item components: {}", result.error());
        }
        return result;
    }

    public static ItemComponentManager.ApplyResult applyCustomComponents(ItemStack stack, SourceContext source,
                                                                          ItemStack identity) {
        ServerRuntime active = runtime;
        if (active == null) return ItemComponentManager.ApplyResult.unchanged();
        ItemComponentManager.ApplyResult result = active.manager.applyComponentsUsingIdentity(stack, source, identity,
                active.server.getRegistryManager());
        if (!result.success()) LOGGER.warn("OriginLore rejected generated item components: {}", result.error());
        return result;
    }

    public static ItemComponentManager.ApplyResult applyCustomComponents(ItemStack stack) {
        return applyCustomComponents(stack, SourceContext.unknown());
    }

    /**
     * Returns whether a furnace output must be treated as an OriginLore
     * transaction. This also recognizes configured inputs before their first
     * result exists, preventing vanilla from consuming an input into a slot
     * that cannot accept the customized result.
     */
    public static boolean shouldPauseFurnace(ItemStack output, ItemStack input) {
        ServerRuntime active = runtime;
        if (active == null) return false;
        if (ItemComponentManager.hasOriginLoreMetadata(output)) return true;
        // The output item, rather than the raw input, determines whether the
        // result will be customized. An input may itself be configured for a
        // different recipe and must not make an otherwise vanilla furnace
        // pause unnecessarily.
        return isConfiguredItem(active, output);
    }

    private static boolean isConfiguredItem(ServerRuntime active, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
        return active.config.getItemConfig(itemId.toString()) != null;
    }

    public static Identifier resolveRecipeId(Object recipe) {
        ServerRuntime active = runtime;
        return active == null ? null : active.resolveRecipeId(recipe);
    }

    public static Identifier resolveLootTableId(LootTable table) {
        ServerRuntime active = runtime;
        if (active == null || table == null) return null;
        Registry<LootTable> registry = active.server.getReloadableRegistries()
                .getRegistryManager().get(RegistryKeys.LOOT_TABLE);
        return registry.getId(table);
    }

    public static RefreshService getRefreshService() {
        return runtime == null ? null : runtime.refreshService;
    }

    public static MinecraftServer getServer() {
        return runtime == null ? null : runtime.server;
    }

    /** Prevents common-side inventory hooks from touching client-thread copies in integrated play. */
    public static boolean isOnServerThread() {
        MinecraftServer server = getServer();
        return server != null && server.isOnThread();
    }

    public static long getRevision() {
        return runtime == null ? -1 : runtime.config.getRevision();
    }

    public static ItemComponentConfig.ConfigSnapshot getSnapshot() {
        return runtime == null ? null : runtime.config.snapshot();
    }

    /** Kept for command compatibility; client code must never edit this object directly. */
    public static ItemComponentConfig getConfig() {
        return runtime == null ? null : runtime.config;
    }

    public static SubmitResult submitSnapshot(String json, long expectedRevision) {
        ServerRuntime active = runtime;
        if (active == null) return SubmitResult.failure(false, -1, "服务器配置尚未就绪", List.of(), "");

        ItemComponentConfig.ConfigSnapshot current = active.config.snapshot();
        if (expectedRevision != current.revision()) {
            return SubmitResult.failure(true, current.revision(), "配置已被其他管理员修改，请检查新版本",
                    List.of(), ItemComponentConfig.snapshotToJson(current));
        }

        final ItemComponentConfig.ConfigSnapshot submitted;
        try {
            submitted = ItemComponentConfig.snapshotFromJson(json);
        } catch (RuntimeException exception) {
            return SubmitResult.failure(false, current.revision(), "配置 JSON 无效",
                    List.of(message(exception)), ItemComponentConfig.snapshotToJson(current));
        }

        List<String> errors = active.manager.validateConfiguration(submitted, active.server.getRegistryManager());
        if (!errors.isEmpty()) {
            return SubmitResult.failure(false, current.revision(), "配置校验失败", errors,
                    ItemComponentConfig.snapshotToJson(current));
        }

        ItemComponentConfig.SaveResult saved = active.config.replaceSnapshot(submitted, expectedRevision);
        if (!saved.success()) {
            ItemComponentConfig.ConfigSnapshot latest = active.config.snapshot();
            return SubmitResult.failure(saved.conflict(), latest.revision(), saved.message(), List.of(),
                    ItemComponentConfig.snapshotToJson(latest));
        }

        active.refreshService.markAllDirty();
        ItemComponentConfig.ConfigSnapshot latest = active.config.snapshot();
        return SubmitResult.success(latest.revision(), ItemComponentConfig.snapshotToJson(latest));
    }

    public static ItemComponentConfig.LoadResult reloadFromDisk() {
        ServerRuntime active = runtime;
        if (active == null) return ItemComponentConfig.LoadResult.failure("server configuration is not ready");
        ItemComponentConfig.LoadResult result = active.config.reloadFromDisk(snapshot ->
                active.manager.validateConfiguration(snapshot, active.server.getRegistryManager()));
        if (result.success()) active.refreshService.markAllDirty();
        return result;
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record SubmitResult(boolean success, boolean conflict, long revision, String message,
                               List<String> errors, String snapshotJson) {
        public static SubmitResult success(long revision, String snapshotJson) {
            return new SubmitResult(true, false, revision, "saved", List.of(), snapshotJson);
        }

        public static SubmitResult failure(boolean conflict, long revision, String message,
                                           List<String> errors, String snapshotJson) {
            return new SubmitResult(false, conflict, revision, message,
                    errors == null ? List.of() : List.copyOf(errors), snapshotJson == null ? "" : snapshotJson);
        }
    }

    private static final class ServerRuntime {
        private final MinecraftServer server;
        private final ItemComponentConfig config;
        private final ItemComponentManager manager;
        private final RefreshService refreshService;
        private volatile Map<Recipe<?>, Identifier> recipeIds = Map.of();

        private ServerRuntime(MinecraftServer server, ItemComponentConfig config,
                              ItemComponentManager manager, RefreshService refreshService) {
            this.server = server;
            this.config = config;
            this.manager = manager;
            this.refreshService = refreshService;
            rebuildRecipeIndex();
        }

        private synchronized void rebuildRecipeIndex() {
            Map<Recipe<?>, Identifier> updated = new IdentityHashMap<>();
            for (RecipeEntry<?> entry : server.getRecipeManager().values()) {
                updated.put(entry.value(), entry.id());
            }
            recipeIds = updated;
        }

        private Identifier resolveRecipeId(Object value) {
            if (!(value instanceof Recipe<?> recipe)) return null;
            Identifier id = recipeIds.get(recipe);
            if (id != null) return id;
            rebuildRecipeIndex();
            return recipeIds.get(recipe);
        }
    }
}
