package com.originlore.network;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reassembles one bounded configuration upload per connected player. */
public final class ConfigUploadAssembler {
    public enum Status { PENDING, COMPLETE, REJECTED }

    public record Result(Status status, long expectedRevision, String operation, String snapshotJson,
                         String error) {
        static Result pending() {
            return new Result(Status.PENDING, -1, "", "", "");
        }

        static Result complete(long expectedRevision, String operation, String snapshotJson) {
            return new Result(Status.COMPLETE, expectedRevision, operation, snapshotJson, "");
        }

        static Result rejected(String error) {
            return new Result(Status.REJECTED, -1, "", "", error);
        }
    }

    private final int maxChunkBytes;
    private final int maxChunkCount;
    private final int maxCompressedBytes;
    private final int maxJsonBytes;
    private final long timeoutNanos;
    private final Map<UUID, Upload> uploads = new HashMap<>();

    public ConfigUploadAssembler(int maxChunkBytes, int maxChunkCount, int maxCompressedBytes,
                                 int maxJsonBytes, long timeoutNanos) {
        if (maxChunkBytes < 1 || maxChunkCount < 1 || maxCompressedBytes < 1
                || maxJsonBytes < 1 || timeoutNanos < 1) {
            throw new IllegalArgumentException("upload limits must be positive");
        }
        this.maxChunkBytes = maxChunkBytes;
        this.maxChunkCount = maxChunkCount;
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxJsonBytes = maxJsonBytes;
        this.timeoutNanos = timeoutNanos;
    }

    public synchronized Result accept(UUID playerId, UUID transferId, long expectedRevision, String operation,
                                      int chunkIndex, int chunkCount, int compressedSize, byte[] chunk,
                                      long nowNanos) {
        if (playerId == null || transferId == null) return Result.rejected("missing upload identity");
        expire(nowNanos);
        String validationError = validateEnvelope(chunkIndex, chunkCount, compressedSize, chunk);
        if (validationError != null) {
            uploads.remove(playerId);
            return Result.rejected(validationError);
        }

        Upload upload = uploads.get(playerId);
        if (upload == null) {
            upload = new Upload(transferId, expectedRevision, operation == null ? "" : operation,
                    chunkCount, compressedSize, nowNanos);
            uploads.put(playerId, upload);
        } else if (!upload.matches(transferId, expectedRevision, operation, chunkCount, compressedSize)) {
            uploads.remove(playerId);
            return Result.rejected("another upload is already active or chunk metadata changed");
        }

        if (upload.chunks[chunkIndex] != null) {
            uploads.remove(playerId);
            return Result.rejected("duplicate upload chunk " + chunkIndex);
        }
        upload.chunks[chunkIndex] = Arrays.copyOf(chunk, chunk.length);
        upload.receivedBytes += chunk.length;
        upload.receivedChunks++;
        upload.lastActivityNanos = nowNanos;
        if (upload.receivedBytes > upload.compressedSize || upload.receivedBytes > maxCompressedBytes) {
            uploads.remove(playerId);
            return Result.rejected("upload contains more bytes than declared");
        }
        if (upload.receivedChunks < upload.chunks.length) return Result.pending();
        uploads.remove(playerId);
        if (upload.receivedBytes != upload.compressedSize) {
            return Result.rejected("upload size does not match its declaration");
        }

        ByteArrayOutputStream combined = new ByteArrayOutputStream(upload.compressedSize);
        for (byte[] value : upload.chunks) combined.writeBytes(value);
        try {
            String json = PayloadCompression.decompressUtf8(combined.toByteArray(), maxCompressedBytes, maxJsonBytes);
            return Result.complete(upload.expectedRevision, upload.operation, json);
        } catch (IllegalArgumentException exception) {
            return Result.rejected(exception.getMessage());
        }
    }

    public synchronized void discard(UUID playerId) {
        if (playerId != null) uploads.remove(playerId);
    }

    public synchronized void clear() {
        uploads.clear();
    }

    public synchronized void expire(long nowNanos) {
        uploads.values().removeIf(upload -> elapsed(nowNanos, upload.lastActivityNanos) > timeoutNanos);
    }

    public synchronized int activeUploads() {
        return uploads.size();
    }

    private String validateEnvelope(int chunkIndex, int chunkCount, int compressedSize, byte[] chunk) {
        if (chunkCount < 1 || chunkCount > maxChunkCount) return "invalid upload chunk count";
        if (chunkIndex < 0 || chunkIndex >= chunkCount) return "invalid upload chunk index";
        if (compressedSize < 1 || compressedSize > maxCompressedBytes) return "invalid compressed upload size";
        if (chunk == null || chunk.length < 1 || chunk.length > maxChunkBytes) return "invalid upload chunk size";
        if (chunkCount != (compressedSize + maxChunkBytes - 1) / maxChunkBytes) {
            return "upload chunk count does not match its declared size";
        }
        int expectedSize = chunkIndex == chunkCount - 1
                ? compressedSize - maxChunkBytes * (chunkCount - 1) : maxChunkBytes;
        return chunk.length == expectedSize ? null : "upload chunk has an unexpected length";
    }

    private static long elapsed(long now, long then) {
        long difference = now - then;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }

    private static final class Upload {
        private final UUID transferId;
        private final long expectedRevision;
        private final String operation;
        private final int compressedSize;
        private final byte[][] chunks;
        private int receivedChunks;
        private int receivedBytes;
        private long lastActivityNanos;

        private Upload(UUID transferId, long expectedRevision, String operation, int chunkCount,
                       int compressedSize, long nowNanos) {
            this.transferId = transferId;
            this.expectedRevision = expectedRevision;
            this.operation = operation;
            this.compressedSize = compressedSize;
            this.chunks = new byte[chunkCount][];
            this.lastActivityNanos = nowNanos;
        }

        private boolean matches(UUID candidateTransferId, long candidateRevision, String candidateOperation,
                                int candidateChunkCount, int candidateCompressedSize) {
            return transferId.equals(candidateTransferId)
                    && expectedRevision == candidateRevision
                    && operation.equals(candidateOperation == null ? "" : candidateOperation)
                    && chunks.length == candidateChunkCount
                    && compressedSize == candidateCompressedSize;
        }
    }
}
