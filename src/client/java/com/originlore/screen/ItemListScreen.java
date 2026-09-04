package com.originlore.screen;

import com.originlore.client.ClientConfigSession;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Administrator list backed exclusively by an authoritative server snapshot. */
public final class ItemListScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget searchField;
    private final List<String> filteredItems = new ArrayList<>();
    private int currentPage;
    private long seenGeneration = -1;
    private String pendingAutoOpen;

    public ItemListScreen(Screen parent) {
        this(parent, null);
    }

    /** {@code autoOpenItemId} jumps to that item's editor once the server snapshot arrives. */
    public ItemListScreen(Screen parent, String autoOpenItemId) {
        super(Text.literal("OriginLore 管理"));
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
                Text.literal("搜索物品 ID"));
        searchField.setMaxLength(256);
        searchField.setPlaceholder(Text.literal("搜索物品 ID"));
        searchField.setText(searchText == null ? "" : searchText);
        searchField.setChangedListener(value -> {
            currentPage = 0;
            refreshKeepingSearch();
        });
        addDrawableChild(searchField);

        addDrawableChild(ButtonWidget.builder(Text.literal("同步"), button ->
                ClientConfigSession.requestSnapshot()).dimensions(left + contentWidth - 136, top, 64, 20).build());

        ButtonWidget add = ButtonWidget.builder(Text.literal("+ 新增"), button -> {
            if (client != null && ClientConfigSession.canEdit()) {
                client.setScreen(new ComponentEditorScreen(this, null));
            }
        }).dimensions(left + contentWidth - 68, top, 68, 20).build();
        add.active = ClientConfigSession.canEdit();
        addDrawableChild(add);

        updateFilteredItems();
        int perPage = itemsPerPage();
        int totalPages = Math.max(1, (filteredItems.size() + perPage - 1) / perPage);
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));
        int start = currentPage * perPage;
        int end = Math.min(start + perPage, filteredItems.size());
        int listY = 72;

        for (int index = start; index < end; index++) {
            String itemId = filteredItems.get(index);
            int y = listY + (index - start) * 24;
            ButtonWidget edit = ButtonWidget.builder(Text.literal(itemId), button -> {
                if (client != null && ClientConfigSession.canEdit()) {
                    client.setScreen(new ComponentEditorScreen(this, itemId));
                }
            }).dimensions(left, y, contentWidth - 70, 20).build();
            edit.active = ClientConfigSession.canEdit();
            addDrawableChild(edit);

            ButtonWidget delete = ButtonWidget.builder(Text.literal("删除"), button -> delete(itemId))
                    .dimensions(left + contentWidth - 66, y, 66, 20).build();
            delete.active = ClientConfigSession.canEdit();
            addDrawableChild(delete);
        }

        int navigationY = height - 48;
        ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), button -> {
            currentPage--;
            refreshKeepingSearch();
        }).dimensions(left, navigationY, 36, 20).build();
        previous.active = currentPage > 0;
        addDrawableChild(previous);

        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> {
            currentPage++;
            refreshKeepingSearch();
        }).dimensions(left + contentWidth - 36, navigationY, 36, 20).build();
        next.active = end < filteredItems.size();
        addDrawableChild(next);

        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(center - 45, height - 25, 90, 20).build());
    }

    private void delete(String itemId) {
        ConfigSnapshot current = ClientConfigSession.snapshot();
        if (current == null || !ClientConfigSession.canEdit()) return;
        Map<String, ItemEntry> changed = new LinkedHashMap<>(current.items());
        changed.remove(itemId);
        ClientConfigSession.submit(new ConfigSnapshot(current.revision(), changed), "DELETE");
    }

    private int itemsPerPage() {
        return Math.max(3, (height - 135) / 24);
    }

    private void updateFilteredItems() {
        filteredItems.clear();
        ConfigSnapshot current = ClientConfigSession.snapshot();
        if (current == null) return;
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (String itemId : current.items().keySet()) {
            if (query.isEmpty() || itemId.toLowerCase(Locale.ROOT).contains(query)) filteredItems.add(itemId);
        }
        filteredItems.sort(String::compareTo);
    }

    private void refreshKeepingSearch() {
        String text = searchField == null ? "" : searchField.getText();
        int cursor = searchField == null ? 0 : searchField.getCursor();
        boolean focused = searchField != null && searchField.isFocused();
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
            refreshKeepingSearch();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        String state = switch (ClientConfigSession.state()) {
            case READY -> "版本 " + (ClientConfigSession.revision() < 0 ? "?" : ClientConfigSession.revision());
            case LOADING -> "正在同步...";
            case SAVING -> "正在保存...";
            case DENIED -> "无管理员权限";
            case UNSUPPORTED -> "服务器不支持管理协议";
            case DISCONNECTED -> "未连接";
            case CONFLICT -> "版本冲突";
            case ERROR -> "修改被拒绝";
            case IDLE -> "等待同步";
        };
        int contentWidth = Math.min(520, Math.max(300, width - 32));
        context.drawTextWithShadow(textRenderer, state,
                width / 2 + contentWidth / 2 - textRenderer.getWidth(state), 14,
                ClientConfigSession.canEdit() ? 0x8FE388 : 0xE0B35A);

        int perPage = itemsPerPage();
        int pages = Math.max(1, (filteredItems.size() + perPage - 1) / perPage);
        String page = filteredItems.isEmpty() ? "无配置项"
                : (currentPage + 1) + " / " + pages + "  共 " + filteredItems.size() + " 项";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(page), width / 2, height - 43, 0xA0A0A0);

        String message = ClientConfigSession.message();
        if (message != null && !message.isBlank() && ClientConfigSession.state() != ClientConfigSession.State.READY) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(message), width / 2, 58, 0xFFAA66);
        }
        if (!ClientConfigSession.errors().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(ClientConfigSession.errors().getFirst()), width / 2, height - 60, 0xFF7777);
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
