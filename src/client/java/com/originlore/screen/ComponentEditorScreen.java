package com.originlore.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.originlore.client.ClientConfigSession;
import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.source.SourceContext.SourceType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Transactional editor for base, source and variant rules. */
public final class ComponentEditorScreen extends Screen {
    private enum Page { TEXT, NUMBERS, FOOD, COMPONENTS }
    private boolean itemNameMode = true;

    private static final String[] COLORS = {"", "white", "gray", "green", "aqua", "gold", "red", "light_purple"};
    private final Screen parent;
    private final String originalItemId;
    private final boolean preexisting;
    private final ConfigSnapshot baseSnapshot;
    private final ItemEntry working;
    private int sourceIndex = -1;
    private int variantIndex = -1;
    private int sourceOffset;
    private int variantOffset;
    private Page page = Page.TEXT;
    private int rightX;
    private int rightWidth;
    private int formTop;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean pendingSave;
    private long responseGeneration;
    private ButtonWidget saveButton;

    private TextFieldWidget itemIdField;
    private TextFieldWidget nameField;
    private LoreTextAreaWidget loreField;
    private TextFieldWidget currentDamageField;
    private TextFieldWidget rarityField;
    private TextFieldWidget customModelField;
    private TextFieldWidget variantWeightField;
    private int variantWeightSummaryX;
    private boolean nameDirty;
    private boolean loreDirty;
    private boolean styleDirty;
    private boolean bold;
    private boolean italic;
    private String color = "";
    private IdSuggestionController itemSuggestions;
    private final List<IdSuggestionController> suggestions = new ArrayList<>();

    public ComponentEditorScreen(Screen parent, String itemId) {
        super(Text.literal(isConfigured(itemId) ? GuiText.string("originlore.editor.edit_item") : GuiText.string("originlore.editor.add_item")));
        this.parent = parent;
        this.originalItemId = itemId;
        ConfigSnapshot current = ClientConfigSession.snapshot();
        this.baseSnapshot = current == null ? new ConfigSnapshot(0, Map.of()) : current;
        ItemEntry existing = itemId == null ? null : this.baseSnapshot.items().get(itemId);
        this.preexisting = existing != null;
        this.working = existing == null ? new ItemEntry(itemId == null ? "" : itemId) : existing.copy();
    }

    private static boolean isConfigured(String itemId) {
        if (itemId == null) return false;
        ConfigSnapshot current = ClientConfigSession.snapshot();
        return current != null && current.items().containsKey(itemId);
    }

    @Override
    protected void init() {
        buildUi();
    }

