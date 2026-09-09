package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.client.GuiText;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Administrator list backed exclusively by an authoritative server snapshot. */
public final class ItemListScreen extends Screen {
    private static final String ALL_CATEGORIES = "*";
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_INNER_HEIGHT = 20;
    private static final int DELETE_WIDTH = 56;
    private static final int CATEGORY_WIDTH = 150;
    private static final int LIST_TOP = 92;
    private static final int LIST_BOTTOM_MARGIN = 46;

    private final Screen parent;
    private TextFieldWidget searchField;
    private ItemRowList list;
    private ChoiceDropdownController categoryDropdown;
    private final List<String> filteredItems = new ArrayList<>();
    private String categoryFilter = ALL_CATEGORIES;
    private int totalCount;
    private long seenGeneration = -1;
    private String pendingAutoOpen;
    private double restoredScroll;
    private String restoredScrollKey;
    private Text pendingTooltip;
    private int tooltipX;
    private int tooltipY;

    public ItemListScreen(Screen parent) {
        this(parent, null);
    }

    /** {@code autoOpenItemId} jumps to that item's editor once the server snapshot arrives. */
    public ItemListScreen(Screen parent, String autoOpenItemId) {
        super(GuiText.text("originlore.gui.title"));
        this.parent = parent;
        this.pendingAutoOpen = autoOpenItemId;
    }

    @Override
    protected void init() {
        if (ClientConfigSession.state() == ClientConfigSession.State.IDLE) {
            ClientConfigSession.requestSnapshot();
        }
        seenGeneration = ClientConfigSession.generation();
        buildUi("");
    }

