package com.originlore.client;

import com.originlore.config.ItemComponentConfig;
import com.originlore.network.OriginLorePayloads;
import com.originlore.network.PayloadCompression;
import com.originlore.network.RegistryCatalog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.List;

/** Client-side cache of the authoritative server snapshot. */
public final class ClientConfigSession {
    public enum State {
        DISCONNECTED,
        IDLE,
        LOADING,
        READY,
        SAVING,
        DENIED,
        UNSUPPORTED,
        ERROR,
        CONFLICT
    }

    private static State state = State.DISCONNECTED;
    private static ItemComponentConfig.ConfigSnapshot snapshot;
    private static RegistryCatalog catalog = RegistryCatalog.empty();
    private static String responseKind = "";
    private static String message = "";
    private static List<String> errors = List.of();
    private static long generation;

    private ClientConfigSession() {
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(OriginLorePayloads.ConfigResponse.ID,
                (payload, context) -> accept(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset(State.IDLE));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(State.DISCONNECTED));
    }

    public static void requestSnapshot() {
        if (!ClientPlayNetworking.canSend(OriginLorePayloads.RequestConfig.ID)) {
            state = MinecraftClient.getInstance().getNetworkHandler() == null
                    ? State.DISCONNECTED : State.UNSUPPORTED;
            message = state == State.DISCONNECTED ? "未连接到游戏服务器" : "服务器未安装 OriginLore 或协议不兼容";
            responseKind = state.name();
            errors = List.of();
            generation++;
            return;
        }
        state = State.LOADING;
        responseKind = "LOADING";
        message = "正在读取服务器配置";
        errors = List.of();
        generation++;
        ClientPlayNetworking.send(new OriginLorePayloads.RequestConfig());
    }

    public static boolean submit(ItemComponentConfig.ConfigSnapshot transaction, String operation) {
        if (transaction == null || snapshot == null) {
            state = State.ERROR;
            responseKind = "ERROR";
            message = "没有可提交的配置快照";
            errors = List.of();
            generation++;
            return false;
        }
        if (state != State.READY) return false;
        if (!ClientPlayNetworking.canSend(OriginLorePayloads.SubmitConfig.ID)) {
            state = MinecraftClient.getInstance().getNetworkHandler() == null
                    ? State.DISCONNECTED : State.UNSUPPORTED;
            responseKind = state.name();
            message = state == State.DISCONNECTED ? "连接已断开，修改未发送" : "服务器管理协议已不可用";
            errors = List.of();
            generation++;
            return false;
        }
        if (transaction.revision() != snapshot.revision()) {
            state = State.CONFLICT;
            responseKind = "CONFLICT";
            message = "服务器配置版本已变化，请重新同步后再编辑";
            errors = List.of();
            generation++;
            return false;
        }

        final List<OriginLorePayloads.SubmitConfig> chunks;
        try {
            chunks = OriginLorePayloads.createSubmission(transaction.revision(), operation,
                    ItemComponentConfig.snapshotToJson(transaction));
        } catch (IllegalArgumentException exception) {
            state = State.ERROR;
            responseKind = "ERROR";
            message = "配置无法上传: " + compactMessage(exception);
            errors = List.of();
            generation++;
            return false;
        }

        state = State.SAVING;
        responseKind = "SAVING";
        message = "正在由服务器校验并保存";
        errors = List.of();
        generation++;
        try {
            for (OriginLorePayloads.SubmitConfig chunk : chunks) ClientPlayNetworking.send(chunk);
            return true;
        } catch (RuntimeException exception) {
            state = State.ERROR;
            responseKind = "ERROR";
            message = "配置上传中断: " + compactMessage(exception);
            errors = List.of();
            generation++;
            return false;
        }
    }

    private static void accept(OriginLorePayloads.ConfigResponse payload) {
        ItemComponentConfig.ConfigSnapshot received = snapshot;
        RegistryCatalog receivedCatalog = catalog;
        try {
            if (payload.compressedSnapshot() != null && payload.compressedSnapshot().length > 0) {
                String json = PayloadCompression.decompressUtf8(payload.compressedSnapshot(),
                        OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES, OriginLorePayloads.MAX_JSON_BYTES);
                received = ItemComponentConfig.snapshotFromJson(json);
            }
            if (payload.replaceCatalog()) {
                if (payload.compressedCatalog() == null || payload.compressedCatalog().length == 0) {
                    receivedCatalog = RegistryCatalog.empty();
                } else {
                    String json = PayloadCompression.decompressUtf8(payload.compressedCatalog(),
                            OriginLorePayloads.MAX_CATALOG_COMPRESSED_BYTES,
                            OriginLorePayloads.MAX_CATALOG_JSON_BYTES);
                    receivedCatalog = RegistryCatalog.fromJson(json);
                }
            }
        } catch (RuntimeException exception) {
            state = State.ERROR;
            responseKind = "ERROR";
            message = "服务器返回了无法解析的配置: " + compactMessage(exception);
            errors = List.of();
            generation++;
            return;
        }

        snapshot = received;
        catalog = receivedCatalog;
        responseKind = payload.kind() == null ? "" : payload.kind();
        message = payload.message() == null ? "" : payload.message();
        errors = payload.errors() == null ? List.of() : List.copyOf(payload.errors());
        if (payload.success()) state = State.READY;
        else if ("DENIED".equals(responseKind)) state = State.DENIED;
        else if ("CONFLICT".equals(responseKind)) state = State.CONFLICT;
        else state = State.ERROR;
        generation++;
    }

    public static State state() {
        return state;
    }

    public static boolean canEdit() {
        return snapshot != null && state == State.READY;
    }

    public static ItemComponentConfig.ConfigSnapshot snapshot() {
        return snapshot == null ? null : new ItemComponentConfig.ConfigSnapshot(snapshot.revision(), snapshot.items());
    }

    public static long revision() {
        return snapshot == null ? -1 : snapshot.revision();
    }

    public static RegistryCatalog catalog() {
        return catalog;
    }

    public static String responseKind() {
        return responseKind;
    }

    public static String message() {
        return message;
    }

    public static List<String> errors() {
        return errors;
    }

    public static long generation() {
        return generation;
    }

    private static void reset(State newState) {
        state = newState;
        snapshot = null;
        catalog = RegistryCatalog.empty();
        responseKind = newState.name();
        message = "";
        errors = List.of();
        generation++;
    }

    private static String compactMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
