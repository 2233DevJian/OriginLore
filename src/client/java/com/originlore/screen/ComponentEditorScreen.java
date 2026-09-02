package com.originlore.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.originlore.client.ClientConfigSession;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import com.originlore.config.ItemComponentConfig.FoodRule;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.NumberRange;
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

    private static final String[] COLORS = {"", "white", "gray", "green", "aqua", "gold", "red", "light_purple"};
    private final Screen parent;
    private final String originalItemId;
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
    private TextFieldWidget maxStackField;
    private TextFieldWidget maxDamageField;
    private TextFieldWidget currentDamageField;
    private TextFieldWidget rarityField;
    private TextFieldWidget customModelField;
    private TextFieldWidget attackMinField;
    private TextFieldWidget attackMaxField;
    private TextFieldWidget nutritionField;
    private TextFieldWidget saturationField;
    private TextFieldWidget eatSecondsField;
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
        super(Text.literal(itemId == null ? "新增物品规则" : "编辑物品规则"));
        this.parent = parent;
        this.originalItemId = itemId;
        ConfigSnapshot current = ClientConfigSession.snapshot();
        this.baseSnapshot = current == null ? new ConfigSnapshot(0, Map.of()) : current;
        ItemEntry existing = itemId == null ? null : this.baseSnapshot.items().get(itemId);
        this.working = existing == null ? new ItemEntry(itemId == null ? "" : itemId) : existing.copy();
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

        saveButton = ButtonWidget.builder(Text.literal("保存"), button -> save())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build();
        saveButton.active = canSaveCurrentRevision();
        addDrawableChild(saveButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
    }

    private void buildHierarchy(int left, int panelWidth) {
        int y = 36;
        addDrawableChild(ButtonWidget.builder(Text.literal(sourceIndex < 0 ? "> 基础规则" : "基础规则"), button -> select(-1, -1))
                .dimensions(left, y, panelWidth, 20).build());
        y += 30;

        int visibleSources = Math.max(2, Math.min(5, (height - 235) / 22));
        sourceOffset = Math.max(0, Math.min(sourceOffset, Math.max(0, working.sources.size() - visibleSources)));
        for (int row = 0; row < visibleSources && sourceOffset + row < working.sources.size(); row++) {
            int index = sourceOffset + row;
            SourceRule source = working.sources.get(index);
            String prefix = sourceIndex == index ? "> " : "";
            Text label = Text.literal(prefix + (index + 1) + ". ").append(SourceTypeDisplay.name(source.type));
            addDrawableChild(ButtonWidget.builder(label, button -> select(index, -1))
                    .dimensions(left, y, panelWidth, 20).build());
            y += 22;
        }
        if (working.sources.size() > visibleSources) {
            addDrawableChild(ButtonWidget.builder(Text.literal("^"), button -> {
                sourceOffset = Math.max(0, sourceOffset - 1);
                rebuildWithoutCollect();
            }).dimensions(left, y, panelWidth / 2 - 1, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("v"), button -> {
                sourceOffset = Math.min(Math.max(0, working.sources.size() - visibleSources), sourceOffset + 1);
                rebuildWithoutCollect();
            }).dimensions(left + panelWidth / 2 + 1, y, panelWidth / 2 - 1, 18).build());
            y += 20;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("+ 来源"), button -> addSource())
                .dimensions(left, y, panelWidth / 2 - 1, 20).build());
        ButtonWidget removeSource = ButtonWidget.builder(Text.literal("- 来源"), button -> removeSource())
                .dimensions(left + panelWidth / 2 + 1, y, panelWidth / 2 - 1, 20).build();
        removeSource.active = sourceIndex >= 0;
        addDrawableChild(removeSource);
        y += 31;

        if (sourceIndex >= 0 && sourceIndex < working.sources.size()) {
            List<Variant> variants = working.sources.get(sourceIndex).variants;
            int visibleVariants = Math.max(2, Math.min(4, (height - y - 82) / 22));
            variantOffset = Math.max(0, Math.min(variantOffset, Math.max(0, variants.size() - visibleVariants)));
            for (int row = 0; row < visibleVariants && variantOffset + row < variants.size(); row++) {
                int index = variantOffset + row;
                Variant variant = variants.get(index);
                String prefix = variantIndex == index ? "> " : "";
                double totalWeight = variants.stream().filter(value -> value != null && value.weight > 0)
                        .mapToDouble(value -> value.weight).sum();
                double probability = totalWeight > 0 && variant.weight > 0
                        ? variant.weight / totalWeight * 100.0 : 0.0;
                String label = prefix + variant.id + "  " + formatWeight(variant.weight)
                        + " (" + formatWeight(probability) + "%)";
                addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> select(sourceIndex, index))
                        .dimensions(left, y, panelWidth, 20).build());
                y += 22;
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("+ 变体"), button -> addVariant())
                    .dimensions(left, y, panelWidth / 2 - 1, 20).build());
            ButtonWidget removeVariant = ButtonWidget.builder(Text.literal("- 变体"), button -> removeVariant())
                    .dimensions(left + panelWidth / 2 + 1, y, panelWidth / 2 - 1, 20).build();
            removeVariant.active = variantIndex >= 0;
            addDrawableChild(removeVariant);
        }
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
        itemIdField = new TextFieldWidget(textRenderer, rightX, itemIdY, itemWidth, 20, Text.literal("物品 ID"));
        itemIdField.setMaxLength(256);
        itemIdField.setText(working.itemId == null ? "" : working.itemId);
        itemIdField.setEditable(originalItemId == null);
        itemSuggestions = new IdSuggestionController(itemIdField, () -> ClientConfigSession.catalog().itemIds());
        itemIdField.setChangedListener(value -> itemSuggestions.update());
        suggestions.add(itemSuggestions);

        if (sourceIndex >= 0 && sourceIndex < working.sources.size()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("来源设置"), button -> openSourceMetadata())
                    .dimensions(rightX + itemWidth + 6, itemIdY, settingsWidth, 20).build());
        }
        addDrawableChild(itemIdField);

        if (sourceIndex >= 0 && sourceIndex < working.sources.size()
                && variantIndex >= 0 && variantIndex < working.sources.get(sourceIndex).variants.size()) {
            Variant variant = working.sources.get(sourceIndex).variants.get(variantIndex);
            boolean compactHeader = rightWidth < 300;
            int weightWidth = compactHeader ? rightWidth : Math.max(92, (rightWidth - 6) / 2);
            variantWeightField = new TextFieldWidget(textRenderer, rightX, variantWeightFieldY, weightWidth, 20,
                    Text.literal("当前变体权重"));
            variantWeightField.setMaxLength(32);
            variantWeightField.setPlaceholder(Text.literal("权重，例如 80 或 80%"));
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
        int tabWidth = Math.max(44, (rightWidth - 12) / 4);
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.TEXT ? "[文本]" : "文本"), button -> switchPage(Page.TEXT))
                .dimensions(rightX, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.NUMBERS ? "[数值]" : "数值"), button -> switchPage(Page.NUMBERS))
                .dimensions(rightX + tabWidth + 4, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.FOOD ? "[食物]" : "食物"), button -> switchPage(Page.FOOD))
                .dimensions(rightX + (tabWidth + 4) * 2, y, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == Page.COMPONENTS ? "[能力]" : "能力"),
                button -> switchPage(Page.COMPONENTS))
                .dimensions(rightX + (tabWidth + 4) * 3, y,
                        rightWidth - (tabWidth + 4) * 3, 20).build());
    }

    private void buildTextPage() {
        ComponentRule rule = currentRule();
        loadStyle(rule);
        nameField = new TextFieldWidget(textRenderer, rightX, formTop + 12, rightWidth, 20, Text.literal("自定义名称"));
        nameField.setMaxLength(2048);
        nameField.setText(extractName(rule));
        nameField.setChangedListener(value -> nameDirty = true);
        addDrawableChild(nameField);

        loreField = new LoreTextAreaWidget(textRenderer, rightX, formTop + 47, rightWidth, 40,
                Text.literal("每行一条 Lore"), Text.literal("Lore"));
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
        addDrawableChild(ButtonWidget.builder(Text.literal("颜色: " + (color.isEmpty() ? "继承" : color)), button -> {
            int index = 0;
            for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) index = i;
            color = COLORS[(index + 1) % COLORS.length];
            styleDirty = true;
            button.setMessage(Text.literal("颜色: " + (color.isEmpty() ? "继承" : color)));
        }).dimensions(rightX + 92, y, Math.max(80, rightWidth - 92), 20).build());

    }

    private void buildNumbersPage() {
        ComponentRule rule = currentRule();
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        maxStackField = field(rightX, formTop + 10, half, rule.maxStackSize, "最大堆叠");
        maxDamageField = field(rightX + half + gap, formTop + 10, half, rule.maxDamage, "最大耐久");
        currentDamageField = field(rightX, formTop + 38, half, rule.currentDamage, "当前耐久损耗");
        rarityField = new TextFieldWidget(textRenderer, rightX + half + gap, formTop + 38, half, 20, Text.literal("稀有度"));
        rarityField.setMaxLength(16);
        rarityField.setPlaceholder(Text.literal("COMMON / 0-3"));
        rarityField.setText(rule.rarityName != null ? rule.rarityName : rule.rarity == null ? "" : rule.rarity.toString());
        addDrawableChild(rarityField);
        customModelField = field(rightX, formTop + 66, half, rule.customModelData, "自定义模型数据");
        attackMinField = new TextFieldWidget(textRenderer, rightX, formTop + 94, half, 20, Text.literal("攻击伤害最小值"));
        attackMaxField = new TextFieldWidget(textRenderer, rightX + half + gap, formTop + 94, half, 20, Text.literal("攻击伤害最大值"));
        attackMinField.setPlaceholder(Text.literal("攻击伤害最小值"));
        attackMaxField.setPlaceholder(Text.literal("攻击伤害最大值"));
        attackMinField.setText(rule.attackDamageRange == null ? "" : Double.toString(rule.attackDamageRange.min));
        attackMaxField.setText(rule.attackDamageRange == null ? "" : Double.toString(rule.attackDamageRange.max));
        addDrawableChild(attackMinField);
        addDrawableChild(attackMaxField);
    }

    private void buildFoodPage() {
        ComponentRule rule = currentRule();
        FoodRule food = rule.food;
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        nutritionField = field(rightX, formTop + 12, half, food == null ? null : food.nutrition, "营养值");
        saturationField = new TextFieldWidget(textRenderer, rightX + half + gap, formTop + 12, half, 20, Text.literal("饱和度"));
        saturationField.setPlaceholder(Text.literal("饱和度"));
        saturationField.setText(food == null || food.saturation == null ? "" : food.saturation.toString());
        addDrawableChild(saturationField);
        eatSecondsField = new TextFieldWidget(textRenderer, rightX, formTop + 47, half, 20, Text.literal("食用秒数"));
        eatSecondsField.setPlaceholder(Text.literal("食用秒数"));
        eatSecondsField.setText(food == null || food.eatSeconds == null ? "" : food.eatSeconds.toString());
        addDrawableChild(eatSecondsField);
        Boolean always = food == null ? null : food.canAlwaysEat;
        addDrawableChild(triStateButton("随时食用", always, value -> {
            FoodRule target = ensureFood(rule);
            target.canAlwaysEat = value;
            clearFoodIfEmpty(rule);
        }, rightX + half + gap, formTop + 47, half));
        addDrawableChild(ButtonWidget.builder(Text.literal("编辑食物状态效果"), button -> openFoodEffects())
                .dimensions(rightX, formTop + 82, rightWidth, 20).build());
    }

    private void buildComponentsPage() {
        ComponentRule rule = currentRule();
        int gap = 6;
        int half = (rightWidth - gap) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("附魔与存储附魔"), button -> openEnchantments())
                .dimensions(rightX, formTop + 12, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("属性修饰符"), button -> openAttributes())
                .dimensions(rightX + half + gap, formTop + 12, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("工具挖掘规则"), button -> openToolRules())
                .dimensions(rightX, formTop + 36, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("食物状态效果"), button -> openFoodEffects())
                .dimensions(rightX + half + gap, formTop + 36, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("高级数据组件"), button -> openAdvancedComponents())
                .dimensions(rightX, formTop + 60, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("完整规则 JSON"), button -> openRawEditor())
                .dimensions(rightX + half + gap, formTop + 60, half, 20).build());
        int triWidth = Math.max(52, (rightWidth - 8) / 3);
        addDrawableChild(triStateButton("防火", rule.fireResistant, value -> rule.fireResistant = value,
                rightX, formTop + 84, triWidth));
        addDrawableChild(triStateButton("隐藏提示", rule.hideTooltip, value -> rule.hideTooltip = value,
                rightX + triWidth + 4, formTop + 84, triWidth));
        addDrawableChild(triStateButton("隐藏附加", rule.hideAdditionalTooltip,
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
        return label + ": " + (value == null ? "继承" : value ? "是" : "否");
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
            throw new IllegalArgumentException("变体权重必须是有限的非负数");
        }
        working.sources.get(sourceIndex).variants.get(variantIndex).weight = weight;
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
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void collectText(ComponentRule rule) {
        if (nameField != null && (nameDirty || styleDirty)) {
            String value = nameField.getText();
            if (value.isEmpty()) {
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
        rule.maxStackSize = parseInteger(maxStackField, "最大堆叠");
        rule.maxDamage = parseInteger(maxDamageField, "最大耐久");
        rule.currentDamage = parseInteger(currentDamageField, "当前耐久损耗");
        rule.customModelData = parseInteger(customModelField, "自定义模型数据");
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
        Double min = parseDouble(attackMinField, "攻击伤害最小值");
        Double max = parseDouble(attackMaxField, "攻击伤害最大值");
        if (min == null && max == null) rule.attackDamageRange = null;
        else if (min == null || max == null || min > max) throw new IllegalArgumentException("攻击伤害范围必须填写有效的最小值和最大值");
        else rule.attackDamageRange = new NumberRange(min, max);
    }

    private void collectFood(ComponentRule rule) {
        Integer nutrition = parseInteger(nutritionField, "营养值");
        Float saturation = parseFloat(saturationField, "饱和度");
        Float seconds = parseFloat(eatSecondsField, "食用秒数");
        if (nutrition == null && saturation == null && seconds == null && rule.food == null) return;
        FoodRule food = ensureFood(rule);
        food.nutrition = nutrition;
        food.saturation = saturation;
        food.eatSeconds = seconds;
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
        client.setScreen(new AttributesEditorScreen(this, currentRule(), this::setCurrentRule));
    }

    private void openToolRules() {
        if (!collectFields() || client == null) return;
        client.setScreen(new ToolRulesEditorScreen(this, currentRule(), this::setCurrentRule));
    }

    private void save() {
        if (!ClientConfigSession.canEdit()) {
            status = ClientConfigSession.message().isBlank() ? "当前连接不允许保存" : ClientConfigSession.message();
            statusColor = 0xFF7777;
            return;
        }
        if (ClientConfigSession.revision() != baseSnapshot.revision()) {
            status = "服务器配置已更新；请取消并重新打开编辑器后再修改";
            statusColor = 0xFF7777;
            return;
        }
        if (!collectFields()) return;
        String itemId = working.itemId == null ? "" : working.itemId.trim();
        Identifier parsed = Identifier.tryParse(itemId);
        if (parsed == null) {
            status = "物品 ID 格式无效";
            statusColor = 0xFF7777;
            return;
        }
        if (originalItemId == null && baseSnapshot.items().containsKey(itemId)) {
            status = "该物品已经存在，请返回列表编辑";
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
                    status = "来源 " + (index + 1) + " 的变体 ID 为空或重复";
                    statusColor = 0xFF7777;
                    return;
                }
                if (!Double.isFinite(variant.weight) || variant.weight < 0) {
                    status = "来源 " + (index + 1) + " 的变体权重必须是有限的非负数";
                    statusColor = 0xFF7777;
                    return;
                }
                totalWeight += variant.weight;
                positiveWeight |= variant.weight > 0;
            }
            if (!source.variants.isEmpty() && (!Double.isFinite(totalWeight) || !positiveWeight)) {
                status = "来源 " + (index + 1) + " 至少需要一个大于 0 的变体权重";
                statusColor = 0xFF7777;
                return;
            }
        }
        Map<String, ItemEntry> items = new LinkedHashMap<>(baseSnapshot.items());
        if (originalItemId != null && !originalItemId.equals(itemId)) items.remove(originalItemId);
        working.itemId = itemId;
        items.put(itemId, working.copy());
        ConfigSnapshot transaction = new ConfigSnapshot(baseSnapshot.revision(), items);
        if (ClientConfigSession.submit(transaction, originalItemId == null ? "CREATE" : "UPDATE")) {
            pendingSave = true;
            responseGeneration = ClientConfigSession.generation();
            status = "正在保存...";
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
            status = "服务器配置已更新；请取消并重新打开编辑器后再修改";
            statusColor = 0xFF7777;
        } else if (!pendingSave && !ClientConfigSession.canEdit()
                && !ClientConfigSession.message().isBlank()) {
            status = ClientConfigSession.message();
            statusColor = 0xFF7777;
        }
        if (pendingSave && ClientConfigSession.generation() > responseGeneration) {
            pendingSave = false;
            String kind = ClientConfigSession.responseKind();
            if ("SAVED".equals(kind)) {
                if (client != null) client.setScreen(parent);
                return;
            }
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
        context.drawText(textRenderer, "物品 ID", rightX, 24, 0xA0A0A0, false);
        if (page == Page.TEXT) {
            context.drawText(textRenderer, "名称", rightX, formTop + 2, 0xA0A0A0, false);
            context.drawText(textRenderer, "Lore", rightX, formTop + 37, 0xA0A0A0, false);
        } else if (page == Page.NUMBERS) {
            context.drawText(textRenderer, "堆叠 / 耐久", rightX, formTop + 1, 0xA0A0A0, false);
            context.drawText(textRenderer, "损耗 / 稀有度", rightX, formTop + 29, 0xA0A0A0, false);
            context.drawText(textRenderer, "模型数据", rightX, formTop + 57, 0xA0A0A0, false);
            context.drawText(textRenderer, "攻击伤害随机范围", rightX, formTop + 85, 0xA0A0A0, false);
        } else if (page == Page.FOOD) {
            context.drawText(textRenderer, "营养 / 饱和度", rightX, formTop + 2, 0xA0A0A0, false);
            context.drawText(textRenderer, "食用时间 / 随时食用", rightX, formTop + 37, 0xA0A0A0, false);
        } else {
            context.drawText(textRenderer, "结构化组件编辑器", rightX, formTop + 2, 0xA0A0A0, false);
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
            context.drawText(textRenderer, "当前变体权重", rightX, 58, 0xA0A0A0, false);
            int summaryY = rightWidth < 300 ? 96 : 74;
            context.drawText(textRenderer, "总权重 " + formatWeight(totalWeight)
                    + " · 当前概率 " + formatWeight(probability) + "%", variantWeightSummaryX, summaryY,
                    0x8FC7FF, false);
        }
        String layer = sourceIndex < 0 ? "基础规则" : variantIndex < 0
                ? "来源规则 " + (sourceIndex + 1) : "变体 " + working.sources.get(sourceIndex).variants.get(variantIndex).id;
        context.drawText(textRenderer, layer, rightX, formTop - 31, 0x8FC7FF, false);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, height - 39, statusColor);
        } else if (ClientConfigSession.revision() >= 0
                && ClientConfigSession.revision() != baseSnapshot.revision()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("服务器版本已变化，请重新打开编辑器"),
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
        JsonElement style = rule.customNameJson;
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
                && rule.food.eatSeconds == null && rule.food.canAlwaysEat == null && rule.food.effects == null) {
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
            throw new IllegalArgumentException(label + "必须是整数");
        }
    }

    private static Float parseFloat(TextFieldWidget field, String label) {
        if (field == null || field.getText().trim().isEmpty()) return null;
        try {
            float value = Float.parseFloat(field.getText().trim());
            if (!Float.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有限数值");
        }
    }

    private static Double parseDouble(TextFieldWidget field, String label) {
        if (field == null || field.getText().trim().isEmpty()) return null;
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有限数值");
        }
    }

}
