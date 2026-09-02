package com.originlore.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Transactional editor for arbitrary persistent Minecraft data components. */
public final class AdvancedComponentsScreen extends Screen {
    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private final ComponentRule working;
    private final List<String> componentIds = new ArrayList<>();
    private String selectedId;
    private boolean removeMode;
    private boolean entryDirty;
    private boolean populating;
    private int listOffset;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private String idDraft = "";
    private String valueDraft = "{}";
    private String status = "";

    private TextFieldWidget componentIdField;
    private LoreTextAreaWidget valueField;
    private IdSuggestionController suggestions;

    public AdvancedComponentsScreen(Screen parent, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal("高级数据组件"));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
    }

    @Override
    protected void init() {
        buildUi();
    }

    private void buildUi() {
        rebuildComponentIds();
        int totalWidth = Math.min(720, Math.max(300, width - 20));
        left = (width - totalWidth) / 2;
        listWidth = totalWidth < 500 ? 132 : 210;
        editorX = left + listWidth + 10;
        editorWidth = totalWidth - listWidth - 10;

        int visibleRows = visibleRows();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, componentIds.size() - visibleRows)));
        int y = 38;
        for (int row = 0; row < visibleRows && listOffset + row < componentIds.size(); row++) {
            String id = componentIds.get(listOffset + row);
            boolean removed = working.removeComponents != null && working.removeComponents.contains(id)
                    && (working.setComponents == null || !working.setComponents.containsKey(id));
            String prefix = id.equals(selectedId) ? "> " : "";
            String label = prefix + (removed ? "REMOVE " : "SET ") + id;
            label = textRenderer.trimToWidth(label, listWidth - 8);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> select(id))
                    .dimensions(left, y, listWidth, 20).build());
            y += 22;
        }

        if (componentIds.size() > visibleRows) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                preserveDrafts();
                listOffset = Math.max(0, listOffset - 1);
                rebuildUi();
            }).dimensions(left, y, listWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                preserveDrafts();
                listOffset = Math.min(Math.max(0, componentIds.size() - visibleRows), listOffset + 1);
                rebuildUi();
            }).dimensions(left + listWidth / 2 + 1, y, listWidth / 2 - 1, 18).build());
        }

        componentIdField = new TextFieldWidget(textRenderer, editorX, 50, editorWidth, 20,
                Text.literal("数据组件 ID"));
        componentIdField.setMaxLength(256);
        componentIdField.setPlaceholder(Text.literal("minecraft:food"));
        componentIdField.setText(idDraft);
        suggestions = new IdSuggestionController(componentIdField,
                () -> ClientConfigSession.catalog().componentIds());
        componentIdField.setChangedListener(value -> {
            if (!populating) {
                idDraft = value;
                entryDirty = true;
                status = "";
            }
            suggestions.update();
        });
        addDrawableChild(componentIdField);

        addDrawableChild(ButtonWidget.builder(Text.literal(removeMode ? "模式: REMOVE" : "模式: SET"), button -> {
            preserveDrafts();
            removeMode = !removeMode;
            entryDirty = true;
            rebuildUi();
        }).dimensions(editorX, 84, Math.min(130, editorWidth), 20).build());

        int valueTop = 119;
        int valueHeight = Math.max(54, height - valueTop - 93);
        valueField = new LoreTextAreaWidget(textRenderer, editorX, valueTop, editorWidth, valueHeight,
                Text.literal("组件值 JSON"), Text.literal("组件值 JSON"));
        valueField.setMaxLength(262_144);
        valueField.setText(valueDraft);
        valueField.setChangeListener(value -> {
            if (!populating) {
                valueDraft = value;
                entryDirty = true;
                status = "";
            }
        });
        valueField.active = !removeMode;
        addDrawableChild(valueField);

        int actionY = height - 60;
        int gap = 4;
        int actionWidth = Math.max(48, (editorWidth - gap * 2) / 3);
        addDrawableChild(ButtonWidget.builder(Text.literal("保存条目"), button -> saveEntry(true))
                .dimensions(editorX, actionY, actionWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("新建"), button -> beginNew())
                .dimensions(editorX + actionWidth + gap, actionY, actionWidth, 20).build());
        ButtonWidget delete = ButtonWidget.builder(Text.literal("删除"), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + gap) * 2, actionY,
                        editorWidth - (actionWidth + gap) * 2, 20).build();
        delete.active = selectedId != null;
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal("应用到规则"), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 102, 20).build());
    }

    private void rebuildComponentIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (working.setComponents != null) ids.addAll(working.setComponents.keySet());
        if (working.removeComponents != null) ids.addAll(working.removeComponents);
        componentIds.clear();
        componentIds.addAll(ids);
        componentIds.sort(String::compareTo);
    }

    private int visibleRows() {
        return Math.max(3, (height - 102) / 22);
    }

    private void select(String id) {
        if (entryDirty && !saveEntry(false)) return;
        selectedId = id;
        loadSelected();
        rebuildUi();
    }

    private void loadSelected() {
        populating = true;
        idDraft = selectedId == null ? "" : selectedId;
        JsonElement value = selectedId == null || working.setComponents == null
                ? null : working.setComponents.get(selectedId);
        removeMode = selectedId != null && working.removeComponents != null
                && working.removeComponents.contains(selectedId) && value == null;
        valueDraft = value == null ? "{}" : value.toString();
        entryDirty = false;
        populating = false;
    }

    private void beginNew() {
        if (entryDirty && !saveEntry(false)) return;
        selectedId = null;
        idDraft = "";
        valueDraft = "{}";
        removeMode = false;
        entryDirty = false;
        status = "";
        rebuildUi();
        componentIdField.setFocused(true);
        setFocused(componentIdField);
    }

    private boolean saveEntry(boolean rebuild) {
        preserveDrafts();
        String rawId = idDraft.trim();
        Identifier parsed = Identifier.tryParse(rawId);
        if (parsed == null) {
            status = "数据组件 ID 格式无效";
            return false;
        }
        String id = parsed.toString();
        JsonElement value = null;
        if (!removeMode) {
            try {
                value = JsonParser.parseString(valueDraft);
                if (value == null || value.isJsonNull()) {
                    status = "SET 模式不能使用 null 值";
                    return false;
                }
            } catch (RuntimeException exception) {
                status = "组件值不是有效 JSON: " + compactMessage(exception);
                return false;
            }
        }

        if (selectedId != null && !selectedId.equals(id)) removeEntry(selectedId);
        if (removeMode) {
            if (working.removeComponents == null) working.removeComponents = new LinkedHashSet<>();
            working.removeComponents.add(id);
            if (working.setComponents != null) working.setComponents.remove(id);
        } else {
            if (working.setComponents == null) working.setComponents = new LinkedHashMap<>();
            working.setComponents.put(id, value.deepCopy());
            if (working.removeComponents != null) working.removeComponents.remove(id);
        }
        cleanupEmptyCollections();
        selectedId = id;
        idDraft = id;
        entryDirty = false;
        status = "条目已写入事务副本";
        if (rebuild) rebuildUi();
        return true;
    }

    private void deleteSelected() {
        if (selectedId == null) return;
        removeEntry(selectedId);
        cleanupEmptyCollections();
        selectedId = null;
        idDraft = "";
        valueDraft = "{}";
        removeMode = false;
        entryDirty = false;
        status = "条目已从事务副本删除";
        rebuildUi();
    }

    private void removeEntry(String id) {
        if (working.setComponents != null) working.setComponents.remove(id);
        if (working.removeComponents != null) working.removeComponents.remove(id);
    }

    private void cleanupEmptyCollections() {
        if (working.setComponents != null && working.setComponents.isEmpty()) working.setComponents = null;
        if (working.removeComponents != null && working.removeComponents.isEmpty()) working.removeComponents = null;
    }

    private void apply() {
        preserveDrafts();
        if (entryDirty && !saveEntry(false)) return;
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private void preserveDrafts() {
        if (componentIdField != null) idDraft = componentIdField.getText();
        if (valueField != null) valueDraft = valueField.getText();
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
        context.drawText(textRenderer, "已配置组件", left, 26, 0xA0A0A0, false);
        context.drawText(textRenderer, "数据组件 ID", editorX, 38, 0xA0A0A0, false);
        context.drawText(textRenderer, removeMode ? "该组件会从物品移除" : "组件值 JSON", editorX, 108,
                removeMode ? 0xE0B35A : 0xA0A0A0, false);
        if (!status.isBlank()) {
            int color = status.startsWith("条目已") ? 0x8FE388 : 0xFF7777;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 39, color);
        }
        if (suggestions != null) suggestions.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private static String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
