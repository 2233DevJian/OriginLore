package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.EffectRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Transactional list editor for food status effects. */
public final class FoodEffectsEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private final ComponentRule working;
    private final List<EffectRule> effects = new ArrayList<>();
    private boolean managed;
    private int selected = -1;
    private int listOffset;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private EffectRule draft = defaultEffect();
    private boolean dirty;
    private String status = "";

    private TextFieldWidget idField;
    private TextFieldWidget durationField;
    private TextFieldWidget amplifierField;
    private TextFieldWidget probabilityField;
    private IdSuggestionController suggestions;

    public FoodEffectsEditorScreen(Screen parent, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal("食物状态效果"));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
        FoodRule food = this.working.food;
        managed = food != null && food.effects != null;
        if (managed) {
            for (EffectRule effect : food.effects) if (effect != null) effects.add(effect.copy());
        }
        if (!effects.isEmpty()) {
            selected = 0;
            draft = effects.getFirst().copy();
        }
    }

    @Override
    protected void init() {
        buildUi();
    }

    private void buildUi() {
        int totalWidth = Math.min(720, Math.max(300, width - 20));
        left = (width - totalWidth) / 2;
        listWidth = totalWidth < 500 ? 126 : 190;
        editorX = left + listWidth + 10;
        editorWidth = totalWidth - listWidth - 10;

        ButtonWidget managedButton = ButtonWidget.builder(managedLabel(), button -> {
            if (dirty && !saveDraft(false)) return;
            managed = !managed;
            rebuildUi();
        }).dimensions(left, 34, totalWidth, 20).build();
        addDrawableChild(managedButton);

        int visibleRows = visibleRows();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, effects.size() - visibleRows)));
        int y = 64;
        for (int row = 0; row < visibleRows && listOffset + row < effects.size(); row++) {
            int index = listOffset + row;
            EffectRule effect = effects.get(index);
            String label = (selected == index ? "> " : "") + effect.id;
            label = textRenderer.trimToWidth(label, listWidth - 8);
            ButtonWidget entry = ButtonWidget.builder(Text.literal(label), button -> select(index))
                    .dimensions(left, y, listWidth, 20).build();
            entry.active = managed;
            addDrawableChild(entry);
            y += 22;
        }
        if (effects.size() > visibleRows) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                listOffset = Math.max(0, listOffset - 1);
                rebuildUi();
            }).dimensions(left, y, listWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                listOffset = Math.min(Math.max(0, effects.size() - visibleRows), listOffset + 1);
                rebuildUi();
            }).dimensions(left + listWidth / 2 + 1, y, listWidth / 2 - 1, 18).build());
        }

        buildFields();

        int actionY = height - 58;
        int gap = 4;
        int actionWidth = Math.max(45, (editorWidth - gap * 2) / 3);
        ButtonWidget saveEntry = ButtonWidget.builder(Text.literal("保存条目"), button -> saveDraft(true))
                .dimensions(editorX, actionY, actionWidth, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal("新建"), button -> beginNew())
                .dimensions(editorX + actionWidth + gap, actionY, actionWidth, 20).build();
        ButtonWidget delete = ButtonWidget.builder(Text.literal("删除"), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + gap) * 2, actionY,
                        editorWidth - (actionWidth + gap) * 2, 20).build();
        saveEntry.active = managed;
        add.active = managed;
        delete.active = managed && selected >= 0;
        addDrawableChild(saveEntry);
        addDrawableChild(add);
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal("应用到规则"), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 102, 20).build());
    }

    private void buildFields() {
        idField = new TextFieldWidget(textRenderer, editorX, 64, editorWidth, 20, Text.literal("状态效果 ID"));
        idField.setMaxLength(256);
        idField.setPlaceholder(Text.literal("minecraft:nausea"));
        idField.setText(draft.id == null ? "" : draft.id);
        idField.setChangedListener(value -> dirty = true);
        idField.active = managed;
        suggestions = new IdSuggestionController(idField, () -> ClientConfigSession.catalog().statusEffectIds());
        addDrawableChild(idField);

        int gap = 6;
        int half = (editorWidth - gap) / 2;
        durationField = numberField(editorX, 94, half, Integer.toString(draft.duration), "持续时间（tick）");
        amplifierField = numberField(editorX + half + gap, 94, half,
                Integer.toString(draft.amplifier), "等级（从 0 开始）");
        probabilityField = numberField(editorX, 124, half, Float.toString(draft.probability), "概率 0..1");

        int toggleWidth = Math.max(48, (editorWidth - 8) / 3);
        addDrawableChild(booleanButton("环境", draft.ambient, value -> draft.ambient = value,
                editorX, 154, toggleWidth));
        addDrawableChild(booleanButton("粒子", draft.showParticles, value -> draft.showParticles = value,
                editorX + toggleWidth + 4, 154, toggleWidth));
        addDrawableChild(booleanButton("图标", draft.showIcon, value -> draft.showIcon = value,
                editorX + (toggleWidth + 4) * 2, 154, editorWidth - (toggleWidth + 4) * 2));
    }

    private TextFieldWidget numberField(int x, int y, int width, String value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 20, Text.literal(placeholder));
        field.setMaxLength(32);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value);
        field.setChangedListener(ignored -> dirty = true);
        field.active = managed;
        addDrawableChild(field);
        return field;
    }

    private ButtonWidget booleanButton(String label, boolean initial, Consumer<Boolean> setter,
                                       int x, int y, int buttonWidth) {
        final boolean[] value = {initial};
        ButtonWidget button = ButtonWidget.builder(booleanLabel(label, initial), widget -> {
            value[0] = !value[0];
            setter.accept(value[0]);
            dirty = true;
            widget.setMessage(booleanLabel(label, value[0]));
        }).dimensions(x, y, buttonWidth, 20).build();
        button.active = managed;
        return button;
    }

    private void select(int index) {
        if (dirty && !saveDraft(false)) return;
        selected = index;
        draft = effects.get(index).copy();
        dirty = false;
        status = "";
        rebuildUi();
    }

    private void beginNew() {
        if (dirty && !saveDraft(false)) return;
        selected = -1;
        draft = defaultEffect();
        dirty = false;
        status = "";
        rebuildUi();
        idField.setFocused(true);
        setFocused(idField);
    }

    private boolean saveDraft(boolean rebuild) {
        if (!managed) return true;
        try {
            String id = idField.getText().trim();
            if (Identifier.tryParse(id) == null) throw new IllegalArgumentException("状态效果 ID 格式无效");
            List<String> known = ClientConfigSession.catalog().statusEffectIds();
            if (!known.isEmpty() && !known.contains(id)) throw new IllegalArgumentException("服务器没有该状态效果");
            draft.id = id;
            draft.duration = parseInteger(durationField, "持续时间");
            if (draft.duration < -1) throw new IllegalArgumentException("持续时间不能小于 -1");
            draft.amplifier = parseInteger(amplifierField, "等级");
            if (draft.amplifier < 0) throw new IllegalArgumentException("等级不能小于 0");
            draft.probability = parseFloat(probabilityField, "概率");
            if (draft.probability < 0 || draft.probability > 1) {
                throw new IllegalArgumentException("概率必须在 0 到 1 之间");
            }
            if (selected < 0) {
                effects.add(draft.copy());
                selected = effects.size() - 1;
            } else {
                effects.set(selected, draft.copy());
            }
            dirty = false;
            status = "条目已写入事务副本";
            if (rebuild) rebuildUi();
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= effects.size()) return;
        effects.remove(selected);
        selected = effects.isEmpty() ? -1 : Math.min(selected, effects.size() - 1);
        draft = selected < 0 ? defaultEffect() : effects.get(selected).copy();
        dirty = false;
        status = "条目已从事务副本删除";
        rebuildUi();
    }

    private void apply() {
        if (managed && dirty && !saveDraft(false)) return;
        if (managed) {
            if (working.food == null) working.food = new FoodRule();
            working.food.effects = new ArrayList<>();
            for (EffectRule effect : effects) working.food.effects.add(effect.copy());
        } else if (working.food != null) {
            working.food.effects = null;
            clearFoodIfEmpty();
        }
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private void clearFoodIfEmpty() {
        FoodRule food = working.food;
        if (food != null && food.nutrition == null && food.saturation == null && food.canAlwaysEat == null
                && food.eatSeconds == null && food.effects == null) working.food = null;
    }

    private void rebuildUi() {
        clearChildren();
        buildUi();
    }

    private int visibleRows() {
        return Math.max(3, (height - 135) / 22);
    }

    @Override
    public void tick() {
        super.tick();
        if (suggestions != null) suggestions.update();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (suggestions != null && suggestions.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (suggestions != null && suggestions.mouseClicked(mouseX, mouseY, height)) return true;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (suggestions != null) suggestions.update();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (suggestions != null && suggestions.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawText(textRenderer, "效果列表", left, 55, 0xA0A0A0, false);
        context.drawText(textRenderer, "状态效果 ID", editorX, 55, 0xA0A0A0, false);
        context.drawText(textRenderer, "持续时间 / 等级", editorX, 85, 0xA0A0A0, false);
        context.drawText(textRenderer, "触发概率", editorX, 115, 0xA0A0A0, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 38, status.startsWith("条目已") ? 0x8FE388 : 0xFF7777);
        }
        if (suggestions != null) suggestions.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private Text managedLabel() {
        return Text.literal("接管状态效果: " + (managed ? "是" : "否（保留物品原值）"));
    }

    private static Text booleanLabel(String label, boolean value) {
        return Text.literal(label + ": " + (value ? "是" : "否"));
    }

    private static EffectRule defaultEffect() {
        return new EffectRule("minecraft:nausea", 100, 0, 1.0f);
    }

    private static int parseInteger(TextFieldWidget field, String label) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "必须是整数");
        }
    }

    private static float parseFloat(TextFieldWidget field, String label) {
        try {
            float value = Float.parseFloat(field.getText().trim());
            if (!Float.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "必须是有限数值");
        }
    }
}
