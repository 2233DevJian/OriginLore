package com.originlore.screen;

import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.NumberRange;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Paginated fixed/range controls. Validation completes before any transaction field is changed. */
public final class NumericSettingsScreen extends Screen {
    public record Value(Double fixed, NumberRange range) {
        public Integer integer() { return fixed == null ? null : fixed.intValue(); }
        public Float decimal() { return fixed == null ? null : fixed.floatValue(); }
        public int[] integers() { return range == null ? null : new int[]{(int) range.min, (int) range.max}; }
        public NumberRange asRange() { return range != null ? range : fixed == null ? null : new NumberRange(fixed, fixed); }
    }

    public record Setting(String label, boolean integer, boolean optional, boolean rangeAllowed,
                          double minimum, double maximum, Value initial, Consumer<Value> setter, Text reference,
                          Number defaultValue) {
        public Setting(String label, boolean integer, boolean optional, boolean rangeAllowed,
                       double minimum, double maximum, Value initial, Consumer<Value> setter) {
            this(label, integer, optional, rangeAllowed, minimum, maximum, initial, setter, null, null);
        }

        public Setting withReference(Text value) {
            return new Setting(label, integer, optional, rangeAllowed, minimum, maximum, initial, setter, value, defaultValue);
        }

        public Setting withDefault(Number value) {
            return new Setting(label, integer, optional, rangeAllowed, minimum, maximum, initial, setter, reference, value);
        }

        public static Setting decimal(String label, Number fixed, NumberRange range, double minimum,
                                      double maximum, Consumer<Value> setter) {
            return new Setting(label, false, true, true, minimum, maximum,
                    new Value(fixed == null ? null : fixed.doubleValue(), range), setter, null, null);
        }

        public static Setting integer(String label, Integer fixed, int[] range, int minimum,
                                      int maximum, Consumer<Value> setter) {
            return new Setting(label, true, true, true, minimum, maximum,
                    new Value(fixed == null ? null : fixed.doubleValue(),
                            range == null ? null : new NumberRange(range[0], range[1])), setter, null, null);
        }

        public static Setting scalar(String label, Double fixed, boolean optional, double minimum,
                                     double maximum, Consumer<Value> setter) {
            return new Setting(label, false, optional, false, minimum, maximum, new Value(fixed, null), setter, null, null);
        }
    }

    private enum Mode { INHERIT, FIXED, RANGE }
    private final Screen parent;
    private final List<Setting> settings;
    private final List<Draft> drafts = new ArrayList<>();
    private final Runnable onApply;
    private int page;
    private int rowsPerPage;
    private int left;
    private int contentWidth;
    private String status = "";

    public NumericSettingsScreen(Screen parent, String title, List<Setting> settings, Runnable onApply) {
        super(GuiText.text(title));
        this.parent = parent;
        this.settings = List.copyOf(settings);
        this.onApply = onApply;
        for (Setting setting : settings) drafts.add(new Draft(setting));
    }

    @Override
    protected void init() {
        capture();
        contentWidth = Math.min(560, Math.max(260, width - 24));
        left = (width - contentWidth) / 2;
        rowsPerPage = Math.max(1, (height - 105) / 52);
        page = Math.min(page, pageCount() - 1);
        buildUi();
    }

