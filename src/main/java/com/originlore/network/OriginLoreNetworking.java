package com.originlore.network;

import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.network.ConfigUploadAssembler.Result;
import com.originlore.network.ConfigUploadAssembler.Status;
import com.originlore.network.OriginLorePayloads.ConfigResponse;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Locale;

/** Permissioned, bounded server endpoint for the remote configuration editor. */
public final class OriginLoreNetworking {
    private static final ConfigUploadAssembler UPLOADS = new ConfigUploadAssembler(
            OriginLorePayloads.SUBMIT_CHUNK_BYTES,
            OriginLorePayloads.MAX_SUBMIT_CHUNKS,
            OriginLorePayloads.MAX_SUBMISSION_COMPRESSED_BYTES,
            OriginLorePayloads.MAX_JSON_BYTES,
            OriginLorePayloads.UPLOAD_TIMEOUT_NANOS);

    private OriginLoreNetworking() {
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(OriginLorePayloads.RequestConfig.ID,
                (payload, context) -> sendSnapshotOrDenial(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(OriginLorePayloads.SubmitConfig.ID,
                (payload, context) -> acceptChunk(context.player(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                UPLOADS.discard(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> UPLOADS.expire(System.nanoTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> UPLOADS.clear());
    }

    private static void sendSnapshotOrDenial(ServerPlayerEntity player) {
        if (!hasPermission(player)) {
            sendResponse(player, "DENIED", false, Originlore.getRevision(), "", true,
                    "", "需要管理员权限等级 2", List.of());
            return;
        }
        sendSnapshot(player, "SNAPSHOT", "配置已同步", true);
    }

    private static void acceptChunk(ServerPlayerEntity player, OriginLorePayloads.SubmitConfig payload) {
        if (!hasPermission(player)) {
            if (player != null) UPLOADS.discard(player.getUuid());
            sendResponse(player, "DENIED", false, Originlore.getRevision(), "", true,
                    "", "需要管理员权限等级 2", List.of());
            return;
        }

        Result result = UPLOADS.accept(player.getUuid(), payload.transferId(), payload.expectedRevision(),
                payload.operation(), payload.chunkIndex(), payload.chunkCount(), payload.compressedSize(),
                payload.chunk(), System.nanoTime());
        if (result.status() == Status.PENDING) return;
        if (result.status() == Status.REJECTED) {
            sendResponse(player, "VALIDATION_ERROR", false, Originlore.getRevision(), "", false,
                    "", "配置上传被拒绝", List.of(result.error()));
            return;
        }
        submit(player, result.expectedRevision(), result.operation(), result.snapshotJson());
    }

    private static void submit(ServerPlayerEntity player, long expectedRevision, String requestedOperation,
                               String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            sendResponse(player, "VALIDATION_ERROR", false, Originlore.getRevision(), "", false,
                    "", "配置快照为空", List.of("snapshot: empty"));
            return;
        }

        String operation = requestedOperation == null ? "UPDATE"
                : requestedOperation.trim().toUpperCase(Locale.ROOT);
        if (!operation.equals("CREATE") && !operation.equals("UPDATE") && !operation.equals("DELETE")) {
            sendResponse(player, "VALIDATION_ERROR", false, Originlore.getRevision(), "", false,
                    "", "未知配置操作", List.of("operation: " + operation));
            return;
        }

        if (!fitsSnapshotResponse(snapshotJson)) {
            sendResponse(player, "VALIDATION_ERROR", false, Originlore.getRevision(), "", false,
                    "", "配置过大，服务器无法安全同步给客户端",
                    List.of("canonical snapshot exceeds the compressed response limit"));
            return;
        }

        Originlore.SubmitResult result = Originlore.submitSnapshot(snapshotJson, expectedRevision);
        if (!result.success()) {
            String kind = result.conflict() ? "CONFLICT" : "VALIDATION_ERROR";
            sendResponse(player, kind, false, result.revision(), result.snapshotJson(), false,
                    "", result.message(), result.errors());
            return;
        }

        broadcastSnapshot(player.getServer(), "SAVED", operation + " 已保存");
    }

    private static boolean fitsSnapshotResponse(String submittedJson) {
        final ItemComponentConfig.ConfigSnapshot parsed;
        try {
            parsed = ItemComponentConfig.snapshotFromJson(submittedJson);
        } catch (RuntimeException exception) {
            // Invalid JSON is handled by the authoritative parser so its precise validation error reaches the client.
            return true;
        }
        try {
            PayloadCompression.compressUtf8(ItemComponentConfig.snapshotToJson(parsed),
                    OriginLorePayloads.MAX_JSON_BYTES, OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void broadcastSnapshot(MinecraftServer server, String kind, String message) {
        broadcastSnapshot(server, kind, message, false);
    }

    public static void broadcastSnapshot(MinecraftServer server, String kind, String message,
                                         boolean includeCatalog) {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (hasPermission(player) && ServerPlayNetworking.canSend(player, ConfigResponse.ID)) {
                sendSnapshot(player, kind, message, includeCatalog);
            }
        }
    }

    private static void sendSnapshot(ServerPlayerEntity player, String kind, String message,
                                     boolean includeCatalog) {
        ItemComponentConfig.ConfigSnapshot snapshot = Originlore.getSnapshot();
        if (snapshot == null) {
            sendResponse(player, "ERROR", false, -1, "", includeCatalog, "",
                    "服务器配置尚未就绪", List.of());
            return;
        }
        String catalog = includeCatalog ? registryCatalog(player.getServer()) : "";
        sendResponse(player, kind, true, snapshot.revision(), ItemComponentConfig.snapshotToJson(snapshot),
                includeCatalog, catalog, message, List.of());
    }

    private static String registryCatalog(MinecraftServer server) {
        return server == null ? "" : RegistryCatalog.fromServer(server).toJson();
    }

    private static void sendResponse(ServerPlayerEntity player, String kind, boolean success, long revision,
                                     String snapshotJson, boolean replaceCatalog, String catalogJson,
                                     String message, List<String> errors) {
        byte[] snapshot = new byte[0];
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            try {
                snapshot = PayloadCompression.compressUtf8(snapshotJson, OriginLorePayloads.MAX_JSON_BYTES,
                        OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES);
            } catch (IllegalArgumentException exception) {
                Originlore.LOGGER.error("OriginLore configuration cannot fit in a response payload: {}",
                        exception.getMessage());
                sendRaw(player, new ConfigResponse("ERROR", false, revision, new byte[0], false, new byte[0],
                        "服务器配置过大，无法通过管理协议同步；上一份客户端快照未被覆盖", List.of()));
                return;
            }
        }

        byte[] catalog = new byte[0];
        String effectiveMessage = message == null ? "" : message;
        if (replaceCatalog && catalogJson != null && !catalogJson.isBlank()) {
            try {
                catalog = PayloadCompression.compressUtf8(catalogJson,
                        OriginLorePayloads.MAX_CATALOG_JSON_BYTES,
                        OriginLorePayloads.MAX_CATALOG_COMPRESSED_BYTES);
            } catch (IllegalArgumentException exception) {
                Originlore.LOGGER.warn("OriginLore registry catalog is too large for remote completion: {}",
                        exception.getMessage());
                effectiveMessage = effectiveMessage.isBlank()
                        ? "注册表目录过大，Tab 补全已禁用"
                        : effectiveMessage + "；注册表目录过大，Tab 补全已禁用";
            }
        }
        sendRaw(player, new ConfigResponse(kind, success, revision, snapshot, replaceCatalog, catalog,
                effectiveMessage, errors == null ? List.of() : errors));
    }

    private static boolean hasPermission(ServerPlayerEntity player) {
        return player != null && player.hasPermissionLevel(2);
    }

    private static void sendRaw(ServerPlayerEntity player, ConfigResponse payload) {
        if (player != null && ServerPlayNetworking.canSend(player, ConfigResponse.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
