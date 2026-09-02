package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.ToolRule;
import com.originlore.config.ItemComponentConfig.ToolRuleEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Transactional editor for the vanilla tool data component. */
public final class ToolRulesEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private final ComponentRule working;
    private final List<ToolRuleEntry> rules = new ArrayList<>();
    private boolean managed;
    private boolean rulesManaged;
    private Float defaultMiningSpeed;
    private Integer damagePerBlock;
    private int selected = -1;
    private int listOffset;
    private int blockIndex;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private ToolRuleEntry draft = defaultEntry();
    private boolean baseDirty;
    private boolean entryDirty;
    private String status = "";

    private TextFieldWidget defaultSpeedField;
    private TextFieldWidget damagePerBlockField;
    private TextFieldWidget blockField;
    private TextFieldWidget speedField;
    private IdSuggestionController blockSuggestions;

    public ToolRulesEditorScreen(Screen parent, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal("工具规则"));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
        ToolRule tool = this.working.tool;
        managed = tool != null;
        if (tool != null) {
            defaultMiningSpeed = tool.defaultMiningSpeed;
            damagePerBlock = tool.damagePerBlock;
            rulesManaged = tool.rules != null;
            if (tool.rules != null) {
                for (ToolRuleEntry entry : tool.rules) if (entry != null) rules.add(entry.copy());
            }
        }
        if (!rules.isEmpty()) {
            selected = 0;
            draft = rules.getFirst().copy();
            normalizeDraft();
        }
    }

    @Override
    protected void init() {
        buildUi();
    }

    private void buildUi() {
        int totalWidth = Math.min(720, Math.max(300, width - 20));
        left = (width - totalWidth) / 2;
        listWidth = totalWidth < 500 ? 126 : 200;
        editorX = left + listWidth + 10;
        editorWidth = totalWidth - listWidth - 10;

        addDrawableChild(ButtonWidget.builder(managedLabel(), button -> toggleManaged())
                .dimensions(left, 30, totalWidth, 20).build());

        int gap = 6;
        int halfTotal = (totalWidth - gap) / 2;
        defaultSpeedField = numberField(left, 54, halfTotal, defaultMiningSpeed, "默认挖掘速度", true);
        damagePerBlockField = numberField(left + halfTotal + gap, 54, halfTotal,
                damagePerBlock, "每方块耐久损耗", true);

        ButtonWidget rulesManagedButton = ButtonWidget.builder(rulesManagedLabel(), button -> toggleRulesManaged())
                .dimensions(left, 78, totalWidth, 20).build();
        rulesManagedButton.active = managed;
        addDrawableChild(rulesManagedButton);

        int visibleRows = visibleRows();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, rules.size() - visibleRows)));
        int y = 108;
        for (int row = 0; row < visibleRows && listOffset + row < rules.size(); row++) {
            int index = listOffset + row;
            ToolRuleEntry entry = rules.get(index);
            String firstBlock = entry.blocks == null || entry.blocks.isEmpty() ? "<空>" : entry.blocks.getFirst();
            String suffix = entry.blocks != null && entry.blocks.size() > 1 ? " +" + (entry.blocks.size() - 1) : "";
            String label = (selected == index ? "> " : "") + firstBlock + suffix;
            label = textRenderer.trimToWidth(label, listWidth - 8);
            ButtonWidget rowButton = ButtonWidget.builder(Text.literal(label), button -> select(index))
                    .dimensions(left, y, listWidth, 20).build();
            rowButton.active = managed && rulesManaged;
            addDrawableChild(rowButton);
            y += 22;
        }
        if (rules.size() > visibleRows) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                listOffset = Math.max(0, listOffset - 1);
                rebuildUi();
            }).dimensions(left, y, listWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                listOffset = Math.min(Math.max(0, rules.size() - visibleRows), listOffset + 1);
                rebuildUi();
            }).dimensions(left + listWidth / 2 + 1, y, listWidth / 2 - 1, 18).build());
        }

        normalizeDraft();
        blockIndex = Math.max(0, Math.min(blockIndex, draft.blocks.size() - 1));
        blockField = new TextFieldWidget(textRenderer, editorX, 108, editorWidth, 20, Text.literal("方块 ID"));
        blockField.setMaxLength(256);
        blockField.setPlaceholder(Text.literal("minecraft:stone"));
        blockField.setText(draft.blocks.get(blockIndex));
        blockField.setChangedListener(value -> entryDirty = true);
        blockField.active = managed && rulesManaged;
        blockSuggestions = new IdSuggestionController(blockField, () -> ClientConfigSession.catalog().blockIds());
        addDrawableChild(blockField);

        buildBlockNavigation();

        int halfEditor = (editorWidth - gap) / 2;
        speedField = numberField(editorX, 156, halfEditor, draft.speed, "该规则挖掘速度", false);
        addDrawableChild(triStateButton(draft.correctForDrops, editorX + halfEditor + gap, 156, halfEditor));

        int actionY = height - 58;
        int actionWidth = Math.max(45, (editorWidth - 8) / 3);
        ButtonWidget save = ButtonWidget.builder(Text.literal("保存条目"), button -> saveEntry(true))
                .dimensions(editorX, actionY, actionWidth, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal("新建"), button -> beginNew())
                .dimensions(editorX + actionWidth + 4, actionY, actionWidth, 20).build();
        ButtonWidget delete = ButtonWidget.builder(Text.literal("删除"), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + 4) * 2, actionY,
                        editorWidth - (actionWidth + 4) * 2, 20).build();
        save.active = managed && rulesManaged;
        add.active = managed && rulesManaged;
        delete.active = managed && rulesManaged && selected >= 0;
        addDrawableChild(save);
        addDrawableChild(add);
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal("应用到规则"), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 102, 20).build());
    }

    private void buildBlockNavigation() {
        int gap = 3;
        int unit = Math.max(22, (editorWidth - gap * 4) / 5);
        ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), button -> changeBlock(-1))
                .dimensions(editorX, 132, unit, 20).build();
        ButtonWidget indicator = ButtonWidget.builder(Text.literal((blockIndex + 1) + "/" + draft.blocks.size()),
                button -> { }).dimensions(editorX + unit + gap, 132, unit, 20).build();
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> changeBlock(1))
                .dimensions(editorX + (unit + gap) * 2, 132, unit, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal("+"), button -> addBlock())
                .dimensions(editorX + (unit + gap) * 3, 132, unit, 20).build();
        ButtonWidget remove = ButtonWidget.builder(Text.literal("-"), button -> removeBlock())
                .dimensions(editorX + (unit + gap) * 4, 132,
                        editorWidth - (unit + gap) * 4, 20).build();
        boolean active = managed && rulesManaged;
        previous.active = active && blockIndex > 0;
        indicator.active = false;
        next.active = active && blockIndex + 1 < draft.blocks.size();
        add.active = active;
        remove.active = active && draft.blocks.size() > 1;
        addDrawableChild(previous);
        addDrawableChild(indicator);
        addDrawableChild(next);
        addDrawableChild(add);
        addDrawableChild(remove);
    }

    private TextFieldWidget numberField(int x, int y, int fieldWidth, Number value, String placeholder,
                                        boolean baseField) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.literal(placeholder));
        field.setMaxLength(32);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value == null ? "" : value.toString());
        field.setChangedListener(ignored -> {
            if (baseField) baseDirty = true;
            else entryDirty = true;
        });
        field.active = managed;
        if (!baseField) field.active = managed && rulesManaged;
        addDrawableChild(field);
        return field;
    }

    private ButtonWidget triStateButton(Boolean initial, int x, int y, int buttonWidth) {
        final Boolean[] value = {initial};
        ButtonWidget button = ButtonWidget.builder(correctLabel(value[0]), widget -> {
            value[0] = value[0] == null ? Boolean.TRUE : value[0] ? Boolean.FALSE : null;
            draft.correctForDrops = value[0];
            entryDirty = true;
            widget.setMessage(correctLabel(value[0]));
        }).dimensions(x, y, buttonWidth, 20).build();
        button.active = managed && rulesManaged;
        return button;
    }

    private void toggleManaged() {
        if (!collectBase()) return;
        if (entryDirty && !saveEntry(false)) return;
        managed = !managed;
        rebuildUi();
    }

    private void toggleRulesManaged() {
        if (!collectBase()) return;
        if (entryDirty && !saveEntry(false)) return;
        rulesManaged = !rulesManaged;
        rebuildUi();
    }

    private void select(int index) {
        if (!collectBase() || (entryDirty && !saveEntry(false))) return;
        selected = index;
        draft = rules.get(index).copy();
        normalizeDraft();
        blockIndex = 0;
        entryDirty = false;
        status = "";
        rebuildUi();
    }

    private void beginNew() {
        if (!collectBase() || (entryDirty && !saveEntry(false))) return;
        selected = -1;
        draft = defaultEntry();
        blockIndex = 0;
        entryDirty = false;
        status = "";
        rebuildUi();
        blockField.setFocused(true);
        setFocused(blockField);
    }

    private void changeBlock(int direction) {
        if (!captureCurrentBlock()) return;
        blockIndex = Math.max(0, Math.min(draft.blocks.size() - 1, blockIndex + direction));
        rebuildUi();
    }

    private void addBlock() {
        if (!captureCurrentBlock()) return;
        draft.blocks.add("minecraft:stone");
        blockIndex = draft.blocks.size() - 1;
        entryDirty = true;
        rebuildUi();
        blockField.setFocused(true);
        blockField.setSelectionStart(0);
        blockField.setSelectionEnd(blockField.getText().length());
        setFocused(blockField);
    }

    private void removeBlock() {
        if (draft.blocks.size() <= 1) return;
        draft.blocks.remove(blockIndex);
        blockIndex = Math.min(blockIndex, draft.blocks.size() - 1);
        entryDirty = true;
        rebuildUi();
    }

    private boolean captureCurrentBlock() {
        try {
            String id = blockField.getText().trim();
            validateBlockId(id);
            draft.blocks.set(blockIndex, id);
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private boolean collectBase() {
        if (!managed && !baseDirty) return true;
        try {
            defaultMiningSpeed = parseNullableFloat(defaultSpeedField, "默认挖掘速度");
            if (defaultMiningSpeed != null && defaultMiningSpeed < 0) {
                throw new IllegalArgumentException("默认挖掘速度不能小于 0");
            }
            damagePerBlock = parseNullableInteger(damagePerBlockField, "每方块耐久损耗");
            if (damagePerBlock != null && damagePerBlock < 0) {
                throw new IllegalArgumentException("每方块耐久损耗不能小于 0");
            }
            baseDirty = false;
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private boolean saveEntry(boolean rebuild) {
        if (!managed || !rulesManaged) return true;
        try {
            if (!captureCurrentBlock()) return false;
            Float speed = parseNullableFloat(speedField, "规则挖掘速度");
            if (speed != null && speed < 0) throw new IllegalArgumentException("规则挖掘速度不能小于 0");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String block : draft.blocks) {
                validateBlockId(block);
                if (!unique.add(block)) throw new IllegalArgumentException("方块列表不能包含重复 ID");
            }
            draft.blocks = new ArrayList<>(unique);
            draft.speed = speed;
            if (selected < 0) {
                rules.add(draft.copy());
                selected = rules.size() - 1;
            } else {
                rules.set(selected, draft.copy());
            }
            entryDirty = false;
            status = "条目已写入事务副本";
            if (rebuild) rebuildUi();
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private void validateBlockId(String id) {
        if (Identifier.tryParse(id) == null) throw new IllegalArgumentException("方块 ID 格式无效");
        List<String> known = ClientConfigSession.catalog().blockIds();
        if (!known.isEmpty() && !known.contains(id)) throw new IllegalArgumentException("服务器没有该方块");
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= rules.size()) return;
        rules.remove(selected);
        selected = rules.isEmpty() ? -1 : Math.min(selected, rules.size() - 1);
        draft = selected < 0 ? defaultEntry() : rules.get(selected).copy();
        normalizeDraft();
        blockIndex = 0;
        entryDirty = false;
        status = "条目已从事务副本删除";
        rebuildUi();
    }

    private void apply() {
        if (!collectBase()) return;
        if (managed && rulesManaged && entryDirty && !saveEntry(false)) return;
        if (!managed) {
            working.tool = null;
        } else {
            ToolRule tool = new ToolRule();
            tool.defaultMiningSpeed = defaultMiningSpeed;
            tool.damagePerBlock = damagePerBlock;
            if (rulesManaged) {
                tool.rules = new ArrayList<>();
                for (ToolRuleEntry entry : rules) tool.rules.add(entry.copy());
            }
            working.tool = tool;
        }
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private void normalizeDraft() {
        if (draft.blocks == null || draft.blocks.isEmpty()) draft.blocks = new ArrayList<>(List.of("minecraft:stone"));
        else draft.blocks = new ArrayList<>(draft.blocks);
    }

    private int visibleRows() {
        return Math.max(1, (height - 196) / 22);
    }

    private void rebuildUi() {
        clearChildren();
        buildUi();
    }

    @Override
    public void tick() {
        super.tick();
        if (blockSuggestions != null) blockSuggestions.update();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (blockSuggestions != null && blockSuggestions.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (blockSuggestions != null && blockSuggestions.mouseClicked(mouseX, mouseY, height)) return true;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (blockSuggestions != null) blockSuggestions.update();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (blockSuggestions != null
                && blockSuggestions.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
        context.drawText(textRenderer, "方块规则", left, 99, 0xA0A0A0, false);
        context.drawText(textRenderer, "方块 ID（使用 +/- 管理同组方块）", editorX, 99, 0xA0A0A0, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 38, status.startsWith("条目已") ? 0x8FE388 : 0xFF7777);
        }
        if (blockSuggestions != null) blockSuggestions.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private Text managedLabel() {
        return Text.literal("接管工具组件: " + (managed ? "是" : "否（保留物品原值）"));
    }

    private Text rulesManagedLabel() {
        return Text.literal("方块规则字段: " + (rulesManaged ? "覆盖" : "继承上层规则"));
    }

    private static Text correctLabel(Boolean value) {
        return Text.literal("正确掉落: " + (value == null ? "继承" : value ? "是" : "否"));
    }

    private static ToolRuleEntry defaultEntry() {
        ToolRuleEntry entry = new ToolRuleEntry();
        entry.blocks = new ArrayList<>(List.of("minecraft:stone"));
        return entry;
    }

    private static Float parseNullableFloat(TextFieldWidget field, String label) {
        String text = field == null ? "" : field.getText().trim();
        if (text.isEmpty()) return null;
        try {
            float value = Float.parseFloat(text);
            if (!Float.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "必须是有限数值");
        }
    }

    private static Integer parseNullableInteger(TextFieldWidget field, String label) {
        String text = field == null ? "" : field.getText().trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "必须是整数");
        }
    }
}
