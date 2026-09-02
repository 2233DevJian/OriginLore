package com.originlore.screen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Small registry-ID completion popup with vanilla-style keyboard behavior. */
final class IdSuggestionController {
    private static final int ROW_HEIGHT = 14;
    private static final int MAX_VISIBLE = 7;
    private static final int SCREEN_MARGIN = 4;
    private static final int BOTTOM_RESERVED = 30;
    private static final int POPUP_Z = 400;
    private final TextFieldWidget field;
    private final Supplier<List<String>> values;
    private final List<String> matches = new ArrayList<>();
    private int selected;
    private int scroll;
    private boolean visible;

    IdSuggestionController(TextFieldWidget field, Supplier<List<String>> values) {
        this.field = field;
        this.values = values;
    }

    void update() {
        if (!field.isFocused()) {
            visible = false;
            return;
        }
        String query = field.getText().trim().toLowerCase(Locale.ROOT);
        matches.clear();
        if (!query.isEmpty()) {
            for (String value : values.get()) {
                if (value.toLowerCase(Locale.ROOT).startsWith(query)) matches.add(value);
                if (matches.size() >= 256) break;
            }
        }
        visible = !matches.isEmpty() && !(matches.size() == 1 && matches.getFirst().equals(field.getText()));
        selected = Math.max(0, Math.min(selected, matches.size() - 1));
        ensureVisible();
    }

    boolean keyPressed(int keyCode) {
        if (!visible || !field.isFocused()) return false;
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            selected = Math.min(matches.size() - 1, selected + 1);
            ensureVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            selected = Math.max(0, selected - 1);
            ensureVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER) {
            confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            visible = false;
            return true;
        }
        return false;
    }

    boolean mouseClicked(double mouseX, double mouseY, int screenHeight) {
        if (!visible) return false;
        PopupLayout layout = popupLayout(screenHeight);
        if (mouseX < field.getX() || mouseX >= field.getX() + field.getWidth()
                || mouseY < layout.top || mouseY >= layout.bottom()) return false;
        int index = scroll + (int) ((mouseY - layout.top) / ROW_HEIGHT);
        if (index >= 0 && index < matches.size()) {
            selected = index;
            confirm();
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount, int screenHeight) {
        if (!visible || verticalAmount == 0) return false;
        PopupLayout layout = popupLayout(screenHeight);
        boolean overPopup = mouseX >= field.getX() && mouseX < field.getX() + field.getWidth()
                && mouseY >= layout.top && mouseY < layout.bottom();
        boolean overField = mouseX >= field.getX() && mouseX < field.getX() + field.getWidth()
                && mouseY >= field.getY() && mouseY < field.getY() + field.getHeight();
        if (!overPopup && !overField) return false;
        selected = Math.max(0, Math.min(matches.size() - 1,
                selected + (verticalAmount > 0 ? -1 : 1)));
        ensureVisible(layout.count);
        return true;
    }

    void render(DrawContext context, TextRenderer renderer, int screenHeight) {
        if (!visible) return;
        PopupLayout layout = popupLayout(screenHeight);
        int left = field.getX();
        int right = left + field.getWidth();
        context.getMatrices().push();
        context.draw(() -> renderBackground(context, layout, left, right));
        context.getMatrices().translate(0.0F, 0.0F, POPUP_Z);
        renderLabels(context, renderer, layout, left);
        context.draw();
        context.getMatrices().pop();
    }

    private void renderBackground(DrawContext context, PopupLayout layout, int left, int right) {
        context.fill(left - 1, layout.top - 1, right + 1, layout.bottom() + 1, POPUP_Z, 0xFFB0B0B0);
        context.fill(left, layout.top, right, layout.bottom(), POPUP_Z, 0xFF101010);
        for (int row = 0; row < layout.count; row++) {
            int index = scroll + row;
            int y = layout.top + row * ROW_HEIGHT;
            if (index == selected) {
                context.fill(left, y, right, y + ROW_HEIGHT, POPUP_Z, 0xFF3A5F84);
            } else if (row > 0) {
                context.fill(left, y, right, y + 1, POPUP_Z, 0xFF242424);
            }
        }
    }

    private void renderLabels(DrawContext context, TextRenderer renderer, PopupLayout layout, int left) {
        for (int row = 0; row < layout.count; row++) {
            int index = scroll + row;
            int y = layout.top + row * ROW_HEIGHT;
            String text = renderer.trimToWidth(matches.get(index), field.getWidth() - 8);
            int textY = y + Math.max(1, (ROW_HEIGHT - renderer.fontHeight) / 2);
            context.drawText(renderer, Text.literal(text), left + 4, textY,
                    index == selected ? 0xFFFFFF : 0xD0D0D0, false);
        }
    }

    private void confirm() {
        if (selected < 0 || selected >= matches.size()) return;
        field.setText(matches.get(selected));
        field.setCursorToEnd(false);
        visible = false;
    }

    private void ensureVisible() {
        ensureVisible(MAX_VISIBLE);
    }

    private void ensureVisible(int visibleRows) {
        visibleRows = Math.max(1, visibleRows);
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visibleRows) scroll = selected - visibleRows + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, matches.size() - visibleRows)));
    }

    private PopupLayout popupLayout(int screenHeight) {
        int desiredRows = Math.min(MAX_VISIBLE, matches.size());
        int belowTop = field.getY() + field.getHeight() + 1;
        int bottomLimit = Math.max(SCREEN_MARGIN + ROW_HEIGHT, screenHeight - BOTTOM_RESERVED);
        int belowRows = Math.max(0, (bottomLimit - belowTop) / ROW_HEIGHT);
        int aboveBottom = field.getY() - 1;
        int aboveRows = Math.max(0, (aboveBottom - SCREEN_MARGIN) / ROW_HEIGHT);
        boolean openBelow = belowRows >= desiredRows || belowRows >= aboveRows;
        int capacity = Math.min(desiredRows, openBelow ? belowRows : aboveRows);
        if (capacity <= 0) {
            capacity = Math.min(desiredRows, 1);
            openBelow = true;
        }
        ensureVisible(capacity);
        int count = Math.min(capacity, matches.size() - scroll);
        int top = openBelow ? belowTop : aboveBottom - count * ROW_HEIGHT;
        int maxTop = Math.max(SCREEN_MARGIN, bottomLimit - count * ROW_HEIGHT);
        top = Math.max(SCREEN_MARGIN, Math.min(top, maxTop));
        return new PopupLayout(top, count);
    }

    private record PopupLayout(int top, int count) {
        int bottom() {
            return top + count * ROW_HEIGHT;
        }
    }
}
