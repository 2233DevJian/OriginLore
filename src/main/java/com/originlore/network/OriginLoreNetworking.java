package com.originlore.network;

import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.PresetLanguage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.network.ConfigUploadAssembler.Result;
import com.originlore.network.ConfigUploadAssembler.Status;
import com.originlore.network.OriginLorePayloads.ConfigResponse;
import com.originlore.network.OriginLorePayloads.SnapshotBegin;
import com.originlore.network.OriginLorePayloads.SnapshotChunk;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
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

    private static final String OVERSIZED_MESSAGE = "服务器配置过大，无法通过管理协议同步；上一份客户端快照未被覆盖";

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
            deliver(player, outbound("DENIED", false, Originlore.getRevision(), "", true, "",
                    "需要管理员权限等级 2", List.of()));
            return;
        }
        deliver(player, snapshotOutbound("SNAPSHOT", "配置已同步", true, player == null ? null : player.getServer()));
    }

    private static void acceptChunk(ServerPlayerEntity player, OriginLorePayloads.SubmitConfig payload) {
        if (!hasPermission(player)) {
            if (player != null) UPLOADS.discard(player.getUuid());
            deliver(player, outbound("DENIED", false, Originlore.getRevision(), "", true, "",
                    "需要管理员权限等级 2", List.of()));
            return;
        }

        Result result = UPLOADS.accept(player.getUuid(), payload.transferId(), payload.expectedRevision(),
                payload.operation(), payload.chunkIndex(), payload.chunkCount(), payload.compressedSize(),
                payload.chunk(), System.nanoTime());
        if (result.status() == Status.PENDING) return;
        if (result.status() == Status.REJECTED) {
            deliver(player, outbound("VALIDATION_ERROR", false, Originlore.getRevision(), "", false, "",
                    "配置上传被拒绝", List.of(result.error())));
            return;
        }
        submit(player, result.expectedRevision(), result.operation(), result.snapshotJson());
    }

    private static void submit(ServerPlayerEntity player, long expectedRevision, String requestedOperation,
                               String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            deliver(player, outbound("VALIDATION_ERROR", false, Originlore.getRevision(), "", false, "",
                    "配置快照为空", List.of("snapshot: empty")));
            return;
        }

        String operation = requestedOperation == null ? "UPDATE"
                : requestedOperation.trim().toUpperCase(Locale.ROOT);
        if (!operation.equals("CREATE") && !operation.equals("UPDATE") && !operation.equals("DELETE") && !operation.equals("LANGUAGE")) {
            deliver(player, outbound("VALIDATION_ERROR", false, Originlore.getRevision(), "", false, "",
                    "未知配置操作", List.of("operation: " + operation)));
            return;
        }

        try {
            JsonObject submitted = JsonParser.parseString(snapshotJson).getAsJsonObject();
            if (!submitted.has("schemaVersion") || submitted.get("schemaVersion").getAsInt() != ItemComponentConfig.CURRENT_SCHEMA_VERSION
                    || !submitted.has("settings")) throw new IllegalArgumentException("Please update the OriginLore editor client");
            if (operation.equals("LANGUAGE")) {
                String language = submitted.getAsJsonObject("settings").get("presetLanguage").getAsString();
                snapshotJson = ItemComponentConfig.snapshotToJson(PresetLanguage.switchLanguage(Originlore.getSnapshot(), language));
            }
        } catch (RuntimeException exception) {
            deliver(player, outbound("VALIDATION_ERROR", false, Originlore.getRevision(), "", false, "",
                    "Invalid configuration submission", List.of(exception.getMessage() == null ? "Invalid settings" : exception.getMessage())));
            return;
        }

        if (!fitsSnapshotResponse(snapshotJson)) {
            deliver(player, outbound("VALIDATION_ERROR", false, Originlore.getRevision(), "", false, "",
                    "配置过大，服务器无法安全同步给客户端",
                    List.of("canonical snapshot exceeds the compressed response limit")));
            return;
        }

        Originlore.SubmitResult result = Originlore.submitSnapshot(snapshotJson, expectedRevision);
        if (!result.success()) {
            String kind = result.conflict() ? "CONFLICT" : "VALIDATION_ERROR";
            deliver(player, outbound(kind, false, result.revision(), result.snapshotJson(), false, "",
                    result.message(), result.errors()));
            return;
        }

        String message = operation.equals("LANGUAGE") ? "Preset language updated" : operation + " 已保存";
        Outbound changed = snapshotOutbound("SNAPSHOT", message, false, player.getServer());
        for (ServerPlayerEntity recipient : player.getServer().getPlayerManager().getPlayerList()) {
            if (hasPermission(recipient) && recipient != player) deliver(recipient, changed);
        }
        deliver(player, snapshotOutbound(operation.equals("LANGUAGE") ? "LANGUAGE_SAVED" : "SAVED",
                message, false, player.getServer()));
    }

    private static boolean fitsSnapshotResponse(String submittedJson) {
        final ItemComponentConfig.ConfigSnapshot parsed;
        try {
            parsed = ItemComponentConfig.snapshotFromJson(submittedJson);
        } catch (RuntimeException exception) {
            // Invalid JSON is handled by the authoritative parser so its precise validation error reaches the client.
            return true;
        }
        return !(SnapshotTransport.plan(ItemComponentConfig.snapshotToJson(parsed))
                instanceof SnapshotTransport.Oversized);
    }

    public static void broadcastSnapshot(MinecraftServer server, String kind, String message) {
        broadcastSnapshot(server, kind, message, false);
    }

    public static void broadcastSnapshot(MinecraftServer server, String kind, String message,
                                         boolean includeCatalog) {
        if (server == null) return;
        Outbound prepared = snapshotOutbound(kind, message, includeCatalog, server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (hasPermission(player)) deliver(player, prepared);
        }
    }

    private static Outbound snapshotOutbound(String kind, String message, boolean includeCatalog,
                                             MinecraftServer server) {
        ItemComponentConfig.ConfigSnapshot snapshot = Originlore.getSnapshot();
        if (snapshot == null) {
            return outbound("ERROR", false, -1, "", includeCatalog, "", "服务器配置尚未就绪", List.of());
        }
        return outbound(kind, true, snapshot.revision(), ItemComponentConfig.snapshotToJson(snapshot),
                includeCatalog, includeCatalog ? registryCatalog(server) : "", message, List.of());
    }

    private static String registryCatalog(MinecraftServer server) {
        return server == null ? "" : RegistryCatalog.fromServer(server).toJson();
    }

    /** Compresses once so a broadcast to several operators does not repeat the work per recipient. */
    private static Outbound outbound(String kind, boolean success, long revision, String snapshotJson,
                                     boolean replaceCatalog, String catalogJson, String message,
                                     List<String> errors) {
        SnapshotTransport.Plan plan = snapshotJson == null || snapshotJson.isBlank()
                ? null : SnapshotTransport.plan(snapshotJson);
        if (plan instanceof SnapshotTransport.Oversized oversized) {
            Originlore.LOGGER.error("OriginLore configuration cannot fit in a response payload: {}",
                    oversized.reason());
            return oversizedOutbound(revision);
        }

        byte[] catalog = new byte[0];
        String effectiveMessage = message == null ? "" : message;
        if (replaceCatalog && catalogJson != null && !catalogJson.isBlank()) {
            try {
                catalog = PayloadCompression.compressUtf8(catalogJson, OriginLorePayloads.MAX_CATALOG_JSON_BYTES,
                        OriginLorePayloads.MAX_CATALOG_COMPRESSED_BYTES);
            } catch (IllegalArgumentException exception) {
                Originlore.LOGGER.warn("OriginLore registry catalog is too large for remote completion: {}",
                        exception.getMessage());
                effectiveMessage = effectiveMessage.isBlank()
                        ? "注册表目录过大，Tab 补全已禁用"
                        : effectiveMessage + "；注册表目录过大，Tab 补全已禁用";
            }
        }
        return new Outbound(kind, success, revision, plan, replaceCatalog, catalog, effectiveMessage,
                errors == null ? List.of() : List.copyOf(errors));
    }

    private static Outbound oversizedOutbound(long revision) {
        return new Outbound("ERROR", false, revision, null, false, new byte[0], OVERSIZED_MESSAGE, List.of());
    }

    private static void deliver(ServerPlayerEntity player, Outbound outbound) {
        if (player == null) return;
        if (outbound.plan() instanceof SnapshotTransport.Chunked chunked) {
            if (!ServerPlayNetworking.canSend(player, SnapshotBegin.ID)) {
                sendRaw(player, errorResponse(outbound.revision()), ConfigResponse.ID);
                return;
            }
            sendRaw(player, new SnapshotBegin(chunked.transferId(), outbound.kind(), outbound.success(),
                    outbound.revision(), chunked.chunkCount(), chunked.compressedSize(), outbound.replaceCatalog(),
                    outbound.compressedCatalog(), outbound.message(), outbound.errors()), SnapshotBegin.ID);
            for (SnapshotChunk chunk : chunked.chunks()) sendRaw(player, chunk, SnapshotChunk.ID);
            return;
        }

        byte[] snapshot = outbound.plan() instanceof SnapshotTransport.Inline inline
                ? inline.compressed() : new byte[0];
        sendRaw(player, new ConfigResponse(outbound.kind(), outbound.success(), outbound.revision(), snapshot,
                outbound.replaceCatalog(), outbound.compressedCatalog(), outbound.message(), outbound.errors()),
                ConfigResponse.ID);
    }

    private static ConfigResponse errorResponse(long revision) {
        return new ConfigResponse("ERROR", false, revision, new byte[0], false, new byte[0], OVERSIZED_MESSAGE,
                List.of());
    }

    private static boolean hasPermission(ServerPlayerEntity player) {
        return player != null && player.hasPermissionLevel(2);
    }

    private static <T extends CustomPayload> void sendRaw(ServerPlayerEntity player, T payload,
                                                          CustomPayload.Id<T> id) {
        if (player != null && ServerPlayNetworking.canSend(player, id)) ServerPlayNetworking.send(player, payload);
    }

    private record Outbound(String kind, boolean success, long revision, SnapshotTransport.Plan plan,
                            boolean replaceCatalog, byte[] compressedCatalog, String message,
                            List<String> errors) {
    }
}
