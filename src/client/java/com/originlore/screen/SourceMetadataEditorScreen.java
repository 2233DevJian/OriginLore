package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
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
        super(Text.literal("来源匹配设置"));
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
                button -> sourceDropdown.toggle()).dimensions(left, 34, contentWidth, 20).build();
        addDrawableChild(typeButton);
        sourceDropdown = new ChoiceDropdownController(typeButton,
                Arrays.stream(SourceType.values()).map(Enum::name).toList(),
                () -> working.type,
                value -> working.type = value,
                SourceTypeDisplay::selectionLabel);

        lootTableField = textField(left, 70, contentWidth, working.lootTableId, "战利品表 ID（可选）");
        lootSuggestions = new IdSuggestionController(lootTableField,
                () -> ClientConfigSession.catalog().lootTableIds());
        recipeField = textField(left, 106, contentWidth, working.recipeId, "配方 ID（可选）");
        recipeSuggestions = new IdSuggestionController(recipeField,
                () -> ClientConfigSession.catalog().recipeIds());

        if (hasVariant()) {
            Variant variant = working.variants.get(variantIndex);
            int gap = 6;
            int half = (contentWidth - gap) / 2;
            variantIdField = textField(left, 142, half, variant.id, "变体 ID");
            variantWeightField = textField(left + half + gap, 142, half,
                    Double.toString(variant.weight), "变体权重（可填 80%）");
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("应用"), button -> apply())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
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
        try {
            working.lootTableId = optionalIdentifier(lootTableField.getText(), "战利品表 ID");
            working.recipeId = optionalIdentifier(recipeField.getText(), "配方 ID");
            if (hasVariant()) {
                Variant selected = working.variants.get(variantIndex);
                String id = variantIdField.getText().trim();
                if (id.isEmpty()) throw new IllegalArgumentException("变体 ID 不能为空");
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (int index = 0; index < working.variants.size(); index++) {
                    String candidate = index == variantIndex ? id : working.variants.get(index).id;
                    if (candidate == null || candidate.isBlank() || !ids.add(candidate)) {
                        throw new IllegalArgumentException("同一来源的变体 ID 必须唯一");
                    }
                }
                double weight;
                try {
                    weight = parseWeight(variantWeightField.getText());
                } catch (IllegalArgumentException exception) {
                    throw exception;
                }
                if (weight < 0) throw new IllegalArgumentException("变体权重不能小于 0");
                selected.id = id;
                selected.weight = weight;
                boolean hasPositiveWeight = working.variants.stream().anyMatch(variant -> variant.weight > 0);
                if (!hasPositiveWeight) throw new IllegalArgumentException("至少一个变体的权重必须大于 0");
            }
            onApply.accept(working.copy());
            if (client != null) client.setScreen(parent);
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
        }
    }

    private boolean hasVariant() {
        return variantIndex >= 0 && variantIndex < working.variants.size();
    }

    private static String optionalIdentifier(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (Identifier.tryParse(value) == null) throw new IllegalArgumentException(label + "格式无效");
        return value;
    }

    private static double parseWeight(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("变体权重不能为空");
        try {
            double weight = Double.parseDouble(value);
            if (!Double.isFinite(weight)) throw new NumberFormatException();
            return weight;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("变体权重必须是数字，可输入 80 或 80%");
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
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawText(textRenderer, "来源类型", left, 25, 0xA0A0A0, false);
        context.drawText(textRenderer, "具体战利品表", left, 61, 0xA0A0A0, false);
        context.drawText(textRenderer, "具体配方", left, 97, 0xA0A0A0, false);
        if (hasVariant()) context.drawText(textRenderer, "变体 ID / 权重", left, 133, 0xA0A0A0, false);
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
