package com.originlore.screen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Compact keyboard-accessible dropdown anchored to a normal button. */
final class ChoiceDropdownController {
    private static final int ROW_HEIGHT = 14;
    private static final int MAX_VISIBLE = 10;
    private static final int SCREEN_MARGIN = 4;
    private static final int BOTTOM_RESERVED = 30;
    private static final int POPUP_Z = 400;

    private final ButtonWidget anchor;
    private final List<String> values;
    private final Supplier<String> current;
    private final Consumer<String> onSelected;
    private final Function<String, Text> label;
    private int selected;
    private int scroll;
    private boolean visible;

    ChoiceDropdownController(ButtonWidget anchor, List<String> values, Supplier<String> current,
                             Consumer<String> onSelected, Function<String, Text> label) {
        this.anchor = anchor;
        this.values = List.copyOf(values);
        this.current = current;
        this.onSelected = onSelected;
        this.label = label;
        syncSelection();
    }

    void toggle() {
        if (values.isEmpty()) return;
        visible = !visible;
        if (visible) syncSelection();
    }

    void close() {
        visible = false;
    }

    boolean keyPressed(int keyCode) {
        if (!visible) return false;
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            selected = Math.min(values.size() - 1, selected + 1);
            ensureVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            selected = Math.max(0, selected - 1);
            ensureVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_TAB) {
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
        if (mouseX >= anchor.getX() && mouseX < anchor.getX() + anchor.getWidth()
                && mouseY >= layout.top && mouseY < layout.bottom()) {
            selected = scroll + (int) ((mouseY - layout.top) / ROW_HEIGHT);
            confirm();
            return true;
        }
        if (mouseX < anchor.getX() || mouseX >= anchor.getX() + anchor.getWidth()
                || mouseY < anchor.getY() || mouseY >= anchor.getY() + anchor.getHeight()) {
            visible = false;
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount, int screenHeight) {
        if (!visible || verticalAmount == 0) return false;
        PopupLayout layout = popupLayout(screenHeight);
        boolean overPopup = mouseX >= anchor.getX() && mouseX < anchor.getX() + anchor.getWidth()
                && mouseY >= layout.top && mouseY < layout.bottom();
        boolean overAnchor = mouseX >= anchor.getX() && mouseX < anchor.getX() + anchor.getWidth()
                && mouseY >= anchor.getY() && mouseY < anchor.getY() + anchor.getHeight();
        if (!overPopup && !overAnchor) return false;
        selected = Math.max(0, Math.min(values.size() - 1,
                selected + (verticalAmount > 0 ? -1 : 1)));
        ensureVisible(layout.count);
        return true;
    }

    void render(DrawContext context, TextRenderer renderer, int screenHeight) {
        if (!visible || values.isEmpty()) return;
        PopupLayout layout = popupLayout(screenHeight);
        int left = anchor.getX();
        int right = anchor.getX() + anchor.getWidth();
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
            String text = renderer.trimToWidth(label.apply(values.get(index)).getString(), anchor.getWidth() - 8);
            int textY = y + Math.max(1, (ROW_HEIGHT - renderer.fontHeight) / 2);
            context.drawText(renderer, Text.literal(text), left + 4, textY,
                    index == selected ? 0xFFFFFF : 0xD0D0D0, false);
        }
    }

    private void confirm() {
        if (selected < 0 || selected >= values.size()) return;
        String value = values.get(selected);
        visible = false;
        anchor.setMessage(label.apply(value));
        onSelected.accept(value);
    }

    private void syncSelection() {
        int index = values.indexOf(current.get());
        selected = index < 0 ? 0 : index;
        ensureVisible();
    }

    private void ensureVisible() {
        ensureVisible(MAX_VISIBLE);
    }

    private void ensureVisible(int visibleRows) {
        visibleRows = Math.max(1, visibleRows);
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visibleRows) scroll = selected - visibleRows + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, values.size() - visibleRows)));
    }

    private PopupLayout popupLayout(int screenHeight) {
        int desiredRows = Math.min(MAX_VISIBLE, values.size());
        int belowTop = anchor.getY() + anchor.getHeight() + 1;
        int bottomLimit = Math.max(SCREEN_MARGIN + ROW_HEIGHT, screenHeight - BOTTOM_RESERVED);
        int belowRows = Math.max(0, (bottomLimit - belowTop) / ROW_HEIGHT);
        int aboveBottom = anchor.getY() - 1;
        int aboveRows = Math.max(0, (aboveBottom - SCREEN_MARGIN) / ROW_HEIGHT);
        boolean openBelow = belowRows >= desiredRows || belowRows >= aboveRows;
        int capacity = Math.min(desiredRows, openBelow ? belowRows : aboveRows);
        if (capacity <= 0) {
            capacity = Math.min(desiredRows, 1);
            openBelow = true;
        }
        ensureVisible(capacity);
        int count = Math.min(capacity, values.size() - scroll);
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
