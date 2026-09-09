package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.ToolRule;
import com.originlore.config.ItemComponentConfig.ToolRuleEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
    private final ToolRule baseTool;
    private final ToolComponent vanillaTool;
    private final List<ToolRuleEntry> rules = new ArrayList<>();
    private boolean managed;
    private boolean rulesManaged;
    private int selected = -1;
    private int listOffset;
    private int blockIndex;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private ToolRuleEntry draft = emptyEntry();
    private boolean creatingNew;
    private boolean entryDirty;
    private String status = "";

    private TextFieldWidget blockField;
    private IdSuggestionController blockSuggestions;

    public ToolRulesEditorScreen(Screen parent, String itemId, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal(GuiText.string("originlore.editor.tool_rules")));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
        vanillaTool = vanillaTool(itemId);
        ToolRule tool = this.working.tool;
        baseTool = tool == null ? new ToolRule() : tool.copy();
        managed = tool != null;
        if (tool != null) {
            rulesManaged = tool.rules != null;
            if (tool.rules != null) {
                for (ToolRuleEntry entry : tool.rules) if (entry != null) rules.add(entry.copy());
            }
        }
        if (!rulesManaged && vanillaTool != null) {
            for (ToolComponent.Rule original : vanillaTool.rules()) {
                ToolRuleEntry entry = new ToolRuleEntry();
                entry.blocks = new ArrayList<>(original.blocks().stream()
                        .map(block -> Registries.BLOCK.getId(block.value()).toString()).toList());
                if (entry.blocks.isEmpty()) continue;
                entry.speed = original.speed().orElse(null);
                entry.correctForDrops = original.correctForDrops().orElse(null);
                rules.add(entry);
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
        ButtonWidget baseNumbers = ButtonWidget.builder(GuiText.text("originlore.number.tool"), button -> openBaseNumbers())
                .dimensions(left, 54, totalWidth, 20).build();
        baseNumbers.active = managed;
        addDrawableChild(baseNumbers);

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
            String firstBlock = entry.blocks == null || entry.blocks.isEmpty() ? GuiText.string("originlore.editor.empty") : entry.blocks.getFirst();
            String suffix = entry.blocks != null && entry.blocks.size() > 1 ? " +" + (entry.blocks.size() - 1) : "";
            String label = (selected == index ? "> " : "") + firstBlock + suffix;
            label = textRenderer.trimToWidth(label, listWidth - 8);
            ButtonWidget rowButton = ButtonWidget.builder(Text.literal(label), button -> select(index))
                    .dimensions(left, y, listWidth, 20).build();
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
        blockField = new TextFieldWidget(textRenderer, editorX, 108, editorWidth, 20, Text.literal(GuiText.string("originlore.editor.block_id")));
        blockField.setMaxLength(256);
        blockField.setPlaceholder(Text.literal("minecraft:stone"));
        blockField.setText(draft.blocks.get(blockIndex));
        blockField.setChangedListener(value -> entryDirty = true);
        blockField.active = canEditDraft();
        blockSuggestions = new IdSuggestionController(blockField, () -> ClientConfigSession.catalog().blockIds());
        addDrawableChild(blockField);

        buildBlockNavigation();

        int halfEditor = (editorWidth - gap) / 2;
        ButtonWidget speedButton = ButtonWidget.builder(GuiText.text("originlore.number.mining_speed"), button -> {
            if (!captureCurrentBlock() || client == null) return;
            client.setScreen(new NumericSettingsScreen(this, "originlore.number.mining_speed", List.of(
                    NumericSettingsScreen.Setting.decimal("originlore.number.mining_speed", draft.speed,
                            draft.speedRange, 0, Float.MAX_VALUE, value -> {
                        draft.speed = value.decimal();
                        draft.speedRange = value.range();
                    }).withDefault(vanillaMiningSpeed())), () -> entryDirty = true));
        }).dimensions(editorX, 156, halfEditor, 20).build();
        speedButton.active = canEditDraft();
        addDrawableChild(speedButton);
        addDrawableChild(triStateButton(draft.correctForDrops, editorX + halfEditor + gap, 156, halfEditor));

        int actionY = height - 58;
        int actionWidth = Math.max(45, (editorWidth - 8) / 3);
        ButtonWidget save = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.save_entry")), button -> saveEntry(true))
                .dimensions(editorX, actionY, actionWidth, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.new_entry")), button -> beginNew())
                .dimensions(editorX + actionWidth + 4, actionY, actionWidth, 20).build();
        ButtonWidget delete = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.delete")), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + 4) * 2, actionY,
                        editorWidth - (actionWidth + 4) * 2, 20).build();
        save.active = canEditDraft();
        add.active = managed && rulesManaged;
        delete.active = managed && rulesManaged && selected >= 0;
        addDrawableChild(save);
        addDrawableChild(add);
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.apply_rule")), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.cancel")), button -> close())
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
        boolean active = canEditDraft();
        previous.active = blockIndex > 0;
        indicator.active = false;
        next.active = blockIndex + 1 < draft.blocks.size();
        add.active = active;
        remove.active = active && draft.blocks.size() > 1;
        addDrawableChild(previous);
        addDrawableChild(indicator);
        addDrawableChild(next);
        addDrawableChild(add);
        addDrawableChild(remove);
    }

    private ButtonWidget triStateButton(Boolean initial, int x, int y, int buttonWidth) {
        final Boolean[] value = {initial};
        ButtonWidget button = ButtonWidget.builder(correctLabel(value[0]), widget -> {
            value[0] = value[0] == null ? Boolean.TRUE : value[0] ? Boolean.FALSE : null;
            draft.correctForDrops = value[0];
            entryDirty = true;
            widget.setMessage(correctLabel(value[0]));
        }).dimensions(x, y, buttonWidth, 20).build();
        button.active = canEditDraft();
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
        creatingNew = false;
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
        creatingNew = true;
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
        return true;
    }

    private void openBaseNumbers() {
        if (entryDirty && !saveEntry(false) || client == null) return;
        client.setScreen(new NumericSettingsScreen(this, "originlore.number.tool", List.of(
                NumericSettingsScreen.Setting.decimal("originlore.number.default_mining", baseTool.defaultMiningSpeed,
                        baseTool.defaultMiningSpeedRange, 0, Float.MAX_VALUE, value -> {
                    baseTool.defaultMiningSpeed = value.decimal();
                    baseTool.defaultMiningSpeedRange = value.range();
                }).withDefault(vanillaTool == null ? null : vanillaTool.defaultMiningSpeed()),
                NumericSettingsScreen.Setting.decimal("originlore.number.mining_multiplier", null,
                        baseTool.miningSpeedMultiplier, 0, Float.MAX_VALUE,
                        value -> baseTool.miningSpeedMultiplier = value.asRange()).withDefault(1),
                NumericSettingsScreen.Setting.integer("originlore.number.damage_per_block", baseTool.damagePerBlock,
                        baseTool.damagePerBlockRange, 0, Integer.MAX_VALUE, value -> {
                    baseTool.damagePerBlock = value.integer();
                    baseTool.damagePerBlockRange = value.integers();
                }).withDefault(vanillaTool == null ? null : vanillaTool.damagePerBlock())
        ), () -> { }));
    }

    private boolean saveEntry(boolean rebuild) {
        if (!canEditDraft()) return true;
        try {
            if (!captureCurrentBlock()) return false;
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String block : draft.blocks) {
                validateBlockId(block);
                if (!unique.add(block)) throw new IllegalArgumentException(GuiText.string("originlore.editor.block_duplicate"));
            }
            draft.blocks = new ArrayList<>(unique);
            if (selected < 0) {
                rules.add(draft.copy());
                selected = rules.size() - 1;
            } else {
                rules.set(selected, draft.copy());
            }
            creatingNew = false;
            entryDirty = false;
            status = GuiText.string("originlore.editor.entry_saved");
            if (rebuild) rebuildUi();
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private void validateBlockId(String id) {
        if (Identifier.tryParse(id) == null) throw new IllegalArgumentException(GuiText.string("originlore.editor.block_id_invalid"));
        List<String> known = ClientConfigSession.catalog().blockIds();
        if (!known.isEmpty() && !known.contains(id)) throw new IllegalArgumentException(GuiText.string("originlore.editor.block_unknown"));
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= rules.size()) return;
        rules.remove(selected);
        creatingNew = false;
        selected = rules.isEmpty() ? -1 : Math.min(selected, rules.size() - 1);
        draft = selected < 0 ? emptyEntry() : rules.get(selected).copy();
        normalizeDraft();
        blockIndex = 0;
        entryDirty = false;
        status = GuiText.string("originlore.editor.entry_deleted");
        rebuildUi();
    }

    private void apply() {
        if (!collectBase()) return;
        if (managed && rulesManaged && entryDirty && !saveEntry(false)) return;
        if (!managed) {
            working.tool = null;
        } else {
            ToolRule tool = baseTool.copy();
            tool.rules = null;
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
        if (draft.blocks == null || draft.blocks.isEmpty()) draft.blocks = new ArrayList<>(List.of(""));
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
        context.drawText(textRenderer, GuiText.string("originlore.editor.block_rules"), left, 99, 0xA0A0A0, false);
        context.drawText(textRenderer, GuiText.string("originlore.editor.block_group"), editorX, 99, 0xA0A0A0, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 38, status.startsWith(GuiText.string("originlore.editor.entry_prefix")) ? 0x8FE388 : 0xFF7777);
        }
        if (blockSuggestions != null) blockSuggestions.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private Text managedLabel() {
        return Text.literal(GuiText.string("originlore.editor.manage_tool") + (managed ? GuiText.string("originlore.editor.yes") : GuiText.string("originlore.editor.preserve")));
    }

    private Text rulesManagedLabel() {
        return Text.literal(GuiText.string("originlore.editor.manage_block_rules") + (rulesManaged ? GuiText.string("originlore.editor.replace") : GuiText.string("originlore.editor.inherit_parent")));
    }

    private static Text correctLabel(Boolean value) {
        return Text.literal(GuiText.string("originlore.editor.correct_drops") + (value == null ? GuiText.string("originlore.editor.inherit") : value ? GuiText.string("originlore.editor.yes") : GuiText.string("originlore.editor.no")));
    }

    private boolean canEditDraft() {
        return managed && rulesManaged && (selected >= 0 || creatingNew);
    }

    private Float vanillaMiningSpeed() {
        if (vanillaTool == null || draft.blocks == null || draft.blocks.isEmpty()) return null;
        Identifier id = Identifier.tryParse(draft.blocks.get(blockIndex));
        return id == null || !Registries.BLOCK.containsId(id) ? null
                : vanillaTool.getSpeed(Registries.BLOCK.get(id).getDefaultState());
    }

    private static ToolComponent vanillaTool(String itemId) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        return id == null || !Registries.ITEM.containsId(id) ? null
                : new ItemStack(Registries.ITEM.get(id)).get(DataComponentTypes.TOOL);
    }

    private static ToolRuleEntry emptyEntry() {
        ToolRuleEntry entry = new ToolRuleEntry();
        entry.blocks = new ArrayList<>(List.of(""));
        return entry;
    }

    private static ToolRuleEntry defaultEntry() {
        ToolRuleEntry entry = new ToolRuleEntry();
        entry.blocks = new ArrayList<>(List.of("minecraft:stone"));
        return entry;
    }


}
