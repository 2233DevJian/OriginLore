package com.originlore.screen;

import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.ProcessingRule;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.List;
import java.util.function.Consumer;

public final class SourceTuningScreen extends Screen {
    private final Screen parent;
    private final SourceRule working;
    private final int variantIndex;
    private final Consumer<SourceRule> onApply;
    private ProcessingRule processing;
    private boolean processingEnabled;

    public SourceTuningScreen(Screen parent, SourceRule source, int variantIndex, Consumer<SourceRule> onApply) {
        super(GuiText.text("originlore.source.tuning"));
        this.parent = parent;
        working = source.copy();
        this.variantIndex = variantIndex;
        this.onApply = onApply;
        processingEnabled = working.processing != null;
        processing = processingEnabled ? working.processing.copy() : new ProcessingRule();
    }

    @Override
    protected void init() {
        clearChildren();
        int contentWidth = Math.min(440, Math.max(260, width - 24));
        int left = (width - contentWidth) / 2;
        int y = 38;
        if (variantIndex >= 0 && variantIndex < working.variants.size()) {
            Variant variant = working.variants.get(variantIndex);
            addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.quality.title"), button -> {
                if (client == null) return;
                client.setScreen(new NumericSettingsScreen(this, "originlore.quality.title", List.of(
                        NumericSettingsScreen.Setting.scalar("originlore.quality.score", variant.qualityScore, true, 0, 1,
                                value -> variant.qualityScore = value.fixed()),
                        NumericSettingsScreen.Setting.scalar("originlore.quality.spoilage", variant.spoilage, true, 0, 1,
                                value -> variant.spoilage = value.fixed())
                ), () -> { }));
            }).dimensions(left, y, contentWidth, 20).build());
            addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.quality.curve"), button -> {
                if (client != null) client.setScreen(new IngredientWeightsScreen(this, variant.ingredientWeights,
                        points -> variant.ingredientWeights = points));
            }).dimensions(left, y + 26, contentWidth, 20).build());
            y += 62;
        }
        addDrawableChild(ButtonWidget.builder(GuiText.text(processingEnabled
                ? "originlore.processing.enabled" : "originlore.processing.disabled"), button -> {
            processingEnabled = !processingEnabled;
            init();
        }).dimensions(left, y, contentWidth, 20).build());
        ButtonWidget numbers = ButtonWidget.builder(GuiText.text("originlore.processing.risk"), button -> {
            if (client == null) return;
            client.setScreen(new NumericSettingsScreen(this, "originlore.processing.risk", List.of(
                    NumericSettingsScreen.Setting.scalar("originlore.processing.retention", processing.riskRetention,
                            false, 0, 1, value -> processing.riskRetention = value.fixed()),
                    NumericSettingsScreen.Setting.scalar("originlore.processing.floor", processing.riskFloor,
                            false, 0, 1, value -> processing.riskFloor = value.fixed())
            ), () -> { }));
        }).dimensions(left, y + 26, contentWidth, 20).build();
        numbers.active = processingEnabled;
        addDrawableChild(numbers);
        ButtonWidget effects = ButtonWidget.builder(GuiText.text("originlore.processing.effects"), button -> {
            if (client == null) return;
            ComponentRule wrapper = new ComponentRule();
            wrapper.food = new FoodRule();
            wrapper.food.effects = processing.effects;
            client.setScreen(new FoodEffectsEditorScreen(this, wrapper,
                    result -> processing.effects = result.food == null ? null : result.food.effects));
        }).dimensions(left, y + 52, contentWidth, 20).build();
        effects.active = processingEnabled;
        addDrawableChild(effects);
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.apply"), button -> {
            working.processing = processingEnabled ? processing.copy() : null;
            onApply.accept(working.copy());
            close();
        }).dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.action.cancel"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void close() { if (client != null) client.setScreen(parent); }
}
