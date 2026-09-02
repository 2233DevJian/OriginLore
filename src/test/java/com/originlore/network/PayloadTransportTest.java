package com.originlore.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTransportTest {
    @Test
    void gzipRoundTripsUtf8AndRejectsLimits() {
        String json = "{\"lore\":\"酸甜多汁的浆果\",\"padding\":\"" + "x".repeat(10_000) + "\"}";
        byte[] compressed = PayloadCompression.compressUtf8(json, 20_000, 2_000);

        assertEquals(json, PayloadCompression.decompressUtf8(compressed, 2_000, 20_000));
        assertThrows(IllegalArgumentException.class,
                () -> PayloadCompression.compressUtf8(json, 100, 2_000));
        assertThrows(IllegalArgumentException.class,
                () -> PayloadCompression.decompressUtf8(compressed, 2_000, 100));
    }

    @Test
    void gzipRejectsCorruptAndNonUtf8Payloads() {
        assertThrows(IllegalArgumentException.class,
                () -> PayloadCompression.decompressUtf8(new byte[] {1, 2, 3}, 32, 32));

        byte[] invalidUtf8Gzip = gzipBytes(new byte[] {(byte) 0xC3, 0x28});
        assertThrows(IllegalArgumentException.class,
                () -> PayloadCompression.decompressUtf8(invalidUtf8Gzip, 128, 128));
    }

    @Test
    void chunksReassembleOnceEvenWhenDeliveredOutOfOrder() {
        int chunkSize = 17;
        String json = "{\"items\":\"" + "abcdef".repeat(200) + "\"}";
        byte[] compressed = PayloadCompression.compressUtf8(json, 8_000, 8_000);
        int count = (compressed.length + chunkSize - 1) / chunkSize;
        ConfigUploadAssembler assembler = new ConfigUploadAssembler(chunkSize, 100, 8_000, 8_000, 1_000);
        UUID player = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        ConfigUploadAssembler.Result result = null;

        for (int index = count - 1; index >= 0; index--) {
            int start = index * chunkSize;
            byte[] chunk = Arrays.copyOfRange(compressed, start, Math.min(compressed.length, start + chunkSize));
            result = assembler.accept(player, transfer, 41, "UPDATE", index, count,
                    compressed.length, chunk, 10 + count - 1 - index);
        }

        assertEquals(ConfigUploadAssembler.Status.COMPLETE, result.status());
        assertEquals(41, result.expectedRevision());
        assertEquals("UPDATE", result.operation());
        assertEquals(json, result.snapshotJson());
        assertEquals(0, assembler.activeUploads());
    }

    @Test
    void productionChunkFactoryStaysBelowTheServerboundLimit() {
        Random random = new Random(7);
        StringBuilder value = new StringBuilder(160_000);
        for (int index = 0; index < 160_000; index++) value.append((char) ('!' + random.nextInt(90)));
        String json = "{\"value\":" + quote(value.toString()) + "}";

        List<OriginLorePayloads.SubmitConfig> chunks = OriginLorePayloads.createSubmission(9, "UPDATE", json);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.chunk().length
                <= OriginLorePayloads.SUBMIT_CHUNK_BYTES));

        ConfigUploadAssembler assembler = new ConfigUploadAssembler(
                OriginLorePayloads.SUBMIT_CHUNK_BYTES, OriginLorePayloads.MAX_SUBMIT_CHUNKS,
                OriginLorePayloads.MAX_SUBMISSION_COMPRESSED_BYTES, OriginLorePayloads.MAX_JSON_BYTES,
                OriginLorePayloads.UPLOAD_TIMEOUT_NANOS);
        ConfigUploadAssembler.Result result = null;
        for (int index = 0; index < chunks.size(); index++) {
            OriginLorePayloads.SubmitConfig chunk = chunks.get(index);
            result = assembler.accept(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    chunk.transferId(), chunk.expectedRevision(), chunk.operation(), chunk.chunkIndex(),
                    chunk.chunkCount(), chunk.compressedSize(), chunk.chunk(), index);
        }
        assertEquals(ConfigUploadAssembler.Status.COMPLETE, result.status());
        assertEquals(json, result.snapshotJson());
    }

    @Test
    void changedMetadataDuplicateAndCorruptionAreRejected() {
        byte[] compressed = PayloadCompression.compressUtf8("{}", 128, 128);
        ConfigUploadAssembler assembler = new ConfigUploadAssembler(16, 16, 256, 256, 1_000);
        UUID player = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        int count = (compressed.length + 15) / 16;
        byte[] first = Arrays.copyOfRange(compressed, 0, 16);

        assertEquals(ConfigUploadAssembler.Status.PENDING,
                assembler.accept(player, transfer, 1, "UPDATE", 0, count, compressed.length, first, 1).status());
        assertEquals(ConfigUploadAssembler.Status.REJECTED,
                assembler.accept(player, UUID.randomUUID(), 1, "UPDATE", 0, count,
                        compressed.length, first, 2).status());
        assertEquals(0, assembler.activeUploads());

        ConfigUploadAssembler.Result corrupt = assembler.accept(player, UUID.randomUUID(), 1, "UPDATE",
                0, 1, 4, new byte[] {1, 2, 3, 4}, 3);
        assertEquals(ConfigUploadAssembler.Status.REJECTED, corrupt.status());
        assertTrue(corrupt.error().contains("invalid"));
    }

    @Test
    void inactiveUploadExpires() {
        byte[] compressed = PayloadCompression.compressUtf8("{\"large\":\""
                + "z".repeat(200) + "\"}", 1_000, 1_000);
        ConfigUploadAssembler assembler = new ConfigUploadAssembler(16, 100, 1_000, 1_000, 100);
        int count = (compressed.length + 15) / 16;
        ConfigUploadAssembler.Result pending = assembler.accept(UUID.randomUUID(), UUID.randomUUID(), 1,
                "UPDATE", 0, count, compressed.length, Arrays.copyOfRange(compressed, 0, 16), 10);
        assertEquals(ConfigUploadAssembler.Status.PENDING, pending.status());

        assembler.expire(111);
        assertEquals(0, assembler.activeUploads());
    }

    private static byte[] gzipBytes(byte[] value) {
        String reversible = new String(value, StandardCharsets.ISO_8859_1);
        byte[] utf8 = reversible.getBytes(StandardCharsets.ISO_8859_1);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(output)) {
                gzip.write(utf8);
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String quote(String value) {
        return com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(value)).toString();
    }
}
