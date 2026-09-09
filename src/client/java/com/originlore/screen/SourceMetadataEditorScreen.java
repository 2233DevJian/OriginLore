package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Transactional source matcher and variant identity editor. */
public final class SourceMetadataEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<SourceRule> onApply;
    private final SourceRule working;
    private final int variantIndex;
    private int left;
    private int contentWidth;
    private String status = "";

    private TextFieldWidget lootTableField;
    private TextFieldWidget recipeField;
    private TextFieldWidget variantIdField;
    private TextFieldWidget variantWeightField;
    private IdSuggestionController lootSuggestions;
    private IdSuggestionController recipeSuggestions;
    private ChoiceDropdownController sourceDropdown;

    public SourceMetadataEditorScreen(Screen parent, SourceRule source, int variantIndex,
                                      Consumer<SourceRule> onApply) {
        super(Text.literal(GuiText.string("originlore.editor.source_metadata")));
        this.parent = parent;
        this.onApply = onApply;
        this.working = source == null ? new SourceRule() : source.copy();
        this.variantIndex = variantIndex;
        this.working.type = SourceType.parse(this.working.type).name();
    }

    @Override
    protected void init() {
        contentWidth = Math.min(520, Math.max(280, width - 24));
        left = (width - contentWidth) / 2;

        ButtonWidget typeButton = ButtonWidget.builder(SourceTypeDisplay.selectionLabel(working.type),
                button -> sourceDropdown.toggle()).dimensions(left, 34, contentWidth - 104, 20).build();
        addDrawableChild(typeButton);
        sourceDropdown = new ChoiceDropdownController(typeButton,
                Arrays.stream(SourceType.values()).map(Enum::name).toList(),
                () -> working.type,
                value -> working.type = value,
                SourceTypeDisplay::selectionLabel);
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.source.tuning_short"), button -> {
            if (!collectMetadata() || client == null) return;
            client.setScreen(new SourceTuningScreen(this, working, variantIndex, replacement -> {
                working.processing = replacement.processing;
                working.variants = replacement.variants;
            }));
        }).dimensions(left + contentWidth - 100, 34, 100, 20).build());

        lootTableField = textField(left, 70, contentWidth, working.lootTableId, GuiText.string("originlore.editor.loot_table_optional"));
        lootSuggestions = new IdSuggestionController(lootTableField,
                () -> ClientConfigSession.catalog().lootTableIds());
        recipeField = textField(left, 106, contentWidth, working.recipeId, GuiText.string("originlore.editor.recipe_optional"));
        recipeSuggestions = new IdSuggestionController(recipeField,
                () -> ClientConfigSession.catalog().recipeIds());

        if (hasVariant()) {
            Variant variant = working.variants.get(variantIndex);
            int gap = 6;
            int half = (contentWidth - gap) / 2;
            variantIdField = textField(left, 142, half, variant.id, GuiText.string("originlore.editor.variant_id"));
            variantWeightField = textField(left + half + gap, 142, half,
                    Double.toString(variant.weight), GuiText.string("originlore.editor.weight_percent"));
        }

        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.apply")), button -> apply())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.cancel")), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    private TextFieldWidget textField(int x, int y, int fieldWidth, String value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.literal(placeholder));
        field.setMaxLength(256);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value == null ? "" : value);
        field.setChangedListener(ignored -> status = "");
        addDrawableChild(field);
        return field;
    }

    private void apply() {
        if (!collectMetadata()) return;
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private boolean collectMetadata() {
        try {
            working.lootTableId = optionalIdentifier(lootTableField.getText(), GuiText.string("originlore.editor.loot_table_id"));
            working.recipeId = optionalIdentifier(recipeField.getText(), GuiText.string("originlore.editor.recipe_id"));
            if (hasVariant()) {
                Variant selected = working.variants.get(variantIndex);
                String id = variantIdField.getText().trim();
                if (id.isEmpty()) throw new IllegalArgumentException(GuiText.string("originlore.editor.variant_id_required"));
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (int index = 0; index < working.variants.size(); index++) {
                    String candidate = index == variantIndex ? id : working.variants.get(index).id;
                    if (candidate == null || candidate.isBlank() || !ids.add(candidate)) {
                        throw new IllegalArgumentException(GuiText.string("originlore.editor.variant_id_unique"));
                    }
                }
                double weight;
                try {
                    weight = parseWeight(variantWeightField.getText());
                } catch (IllegalArgumentException exception) {
                    throw exception;
                }
                if (weight < 0) throw new IllegalArgumentException(GuiText.string("originlore.editor.weight_negative"));
                selected.id = id;
                selected.weight = weight;
                boolean hasPositiveWeight = working.variants.stream().anyMatch(variant -> variant.weight > 0);
                if (!hasPositiveWeight) throw new IllegalArgumentException(GuiText.string("originlore.editor.weight_positive"));
            }
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private boolean hasVariant() {
        return variantIndex >= 0 && variantIndex < working.variants.size();
    }

    private static String optionalIdentifier(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (Identifier.tryParse(value) == null) throw new IllegalArgumentException(label + GuiText.string("originlore.editor.invalid_format"));
        return value;
    }

    private static double parseWeight(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(GuiText.string("originlore.editor.weight_required"));
        try {
            double weight = Double.parseDouble(value);
            if (!Double.isFinite(weight)) throw new NumberFormatException();
            return weight;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(GuiText.string("originlore.editor.weight_invalid"));
        }
    }

    private static String formatWeight(double value) {
        if (!Double.isFinite(value)) return "?";
        if (Math.rint(value) == value) return Long.toString((long) value);
        return String.format(java.util.Locale.ROOT, "%.2f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    public void tick() {
        super.tick();
        lootSuggestions.update();
        recipeSuggestions.update();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (sourceDropdown.keyPressed(keyCode)) return true;
        if (lootSuggestions.keyPressed(keyCode) || recipeSuggestions.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sourceDropdown.mouseClicked(mouseX, mouseY, height)) return true;
        if (lootSuggestions.mouseClicked(mouseX, mouseY, height)
                || recipeSuggestions.mouseClicked(mouseX, mouseY, height)) return true;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        lootSuggestions.update();
        recipeSuggestions.update();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (sourceDropdown.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        if (lootSuggestions.mouseScrolled(mouseX, mouseY, verticalAmount, height)
                || recipeSuggestions.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        boolean overDropdown = sourceDropdown != null && sourceDropdown.isMouseOverPopup(mouseX, mouseY, height);
        super.render(context, overDropdown ? -1 : mouseX, overDropdown ? -1 : mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawText(textRenderer, GuiText.string("originlore.editor.source_type"), left, 25, 0xA0A0A0, false);
        context.drawText(textRenderer, GuiText.string("originlore.editor.specific_loot_table"), left, 61, 0xA0A0A0, false);
        context.drawText(textRenderer, GuiText.string("originlore.editor.specific_recipe"), left, 97, 0xA0A0A0, false);
        if (hasVariant()) context.drawText(textRenderer, GuiText.string("originlore.editor.variant_id_weight"), left, 133, 0xA0A0A0, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 43, 0xFF7777);
        }
        lootSuggestions.render(context, textRenderer, height);
        recipeSuggestions.render(context, textRenderer, height);
        sourceDropdown.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
