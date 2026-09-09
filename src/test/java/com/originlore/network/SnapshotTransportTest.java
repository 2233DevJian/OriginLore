package com.originlore.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTransportTest {
    private static final int SMALL_CHUNK_BYTES = 16;
    private static final int SMALL_CHUNK_COUNT = 8;
    private static final int SMALL_COMPRESSED_BYTES = 256;
    private static final int SMALL_JSON_BYTES = 4_096;
    private static final long SMALL_TIMEOUT_NANOS = 1_000;

    @Test
    void snapshotsBelowTheInlineThresholdReuseTheLegacySinglePacket() {
        String json = "{\"schemaVersion\":3,\"revision\":0,\"items\":{}}";
        SnapshotTransport.Inline inline = assertInstanceOf(SnapshotTransport.Inline.class,
                SnapshotTransport.plan(json));

        assertTrue(inline.compressed().length <= OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES);
        assertEquals(json, PayloadCompression.decompressUtf8(inline.compressed(),
                OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES, OriginLorePayloads.MAX_JSON_BYTES));
    }

    @Test
    void snapshotsAboveTheInlineThresholdBecomeBoundedChunks() {
        String json = incompressibleJson(200_000);
        SnapshotTransport.Chunked chunked = assertInstanceOf(SnapshotTransport.Chunked.class,
                SnapshotTransport.plan(json));

        assertTrue(chunked.compressedSize() > OriginLorePayloads.MAX_SNAPSHOT_COMPRESSED_BYTES);
        assertTrue(chunked.compressedSize() <= OriginLorePayloads.MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES);
        assertEquals(chunked.chunkCount(), chunked.chunks().size());
        assertEquals((chunked.compressedSize() + OriginLorePayloads.SNAPSHOT_CHUNK_BYTES - 1)
                / OriginLorePayloads.SNAPSHOT_CHUNK_BYTES, chunked.chunkCount());
        for (OriginLorePayloads.SnapshotChunk chunk : chunked.chunks()) {
            assertEquals(chunked.transferId(), chunk.transferId());
            assertTrue(chunk.chunk().length > 0);
            assertTrue(chunk.chunk().length <= OriginLorePayloads.SNAPSHOT_CHUNK_BYTES);
        }
    }

    @Test
    void snapshotsAboveTheTransferCeilingAreReportedInsteadOfDropped() {
        SnapshotTransport.Oversized oversized = assertInstanceOf(SnapshotTransport.Oversized.class,
                SnapshotTransport.plan(incompressibleJson(3_000_000)));
        assertFalse(oversized.reason().isBlank());
    }

    @Test
    void productionChunksReassembleOnceEvenWhenDeliveredOutOfOrder() {
        String json = incompressibleJson(200_000);
        SnapshotTransport.Chunked chunked = assertInstanceOf(SnapshotTransport.Chunked.class,
                SnapshotTransport.plan(json));
        SnapshotDownloadAssembler assembler = productionAssembler();
        List<OriginLorePayloads.SnapshotChunk> chunks = chunked.chunks();

        assertNull(assembler.begin(chunked.transferId(), chunked.chunkCount(), chunked.compressedSize(), 10));
        SnapshotDownloadAssembler.Result result = null;
        for (int position = chunks.size() - 1; position >= 0; position--) {
            OriginLorePayloads.SnapshotChunk chunk = chunks.get(position);
            result = assembler.accept(chunk.transferId(), chunk.chunkIndex(), chunk.chunk(), 11 + position);
            if (position > 0) assertEquals(SnapshotDownloadAssembler.Status.PENDING, result.status());
        }

        assertEquals(SnapshotDownloadAssembler.Status.COMPLETE, result.status());
        assertEquals(json, result.snapshotJson());
        assertFalse(assembler.active());
    }

    @Test
    void chunksArrivingWithoutADeclarationAreRejected() {
        SnapshotDownloadAssembler.Result result = smallAssembler()
                .accept(UUID.randomUUID(), 0, new byte[] {1, 2, 3, 4}, 1);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, result.status());
        assertTrue(result.error().contains("no snapshot transfer is active"));
    }

    @Test
    void inconsistentDeclarationsAreRefusedBeforeAnyChunkArrives() {
        SnapshotDownloadAssembler assembler = smallAssembler();
        byte[] compressed = smallCompressed("{\"a\":1}");

        assertNotNull(assembler.begin(UUID.randomUUID(), chunkCount(compressed) + 1, compressed.length, 1));
        assertNotNull(assembler.begin(UUID.randomUUID(), 0, SMALL_CHUNK_BYTES, 1));
        assertNotNull(assembler.begin(UUID.randomUUID(), 2, SMALL_COMPRESSED_BYTES + 1, 1));
        assertNotNull(assembler.begin(UUID.randomUUID(), SMALL_CHUNK_COUNT + 1,
                SMALL_CHUNK_BYTES * (SMALL_CHUNK_COUNT + 1), 1));
        assertNotNull(assembler.begin(null, 1, SMALL_CHUNK_BYTES, 1));
        assertFalse(assembler.active());
    }

    @Test
    void duplicateChunksAreRejectedAndClearTheTransfer() {
        byte[] compressed = smallCompressed("{\"a\":1}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID transfer = UUID.randomUUID();
        assertNull(assembler.begin(transfer, chunkCount(compressed), compressed.length, 1));

        assertEquals(SnapshotDownloadAssembler.Status.PENDING,
                assembler.accept(transfer, 0, chunk(compressed, 0), 2).status());
        SnapshotDownloadAssembler.Result duplicate = assembler.accept(transfer, 0, chunk(compressed, 0), 3);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, duplicate.status());
        assertTrue(duplicate.error().contains("duplicate"));
        assertFalse(assembler.active());
    }

    @Test
    void chunksFromAnotherTransferAreRejected() {
        byte[] compressed = smallCompressed("{\"a\":1}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        assertNull(assembler.begin(UUID.randomUUID(), chunkCount(compressed), compressed.length, 1));

        SnapshotDownloadAssembler.Result foreign =
                assembler.accept(UUID.randomUUID(), 0, chunk(compressed, 0), 2);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, foreign.status());
        assertTrue(foreign.error().contains("another transfer"));
        assertFalse(assembler.active());
    }

    @Test
    void chunkIndexesOutsideTheDeclarationAreRejected() {
        byte[] compressed = smallCompressed("{\"a\":1}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID transfer = UUID.randomUUID();
        int count = chunkCount(compressed);
        assertNull(assembler.begin(transfer, count, compressed.length, 1));

        SnapshotDownloadAssembler.Result result = assembler.accept(transfer, count, chunk(compressed, 0), 2);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, result.status());
        assertTrue(result.error().contains("invalid snapshot chunk index"));
        assertFalse(assembler.active());
    }

    @Test
    void chunksOfAnUnexpectedLengthAreRejected() {
        byte[] compressed = smallCompressed("{\"a\":1}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID transfer = UUID.randomUUID();
        assertNull(assembler.begin(transfer, chunkCount(compressed), compressed.length, 1));

        SnapshotDownloadAssembler.Result result = assembler.accept(transfer, 0,
                Arrays.copyOfRange(compressed, 0, SMALL_CHUNK_BYTES - 1), 2);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, result.status());
        assertTrue(result.error().contains("unexpected length"));
        assertFalse(assembler.active());
    }

    @Test
    void aFreshDeclarationDiscardsTheTransferAlreadyInProgress() {
        byte[] stale = smallCompressed("{\"a\":1}");
        byte[] fresh = smallCompressed("{\"b\":22}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID abandoned = UUID.randomUUID();
        assertNull(assembler.begin(abandoned, chunkCount(stale), stale.length, 1));
        assertEquals(SnapshotDownloadAssembler.Status.PENDING,
                assembler.accept(abandoned, 0, chunk(stale, 0), 2).status());

        UUID transfer = UUID.randomUUID();
        assertNull(assembler.begin(transfer, chunkCount(fresh), fresh.length, 3));
        SnapshotDownloadAssembler.Result result = null;
        for (int index = 0; index < chunkCount(fresh); index++) {
            result = assembler.accept(transfer, index, chunk(fresh, index), 4 + index);
        }

        assertEquals(SnapshotDownloadAssembler.Status.COMPLETE, result.status());
        assertEquals("{\"b\":22}", result.snapshotJson());
    }

    @Test
    void aStalledTransferExpiresAndRejectsLateChunks() {
        byte[] compressed = smallCompressed("{\"a\":1}");
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID transfer = UUID.randomUUID();
        assertNull(assembler.begin(transfer, chunkCount(compressed), compressed.length, 10));

        assertFalse(assembler.expire(10 + SMALL_TIMEOUT_NANOS));
        assertTrue(assembler.active());
        assertTrue(assembler.expire(11 + SMALL_TIMEOUT_NANOS));
        assertFalse(assembler.active());

        SnapshotDownloadAssembler.Result late =
                assembler.accept(transfer, 0, chunk(compressed, 0), 12 + SMALL_TIMEOUT_NANOS);
        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, late.status());
    }

    @Test
    void corruptCompressedBytesAreRejectedWithoutCompleting() {
        SnapshotDownloadAssembler assembler = smallAssembler();
        UUID transfer = UUID.randomUUID();
        assertNull(assembler.begin(transfer, 1, 4, 1));

        SnapshotDownloadAssembler.Result result = assembler.accept(transfer, 0, new byte[] {1, 2, 3, 4}, 2);

        assertEquals(SnapshotDownloadAssembler.Status.REJECTED, result.status());
        assertTrue(result.error().contains("invalid"));
        assertFalse(assembler.active());
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotDownloadAssembler(0, SMALL_CHUNK_COUNT,
                SMALL_COMPRESSED_BYTES, SMALL_JSON_BYTES, SMALL_TIMEOUT_NANOS));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotDownloadAssembler(SMALL_CHUNK_BYTES,
                SMALL_CHUNK_COUNT, SMALL_COMPRESSED_BYTES, SMALL_JSON_BYTES, 0));
    }

    private static SnapshotDownloadAssembler productionAssembler() {
        return new SnapshotDownloadAssembler(OriginLorePayloads.SNAPSHOT_CHUNK_BYTES,
                OriginLorePayloads.MAX_SNAPSHOT_CHUNKS,
                OriginLorePayloads.MAX_SNAPSHOT_TRANSFER_COMPRESSED_BYTES,
                OriginLorePayloads.MAX_JSON_BYTES,
                OriginLorePayloads.SNAPSHOT_TIMEOUT_NANOS);
    }

    private static SnapshotDownloadAssembler smallAssembler() {
        return new SnapshotDownloadAssembler(SMALL_CHUNK_BYTES, SMALL_CHUNK_COUNT, SMALL_COMPRESSED_BYTES,
                SMALL_JSON_BYTES, SMALL_TIMEOUT_NANOS);
    }

    private static byte[] smallCompressed(String json) {
        return PayloadCompression.compressUtf8(json, SMALL_JSON_BYTES, SMALL_COMPRESSED_BYTES);
    }

    private static int chunkCount(byte[] compressed) {
        return (compressed.length + SMALL_CHUNK_BYTES - 1) / SMALL_CHUNK_BYTES;
    }

    private static byte[] chunk(byte[] compressed, int index) {
        int start = index * SMALL_CHUNK_BYTES;
        return Arrays.copyOfRange(compressed, start, Math.min(compressed.length, start + SMALL_CHUNK_BYTES));
    }

    /** Random text from an alphabet that needs no JSON escaping, so gzip cannot shrink it below the limit under test. */
    private static String incompressibleJson(int valueChars) {
        Random random = new Random(11);
        char[] alphabet = alphabet();
        StringBuilder value = new StringBuilder(valueChars);
        for (int index = 0; index < valueChars; index++) value.append(alphabet[random.nextInt(alphabet.length)]);
        return "{\"value\":\"" + value + "\"}";
    }

    private static char[] alphabet() {
        StringBuilder characters = new StringBuilder();
        for (char value = '!'; value <= '~'; value++) {
            if (value != '"' && value != '\\') characters.append(value);
        }
        return characters.toString().toCharArray();
    }
}
