package com.originlore.client;

import com.originlore.config.ItemComponentConfig;
import com.originlore.network.OriginLorePayloads;
import com.originlore.network.PayloadCompression;
import com.originlore.network.PendingConfigSubmission;
import com.originlore.network.RegistryCatalog;
import com.originlore.network.SnapshotDownloadAssembler;
import com.originlore.network.SnapshotResponseValidator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.UUID;

/** Client-side cache of the authoritative server snapshot. */
public final class ClientConfigSession {
    public enum State {
        DISCONNECTED,
        IDLE,
        LOADING,
        RECEIVING,
        READY,
        SAVING,
        DENIED,
        UNSUPPORTED,
        ERROR,
        CONFLICT
    }

    private static final SnapshotDownloadAssembler DOWNLOADS = new SnapshotDownloadAssembler(
            OriginLorePayloads.SNAPSHOT_CHUNK_BYTES,
            OriginLorePayloads.MAX_SNAPSHOT_CHUNKS,
            OriginLorePayloads.MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES,
            OriginLorePayloads.MAX_JSON_BYTES,
            OriginLorePayloads.SNAPSHOT_TIMEOUT_NANOS);
    private static final PendingConfigSubmission SUBMISSION =
            new PendingConfigSubmission(OriginLorePayloads.UPLOAD_TIMEOUT_NANOS * 2);

    private static State state = State.DISCONNECTED;
    private static ItemComponentConfig.ConfigSnapshot snapshot;
    private static ItemComponentConfig.ConfigSnapshot sharedView;
    private static long sharedViewGeneration = Long.MIN_VALUE;
    private static RegistryCatalog catalog = RegistryCatalog.empty();
    private static String responseKind = "";
    private static String message = "";
    private static List<String> errors = List.of();
    private static long generation;
    private static Transfer transfer;

