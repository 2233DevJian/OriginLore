package com.originlore.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded UTF-8 GZIP helpers used at the untrusted network boundary. */
public final class PayloadCompression {
    private static final int BUFFER_SIZE = 8 * 1024;

    private PayloadCompression() {
    }

    public static byte[] compressUtf8(String value, int maxUtf8Bytes, int maxCompressedBytes) {
        if (value == null) throw new IllegalArgumentException("payload is null");
        if (maxUtf8Bytes < 0 || maxCompressedBytes < 1) throw new IllegalArgumentException("invalid payload limit");
        byte[] source = value.getBytes(StandardCharsets.UTF_8);
        if (source.length > maxUtf8Bytes) {
            throw new IllegalArgumentException("payload exceeds the uncompressed limit of " + maxUtf8Bytes + " bytes");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, maxCompressedBytes));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(source);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not compress payload", exception);
        }
        byte[] compressed = output.toByteArray();
        if (compressed.length > maxCompressedBytes) {
            throw new IllegalArgumentException("payload exceeds the compressed limit of "
                    + maxCompressedBytes + " bytes");
        }
        return compressed;
    }

    public static String decompressUtf8(byte[] compressed, int maxCompressedBytes, int maxUtf8Bytes) {
        if (compressed == null || compressed.length == 0) throw new IllegalArgumentException("compressed payload is empty");
        if (maxCompressedBytes < 1 || maxUtf8Bytes < 0) throw new IllegalArgumentException("invalid payload limit");
        if (compressed.length > maxCompressedBytes) {
            throw new IllegalArgumentException("compressed payload exceeds the limit of "
                    + maxCompressedBytes + " bytes");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxUtf8Bytes, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() > maxUtf8Bytes - read) {
                    throw new IllegalArgumentException("payload exceeds the uncompressed limit of "
                            + maxUtf8Bytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("compressed payload is invalid", exception);
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("payload is not valid UTF-8", exception);
        }
    }
}
