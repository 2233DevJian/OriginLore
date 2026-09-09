package com.originlore.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.originlore.source.SourceContext.SourceType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiTranslationsTest {
    @Test
    void bothEditorLanguagesCoverTheSameFieldsAndEverySourceType() throws IOException {
        JsonObject chinese = dictionary("zh_cn");
        JsonObject english = dictionary("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        for (SourceType type : SourceType.values()) {
            String key = "originlore.source." + type.name().toLowerCase(Locale.ROOT);
            assertTrue(chinese.has(key), "Chinese source label is missing: " + key);
            assertTrue(english.has(key), "English source label is missing: " + key);
            assertTrue(!chinese.get(key).getAsString().isBlank() && !english.get(key).getAsString().isBlank(), key);
        }
    }

    private static JsonObject dictionary(String language) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/originlore/lang/" + language + ".json")))
                .getAsJsonObject();
    }
}