    private void buildUi(String searchText) {
        int center = width / 2;
        int contentWidth = Math.min(520, Math.max(300, width - 32));
        int left = center - contentWidth / 2;
        int top = 34;

        searchField = new TextFieldWidget(textRenderer, left, top, contentWidth - 142, 20,
                GuiText.text("originlore.gui.search_hint"));
        searchField.setMaxLength(256);
        searchField.setPlaceholder(GuiText.text("originlore.gui.search_hint"));
        searchField.setText(searchText == null ? "" : searchText);
        searchField.setChangedListener(value -> refreshKeepingSearch(false));
        addDrawableChild(searchField);

        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.gui.sync"), button ->
                ClientConfigSession.requestSnapshot()).dimensions(left + contentWidth - 136, top, 64, 20).build());

        ButtonWidget add = ButtonWidget.builder(GuiText.text("originlore.gui.add"), button -> openEditor(null))
                .dimensions(left + contentWidth - 68, top, 68, 20).build();
        add.active = ClientConfigSession.canEdit();
        addDrawableChild(add);

        ButtonWidget categoryButton = ButtonWidget.builder(categoryLabel(categoryFilter),
                button -> categoryDropdown.toggle()).dimensions(left, 58, CATEGORY_WIDTH, 20).build();
        addDrawableChild(categoryButton);
        categoryDropdown = new ChoiceDropdownController(categoryButton, categoryChoices(), () -> categoryFilter,
                value -> {
                    categoryFilter = value;
                    refreshKeepingSearch(false);
                }, this::categoryLabel);

        ButtonWidget languageButton = ButtonWidget.builder(Text.literal("zh_cn".equals(GuiText.language())
                ? "English" : GuiText.string("originlore.editor.chinese")), button -> ClientConfigSession.changeLanguage(GuiText.otherLanguage()))
                .dimensions(left + contentWidth - 100, 58, 100, 20).build();
        languageButton.active = ClientConfigSession.canEdit();
        addDrawableChild(languageButton);

        updateFilteredItems();
        int listHeight = Math.max(ROW_HEIGHT * 2, height - LIST_BOTTOM_MARGIN - LIST_TOP);
        list = new ItemRowList(contentWidth, listHeight, left, LIST_TOP);
        list.setItems(filteredItems);
        if (restoredScrollKey != null && restoredScrollKey.equals(filterKey(searchText))) {
            list.setScrollAmount(restoredScroll);
        }
        addDrawableChild(list);

        addDrawableChild(ButtonWidget.builder(GuiText.text("originlore.gui.close"), button -> close())
                .dimensions(center - 45, height - 25, 90, 20).build());
    }

    private void openEditor(String itemId) {
        if (client != null && ClientConfigSession.canEdit()) {
            client.setScreen(new ComponentEditorScreen(this, itemId));
        }
    }

    private void delete(String itemId) {
        ConfigSnapshot current = ClientConfigSession.snapshot();
        if (current == null || !ClientConfigSession.canEdit()) return;
        Map<String, ItemEntry> changed = new LinkedHashMap<>(current.items());
        changed.remove(itemId);
        ClientConfigSession.submit(current.withItems(changed), "DELETE");
    }

    private Text categoryLabel(String key) {
        return ALL_CATEGORIES.equals(key)
                ? GuiText.text("originlore.category.all")
                : ItemDisplayName.categoryLabel(key);
    }

    /** Every category the current snapshot actually uses, so the dropdown never offers an empty filter. */
    private List<String> categoryChoices() {
        Set<String> present = new LinkedHashSet<>();
        ConfigSnapshot current = ClientConfigSession.snapshot();
        if (current != null) {
            for (String itemId : current.items().keySet()) present.add(ItemDisplayName.categoryOf(itemId));
        }

        List<String> named = new ArrayList<>();
        for (String key : present) {
            if (!key.isEmpty()) named.add(key);
        }
        Collator collator = collator();
        named.sort((first, second) -> {
            int order = collator.compare(categoryLabel(first).getString(), categoryLabel(second).getString());
            return order != 0 ? order : first.compareTo(second);
        });

        List<String> choices = new ArrayList<>(named.size() + 2);
        choices.add(ALL_CATEGORIES);
        choices.addAll(named);
        if (present.contains(ItemDisplayName.UNCATEGORIZED)) choices.add(ItemDisplayName.UNCATEGORIZED);
        return choices;
    }

    private void updateFilteredItems() {
        filteredItems.clear();
        totalCount = 0;
        ConfigSnapshot current = ClientConfigSession.snapshot();
        if (current == null) return;
        totalCount = current.items().size();
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (String itemId : current.items().keySet()) {
            if (!ALL_CATEGORIES.equals(categoryFilter)
                    && !categoryFilter.equals(ItemDisplayName.categoryOf(itemId))) {
                continue;
            }
            if (query.isEmpty() || itemId.toLowerCase(Locale.ROOT).contains(query)
                    || ItemDisplayName.plainOf(itemId).toLowerCase(Locale.ROOT).contains(query)) {
                filteredItems.add(itemId);
            }
        }

        // Collation follows the client language so a Chinese list sorts by pinyin rather than by code point.
        Collator collator = collator();
        filteredItems.sort((first, second) -> {
            int order = collator.compare(ItemDisplayName.plainOf(first), ItemDisplayName.plainOf(second));
            return order != 0 ? order : first.compareTo(second);
        });
    }

    private Collator collator() {
        return Collator.getInstance(currentLocale());
    }

    private Locale currentLocale() {
        String code = GuiText.language();
        return code == null ? Locale.ROOT : Locale.forLanguageTag(code.replace('_', '-'));
    }

    private String filterKey(String searchText) {
        return (searchText == null ? "" : searchText) + '\u0000' + categoryFilter;
    }

    /**
     * Rebuilds the widgets without losing what the administrator typed. Scroll position is only carried over for a
     * data refresh; a new search or category is meant to be read from the top.
     */
    private void refreshKeepingSearch(boolean preserveScroll) {
        String text = searchField == null ? "" : searchField.getText();
        int cursor = searchField == null ? 0 : searchField.getCursor();
        boolean focused = searchField != null && searchField.isFocused();
        if (preserveScroll && list != null) {
            restoredScroll = list.getScrollAmount();
            restoredScrollKey = filterKey(text);
        } else {
            restoredScrollKey = null;
        }
        clearChildren();
        buildUi(text);
        if (focused) {
            searchField.setFocused(true);
            setFocused(searchField);
            searchField.setCursor(cursor, false);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (tryAutoOpen()) return;
        if (seenGeneration != ClientConfigSession.generation()) {
            seenGeneration = ClientConfigSession.generation();
            refreshKeepingSearch(true);
        }
    }

    private boolean tryAutoOpen() {
        if (pendingAutoOpen == null || !ClientConfigSession.canEdit()) return false;
        String itemId = pendingAutoOpen;
        pendingAutoOpen = null;
        if (client != null) client.setScreen(new ComponentEditorScreen(null, itemId));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (categoryDropdown != null && categoryDropdown.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (categoryDropdown != null && categoryDropdown.mouseClicked(mouseX, mouseY, height)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (categoryDropdown != null
                && categoryDropdown.mouseScrolled(mouseX, mouseY, verticalAmount, height)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        pendingTooltip = null;
        renderBackground(context, mouseX, mouseY, delta);
        boolean overDropdown = categoryDropdown != null && categoryDropdown.isMouseOverPopup(mouseX, mouseY, height);
        // Covered widgets must not hover or queue tooltips beneath the popup.
        super.render(context, overDropdown ? -1 : mouseX, overDropdown ? -1 : mouseY, delta);

        context.drawTextWithShadow(textRenderer, GuiText.text("originlore.gui.title"),
                width / 2 - Math.min(520, Math.max(300, width - 32)) / 2, 14, 0xFFFFFF);
        Text state = switch (ClientConfigSession.state()) {
            case READY -> Text.empty();
            case LOADING -> GuiText.text("originlore.gui.state.loading");
            case RECEIVING -> GuiText.text("originlore.gui.state.receiving");
            case SAVING -> GuiText.text("originlore.gui.state.saving");
            case DENIED -> GuiText.text("originlore.gui.state.denied");
            case UNSUPPORTED -> GuiText.text("originlore.gui.state.unsupported");
            case DISCONNECTED -> GuiText.text("originlore.gui.state.disconnected");
            case CONFLICT -> GuiText.text("originlore.gui.state.conflict");
            case ERROR -> GuiText.text("originlore.gui.state.error");
            case IDLE -> GuiText.text("originlore.gui.state.idle");
        };
        int contentWidth = Math.min(520, Math.max(300, width - 32));
        context.drawTextWithShadow(textRenderer, state,
                width / 2 + contentWidth / 2 - textRenderer.getWidth(state), 14,
                ClientConfigSession.canEdit() ? 0x8FE388 : 0xE0B35A);

        Text summary = filteredItems.isEmpty() ? GuiText.text("originlore.gui.empty")
                : filteredItems.size() == totalCount
                ? GuiText.text("originlore.gui.count", totalCount)
                : GuiText.text("originlore.gui.count_filtered", filteredItems.size(), totalCount);
        context.drawCenteredTextWithShadow(textRenderer, summary, width / 2, height - 43, 0xA0A0A0);

        String message = ClientConfigSession.message();
        if (message != null && !message.isBlank() && ClientConfigSession.state() != ClientConfigSession.State.READY) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(message), width / 2, 80, 0xFFAA66);
        }
        if (!ClientConfigSession.errors().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(ClientConfigSession.errors().getFirst()), width / 2, height - 60, 0xFF7777);
        }

        if (categoryDropdown != null) categoryDropdown.render(context, textRenderer, height);
        // Rows render inside the list's scissor, so the id tooltip has to wait until clipping is lifted.
        if (pendingTooltip != null) context.drawTooltip(textRenderer, pendingTooltip, tooltipX, tooltipY);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private final class ItemRowList extends EntryListWidget<ItemListScreen.ItemRowList.ItemRow> {
        private final int rowWidth;

        ItemRowList(int width, int height, int x, int y) {
            super(ItemListScreen.this.client, width, height, y, ROW_HEIGHT);
            this.rowWidth = width - 24;
            setDimensionsAndPosition(width, height, x, y);
        }

        void setItems(List<String> itemIds) {
            List<ItemRow> rows = new ArrayList<>(itemIds.size());
            for (String itemId : itemIds) rows.add(new ItemRow(itemId));
            replaceEntries(rows);
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }

        @Override
        protected int getScrollbarX() {
            return getX() + getWidth() - 10;
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        }

        private final class ItemRow extends Entry<ItemRow> {
            private final String itemId;

            ItemRow(String itemId) {
                this.itemId = itemId;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean hovered, float tickDelta) {
                boolean editable = ClientConfigSession.canEdit();
                int deleteLeft = x + entryWidth - DELETE_WIDTH;
                boolean overDelete = hovered && editable && mouseX >= deleteLeft;
                int bottom = y + ROW_INNER_HEIGHT;

                context.fill(x, y, deleteLeft - 2, bottom, hovered && !overDelete ? 0x50FFFFFF : 0x28FFFFFF);
                if (editable) context.fill(deleteLeft, y, x + entryWidth, bottom, overDelete ? 0x60FF6666 : 0x28FFFFFF);

                ItemStack icon = ItemDisplayName.iconOf(itemId);
                if (!icon.isEmpty()) context.drawItem(icon, x + 3, y + 2);
                int nameWidth = Math.max(20, deleteLeft - x - 26);
                String name = textRenderer.trimToWidth(ItemDisplayName.plainOf(itemId), nameWidth);
                context.drawTextWithShadow(textRenderer, Text.literal(name), x + 22, y + 6,
                        editable ? 0xFFFFFF : 0xA0A0A0);
                if (editable) {
                    context.drawCenteredTextWithShadow(textRenderer, GuiText.text("originlore.gui.delete"),
                            deleteLeft + DELETE_WIDTH / 2, y + 6, overDelete ? 0xFFDDDD : 0xFF9999);
                }

                if (hovered && !overDelete) {
                    pendingTooltip = Text.literal(itemId);
                    tooltipX = mouseX;
                    tooltipY = mouseY;
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0 || !ClientConfigSession.canEdit()) return false;
                if (mouseX >= getRowLeft() + getRowWidth() - DELETE_WIDTH) {
                    delete(itemId);
                    return true;
                }
                openEditor(itemId);
                return true;
            }
        }
    }
}
