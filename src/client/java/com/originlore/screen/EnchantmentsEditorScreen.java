package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Transactional editor for direct and stored enchantment components. */
public final class EnchantmentsEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private final ComponentRule working;
    private final LinkedHashMap<String, Integer> ordinary = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> stored = new LinkedHashMap<>();
    private boolean ordinaryManaged;
    private boolean storedManaged;
    private boolean storedMode;
    private String selectedId;
    private int listOffset;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private boolean dirty;
    private String status = "";

    private TextFieldWidget idField;
    private TextFieldWidget levelField;
    private IdSuggestionController suggestions;

    public EnchantmentsEditorScreen(Screen parent, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal("附魔编辑器"));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
        ordinaryManaged = this.working.enchantments != null;
        storedManaged = this.working.storedEnchantments != null;
        if (ordinaryManaged) ordinary.putAll(this.working.enchantments);
        if (storedManaged) stored.putAll(this.working.storedEnchantments);
    }

    @Override
    protected void init() {
        buildUi();
    }

    private void buildUi() {
        int totalWidth = Math.min(700, Math.max(300, width - 20));
        left = (width - totalWidth) / 2;
        listWidth = totalWidth < 500 ? 126 : 190;
        editorX = left + listWidth + 10;
        editorWidth = totalWidth - listWidth - 10;

        int halfTab = (totalWidth - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal(storedMode ? "普通附魔" : "[普通附魔]"),
                button -> switchMode(false)).dimensions(left, 34, halfTab, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(storedMode ? "[存储附魔]" : "存储附魔"),
                button -> switchMode(true)).dimensions(left + halfTab + 4, 34,
                totalWidth - halfTab - 4, 20).build());
        addDrawableChild(ButtonWidget.builder(managedLabel(), button -> toggleManaged())
                .dimensions(left, 58, totalWidth, 20).build());

        List<String> ids = entryIds();
        int visibleRows = visibleRows();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, ids.size() - visibleRows)));
        int y = 90;
        for (int row = 0; row < visibleRows && listOffset + row < ids.size(); row++) {
            String id = ids.get(listOffset + row);
            String label = (id.equals(selectedId) ? "> " : "") + id + " " + activeMap().get(id);
            label = textRenderer.trimToWidth(label, listWidth - 8);
            ButtonWidget entry = ButtonWidget.builder(Text.literal(label), button -> select(id))
                    .dimensions(left, y, listWidth, 20).build();
            entry.active = isManaged();
            addDrawableChild(entry);
            y += 22;
        }
        if (ids.size() > visibleRows) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                listOffset = Math.max(0, listOffset - 1);
                rebuildUi();
            }).dimensions(left, y, listWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                listOffset = Math.min(Math.max(0, ids.size() - visibleRows), listOffset + 1);
                rebuildUi();
            }).dimensions(left + listWidth / 2 + 1, y, listWidth / 2 - 1, 18).build());
        }

        idField = new TextFieldWidget(textRenderer, editorX, 90, editorWidth, 20, Text.literal("附魔 ID"));
        idField.setMaxLength(256);
        idField.setPlaceholder(Text.literal("minecraft:sharpness"));
        idField.setText(selectedId == null ? "" : selectedId);
        idField.setChangedListener(value -> dirty = true);
        idField.active = isManaged();
        suggestions = new IdSuggestionController(idField, () -> ClientConfigSession.catalog().enchantmentIds());
        addDrawableChild(idField);

        levelField = new TextFieldWidget(textRenderer, editorX, 125, editorWidth, 20, Text.literal("附魔等级"));
        levelField.setMaxLength(16);
        levelField.setPlaceholder(Text.literal("附魔等级"));
        Integer currentLevel = selectedId == null ? null : activeMap().get(selectedId);
        levelField.setText(currentLevel == null ? "1" : currentLevel.toString());
        levelField.setChangedListener(value -> dirty = true);
        levelField.active = isManaged();
        addDrawableChild(levelField);

        int actionY = height - 58;
        int gap = 4;
        int actionWidth = Math.max(45, (editorWidth - gap * 2) / 3);
        ButtonWidget save = ButtonWidget.builder(Text.literal("保存条目"), button -> saveEntry(true))
                .dimensions(editorX, actionY, actionWidth, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal("新建"), button -> beginNew())
                .dimensions(editorX + actionWidth + gap, actionY, actionWidth, 20).build();
        ButtonWidget delete = ButtonWidget.builder(Text.literal("删除"), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + gap) * 2, actionY,
                        editorWidth - (actionWidth + gap) * 2, 20).build();
        save.active = isManaged();
        add.active = isManaged();
        delete.active = isManaged() && selectedId != null;
        addDrawableChild(save);
        addDrawableChild(add);
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal("应用到规则"), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 102, 20).build());
    }

    private void switchMode(boolean targetStored) {
        if (storedMode == targetStored) return;
        if (dirty && !saveEntry(false)) return;
        storedMode = targetStored;
        selectedId = null;
        listOffset = 0;
        dirty = false;
        status = "";
        rebuildUi();
    }

    private void toggleManaged() {
        if (dirty && !saveEntry(false)) return;
        if (storedMode) storedManaged = !storedManaged;
        else ordinaryManaged = !ordinaryManaged;
        rebuildUi();
    }

    private void select(String id) {
        if (dirty && !saveEntry(false)) return;
        selectedId = id;
        dirty = false;
        status = "";
        rebuildUi();
    }

    private void beginNew() {
        if (dirty && !saveEntry(false)) return;
        selectedId = null;
        dirty = false;
        status = "";
        rebuildUi();
        idField.setFocused(true);
        setFocused(idField);
    }

    private boolean saveEntry(boolean rebuild) {
        if (!isManaged()) return true;
        try {
            String id = idField.getText().trim();
            if (Identifier.tryParse(id) == null) throw new IllegalArgumentException("附魔 ID 格式无效");
            List<String> known = ClientConfigSession.catalog().enchantmentIds();
            if (!known.isEmpty() && !known.contains(id)) throw new IllegalArgumentException("服务器没有该附魔");
            int level;
            try {
                level = Integer.parseInt(levelField.getText().trim());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("附魔等级必须是整数");
            }
            if (level < 0) throw new IllegalArgumentException("附魔等级不能小于 0");
            if (activeMap().containsKey(id) && !id.equals(selectedId)) {
                throw new IllegalArgumentException("该附魔已经存在");
            }
            if (selectedId != null && !selectedId.equals(id)) activeMap().remove(selectedId);
            activeMap().put(id, level);
            selectedId = id;
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
        if (selectedId == null) return;
        activeMap().remove(selectedId);
        selectedId = null;
        dirty = false;
        status = "条目已从事务副本删除";
        rebuildUi();
    }

    private void apply() {
        if (dirty && !saveEntry(false)) return;
        working.enchantments = ordinaryManaged ? new LinkedHashMap<>(ordinary) : null;
        working.storedEnchantments = storedManaged ? new LinkedHashMap<>(stored) : null;
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private Map<String, Integer> activeMap() {
        return storedMode ? stored : ordinary;
    }

    private boolean isManaged() {
        return storedMode ? storedManaged : ordinaryManaged;
    }

    private List<String> entryIds() {
        List<String> ids = new ArrayList<>(activeMap().keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    private Text managedLabel() {
        return Text.literal("接管" + (storedMode ? "存储" : "普通") + "附魔: "
                + (isManaged() ? "是" : "否（保留物品原值）"));
    }

    private int visibleRows() {
        return Math.max(3, (height - 160) / 22);
    }

    private void rebuildUi() {
        clearChildren();
        buildUi();
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
        context.drawText(textRenderer, "附魔列表", left, 81, 0xA0A0A0, false);
        context.drawText(textRenderer, "附魔 ID", editorX, 81, 0xA0A0A0, false);
        context.drawText(textRenderer, "等级", editorX, 116, 0xA0A0A0, false);
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
}
