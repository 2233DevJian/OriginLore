package com.originlore.screen;

import com.originlore.source.SourceContext.SourceType;
import net.minecraft.text.Text;

import java.util.Locale;

/** Localized display names for protocol-stable source type identifiers. */
final class SourceTypeDisplay {
    private SourceTypeDisplay() {
    }

    static Text name(String value) {
        SourceType type = SourceType.parse(value);
        return Text.translatable("originlore.source." + type.name().toLowerCase(Locale.ROOT));
    }

    static Text selectionLabel(String value) {
        return Text.translatable("originlore.source.selected", name(value));
    }
}
