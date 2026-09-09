package com.originlore.network;

import com.originlore.network.OriginLorePayloads.SnapshotChunk;

import java.util.List;
import java.util.UUID;

/** Chooses between the single-packet snapshot response and a chunked transfer. */
public final class SnapshotTransport {
    private SnapshotTransport() {
    }

    public sealed interface Plan permits Inline, Chunked, Oversized {
    }

    /** Fits the pre-existing single ConfigResponse packet. */
    public record Inline(byte[] compressed) implements Plan {
    }

    public record Chunked(UUID transferId, int chunkCount, int compressedSize,
                          List<SnapshotChunk> chunks) implements Plan {
    }

    /** Exceeds even the chunked ceiling; the peer must be told instead of silently dropped. */
    public record Oversized(String reason) implements Plan {
    }

    public static Plan plan(String snapshotJson) {
        try {
            return new Inline(PayloadCompression.compressUtf8(snapshotJson, OriginLorePayloads.MAX_JSON_BYTES,
                    OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES));
        } catch (IllegalArgumentException inline) {
            // Re-attempted below so an uncompressed-limit failure surfaces with its own message rather than
            // being mistaken for a snapshot that merely needs chunking.
        }

        byte[] compressed;
        try {
            compressed = PayloadCompression.compressUtf8(snapshotJson, OriginLorePayloads.MAX_JSON_BYTES,
                    OriginLorePayloads.MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES);
        } catch (IllegalArgumentException exception) {
            return new Oversized(exception.getMessage());
        }
        UUID transferId = UUID.randomUUID();
        List<SnapshotChunk> chunks = OriginLorePayloads.createSnapshotChunks(transferId, compressed);
        return new Chunked(transferId, chunks.size(), compressed.length, chunks);
    }
}
