package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.AttributeRule;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Transactional editor for attribute modifier entries. */
public final class AttributesEditorScreen extends Screen {
    private static final List<String> OPERATIONS = List.of(
            "add_value", "add_multiplied_base", "add_multiplied_total");
    private static final List<String> SLOTS = List.of(
            "any", "mainhand", "offhand", "hand", "feet", "legs", "chest", "head", "armor", "body");

    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private final ComponentRule working;
    private final List<AttributeRule> attributes = new ArrayList<>();
    private boolean managed;
    private int selected = -1;
    private int listOffset;
    private int left;
    private int listWidth;
    private int editorX;
    private int editorWidth;
    private AttributeRule draft = emptyAttribute();
    private boolean creatingNew;
    private boolean dirty;
    private String status = "";

    private TextFieldWidget attributeField;
    private TextFieldWidget modifierIdField;
    private IdSuggestionController attributeSuggestions;
    private ChoiceDropdownController operationDropdown;
    private ChoiceDropdownController slotDropdown;

    public AttributesEditorScreen(Screen parent, String itemId, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal(GuiText.string("originlore.editor.attributes")));
        this.parent = parent;
        this.onApply = onApply;
        this.working = rule == null ? new ComponentRule() : rule.copy();
        managed = this.working.attributes != null;
        if (managed) {
            for (AttributeRule attribute : this.working.attributes) {
                if (attribute != null) attributes.add(attribute.copy());
            }
        } else {
            attributes.addAll(vanillaAttributes(itemId));
        }
        if (!attributes.isEmpty()) {
            selected = 0;
            draft = attributes.getFirst().copy();
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

        addDrawableChild(ButtonWidget.builder(managedLabel(), button -> {
            if (dirty && !saveDraft(false)) return;
            managed = !managed;
            rebuildUi();
        }).dimensions(left, 34, totalWidth - 106, 20).build());
        ButtonWidget append = ButtonWidget.builder(GuiText.text(!Boolean.FALSE.equals(working.appendAttributes)
                ? "originlore.mode.append" : "originlore.mode.replace"), button -> {
            working.appendAttributes = Boolean.FALSE.equals(working.appendAttributes);
            button.setMessage(GuiText.text(!Boolean.FALSE.equals(working.appendAttributes)
                    ? "originlore.mode.append" : "originlore.mode.replace"));
        }).dimensions(left + totalWidth - 100, 34, 100, 20).build();
        append.active = managed;
        addDrawableChild(append);

        int visibleRows = visibleRows();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, attributes.size() - visibleRows)));
        int y = 64;
        for (int row = 0; row < visibleRows && listOffset + row < attributes.size(); row++) {
            int index = listOffset + row;
            AttributeRule attribute = attributes.get(index);
            String label = (selected == index ? "> " : "") + attribute.attribute + " " + attribute.amount;
            label = textRenderer.trimToWidth(label, listWidth - 8);
            ButtonWidget entry = ButtonWidget.builder(Text.literal(label), button -> select(index))
                    .dimensions(left, y, listWidth, 20).build();
            addDrawableChild(entry);
            y += 22;
        }
        if (attributes.size() > visibleRows) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                listOffset = Math.max(0, listOffset - 1);
                rebuildUi();
            }).dimensions(left, y, listWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                listOffset = Math.min(Math.max(0, attributes.size() - visibleRows), listOffset + 1);
                rebuildUi();
            }).dimensions(left + listWidth / 2 + 1, y, listWidth / 2 - 1, 18).build());
        }

        attributeField = field(editorX, 64, editorWidth, draft.attribute, "minecraft:generic.attack_damage");
        attributeSuggestions = new IdSuggestionController(attributeField,
                () -> ClientConfigSession.catalog().attributeIds());
        modifierIdField = field(editorX, 94, editorWidth, draft.id, "originlore:modifier_id");
        ButtonWidget amountButton = ButtonWidget.builder(GuiText.text("originlore.number.attribute"), button -> {
            draft.attribute = attributeField.getText().trim();
            draft.id = modifierIdField.getText().trim();
            if (client != null) client.setScreen(new NumericSettingsScreen(this, "originlore.number.attribute", List.of(
                    new NumericSettingsScreen.Setting("originlore.number.attribute", false, false, true,
                            -Double.MAX_VALUE, Double.MAX_VALUE,
                            new NumericSettingsScreen.Value(draft.amount, draft.amountRange), value -> {
                        draft.amount = value.fixed() == null ? 0 : value.fixed();
                        draft.amountRange = value.range();
                    }, null, null)), () -> dirty = true));
        }).dimensions(editorX, 124, editorWidth, 20).build();
        amountButton.active = canEditDraft();
        addDrawableChild(amountButton);

        int gap = 6;
        int half = (editorWidth - gap) / 2;
        ButtonWidget operationButton = ButtonWidget.builder(choiceLabel(GuiText.string("originlore.editor.operation"), draft.operation),
                button -> operationDropdown.toggle()).dimensions(editorX, 154, half, 20).build();
        operationButton.active = canEditDraft();
        addDrawableChild(operationButton);
        operationDropdown = new ChoiceDropdownController(operationButton, OPERATIONS, () -> draft.operation, value -> {
            draft.operation = value;
            dirty = true;
        }, value -> choiceLabel(GuiText.string("originlore.editor.operation"), value));

        ButtonWidget slotButton = ButtonWidget.builder(choiceLabel(GuiText.string("originlore.editor.slot"), draft.slot),
                button -> slotDropdown.toggle()).dimensions(editorX + half + gap, 154, half, 20).build();
        slotButton.active = canEditDraft();
        addDrawableChild(slotButton);
        slotDropdown = new ChoiceDropdownController(slotButton, SLOTS, () -> draft.slot, value -> {
            draft.slot = value;
            dirty = true;
        }, value -> choiceLabel(GuiText.string("originlore.editor.slot"), value));

        int actionY = height - 58;
        int actionWidth = Math.max(45, (editorWidth - 8) / 3);
        ButtonWidget save = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.save_entry")), button -> saveDraft(true))
                .dimensions(editorX, actionY, actionWidth, 20).build();
        ButtonWidget add = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.new_entry")), button -> beginNew())
                .dimensions(editorX + actionWidth + 4, actionY, actionWidth, 20).build();
        ButtonWidget delete = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.delete")), button -> deleteSelected())
                .dimensions(editorX + (actionWidth + 4) * 2, actionY,
                        editorWidth - (actionWidth + 4) * 2, 20).build();
        save.active = canEditDraft();
        add.active = managed;
        delete.active = managed && selected >= 0;
        addDrawableChild(save);
        addDrawableChild(add);
        addDrawableChild(delete);

        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.apply_rule")), button -> apply())
                .dimensions(width / 2 - 106, height - 27, 102, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.cancel")), button -> close())
                .dimensions(width / 2 + 4, height - 27, 102, 20).build());
    }

    private TextFieldWidget field(int x, int y, int fieldWidth, String value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.literal(placeholder));
        field.setMaxLength(256);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value == null ? "" : value);
        field.setChangedListener(ignored -> dirty = true);
        field.active = canEditDraft();
        addDrawableChild(field);
        return field;
    }

    private void select(int index) {
        if (dirty && !saveDraft(false)) return;
        selected = index;
        creatingNew = false;
        draft = attributes.get(index).copy();
        normalizeDraft();
        dirty = false;
        status = "";
        rebuildUi();
    }

    private void beginNew() {
        if (dirty && !saveDraft(false)) return;
        selected = -1;
        creatingNew = true;
        draft = defaultAttribute();
        dirty = false;
        status = "";
        rebuildUi();
        attributeField.setFocused(true);
        setFocused(attributeField);
    }

    private boolean saveDraft(boolean rebuild) {
        if (!canEditDraft()) return true;
        try {
            String attribute = attributeField.getText().trim();
            if (Identifier.tryParse(attribute) == null) throw new IllegalArgumentException(GuiText.string("originlore.editor.attribute_id_invalid"));
            List<String> known = ClientConfigSession.catalog().attributeIds();
            if (!known.isEmpty() && !known.contains(attribute)) throw new IllegalArgumentException(GuiText.string("originlore.editor.attribute_unknown"));
            String modifierId = modifierIdField.getText().trim();
            if (Identifier.tryParse(modifierId) == null) throw new IllegalArgumentException(GuiText.string("originlore.editor.modifier_id_invalid"));
            if (!OPERATIONS.contains(draft.operation)) throw new IllegalArgumentException(GuiText.string("originlore.editor.operation_invalid"));
            if (!SLOTS.contains(draft.slot)) throw new IllegalArgumentException(GuiText.string("originlore.editor.slot_invalid"));
            draft.attribute = attribute;
            draft.id = modifierId;
            if (selected < 0) {
                attributes.add(draft.copy());
                selected = attributes.size() - 1;
            } else {
                attributes.set(selected, draft.copy());
            }
            creatingNew = false;
            dirty = false;
            status = GuiText.string("originlore.editor.entry_saved");
            if (rebuild) rebuildUi();
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            return false;
        }
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= attributes.size()) return;
        attributes.remove(selected);
        creatingNew = false;
        selected = attributes.isEmpty() ? -1 : Math.min(selected, attributes.size() - 1);
        draft = selected < 0 ? emptyAttribute() : attributes.get(selected).copy();
        normalizeDraft();
        dirty = false;
        status = GuiText.string("originlore.editor.entry_deleted");
        rebuildUi();
    }

    private void apply() {
        if (managed && dirty && !saveDraft(false)) return;
        if (managed) {
            working.attributes = new ArrayList<>();
            for (AttributeRule attribute : attributes) working.attributes.add(attribute.copy());
        } else {
            working.attributes = null;
        }
        onApply.accept(working.copy());
        if (client != null) client.setScreen(parent);
    }

    private void normalizeDraft() {
        if (draft.operation == null || !OPERATIONS.contains(draft.operation.toLowerCase())) {
            draft.operation = "add_value";
        } else draft.operation = draft.operation.toLowerCase();
        if (draft.slot == null || !SLOTS.contains(draft.slot.toLowerCase())) draft.slot = "any";
        else draft.slot = draft.slot.toLowerCase();
    }

    private int visibleRows() {
        return Math.max(3, (height - 135) / 22);
    }

    private void rebuildUi() {
        clearChildren();
        buildUi();
    }

    @Override
    public void tick() {
        super.tick();
        if (attributeSuggestions != null) attributeSuggestions.update();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (operationDropdown != null && operationDropdown.keyPressed(keyCode)) return true;
        if (slotDropdown != null && slotDropdown.keyPressed(keyCode)) return true;
        if (attributeSuggestions != null && attributeSuggestions.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (operationDropdown != null && operationDropdown.mouseClicked(mouseX, mouseY, height)) return true;
        if (slotDropdown != null && slotDropdown.mouseClicked(mouseX, mouseY, height)) return true;
        if (attributeSuggestions != null && attributeSuggestions.mouseClicked(mouseX, mouseY, height)) return true;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (attributeSuggestions != null) attributeSuggestions.update();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (operationDropdown != null
                && operationDropdown.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        if (slotDropdown != null && slotDropdown.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        if (attributeSuggestions != null
                && attributeSuggestions.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        boolean overDropdown = (operationDropdown != null && operationDropdown.isMouseOverPopup(mouseX, mouseY, height))
                || (slotDropdown != null && slotDropdown.isMouseOverPopup(mouseX, mouseY, height));
        super.render(context, overDropdown ? -1 : mouseX, overDropdown ? -1 : mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawText(textRenderer, GuiText.string("originlore.editor.attribute_list"), left, 55, 0xA0A0A0, false);
        context.drawText(textRenderer, GuiText.string("originlore.editor.attribute_id"), editorX, 55, 0xA0A0A0, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 38, status.startsWith(GuiText.string("originlore.editor.entry_prefix")) ? 0x8FE388 : 0xFF7777);
        }
        if (attributeSuggestions != null) attributeSuggestions.render(context, textRenderer, height);
        if (operationDropdown != null) operationDropdown.render(context, textRenderer, height);
        if (slotDropdown != null) slotDropdown.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private Text managedLabel() {
        return Text.literal(GuiText.string("originlore.editor.manage_attributes") + (managed ? GuiText.string("originlore.editor.yes") : GuiText.string("originlore.editor.preserve")));
    }

    private static Text choiceLabel(String label, String value) {
        return Text.literal(label + ": " + value);
    }

    private boolean canEditDraft() {
        return managed && (selected >= 0 || creatingNew);
    }

    private static List<AttributeRule> vanillaAttributes(String itemId) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return List.of();
        ItemStack original = new ItemStack(Registries.ITEM.get(id));
        AttributeModifiersComponent component = original.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        if (component.modifiers().isEmpty()) component = original.getItem().getAttributeModifiers();
        List<AttributeRule> result = new ArrayList<>();
        for (AttributeModifiersComponent.Entry entry : component.modifiers()) {
            AttributeRule attribute = new AttributeRule();
            attribute.attribute = Registries.ATTRIBUTE.getId(entry.attribute().value()).toString();
            attribute.id = entry.modifier().id().toString();
            attribute.amount = entry.modifier().value();
            attribute.operation = entry.modifier().operation().asString();
            attribute.slot = entry.slot().asString();
            result.add(attribute);
        }
        return result;
    }

    private static AttributeRule emptyAttribute() {
        AttributeRule rule = new AttributeRule();
        rule.attribute = "";
        rule.id = "";
        rule.operation = "add_value";
        rule.slot = "mainhand";
        return rule;
    }

    private static AttributeRule defaultAttribute() {
        AttributeRule rule = new AttributeRule();
        rule.attribute = "minecraft:generic.attack_damage";
        rule.id = "originlore:modifier";
        rule.amount = 0;
        rule.operation = "add_value";
        rule.slot = "mainhand";
        return rule;
    }
}
