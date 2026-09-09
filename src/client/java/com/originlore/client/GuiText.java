package com.originlore.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** OriginLore's editor language is independent of Minecraft's language setting. */
public final class GuiText {
    private static final Logger LOGGER = LoggerFactory.getLogger("OriginLore/GuiText");
    private static final Path PREFERENCE = FabricLoader.getInstance().getConfigDir().resolve("originlore-client.json");
    private static final Map<String, String> CHINESE = readDictionary("zh_cn");
    private static final Map<String, String> ENGLISH = readDictionary("en_us");
    private static String language = readPreference();

    private GuiText() { }

    public static String language() {
        return language;
    }

    public static String otherLanguage() {
        return "zh_cn".equals(language) ? "en_us" : "zh_cn";
    }

    public static MutableText text(String key, Object... arguments) {
        return Text.literal(string(key, arguments));
    }

    public static String string(String key, Object... arguments) {
        Map<String, String> dictionary = "en_us".equals(language) ? ENGLISH : CHINESE;
        String value = dictionary.getOrDefault(key, CHINESE.getOrDefault(key, key));
        if (arguments.length == 0) return value;
        Object[] formatted = arguments.clone();
        for (int index = 0; index < formatted.length; index++) {
            if (formatted[index] instanceof Text text) formatted[index] = text.getString();
        }
        return String.format(Locale.ROOT, value, formatted);
    }

    public static void setLanguage(String requested) {
        if (!"zh_cn".equals(requested) && !"en_us".equals(requested)) return;
        language = requested;
        JsonObject preference = new JsonObject();
        preference.addProperty("language", language);
        try {
            Files.createDirectories(PREFERENCE.getParent());
            Files.writeString(PREFERENCE, preference.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Could not persist the OriginLore editor language", exception);
        }
    }

    private static Map<String, String> readDictionary(String code) {
        Map<String, String> result = new HashMap<>();
        String resource = "/assets/originlore/lang/" + code + ".json";
        try (InputStream stream = GuiText.class.getResourceAsStream(resource)) {
            if (stream == null) return result;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            json.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load OriginLore editor translations: {}", code, exception);
        }
        return result;
    }

    private static String readPreference() {
        try {
            if (Files.isRegularFile(PREFERENCE)) {
                JsonObject json = JsonParser.parseString(Files.readString(PREFERENCE, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("language") && "en_us".equals(json.get("language").getAsString())) return "en_us";
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read the OriginLore editor language", exception);
        }
        return "zh_cn";
    }
}
