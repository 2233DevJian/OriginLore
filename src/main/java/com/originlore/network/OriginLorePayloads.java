package com.originlore.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Typed, size-bounded play payloads shared by the logical server and optional admin client. */
public final class OriginLorePayloads {
    public static final int PROTOCOL_VERSION = 4;
    public static final int SUBMIT_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_SUBMISSION_COMPRESSED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SUBMIT_CHUNKS =
            (MAX_SUBMISSION_COMPRESSED_BYTES + SUBMIT_CHUNK_BYTES - 1) / SUBMIT_CHUNK_BYTES;
    public static final int MAX_SNAPSHOT_COMPRESSED_BYTES = 128 * 1024;
    /** Snapshots above the inline threshold travel as chunks, capped at what a client may submit. */
    public static final int SNAPSHOT_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SNAPSHOT_CHUNKS =
            (MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES;
    public static final int MAX_CATALOG_COMPRESSED_BYTES = 700 * 1024;
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CATALOG_JSON_BYTES = 32 * 1024 * 1024;
    public static final long UPLOAD_TIMEOUT_NANOS = 30_000_000_000L;
    public static final long SNAPSHOT_TIMEOUT_NANOS = 30_000_000_000L;

    private static final int MAX_OPERATION_LENGTH = 32;
    private static final int MAX_KIND_LENGTH = 32;
    private static final int MAX_MESSAGE_LENGTH = 2_048;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int MAX_ERROR_COUNT = 32;

    private OriginLorePayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(RequestConfig.ID, RequestConfig.CODEC);
        PayloadTypeRegistry.playC2S().register(SubmitConfig.ID, SubmitConfig.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigResponse.ID, ConfigResponse.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotBegin.ID, SnapshotBegin.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotChunk.ID, SnapshotChunk.CODEC);
    }

