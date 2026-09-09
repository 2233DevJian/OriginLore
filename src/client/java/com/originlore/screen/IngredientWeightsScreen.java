package com.originlore.screen;

import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.WeightPoint;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Piecewise-linear weight curve, with one editable quality/multiplier pair per point. */
public final class IngredientWeightsScreen extends Screen {
    private final Screen parent;
    private final Consumer<List<WeightPoint>> onApply;
    private final List<PointDraft> points = new ArrayList<>();
    private int page;
    private int rows;
    private int left;
    private int contentWidth;
    private String status = "";

    public IngredientWeightsScreen(Screen parent, List<WeightPoint> values, Consumer<List<WeightPoint>> onApply) {
        super(GuiText.text("originlore.quality.curve"));
        this.parent = parent;
        this.onApply = onApply;
        if (values != null) for (WeightPoint point : values) if (point != null) {
            points.add(new PointDraft(Double.toString(point.quality), Double.toString(point.multiplier)));
        }
    }

    @Override
    protected void init() {
        capture();
        contentWidth = Math.min(500, Math.max(260, width - 24));
        left = (width - contentWidth) / 2;
        rows = Math.max(1, (height - 115) / 28);
        page = Math.min(page, pageCount() - 1);
        buildUi();
    }

    private void buildUi() {
        clearChildren();
        int column = (contentWidth - 38) / 2;
        for (int row = 0; row < rows && page * rows + row < points.size(); row++) {
            int index = page * rows + row;
            PointDraft point = points.get(index);
            int y = 49 + row * 28;
            point.qualityField = field(left, y, column, point.quality, "originlore.quality.score");
            point.multiplierField = field(left + column + 6, y, column, point.multiplier, "originlore.quality.multiplier");
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> {
                capture();
                points.remove(index);
                page = Math.min(page, pageCount() - 1);
                buildUi();
            }).dimensions(left + contentWidth - 26, y, 26, 20).build());
        }
        int footer = height - 57;
        ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), button -> changePage(-1))
                .dimensions(left, footer, 28, 20).build();
        previous.active = page > 0;
        addDrawableChild(previous);
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> {
            capture();
            points.add(new PointDraft("", "1"));
            page = pageCount() - 1;
            buildUi();
        }).dimensions(left + 34, footer, 28, 20).build());
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> changePage(1))
                .dimensions(left + contentWidth - 28, footer, 28, 20).build();
        next.active = page + 1 < pageCount();
        addDrawableChild(next);
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.apply"), button -> apply())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.cancel"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    private TextFieldWidget field(int x, int y, int fieldWidth, String value, String label) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, GuiText.text(label));
        field.setMaxLength(32);
        field.setText(value);
        addDrawableChild(field);
        return field;
    }

    private void capture() {
        for (PointDraft point : points) {
            if (point.qualityField != null) point.quality = point.qualityField.getText();
            if (point.multiplierField != null) point.multiplier = point.multiplierField.getText();
            point.qualityField = null;
            point.multiplierField = null;
        }
    }

    private int pageCount() { return Math.max(1, (points.size() + rows - 1) / rows); }

    private void changePage(int direction) {
        capture();
        page = Math.max(0, Math.min(pageCount() - 1, page + direction));
        buildUi();
    }

    private void apply() {
        capture();
        List<WeightPoint> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            PointDraft draft = points.get(index);
            try {
                WeightPoint point = new WeightPoint();
                point.quality = Double.parseDouble(draft.quality.trim());
                point.multiplier = Double.parseDouble(draft.multiplier.trim());
                if (!Double.isFinite(point.quality) || point.quality < 0 || point.quality > 1
                        || !Double.isFinite(point.multiplier) || point.multiplier < 0) throw new NumberFormatException();
                if (result.stream().anyMatch(existing -> existing.quality == point.quality)) {
                    throw new IllegalArgumentException(GuiText.string("originlore.quality.duplicate"));
                }
                result.add(point);
            } catch (IllegalArgumentException exception) {
                page = index / rows;
                status = exception instanceof NumberFormatException ? GuiText.string("originlore.quality.invalid") : exception.getMessage();
                buildUi();
                return;
            }
        }
        result.sort(Comparator.comparingDouble(point -> point.quality));
        onApply.accept(result.isEmpty() ? null : result);
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, GuiText.text("originlore.quality.score"), left, 35, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, GuiText.text("originlore.quality.multiplier"),
                left + (contentWidth - 38) / 2 + 6, 35, 0xA0A0A0);
        if (points.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,
                GuiText.text("originlore.number.mode.inherit"), width / 2, 72, 0xA0A0A0);
        context.drawCenteredTextWithShadow(textRenderer, (page + 1) + " / " + pageCount(), width / 2, height - 51, 0xA0A0A0);
        if (!status.isBlank()) context.drawCenteredTextWithShadow(textRenderer,
                textRenderer.trimToWidth(status, width - 24), width / 2, height - 73, 0xFF7777);
    }

    @Override
    public void close() { if (client != null) client.setScreen(parent); }

    private static final class PointDraft {
        private String quality;
        private String multiplier;
        private TextFieldWidget qualityField;
        private TextFieldWidget multiplierField;
        private PointDraft(String quality, String multiplier) { this.quality = quality; this.multiplier = multiplier; }
    }
}
