package com.originlore.network;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

/** Reassembles one bounded server snapshot download on the admin client. */
public final class SnapshotDownloadAssembler {
    public enum Status { PENDING, COMPLETE, REJECTED }

    public record Result(Status status, String snapshotJson, String error) {
        static Result pending() {
            return new Result(Status.PENDING, "", "");
        }

        static Result complete(String snapshotJson) {
            return new Result(Status.COMPLETE, snapshotJson, "");
        }

        static Result rejected(String error) {
            return new Result(Status.REJECTED, "", error);
        }
    }

    private final int maxChunkBytes;
    private final int maxChunkCount;
    private final int maxCompressedBytes;
    private final int maxJsonBytes;
    private final long timeoutNanos;

    private UUID transferId;
    private int chunkCount;
    private int compressedSize;
    private byte[][] chunks;
    private int receivedChunks;
    private int receivedBytes;
    private long lastActivityNanos;

    public SnapshotDownloadAssembler(int maxChunkBytes, int maxChunkCount, int maxCompressedBytes,
                                     int maxJsonBytes, long timeoutNanos) {
        if (maxChunkBytes < 1 || maxChunkCount < 1 || maxCompressedBytes < 1
                || maxJsonBytes < 1 || timeoutNanos < 1) {
            throw new IllegalArgumentException("download limits must be positive");
        }
        this.maxChunkBytes = maxChunkBytes;
        this.maxChunkCount = maxChunkCount;
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxJsonBytes = maxJsonBytes;
        this.timeoutNanos = timeoutNanos;
    }

    /** Replaces any in-flight transfer. Returns null when the declaration is acceptable. */
    public synchronized String begin(UUID candidateTransferId, int candidateChunkCount, int candidateCompressedSize,
                                     long nowNanos) {
        discard();
        if (candidateTransferId == null) return "missing snapshot identity";
        String validationError = ChunkEnvelope.validateDeclaration(candidateChunkCount, candidateCompressedSize,
                maxChunkBytes, maxChunkCount, maxCompressedBytes, "snapshot");
        if (validationError != null) return validationError;

        transferId = candidateTransferId;
        chunkCount = candidateChunkCount;
        compressedSize = candidateCompressedSize;
        chunks = new byte[candidateChunkCount][];
        receivedChunks = 0;
        receivedBytes = 0;
        lastActivityNanos = nowNanos;
        return null;
    }

    public synchronized Result accept(UUID candidateTransferId, int chunkIndex, byte[] chunk, long nowNanos) {
        if (chunks == null) return Result.rejected("no snapshot transfer is active");
        if (candidateTransferId == null || !transferId.equals(candidateTransferId)) {
            discard();
            return Result.rejected("snapshot chunk belongs to another transfer");
        }
        String validationError = ChunkEnvelope.validate(chunkIndex, chunkCount, compressedSize, chunk,
                maxChunkBytes, maxChunkCount, maxCompressedBytes, "snapshot");
        if (validationError != null) {
            discard();
            return Result.rejected(validationError);
        }
        if (chunks[chunkIndex] != null) {
            discard();
            return Result.rejected("duplicate snapshot chunk " + chunkIndex);
        }

        chunks[chunkIndex] = Arrays.copyOf(chunk, chunk.length);
        receivedBytes += chunk.length;
        receivedChunks++;
        lastActivityNanos = nowNanos;
        if (receivedBytes > compressedSize || receivedBytes > maxCompressedBytes) {
            discard();
            return Result.rejected("snapshot contains more bytes than declared");
        }
        if (receivedChunks < chunks.length) return Result.pending();

        byte[][] received = chunks;
        int total = receivedBytes;
        int declared = compressedSize;
        discard();
        if (total != declared) return Result.rejected("snapshot size does not match its declaration");

        ByteArrayOutputStream combined = new ByteArrayOutputStream(declared);
        for (byte[] value : received) combined.writeBytes(value);
        try {
            return Result.complete(PayloadCompression.decompressUtf8(combined.toByteArray(), maxCompressedBytes,
                    maxJsonBytes));
        } catch (IllegalArgumentException exception) {
            return Result.rejected(exception.getMessage());
        }
    }

    public synchronized void discard() {
        transferId = null;
        chunks = null;
        chunkCount = 0;
        compressedSize = 0;
        receivedChunks = 0;
        receivedBytes = 0;
    }

    /** Drops a stalled transfer and reports whether one was dropped. */
    public synchronized boolean expire(long nowNanos) {
        if (chunks == null) return false;
        if (elapsed(nowNanos, lastActivityNanos) <= timeoutNanos) return false;
        discard();
        return true;
    }

    public synchronized boolean active() {
        return chunks != null;
    }

    private static long elapsed(long now, long then) {
        long difference = now - then;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }
}