    private void buildUi() {
        suggestions.clear();
        int totalWidth = Math.min(720, Math.max(300, width - 20));
        int outerLeft = (width - totalWidth) / 2;
        int hierarchyWidth = totalWidth < 500 ? 126 : 180;
        rightX = outerLeft + hierarchyWidth + 10;
        rightWidth = totalWidth - hierarchyWidth - 10;

        buildHierarchy(outerLeft, hierarchyWidth);
        buildHeader();
        buildPageTabs();
        switch (page) {
            case TEXT -> buildTextPage();
            case NUMBERS -> buildNumbersPage();
            case FOOD -> buildFoodPage();
            case COMPONENTS -> buildComponentsPage();
        }

        saveButton = ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.save")), button -> save())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build();
        saveButton.active = canSaveCurrentRevision();
        addDrawableChild(saveButton);
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.cancel")), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    private void buildHierarchy(int left, int panelWidth) {
        addDrawableChild(ButtonWidget.builder(GuiText.text(sourceIndex < 0
                ? "originlore.editor.selected_base" : "originlore.editor.base"), button -> select(-1, -1))
                .dimensions(left, 36, panelWidth, 20).build());
        int y = 64;
        int visibleSources = Math.max(1, Math.min(5, sourceIndex < 0 ? (height - 136) / 22 : (height - 210) / 44));
        sourceOffset = Math.max(0, Math.min(sourceOffset, Math.max(0, working.sources.size() - visibleSources)));
        for (int row = 0; row < visibleSources && sourceOffset + row < working.sources.size(); row++) {
            int index = sourceOffset + row;
            SourceRule source = working.sources.get(index);
            Text label = Text.literal((sourceIndex == index ? "> " : "") + (index + 1) + ". ")
                    .append(SourceTypeDisplay.name(source.type));
            addDrawableChild(ButtonWidget.builder(label, button -> select(index, -1))
                    .dimensions(left, y, panelWidth, 20).build());
            y += 22;
        }
        int unit = (panelWidth - 9) / 4;
        hierarchyButton(left, y, unit, "<", "originlore.action.previous", sourceOffset > 0, () -> {
            if (!collectFields()) return;
            sourceOffset--;
            rebuildWithoutCollect();
        });
        hierarchyButton(left + unit + 3, y, unit, ">", "originlore.action.next",
                sourceOffset + visibleSources < working.sources.size(), () -> {
            if (!collectFields()) return;
            sourceOffset++;
            rebuildWithoutCollect();
        });
        hierarchyButton(left + (unit + 3) * 2, y, unit, "+", "originlore.editor.add_source", true, this::addSource);
        hierarchyButton(left + (unit + 3) * 3, y, unit, "-", "originlore.editor.remove_source",
                sourceIndex >= 0, this::removeSource);
        y += 28;
        if (sourceIndex < 0 || sourceIndex >= working.sources.size()) return;
        List<Variant> variants = working.sources.get(sourceIndex).variants;
        int visibleVariants = Math.max(1, Math.min(4, (height - y - 75) / 22));
        variantOffset = Math.max(0, Math.min(variantOffset, Math.max(0, variants.size() - visibleVariants)));
        double totalWeight = variants.stream().filter(value -> value != null && value.weight > 0)
                .mapToDouble(value -> value.weight).sum();
        for (int row = 0; row < visibleVariants && variantOffset + row < variants.size(); row++) {
            int index = variantOffset + row;
            Variant variant = variants.get(index);
            double probability = totalWeight > 0 && variant.weight > 0 ? variant.weight / totalWeight * 100.0 : 0.0;
            String label = (variantIndex == index ? "> " : "") + variant.id + " " + formatWeight(variant.weight)
                    + " (" + formatWeight(probability) + "%)";
            addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> select(sourceIndex, index))
                    .dimensions(left, y, panelWidth, 20).build());
            y += 22;
        }
        hierarchyButton(left, y, unit, "<", "originlore.action.previous", variantOffset > 0, () -> {
            if (!collectFields()) return;
            variantOffset--;
            rebuildWithoutCollect();
        });
        hierarchyButton(left + unit + 3, y, unit, ">", "originlore.action.next",
                variantOffset + visibleVariants < variants.size(), () -> {
            if (!collectFields()) return;
            variantOffset++;
            rebuildWithoutCollect();
        });
        hierarchyButton(left + (unit + 3) * 2, y, unit, "+", "originlore.editor.add_variant", true, this::addVariant);
        hierarchyButton(left + (unit + 3) * 3, y, unit, "-", "originlore.editor.remove_variant",
                variantIndex >= 0, this::removeVariant);
    }

    private void hierarchyButton(int x, int y, int buttonWidth, String symbol, String tooltip,
                                 boolean active, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(symbol), ignored -> action.run())
                .dimensions(x, y, buttonWidth, 18)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(GuiText.text(tooltip))).build();
        button.active = active;
        addDrawableChild(button);
    }

    private void buildHeader() {
        variantWeightField = null;
        // Keep the variant-weight row below the item id field.  The label is
        // rendered separately from the widget, so placing it at y=48 caused
        // it to intrude into the 34..54 item-id widget on normal GUI scales.
        final int itemIdY = 34;
        final int variantWeightFieldY = 68;
        int settingsWidth = sourceIndex >= 0 ? Math.min(82, Math.max(68, rightWidth / 3)) : 0;
        int itemWidth = settingsWidth == 0 ? rightWidth : rightWidth - settingsWidth - 6;
        itemIdField = new TextFieldWidget(textRenderer, rightX, itemIdY, itemWidth, 20, Text.literal(GuiText.string("originlore.editor.item_id")));
        itemIdField.setMaxLength(256);
        itemIdField.setText(working.itemId == null ? "" : working.itemId);
        itemIdField.setEditable(originalItemId == null);
        itemSuggestions = new IdSuggestionController(itemIdField, () -> ClientConfigSession.catalog().itemIds());
        itemIdField.setChangedListener(value -> itemSuggestions.update());
        suggestions.add(itemSuggestions);

        if (sourceIndex >= 0 && sourceIndex < working.sources.size()) {
            addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.source_settings")), button -> openSourceMetadata())
                    .dimensions(rightX + itemWidth + 6, itemIdY, settingsWidth, 20).build());
        }
        addDrawableChild(itemIdField);

        if (height >= 330 && sourceIndex >= 0 && sourceIndex < working.sources.size()
                && variantIndex >= 0 && variantIndex < working.sources.get(sourceIndex).variants.size()) {
            Variant variant = working.sources.get(sourceIndex).variants.get(variantIndex);
            boolean compactHeader = rightWidth < 300;
            int weightWidth = compactHeader ? rightWidth : Math.max(92, (rightWidth - 6) / 2);
            variantWeightField = new TextFieldWidget(textRenderer, rightX, variantWeightFieldY, weightWidth, 20,
                    Text.literal(GuiText.string("originlore.editor.variant_weight")));
            variantWeightField.setMaxLength(32);
            variantWeightField.setPlaceholder(Text.literal(GuiText.string("originlore.editor.weight_hint")));
            variantWeightField.setText(Double.toString(variant.weight));
            variantWeightField.setChangedListener(value -> {
                status = "";
                try {
                    double parsed = parseWeight(value);
                    if (parsed >= 0 && Double.isFinite(parsed)) variant.weight = parsed;
                } catch (IllegalArgumentException ignored) {
                    // collectFields() reports the complete validation message.
                }
            });
            addDrawableChild(variantWeightField);
            variantWeightSummaryX = compactHeader ? rightX : rightX + weightWidth + 8;
        }
        // Leave a full line of breathing room after the weight row before the
        // page tabs.  This also keeps the compact (narrow-screen) summary from
        // colliding with the tabs below it.
        formTop = variantWeightField == null ? 86 : (rightWidth < 300 ? 148 : 126);
    }

    private void buildPageTabs() {
        int y = formTop - 21;
        int tabWidth = (rightWidth - 12) / 4;
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.TEXT ? GuiText.string("originlore.editor.tab_text_active") : GuiText.string("originlore.editor.tab_text")), button -> switchPage(Page.TEXT))
                .dimensions(rightX, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.NUMBERS ? GuiText.string("originlore.editor.tab_numbers_active") : GuiText.string("originlore.editor.tab_numbers")), button -> switchPage(Page.NUMBERS))
                .dimensions(rightX + tabWidth + 4, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.FOOD ? GuiText.string("originlore.editor.tab_food_active") : GuiText.string("originlore.editor.tab_food")), button -> switchPage(Page.FOOD))
                .dimensions(rightX + (tabWidth + 4) * 2, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.COMPONENTS ? GuiText.string("originlore.editor.tab_components_active") : GuiText.string("originlore.editor.tab_components")),
                button -> switchPage(Page.COMPONENTS))
                .dimensions(rightX + (tabWidth + 4) * 3, y,
                        rightWidth - (tabWidth + 4) * 3, 20).build());
    }

    private void buildTextPage() {
        ComponentRule rule = currentRule();
        loadStyle(rule);
        nameField = new TextFieldWidget(textRenderer, rightX, formTop + 12, rightWidth - 82, 20,
                GuiText.text(itemNameMode ? "originlore.name.item" : "originlore.name.custom"));
        nameField.setMaxLength(2048);
        nameField.setText(extractName(rule));
        nameField.setChangedListener(value -> nameDirty = true);
        addDrawableChild(nameField);
        addDrawableChild(ButtonWidget.builder(GuiText.text(itemNameMode ? "originlore.name.item" : "originlore.name.custom"), button -> {
            if (!collectFields()) return;
            itemNameMode = !itemNameMode;
            rebuildWithoutCollect();
        }).dimensions(rightX + rightWidth - 78, formTop + 12, 78, 20).build());

        loreField = new LoreTextAreaWidget(textRenderer, rightX, formTop + 47, rightWidth, 40,
                Text.literal(GuiText.string("originlore.editor.lore_hint")), Text.literal("Lore"));
        loreField.setMaxLength(16384);
        loreField.setText(extractLore(rule));
        loreField.setChangeListener(value -> loreDirty = true);
        addDrawableChild(loreField);

        // The save/cancel row is anchored to the bottom of the screen. On
        // high GUI scales the available logical height is small, so the
        // fixed form offset could put this row on top of the action buttons.
        // Keep the style controls at least 8 px above the bottom action row.
        int y = Math.min(formTop + 104, height - 58);
        ButtonWidget boldButton = ButtonWidget.builder(styleLabel("B", bold), button -> {
            bold = !bold;
            styleDirty = true;
            button.setMessage(styleLabel("B", bold));
        }).dimensions(rightX, y, 42, 20).build();
        addDrawableChild(boldButton);
        ButtonWidget italicButton = ButtonWidget.builder(styleLabel("I", italic), button -> {
            italic = !italic;
            styleDirty = true;
            button.setMessage(styleLabel("I", italic));
        }).dimensions(rightX + 46, y, 42, 20).build();
        addDrawableChild(italicButton);
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.color") + (color.isEmpty() ? GuiText.string("originlore.editor.inherit") : color)), button -> {
            int index = 0;
            for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) index = i;
            color = COLORS[(index + 1) % COLORS.length];
            styleDirty = true;
            button.setMessage(Text.literal(GuiText.string("originlore.editor.color") + (color.isEmpty() ? GuiText.string("originlore.editor.inherit") : color)));
        }).dimensions(rightX + 92, y, Math.max(80, rightWidth - 92), 20).build());

    }

    private void buildNumbersPage() {
        ComponentRule rule = currentRule();
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.number.equipment"), button -> {
            if (collectFields() && client != null) client.setScreen(RuleNumericSettings.equipment(this, working.itemId, currentRule(), this::setCurrentRule));
        }).dimensions(rightX, formTop + 10, rightWidth, 20).build());
        currentDamageField = field(rightX, formTop + 38, half, rule.currentDamage, GuiText.string("originlore.editor.current_damage"));
        rarityField = new TextFieldWidget(textRenderer, rightX + half + gap, formTop + 38, half, 20, Text.literal(GuiText.string("originlore.editor.rarity")));
        rarityField.setMaxLength(16);
        rarityField.setPlaceholder(Text.literal("COMMON / 0-3"));
        rarityField.setText(rule.rarityName != null ? rule.rarityName : rule.rarity == null ? "" : rule.rarity.toString());
        addDrawableChild(rarityField);
        customModelField = field(rightX, formTop + 66, half, rule.customModelData, GuiText.string("originlore.editor.custom_model"));
    }

    private void buildFoodPage() {
        ComponentRule rule = currentRule();
        FoodRule food = rule.food;
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.number.food"), button -> {
            if (collectFields() && client != null) client.setScreen(RuleNumericSettings.food(this, working.itemId, currentRule(), this::setCurrentRule));
        }).dimensions(rightX, formTop + 12, rightWidth, 20).build());
        Boolean always = food == null ? null : food.canAlwaysEat;
        addDrawableChild(triStateButton(GuiText.string("originlore.editor.always_eat"), always, value -> {
            FoodRule target = ensureFood(rule);
            target.canAlwaysEat = value;
            clearFoodIfEmpty(rule);
        }, rightX, formTop + 47, rightWidth));
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.edit_food_effects")), button -> openFoodEffects())
                .dimensions(rightX, formTop + 82, rightWidth, 20).build());
    }

    private void buildComponentsPage() {
        ComponentRule rule = currentRule();
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.enchantments")), button -> openEnchantments())
                .dimensions(rightX, formTop + 12, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.attributes")), button -> openAttributes())
                .dimensions(rightX + half + gap, formTop + 12, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.tool_mining")), button -> openToolRules())
                .dimensions(rightX, formTop + 36, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.food_effects")), button -> openFoodEffects())
                .dimensions(rightX + half + gap, formTop + 36, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.advanced")), button -> openAdvancedComponents())
                .dimensions(rightX, formTop + 60, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(GuiText.string("originlore.editor.rule_json")), button -> openRawEditor())
                .dimensions(rightX + half + gap, formTop + 60, half, 20).build());
        int triWidth = Math.max(52, (rightWidth - 8) / 3);
        addDrawableChild(triStateButton(GuiText.string("originlore.editor.fire_resistant"), rule.fireResistant, value -> rule.fireResistant = value,
                rightX, formTop + 84, triWidth));
        addDrawableChild(triStateButton(GuiText.string("originlore.editor.hide_tooltip"), rule.hideTooltip, value -> rule.hideTooltip = value,
                rightX + triWidth + 4, formTop + 84, triWidth));
        addDrawableChild(triStateButton(GuiText.string("originlore.editor.hide_additional"), rule.hideAdditionalTooltip,
                value -> rule.hideAdditionalTooltip = value,
                rightX + (triWidth + 4) * 2, formTop + 84,
                rightWidth - (triWidth + 4) * 2));
    }

    private TextFieldWidget field(int x, int y, int width, Number value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 20, Text.literal(placeholder));
        field.setMaxLength(32);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(value == null ? "" : value.toString());
        addDrawableChild(field);
        return field;
    }

    private ButtonWidget triStateButton(String label, Boolean initial, java.util.function.Consumer<Boolean> setter,
                                        int x, int y, int width) {
        final Boolean[] value = {initial};
        return ButtonWidget.builder(Text.literal(triStateLabel(label, value[0])), button -> {
            value[0] = value[0] == null ? Boolean.TRUE : value[0] ? Boolean.FALSE : null;
            setter.accept(value[0]);
            button.setMessage(Text.literal(triStateLabel(label, value[0])));
        }).dimensions(x, y, width, 20).build();
    }

    private static String triStateLabel(String label, Boolean value) {
        return label + ": " + (value == null ? GuiText.string("originlore.editor.inherit") : value ? GuiText.string("originlore.editor.yes") : GuiText.string("originlore.editor.no"));
    }

    private static Text styleLabel(String text, boolean active) {
        return Text.literal(active ? "[" + text + "]" : text);
    }

    private void select(int source, int variant) {
        if (!collectFields()) return;
        sourceIndex = source;
        variantIndex = variant;
        variantOffset = 0;
        rebuildWithoutCollect();
    }

    private void switchPage(Page target) {
        if (page == target || !collectFields()) return;
        page = target;
        rebuildWithoutCollect();
    }

    private void addSource() {
        if (!collectFields()) return;
        working.sources.add(new SourceRule(SourceType.UNKNOWN.name()));
        sourceIndex = working.sources.size() - 1;
        variantIndex = -1;
        sourceOffset = Math.max(0, sourceIndex - 3);
        rebuildWithoutCollect();
    }

    private void removeSource() {
        if (sourceIndex < 0 || sourceIndex >= working.sources.size() || !collectFields()) return;
        working.sources.remove(sourceIndex);
        sourceIndex = -1;
        variantIndex = -1;
        rebuildWithoutCollect();
    }

    private void addVariant() {
        if (sourceIndex < 0 || sourceIndex >= working.sources.size() || !collectFields()) return;
        SourceRule source = working.sources.get(sourceIndex);
        int number = source.variants.size() + 1;
        String id;
        do id = "variant_" + number++; while (containsVariant(source, id));
        source.variants.add(new Variant(id, 1.0));
        variantIndex = source.variants.size() - 1;
        variantOffset = Math.max(0, variantIndex - 2);
        rebuildWithoutCollect();
    }

    private void removeVariant() {
        if (sourceIndex < 0 || variantIndex < 0 || !collectFields()) return;
        List<Variant> variants = working.sources.get(sourceIndex).variants;
        if (variantIndex < variants.size()) variants.remove(variantIndex);
        variantIndex = -1;
        rebuildWithoutCollect();
    }

    private boolean collectFields() {
        try {
            if (itemIdField != null) working.itemId = itemIdField.getText().trim();
            collectVariantWeight();
            ComponentRule rule = currentRule();
            if (page == Page.TEXT) collectText(rule);
            else if (page == Page.NUMBERS) collectNumbers(rule);
            else if (page == Page.FOOD) collectFood(rule);
            status = "";
            return true;
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            statusColor = 0xFF7777;
            return false;
        }
    }

    private void collectVariantWeight() {
        if (variantWeightField == null || sourceIndex < 0 || sourceIndex >= working.sources.size()
                || variantIndex < 0 || variantIndex >= working.sources.get(sourceIndex).variants.size()) return;
        double weight = parseWeight(variantWeightField.getText());
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException(GuiText.string("originlore.editor.weight_nonnegative"));
        }
        working.sources.get(sourceIndex).variants.get(variantIndex).weight = weight;
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
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void collectText(ComponentRule rule) {
        if (nameField != null && (nameDirty || styleDirty)) {
            String value = nameField.getText();
            if (itemNameMode) {
                rule.itemName = value.isEmpty() || hasStyle() ? null : value;
                rule.itemNameJson = value.isEmpty() || !hasStyle() ? null : styledText(value);
            } else if (value.isEmpty()) {
                rule.customName = null;
                rule.customNameJson = null;
            } else if (hasStyle()) {
                rule.customName = null;
                rule.customNameJson = styledText(value);
            } else {
                rule.customName = value;
                rule.customNameJson = null;
            }
        }
        if (loreField != null && (loreDirty || styleDirty)) {
            String value = loreField.getText();
            if (value.isBlank()) {
                rule.lore = null;
                rule.loreJson = null;
            } else {
                String[] lines = value.split("\\R", -1);
                if (hasStyle()) {
                    rule.lore = null;
                    rule.loreJson = new ArrayList<>();
                    for (String line : lines) rule.loreJson.add(styledText(line));
                } else {
                    rule.lore = new ArrayList<>(List.of(lines));
                    rule.loreJson = null;
                }
            }
        }
    }

    private void collectNumbers(ComponentRule rule) {
        rule.currentDamage = parseInteger(currentDamageField, GuiText.string("originlore.editor.current_damage"));
        rule.customModelData = parseInteger(customModelField, GuiText.string("originlore.editor.custom_model"));
        String rarity = rarityField == null ? "" : rarityField.getText().trim();
        rule.rarity = null;
        rule.rarityName = null;
        if (!rarity.isEmpty()) {
            try {
                rule.rarity = Integer.parseInt(rarity);
            } catch (NumberFormatException ignored) {
                rule.rarityName = rarity.toUpperCase(Locale.ROOT);
            }
        }
    }

    private void collectFood(ComponentRule rule) {
        clearFoodIfEmpty(rule);
    }

    private void openRawEditor() {
        if (!collectFields() || client == null) return;
        client.setScreen(new RuleJsonEditorScreen(this, currentRule(), this::setCurrentRule));
    }

    private void openSourceMetadata() {
        if (!collectFields() || client == null || sourceIndex < 0 || sourceIndex >= working.sources.size()) return;
        int editingSource = sourceIndex;
        client.setScreen(new SourceMetadataEditorScreen(this, working.sources.get(editingSource), variantIndex,
                replacement -> working.sources.set(editingSource, replacement.copy())));
    }

    private void openAdvancedComponents() {
        if (!collectFields() || client == null) return;
        client.setScreen(new AdvancedComponentsScreen(this, currentRule(), this::setCurrentRule));
    }

    private void openFoodEffects() {
        if (!collectFields() || client == null) return;
        client.setScreen(new FoodEffectsEditorScreen(this, currentRule(), this::setCurrentRule));
    }

    private void openEnchantments() {
        if (!collectFields() || client == null) return;
        client.setScreen(new EnchantmentsEditorScreen(this, currentRule(), this::setCurrentRule));
    }

    private void openAttributes() {
        if (!collectFields() || client == null) return;
        client.setScreen(new AttributesEditorScreen(this, working.itemId, currentRule(), this::setCurrentRule));
    }

    private void openToolRules() {
        if (!collectFields() || client == null) return;
        client.setScreen(new ToolRulesEditorScreen(this, working.itemId, currentRule(), this::setCurrentRule));
    }

    private void save() {
        if (!ClientConfigSession.canEdit()) {
            status = ClientConfigSession.message().isBlank() ? GuiText.string("originlore.editor.cannot_save") : ClientConfigSession.message();
            statusColor = 0xFF7777;
            return;
        }
        if (ClientConfigSession.revision() != baseSnapshot.revision()) {
            status = GuiText.string("originlore.editor.editor_outdated");
            statusColor = 0xFF7777;
            return;
        }
        if (!collectFields()) return;
        String itemId = working.itemId == null ? "" : working.itemId.trim();
        Identifier parsed = Identifier.tryParse(itemId);
        if (parsed == null) {
            status = GuiText.string("originlore.editor.item_id_invalid");
            statusColor = 0xFF7777;
            return;
        }
        if (originalItemId == null && baseSnapshot.items().containsKey(itemId)) {
            status = GuiText.string("originlore.editor.item_exists");
            statusColor = 0xFF7777;
            return;
        }
        for (int index = 0; index < working.sources.size(); index++) {
            SourceRule source = working.sources.get(index);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            double totalWeight = 0.0;
            boolean positiveWeight = false;
            for (Variant variant : source.variants) {
                if (variant.id == null || variant.id.isBlank() || !ids.add(variant.id)) {
                    status = GuiText.string("originlore.editor.source_prefix") + (index + 1) + GuiText.string("originlore.editor.variant_id_error");
                    statusColor = 0xFF7777;
                    return;
                }
                if (!Double.isFinite(variant.weight) || variant.weight < 0) {
                    status = GuiText.string("originlore.editor.source_prefix") + (index + 1) + GuiText.string("originlore.editor.variant_weight_error");
                    statusColor = 0xFF7777;
                    return;
                }
                totalWeight += variant.weight;
                positiveWeight |= variant.weight > 0;
            }
            if (!source.variants.isEmpty() && (!Double.isFinite(totalWeight) || !positiveWeight)) {
                status = GuiText.string("originlore.editor.source_prefix") + (index + 1) + GuiText.string("originlore.editor.source_weight_error");
                statusColor = 0xFF7777;
                return;
            }
        }
        Map<String, ItemEntry> items = new LinkedHashMap<>(baseSnapshot.items());
        if (originalItemId != null && !originalItemId.equals(itemId)) items.remove(originalItemId);
        working.itemId = itemId;
        items.put(itemId, working.copy());
        ConfigSnapshot transaction = baseSnapshot.withItems(items);
        if (ClientConfigSession.submit(transaction, preexisting ? "UPDATE" : "CREATE")) {
            pendingSave = true;
            responseGeneration = ClientConfigSession.generation();
            status = GuiText.string("originlore.editor.saving");
            statusColor = 0xE0B35A;
            rebuildWithoutCollect();
        }
    }

    @Override
    public void tick() {
        super.tick();
        for (IdSuggestionController suggestion : suggestions) suggestion.update();
        if (saveButton != null) saveButton.active = canSaveCurrentRevision();
        if (!pendingSave && ClientConfigSession.canEdit()
                && ClientConfigSession.revision() != baseSnapshot.revision()) {
            status = GuiText.string("originlore.editor.editor_outdated");
            statusColor = 0xFF7777;
        } else if (!pendingSave && !ClientConfigSession.canEdit()
                && !ClientConfigSession.message().isBlank()) {
            status = ClientConfigSession.message();
            statusColor = 0xFF7777;
        }
        if (pendingSave && ClientConfigSession.generation() > responseGeneration) {
            if (ClientConfigSession.lastSavedRevision() == baseSnapshot.revision() + 1) {
                pendingSave = false;
                if (client != null) client.setScreen(parent);
                return;
            }
            responseGeneration = ClientConfigSession.generation();
            if (ClientConfigSession.state() == ClientConfigSession.State.SAVING
                    || ClientConfigSession.state() == ClientConfigSession.State.RECEIVING) return;
            pendingSave = false;
            status = ClientConfigSession.message();
            if (!ClientConfigSession.errors().isEmpty()) status += "  " + ClientConfigSession.errors().getFirst();
            statusColor = 0xFF7777;
            rebuildWithoutCollect();
        }
    }

    private boolean canSaveCurrentRevision() {
        return !pendingSave && ClientConfigSession.canEdit()
                && ClientConfigSession.revision() == baseSnapshot.revision();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (IdSuggestionController suggestion : suggestions) {
            if (suggestion.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (IdSuggestionController suggestion : suggestions) {
            if (suggestion.mouseClicked(mouseX, mouseY, height)) return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        for (IdSuggestionController suggestion : suggestions) suggestion.update();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (IdSuggestionController suggestion : suggestions) {
            if (suggestion.mouseScrolled(mouseX, mouseY, verticalAmount, height)) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
        context.drawText(textRenderer, GuiText.string("originlore.editor.item_id"), rightX, 24, 0xA0A0A0, false);
        if (page == Page.TEXT) {
            context.drawText(textRenderer, GuiText.string("originlore.editor.name"), rightX, formTop + 2, 0xA0A0A0, false);
            context.drawText(textRenderer, "Lore", rightX, formTop + 37, 0xA0A0A0, false);
        } else if (page == Page.NUMBERS) {
            context.drawText(textRenderer, GuiText.string("originlore.editor.damage_rarity"), rightX, formTop + 29, 0xA0A0A0, false);
            context.drawText(textRenderer, GuiText.string("originlore.editor.model"), rightX, formTop + 57, 0xA0A0A0, false);
        } else if (page == Page.FOOD) {
        } else {
            context.drawText(textRenderer, GuiText.string("originlore.editor.component_editor"), rightX, formTop + 2, 0xA0A0A0, false);
        }
        if (variantWeightField != null && sourceIndex >= 0 && sourceIndex < working.sources.size()
                && variantIndex >= 0 && variantIndex < working.sources.get(sourceIndex).variants.size()) {
            SourceRule source = working.sources.get(sourceIndex);
            double totalWeight = source.variants.stream().filter(value -> value != null && value.weight > 0)
                    .mapToDouble(value -> value.weight).sum();
            double currentWeight = source.variants.get(variantIndex).weight;
            if (variantWeightField.isFocused()) {
                try {
                    currentWeight = parseWeight(variantWeightField.getText());
                } catch (IllegalArgumentException ignored) {
                    // Keep the last valid value while the user is typing.
                }
                double editedWeight = currentWeight;
                totalWeight = 0.0;
                for (int index = 0; index < source.variants.size(); index++) {
                    Variant value = source.variants.get(index);
                    if (value == null) continue;
                    double weight = index == variantIndex ? editedWeight : value.weight;
                    if (weight > 0) totalWeight += weight;
                }
            }
            double probability = totalWeight > 0 && currentWeight > 0
                    ? currentWeight / totalWeight * 100.0 : 0.0;
            context.drawText(textRenderer, GuiText.string("originlore.editor.variant_weight"), rightX, 58, 0xA0A0A0, false);
            int summaryY = rightWidth < 300 ? 96 : 74;
            context.drawText(textRenderer, GuiText.string("originlore.editor.total_weight") + formatWeight(totalWeight)
                    + GuiText.string("originlore.editor.probability_separator") + formatWeight(probability) + "%", variantWeightSummaryX, summaryY,
                    0x8FC7FF, false);
        }
        String layer = sourceIndex < 0 ? GuiText.string("originlore.editor.base") : variantIndex < 0
                ? GuiText.string("originlore.editor.source_rule") + (sourceIndex + 1) : GuiText.string("originlore.editor.variant_prefix") + working.sources.get(sourceIndex).variants.get(variantIndex).id;
        context.drawText(textRenderer, layer, rightX, formTop - 31, 0x8FC7FF, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, height - 39, statusColor);
        } else if (ClientConfigSession.revision() >= 0
                && ClientConfigSession.revision() != baseSnapshot.revision()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(GuiText.string("originlore.editor.editor_reopen")),
                    width / 2, height - 39, 0xE0B35A);
        }
        for (IdSuggestionController suggestion : suggestions) suggestion.render(context, textRenderer, height);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private ComponentRule currentRule() {
        if (sourceIndex < 0 || sourceIndex >= working.sources.size()) return working.base;
        SourceRule source = working.sources.get(sourceIndex);
        if (variantIndex < 0 || variantIndex >= source.variants.size()) return source.rule;
        return source.variants.get(variantIndex).rule;
    }

    private void setCurrentRule(ComponentRule replacement) {
        ComponentRule value = replacement == null ? new ComponentRule() : replacement.copy();
        if (sourceIndex < 0 || sourceIndex >= working.sources.size()) working.base = value;
        else if (variantIndex < 0 || variantIndex >= working.sources.get(sourceIndex).variants.size()) {
            working.sources.get(sourceIndex).rule = value;
        } else working.sources.get(sourceIndex).variants.get(variantIndex).rule = value;
    }

    private void rebuildWithoutCollect() {
        clearChildren();
        buildUi();
    }

    private void loadStyle(ComponentRule rule) {
        bold = false;
        italic = false;
        color = "";
        JsonElement style = itemNameMode ? rule.itemNameJson : rule.customNameJson;
        if ((style == null || !style.isJsonObject()) && rule.loreJson != null && !rule.loreJson.isEmpty()) style = rule.loreJson.getFirst();
        if (style != null && style.isJsonObject()) {
            JsonObject object = style.getAsJsonObject();
            bold = object.has("bold") && object.get("bold").getAsBoolean();
            italic = object.has("italic") && object.get("italic").getAsBoolean();
            color = object.has("color") ? object.get("color").getAsString() : "";
        }
        nameDirty = false;
        loreDirty = false;
        styleDirty = false;
    }

    private String extractName(ComponentRule rule) {
        if (itemNameMode) return rule.itemName == null ? extractText(rule.itemNameJson) : rule.itemName;
        if (rule.customName != null) return rule.customName;
        return extractText(rule.customNameJson);
    }

    private String extractLore(ComponentRule rule) {
        if (rule.lore != null) return String.join("\n", rule.lore);
        if (rule.loreJson == null) return "";
        List<String> lines = new ArrayList<>();
        for (JsonElement line : rule.loreJson) lines.add(extractText(line));
        return String.join("\n", lines);
    }

    private static String extractText(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject() && element.getAsJsonObject().has("text")) return element.getAsJsonObject().get("text").getAsString();
        return element.toString();
    }

    private boolean hasStyle() {
        return bold || italic || !color.isEmpty();
    }

    private JsonObject styledText(String value) {
        JsonObject text = new JsonObject();
        text.addProperty("text", value);
        if (bold) text.addProperty("bold", true);
        if (italic) text.addProperty("italic", true);
        if (!color.isEmpty()) text.addProperty("color", color);
        return text;
    }

    private static FoodRule ensureFood(ComponentRule rule) {
        if (rule.food == null) rule.food = new FoodRule();
        return rule.food;
    }

    private static void clearFoodIfEmpty(ComponentRule rule) {
        if (rule.food != null && rule.food.nutrition == null && rule.food.saturation == null
                && rule.food.eatSeconds == null && rule.food.canAlwaysEat == null && rule.food.effects == null
                && rule.food.nutritionRange == null && rule.food.saturationRange == null && rule.food.eatSecondsRange == null
                && rule.food.appendEffects == null) {
            rule.food = null;
        }
    }

    private static boolean containsVariant(SourceRule source, String id) {
        for (Variant variant : source.variants) if (id.equals(variant.id)) return true;
        return false;
    }

    private static String nullable(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer parseInteger(TextFieldWidget field, String label) {
        if (field == null || field.getText().trim().isEmpty()) return null;
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + GuiText.string("originlore.editor.integer_required"));
        }
    }


}