    private ClientConfigSession() {
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(OriginLorePayloads.ConfigResponse.ID,
                (payload, context) -> accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(OriginLorePayloads.SnapshotBegin.ID,
                (payload, context) -> beginTransfer(payload));
        ClientPlayNetworking.registerGlobalReceiver(OriginLorePayloads.SnapshotChunk.ID,
                (payload, context) -> acceptTransferChunk(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset(State.IDLE));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(State.DISCONNECTED));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (state == State.RECEIVING && DOWNLOADS.expire(System.nanoTime())) {
                failTransfer(GuiText.string("originlore.editor.server_receive_timeout"));
            } else if (SUBMISSION.expire(System.nanoTime())) {
                failTransfer(GuiText.string("originlore.editor.save_timeout"));
            }
        });
    }

    public static void requestSnapshot() {
        SUBMISSION.clear();
        abortTransfer();
        if (!ClientPlayNetworking.canSend(OriginLorePayloads.RequestConfig.ID)) {
            state = MinecraftClient.getInstance().getNetworkHandler() == null
                    ? State.DISCONNECTED : State.UNSUPPORTED;
            message = state == State.DISCONNECTED ? GuiText.string("originlore.editor.not_connected") : GuiText.string("originlore.editor.unsupported");
            responseKind = state.name();
            errors = List.of();
            generation++;
            return;
        }
        state = State.LOADING;
        responseKind = "LOADING";
        message = GuiText.string("originlore.editor.reading_config");
        errors = List.of();
        generation++;
        ClientPlayNetworking.send(new OriginLorePayloads.RequestConfig());
    }

    public static boolean submit(ItemComponentConfig.ConfigSnapshot transaction, String operation) {
        if (transaction == null || snapshot == null) {
            state = State.ERROR;
            responseKind = "ERROR";
            message = GuiText.string("originlore.editor.no_snapshot");
            errors = List.of();
            generation++;
            return false;
        }
        if (state != State.READY || SUBMISSION.active()) return false;
        if (!ClientPlayNetworking.canSend(OriginLorePayloads.SubmitConfig.ID)) {
            state = MinecraftClient.getInstance().getNetworkHandler() == null
                    ? State.DISCONNECTED : State.UNSUPPORTED;
            responseKind = state.name();
            message = state == State.DISCONNECTED ? GuiText.string("originlore.editor.disconnected_unsent") : GuiText.string("originlore.editor.protocol_unavailable");
            errors = List.of();
            generation++;
            return false;
        }
        if (transaction.revision() != snapshot.revision()) {
            state = State.CONFLICT;
            responseKind = "CONFLICT";
            message = GuiText.string("originlore.editor.revision_changed");
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
            message = GuiText.string("originlore.editor.upload_invalid") + compactMessage(exception);
            errors = List.of();
            generation++;
            return false;
        }

        state = State.SAVING;
        SUBMISSION.start(transaction.revision(), operation, transaction.settings().presetLanguage, System.nanoTime());
        responseKind = "SAVING";
        message = GuiText.string("originlore.editor.validating");
        errors = List.of();
        generation++;
        try {
            for (OriginLorePayloads.SubmitConfig chunk : chunks) ClientPlayNetworking.send(chunk);
            return true;
        } catch (RuntimeException exception) {
            SUBMISSION.clear();
            state = State.ERROR;
            responseKind = "ERROR";
            message = GuiText.string("originlore.editor.upload_interrupted") + compactMessage(exception);
            errors = List.of();
            generation++;
            return false;
        }
    }

    public static boolean changeLanguage(String language) {
        if (!canEdit() || (!"zh_cn".equals(language) && !"en_us".equals(language))) return false;
        ItemComponentConfig.Settings settings = snapshot.settings().copy();
        settings.presetLanguage = language;
        return submit(snapshot.withSettings(settings), "LANGUAGE");
    }

    private static void finishLanguageChange(boolean success) {
        try {
            String confirmedLanguage = SUBMISSION.accept(responseKind, success, snapshot == null ? -1 : snapshot.revision(),
                    snapshot == null ? null : snapshot.settings().presetLanguage);
            if (confirmedLanguage != null) GuiText.setLanguage(confirmedLanguage);
            if (SUBMISSION.active()) state = State.SAVING;
        } catch (IllegalArgumentException exception) {
            failTransfer(GuiText.string("originlore.editor.response_invalid") + compactMessage(exception));
        }
    }

    private static void accept(OriginLorePayloads.ConfigResponse payload) {
        if (snapshot != null && payload.revision() < snapshot.revision()
                || transfer != null && payload.revision() < transfer.revision()) return;
        abortTransfer();
        ItemComponentConfig.ConfigSnapshot received = snapshot;
        RegistryCatalog receivedCatalog = catalog;
        try {
            if (payload.compressedSnapshot() != null && payload.compressedSnapshot().length > 0) {
                String json = PayloadCompression.decompressUtf8(payload.compressedSnapshot(),
                        OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES, OriginLorePayloads.MAX_JSON_BYTES);
                received = SnapshotResponseValidator.parse(json, payload.revision());
            }
            if (payload.replaceCatalog()) receivedCatalog = decodeCatalog(payload.compressedCatalog());
        } catch (RuntimeException exception) {
            SUBMISSION.clear();
            state = State.ERROR;
            responseKind = "ERROR";
            message = GuiText.string("originlore.editor.response_invalid") + compactMessage(exception);
            errors = List.of();
            generation++;
            return;
        }

        snapshot = received;
        catalog = receivedCatalog;
        responseKind = payload.kind() == null ? "" : payload.kind();
        message = payload.message() == null ? "" : payload.message();
        errors = payload.errors() == null ? List.of() : List.copyOf(payload.errors());
        state = stateOf(responseKind, payload.success());
        finishLanguageChange(payload.success());
        generation++;
    }

    private static void beginTransfer(OriginLorePayloads.SnapshotBegin payload) {
        // A revision older than what is already applied means a stale broadcast outran a newer response; the
        // catalogue may still legitimately refresh at an unchanged revision, so only strictly older ones are dropped.
        if (snapshot != null && payload.revision() < snapshot.revision()
                || transfer != null && payload.revision() < transfer.revision()) return;

        String rejection = DOWNLOADS.begin(payload.transferId(), payload.chunkCount(), payload.compressedSize(),
                System.nanoTime());
        if (rejection != null) {
            failTransfer(GuiText.string("originlore.editor.snapshot_invalid") + rejection);
            return;
        }
        transfer = new Transfer(payload.transferId(), payload.revision(), payload.kind() == null ? "" : payload.kind(), payload.success(),
                payload.replaceCatalog(), payload.compressedCatalog(), payload.message() == null ? "" : payload.message(),
                payload.errors() == null ? List.of() : List.copyOf(payload.errors()));
        SUBMISSION.touch(System.nanoTime());
        state = State.RECEIVING;
        responseKind = transfer.kind();
        message = GuiText.string("originlore.editor.receiving");
        errors = List.of();
    }

    private static void acceptTransferChunk(OriginLorePayloads.SnapshotChunk payload) {
        Transfer pending = transfer;
        if (pending == null || state != State.RECEIVING) return;
        if (!pending.transferId().equals(payload.transferId())) return;
        SUBMISSION.touch(System.nanoTime());

        SnapshotDownloadAssembler.Result result = DOWNLOADS.accept(payload.transferId(), payload.chunkIndex(),
                payload.chunk(), System.nanoTime());
        if (result.status() == SnapshotDownloadAssembler.Status.PENDING) return;
        if (result.status() == SnapshotDownloadAssembler.Status.REJECTED) {
            failTransfer(GuiText.string("originlore.editor.receive_failed") + result.error());
            return;
        }

        ItemComponentConfig.ConfigSnapshot received;
        RegistryCatalog receivedCatalog = catalog;
        try {
            received = SnapshotResponseValidator.parse(result.snapshotJson(), pending.revision());
            if (pending.replaceCatalog()) receivedCatalog = decodeCatalog(pending.compressedCatalog());
        } catch (RuntimeException exception) {
            failTransfer(GuiText.string("originlore.editor.response_invalid") + compactMessage(exception));
            return;
        }

        transfer = null;
        snapshot = received;
        catalog = receivedCatalog;
        responseKind = pending.kind();
        message = pending.message();
        errors = pending.errors();
        state = stateOf(responseKind, pending.success());
        finishLanguageChange(pending.success());
        generation++;
    }

    /** Leaves the previously applied snapshot untouched so a broken transfer cannot blank the editor. */
    private static void failTransfer(String detail) {
        SUBMISSION.clear();
        DOWNLOADS.discard();
        transfer = null;
        state = State.ERROR;
        responseKind = "ERROR";
        message = detail;
        errors = List.of();
        generation++;
    }

    private static void abortTransfer() {
        DOWNLOADS.discard();
        transfer = null;
    }

    private static RegistryCatalog decodeCatalog(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return RegistryCatalog.empty();
        String json = PayloadCompression.decompressUtf8(compressed,
                OriginLorePayloads.MAX_CATALOG_COMPRESSED_BYTES, OriginLorePayloads.MAX_CATALOG_JSON_BYTES);
        return RegistryCatalog.fromJson(json);
    }

    private static State stateOf(String kind, boolean success) {
        if (success) return State.READY;
        if ("DENIED".equals(kind)) return State.DENIED;
        if ("CONFLICT".equals(kind)) return State.CONFLICT;
        return State.ERROR;
    }

    public static State state() {
        return state;
    }

    public static boolean canEdit() {
        return snapshot != null && state == State.READY && !SUBMISSION.active();
    }

    /** Returns a view shared between calls; callers must copy before mutating, as they already do. */
    public static ItemComponentConfig.ConfigSnapshot snapshot() {
        if (snapshot == null) return null;
        if (sharedView == null || sharedViewGeneration != generation) {
            sharedView = snapshot.withItems(snapshot.items());
            sharedViewGeneration = generation;
        }
        return sharedView;
    }

    public static long revision() {
        return snapshot == null ? -1 : snapshot.revision();
    }

    public static long lastSavedRevision() {
        return SUBMISSION.acknowledgedRevision();
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
        SUBMISSION.clear();
        abortTransfer();
        state = newState;
        snapshot = null;
        sharedView = null;
        sharedViewGeneration = Long.MIN_VALUE;
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

    private record Transfer(UUID transferId, long revision, String kind, boolean success, boolean replaceCatalog, byte[] compressedCatalog,
                            String message, List<String> errors) {
    }
}
