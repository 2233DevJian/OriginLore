package com.originlore.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;

/** Applies the same schema and revision checks to inline and chunked editor responses. */
public final class SnapshotResponseValidator {
    private SnapshotResponseValidator() { }

    public static ConfigSnapshot parse(String json, long declaredRevision) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != ItemComponentConfig.CURRENT_SCHEMA_VERSION
                || !root.has("settings")) {
            throw new IllegalArgumentException("OriginLore client and server configuration schemas differ");
        }
        if (declaredRevision < 0 || !root.has("revision") || root.get("revision").getAsLong() != declaredRevision) {
            throw new IllegalArgumentException("snapshot revision differs from its response envelope");
        }
        return ItemComponentConfig.snapshotFromJson(json);
    }
}