    private void buildUi() {
        clearChildren();
        for (int row = 0; row < rowsPerPage && page * rowsPerPage + row < settings.size(); row++) {
            int index = page * rowsPerPage + row;
            Setting setting = settings.get(index);
            Draft draft = drafts.get(index);
            int y = 49 + row * 52;
            int modeWidth = Math.min(64, contentWidth / 7);
            int modeX = left;
            for (Mode mode : Mode.values()) {
                if (mode == Mode.INHERIT && !setting.optional() || mode == Mode.RANGE && !setting.rangeAllowed()) continue;
                ButtonWidget button = ButtonWidget.builder(GuiText.text("originlore.number.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)),
                        ignored -> {
                            capture();
                            draft.mode = mode;
                            status = "";
                            buildUi();
                        }).dimensions(modeX, y, modeWidth, 20).build();
                button.active = draft.mode != mode;
                addDrawableChild(button);
                modeX += modeWidth + 3;
            }
            int valueX = left + modeWidth * 3 + 13;
            int available = left + contentWidth - valueX;
            int fieldWidth = draft.mode == Mode.RANGE ? (available - 6) / 2 : available;
            draft.first = field(valueX, y, fieldWidth, draft.firstText,
                    draft.mode == Mode.RANGE ? "originlore.number.minimum" : "originlore.number.value");
            draft.first.active = draft.mode != Mode.INHERIT;
            draft.second = draft.mode == Mode.RANGE
                    ? field(valueX + fieldWidth + 6, y, fieldWidth, draft.secondText, "originlore.number.maximum") : null;
        }
        int footerY = height - 57;
        ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), ignored -> changePage(-1))
                .dimensions(left, footerY, 28, 20).build();
        previous.active = page > 0;
        addDrawableChild(previous);
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), ignored -> changePage(1))
                .dimensions(left + contentWidth - 28, footerY, 28, 20).build();
        next.active = page + 1 < pageCount();
        addDrawableChild(next);
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.apply"), ignored -> apply())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.cancel"), ignored -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    private TextFieldWidget field(int x, int y, int fieldWidth, String value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, GuiText.text(placeholder));
        field.setMaxLength(32);
        field.setPlaceholder(GuiText.text(placeholder));
        field.setText(value);
        addDrawableChild(field);
        return field;
    }

    private void capture() {
        for (Draft draft : drafts) {
            if (draft.first != null) draft.firstText = draft.first.getText();
            if (draft.second != null) draft.secondText = draft.second.getText();
            draft.first = null;
            draft.second = null;
        }
    }

    private int pageCount() { return Math.max(1, (settings.size() + rowsPerPage - 1) / rowsPerPage); }

    private void changePage(int direction) {
        capture();
        page = Math.max(0, Math.min(pageCount() - 1, page + direction));
        buildUi();
    }

    private void apply() {
        capture();
        List<Value> values = new ArrayList<>();
        for (int index = 0; index < settings.size(); index++) {
            Setting setting = settings.get(index);
            Draft draft = drafts.get(index);
            try {
                if (draft.mode == Mode.INHERIT) values.add(new Value(null, null));
                else {
                    double first = parse(draft.firstText, setting);
                    if (draft.mode == Mode.FIXED) values.add(new Value(first, null));
                    else {
                        double second = parse(draft.secondText, setting);
                        if (first > second) throw new IllegalArgumentException(GuiText.string("originlore.number.order"));
                        values.add(new Value(null, new NumberRange(first, second)));
                    }
                }
            } catch (IllegalArgumentException exception) {
                page = index / rowsPerPage;
                status = GuiText.string(setting.label()) + ": " + exception.getMessage();
                buildUi();
                return;
            }
        }
        for (int index = 0; index < values.size(); index++) settings.get(index).setter().accept(values.get(index));
        onApply.run();
        close();
    }

    private double parse(String raw, Setting setting) {
        double value;
        try { value = Double.parseDouble(raw.trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(GuiText.string("originlore.number.invalid")); }
        if (!Double.isFinite(value) || value < setting.minimum() || value > setting.maximum()
                || setting.integer() && Math.rint(value) != value) {
            throw new IllegalArgumentException(GuiText.string("originlore.number.bounds", setting.minimum(), setting.maximum()));
        }
        return value;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        for (int row = 0; row < rowsPerPage && page * rowsPerPage + row < settings.size(); row++) {
            Setting setting = settings.get(page * rowsPerPage + row);
            String label = GuiText.string(setting.label());
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(label, contentWidth), left, 36 + row * 52, 0xA0A0A0);
            if (setting.reference() != null) {
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(setting.reference().getString(), contentWidth),
                        left, 73 + row * 52, 0x8FBFB5);
            }
            if (mouseX >= left && mouseX <= left + contentWidth && mouseY >= 35 + row * 52
                    && mouseY <= 46 + row * 52 && textRenderer.getWidth(label) > contentWidth) {
                context.drawTooltip(textRenderer, Text.literal(label), mouseX, mouseY);
            }
        }
        context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + pageCount(), width / 2, height - 51, 0xA0A0A0);
        if (!status.isBlank()) context.drawCenteredTextWithShadow(textRenderer,
                textRenderer.trimToWidth(status, width - 24), width / 2, height - 72, 0xFF7777);
    }

    @Override
    public void close() { if (client != null) client.setScreen(parent); }

    private static final class Draft {
        private Mode mode;
        private String firstText;
        private String secondText;
        private TextFieldWidget first;
        private TextFieldWidget second;

        private Draft(Setting setting) {
            Value initial = setting.initial();
            mode = initial.range() != null && initial.range().min != initial.range().max ? Mode.RANGE
                    : initial.range() != null || initial.fixed() != null || !setting.optional() ? Mode.FIXED : Mode.INHERIT;
            Double fallback = setting.defaultValue() == null ? null : setting.defaultValue().doubleValue();
            firstText = format(initial.range() == null ? (initial.fixed() == null ? fallback : initial.fixed()) : Double.valueOf(initial.range().min), setting.integer());
            secondText = format(initial.range() == null ? (initial.fixed() == null ? fallback : initial.fixed()) : Double.valueOf(initial.range().max), setting.integer());
        }

        private static String format(Double value, boolean integer) {
            if (value == null) return "";
            return integer ? Long.toString(value.longValue()) : Double.toString(value);
        }
    }
}
