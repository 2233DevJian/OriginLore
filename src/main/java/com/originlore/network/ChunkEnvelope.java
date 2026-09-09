package com.originlore.network;

/**
 * Direction-agnostic bounds checks for a chunked transfer. Both the client upload
 * and the server snapshot download share these rules so a limit cannot be relaxed
 * on one side and silently forgotten on the other.
 */
final class ChunkEnvelope {
    private ChunkEnvelope() {
    }

    /** Validates the transfer declaration a peer announces before any chunk arrives. */
    static String validateDeclaration(int chunkCount, int declaredSize, int maxChunkBytes, int maxChunkCount,
                                      int maxCompressedBytes, String noun) {
        if (chunkCount < 1 || chunkCount > maxChunkCount) return "invalid " + noun + " chunk count";
        if (declaredSize < 1 || declaredSize > maxCompressedBytes) return "invalid compressed " + noun + " size";
        if (chunkCount != (declaredSize + maxChunkBytes - 1) / maxChunkBytes) {
            return noun + " chunk count does not match its declared size";
        }
        return null;
    }

    /** Returns null when the envelope is consistent, otherwise a reason safe to log. */
    static String validate(int chunkIndex, int chunkCount, int declaredSize, byte[] chunk,
                           int maxChunkBytes, int maxChunkCount, int maxCompressedBytes, String noun) {
        String declaration = validateDeclaration(chunkCount, declaredSize, maxChunkBytes, maxChunkCount,
                maxCompressedBytes, noun);
        if (declaration != null) return declaration;
        if (chunkIndex < 0 || chunkIndex >= chunkCount) return "invalid " + noun + " chunk index";
        if (chunk == null || chunk.length < 1 || chunk.length > maxChunkBytes) return "invalid " + noun + " chunk size";
        int expectedSize = chunkIndex == chunkCount - 1
                ? declaredSize - maxChunkBytes * (chunkCount - 1) : maxChunkBytes;
        return chunk.length == expectedSize ? null : noun + " chunk has an unexpected length";
    }
}