    public static List<SubmitConfig> createSubmission(long expectedRevision, String operation, String snapshotJson) {
        byte[] compressed = PayloadCompression.compressUtf8(snapshotJson, MAX_JSON_BYTES,
                MAX_SUBMISSION_COMPRESSED_BYTES);
        UUID transferId = UUID.randomUUID();
        int chunkCount = (compressed.length + SUBMIT_CHUNK_BYTES - 1) / SUBMIT_CHUNK_BYTES;
        List<SubmitConfig> result = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int start = index * SUBMIT_CHUNK_BYTES;
            int end = Math.min(compressed.length, start + SUBMIT_CHUNK_BYTES);
            result.add(new SubmitConfig(transferId, expectedRevision, operation, index, chunkCount,
                    compressed.length, Arrays.copyOfRange(compressed, start, end)));
        }
        return List.copyOf(result);
    }

    public static List<SnapshotChunk> createSnapshotChunks(UUID transferId, byte[] compressed) {
        int chunkCount = (compressed.length + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES;
        List<SnapshotChunk> result = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int start = index * SNAPSHOT_CHUNK_BYTES;
            int end = Math.min(compressed.length, start + SNAPSHOT_CHUNK_BYTES);
            result.add(new SnapshotChunk(transferId, index, Arrays.copyOfRange(compressed, start, end)));
        }
        return List.copyOf(result);
    }

    public record RequestConfig() implements CustomPayload {
        public static final Id<RequestConfig> ID = new Id<>(Identifier.of("originlore", "request_config_v2"));
        public static final PacketCodec<RegistryByteBuf, RequestConfig> CODEC = PacketCodec.of(
                (payload, buffer) -> { },
                buffer -> new RequestConfig());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record SubmitConfig(UUID transferId, long expectedRevision, String operation, int chunkIndex,
                               int chunkCount, int compressedSize, byte[] chunk) implements CustomPayload {
        public static final Id<SubmitConfig> ID = new Id<>(Identifier.of("originlore", "submit_config_v2"));
        public static final PacketCodec<RegistryByteBuf, SubmitConfig> CODEC = PacketCodec.of(
                (payload, buffer) -> {
                    buffer.writeUuid(payload.transferId);
                    buffer.writeVarLong(payload.expectedRevision);
                    buffer.writeString(limit(payload.operation, MAX_OPERATION_LENGTH, "UPDATE"), MAX_OPERATION_LENGTH);
                    buffer.writeVarInt(payload.chunkIndex);
                    buffer.writeVarInt(payload.chunkCount);
                    buffer.writeVarInt(payload.compressedSize);
                    writeByteArray(buffer, payload.chunk, SUBMIT_CHUNK_BYTES);
                },
                buffer -> new SubmitConfig(buffer.readUuid(), buffer.readVarLong(),
                        buffer.readString(MAX_OPERATION_LENGTH), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readByteArray(SUBMIT_CHUNK_BYTES)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ConfigResponse(String kind, boolean success, long revision, byte[] compressedSnapshot,
                                 boolean replaceCatalog, byte[] compressedCatalog, String message,
                                 List<String> errors) implements CustomPayload {
        public static final Id<ConfigResponse> ID = new Id<>(Identifier.of("originlore", "config_response_v2"));
        public static final PacketCodec<RegistryByteBuf, ConfigResponse> CODEC = PacketCodec.of(
                (payload, buffer) -> {
                    buffer.writeString(limit(payload.kind, MAX_KIND_LENGTH, "ERROR"), MAX_KIND_LENGTH);
                    buffer.writeBoolean(payload.success);
                    buffer.writeVarLong(payload.revision);
                    writeByteArray(buffer, payload.compressedSnapshot, MAX_SNAPSHOT_COMPRESSED_BYTES);
                    buffer.writeBoolean(payload.replaceCatalog);
                    writeByteArray(buffer, payload.compressedCatalog, MAX_CATALOG_COMPRESSED_BYTES);
                    buffer.writeString(limit(payload.message, MAX_MESSAGE_LENGTH, ""), MAX_MESSAGE_LENGTH);
                    writeErrors(buffer, payload.errors);
                },
                buffer -> {
                    String kind = buffer.readString(MAX_KIND_LENGTH);
                    boolean success = buffer.readBoolean();
                    long revision = buffer.readVarLong();
                    byte[] snapshot = buffer.readByteArray(MAX_SNAPSHOT_COMPRESSED_BYTES);
                    boolean replaceCatalog = buffer.readBoolean();
                    byte[] catalog = buffer.readByteArray(MAX_CATALOG_COMPRESSED_BYTES);
                    String message = buffer.readString(MAX_MESSAGE_LENGTH);
                    return new ConfigResponse(kind, success, revision, snapshot, replaceCatalog, catalog,
                            message, readErrors(buffer));
                });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Declares a chunked snapshot transfer; the snapshot body arrives in the following SnapshotChunk packets. */
    public record SnapshotBegin(UUID transferId, String kind, boolean success, long revision, int chunkCount,
                                int compressedSize, boolean replaceCatalog, byte[] compressedCatalog,
                                String message, List<String> errors) implements CustomPayload {
        public static final Id<SnapshotBegin> ID = new Id<>(Identifier.of("originlore", "snapshot_begin_v2"));
        public static final PacketCodec<RegistryByteBuf, SnapshotBegin> CODEC = PacketCodec.of(
                (payload, buffer) -> {
                    buffer.writeUuid(payload.transferId);
                    buffer.writeString(limit(payload.kind, MAX_KIND_LENGTH, "ERROR"), MAX_KIND_LENGTH);
                    buffer.writeBoolean(payload.success);
                    buffer.writeVarLong(payload.revision);
                    buffer.writeVarInt(payload.chunkCount);
                    buffer.writeVarInt(payload.compressedSize);
                    buffer.writeBoolean(payload.replaceCatalog);
                    writeByteArray(buffer, payload.compressedCatalog, MAX_CATALOG_COMPRESSED_BYTES);
                    buffer.writeString(limit(payload.message, MAX_MESSAGE_LENGTH, ""), MAX_MESSAGE_LENGTH);
                    writeErrors(buffer, payload.errors);
                },
                buffer -> new SnapshotBegin(buffer.readUuid(), buffer.readString(MAX_KIND_LENGTH),
                        buffer.readBoolean(), buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readBoolean(), buffer.readByteArray(MAX_CATALOG_COMPRESSED_BYTES),
                        buffer.readString(MAX_MESSAGE_LENGTH), readErrors(buffer)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record SnapshotChunk(UUID transferId, int chunkIndex, byte[] chunk) implements CustomPayload {
        public static final Id<SnapshotChunk> ID = new Id<>(Identifier.of("originlore", "snapshot_chunk_v2"));
        public static final PacketCodec<RegistryByteBuf, SnapshotChunk> CODEC = PacketCodec.of(
                (payload, buffer) -> {
                    buffer.writeUuid(payload.transferId);
                    buffer.writeVarInt(payload.chunkIndex);
                    writeByteArray(buffer, payload.chunk, SNAPSHOT_CHUNK_BYTES);
                },
                buffer -> new SnapshotChunk(buffer.readUuid(), buffer.readVarInt(),
                        buffer.readByteArray(SNAPSHOT_CHUNK_BYTES)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static void writeErrors(RegistryByteBuf buffer, List<String> errors) {
        List<String> values = errors == null ? List.of() : errors;
        int size = Math.min(values.size(), MAX_ERROR_COUNT);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeString(limit(values.get(index), MAX_ERROR_LENGTH, "validation failed"), MAX_ERROR_LENGTH);
        }
    }

    private static List<String> readErrors(RegistryByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ERROR_COUNT) {
            throw new IllegalArgumentException("invalid OriginLore error count: " + count);
        }
        List<String> errors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            errors.add(buffer.readString(MAX_ERROR_LENGTH));
        }
        return List.copyOf(errors);
    }

    private static void writeByteArray(RegistryByteBuf buffer, byte[] value, int maxLength) {
        byte[] bytes = value == null ? new byte[0] : value;
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException("payload field exceeds " + maxLength + " bytes");
        }
        buffer.writeByteArray(bytes);
    }

    private static String limit(String value, int maxLength, String fallback) {
        String result = value == null ? fallback : value;
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }
}
