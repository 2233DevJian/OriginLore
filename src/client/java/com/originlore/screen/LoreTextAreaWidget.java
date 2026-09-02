package com.originlore.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Small self-contained multiline editor used for Lore input.
 *
 * <p>Minecraft's {@code EditBoxWidget} eagerly loads {@code MultilineText},
 * which CAD Editor 0.1.0 targets with an incompatible accessor on 1.21.1.
 * Keeping this widget independent avoids triggering that unrelated mixin while
 * retaining the multiline editing behavior OriginLore needs.</p>
 */
final class LoreTextAreaWidget extends ClickableWidget {
    private static final int PADDING = 4;
    private static final int BACKGROUND_COLOR = 0xFF000000;
    private static final int BORDER_COLOR = 0xFFA0A0A0;
    private static final int FOCUSED_BORDER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int PLACEHOLDER_COLOR = 0xFF707070;
    private static final int SELECTION_COLOR = 0xFF264F78;

    private final TextRenderer textRenderer;
    private final Text placeholder;
    private String text = "";
    private int maxLength = 16_384;
    private int cursor;
    private int selectionAnchor;
    private int firstVisibleLine;
    private Consumer<String> changeListener = value -> { };

    LoreTextAreaWidget(TextRenderer textRenderer, int x, int y, int width, int height,
                       Text placeholder, Text narrationMessage) {
        super(x, y, width, height, narrationMessage);
        this.textRenderer = textRenderer;
        this.placeholder = placeholder;
    }

    void setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        if (text.length() > this.maxLength) {
            text = text.substring(0, this.maxLength);
            cursor = Math.min(cursor, text.length());
            selectionAnchor = Math.min(selectionAnchor, text.length());
            notifyChanged();
        }
    }

    void setText(String value) {
        String normalized = normalize(value == null ? "" : value);
        text = normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
        cursor = text.length();
        selectionAnchor = cursor;
        firstVisibleLine = Math.max(0, lineCount() - visibleLineCount());
    }

    String getText() {
        return text;
    }

    void setChangeListener(Consumer<String> listener) {
        changeListener = listener == null ? value -> { } : listener;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        int clicked = cursorAt(mouseX, mouseY);
        cursor = clicked;
        if (!Screen.hasShiftDown()) selectionAnchor = clicked;
        ensureCursorVisible();
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
        cursor = cursorAt(mouseX, mouseY);
        ensureCursorVisible();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!visible || !isMouseOver(mouseX, mouseY) || verticalAmount == 0.0) return false;
        int maximum = Math.max(0, lineCount() - visibleLineCount());
        firstVisibleLine = Math.max(0, Math.min(maximum,
                firstVisibleLine + (verticalAmount > 0.0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (!isFocused() || !active || !StringHelper.isValidChar(character)) return false;
        replaceSelection(Character.toString(character));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused() || !active) return false;

        if (Screen.isSelectAll(keyCode)) {
            selectionAnchor = 0;
            cursor = text.length();
            ensureCursorVisible();
            return true;
        }
        if (Screen.isCopy(keyCode)) {
            MinecraftClient.getInstance().keyboard.setClipboard(selectedText());
            return true;
        }
        if (Screen.isCut(keyCode)) {
            MinecraftClient.getInstance().keyboard.setClipboard(selectedText());
            if (hasSelection()) replaceSelection("");
            return true;
        }
        if (Screen.isPaste(keyCode)) {
            replaceSelection(MinecraftClient.getInstance().keyboard.getClipboard());
            return true;
        }

        boolean selecting = Screen.hasShiftDown();
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> replaceSelection("\n");
            case GLFW.GLFW_KEY_BACKSPACE -> eraseBackward();
            case GLFW.GLFW_KEY_DELETE -> eraseForward();
            case GLFW.GLFW_KEY_LEFT -> moveCursor(cursor - 1, selecting);
            case GLFW.GLFW_KEY_RIGHT -> moveCursor(cursor + 1, selecting);
            case GLFW.GLFW_KEY_UP -> moveVertically(-1, selecting);
            case GLFW.GLFW_KEY_DOWN -> moveVertically(1, selecting);
            case GLFW.GLFW_KEY_HOME -> moveCursor(Screen.hasControlDown() ? 0 : lineStart(cursor), selecting);
            case GLFW.GLFW_KEY_END -> moveCursor(Screen.hasControlDown() ? text.length() : lineEnd(cursor), selecting);
            default -> { return false; }
        }
        return true;
    }

    private void eraseBackward() {
        if (hasSelection()) {
            replaceSelection("");
        } else if (cursor > 0) {
            selectionAnchor = cursor - 1;
            replaceSelection("");
        }
    }

    private void eraseForward() {
        if (hasSelection()) {
            replaceSelection("");
        } else if (cursor < text.length()) {
            selectionAnchor = cursor + 1;
            replaceSelection("");
        }
    }

    private void replaceSelection(String replacement) {
        String cleaned = normalize(replacement == null ? "" : replacement);
        int start = Math.min(cursor, selectionAnchor);
        int end = Math.max(cursor, selectionAnchor);
        int available = maxLength - (text.length() - (end - start));
        if (available < cleaned.length()) cleaned = cleaned.substring(0, Math.max(0, available));
        String updated = text.substring(0, start) + cleaned + text.substring(end);
        if (updated.equals(text) && start == end) return;
        text = updated;
        cursor = start + cleaned.length();
        selectionAnchor = cursor;
        ensureCursorVisible();
        notifyChanged();
    }

    private static String normalize(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '\n' || StringHelper.isValidChar(character)) result.append(character);
        }
        return result.toString();
    }

    private void notifyChanged() {
        changeListener.accept(text);
    }

    private boolean hasSelection() {
        return cursor != selectionAnchor;
    }

    private String selectedText() {
        int start = Math.min(cursor, selectionAnchor);
        int end = Math.max(cursor, selectionAnchor);
        return text.substring(start, end);
    }

    private void moveCursor(int target, boolean selecting) {
        cursor = Math.max(0, Math.min(text.length(), target));
        if (!selecting) selectionAnchor = cursor;
        ensureCursorVisible();
    }

    private void moveVertically(int direction, boolean selecting) {
        int start = lineStart(cursor);
        int column = cursor - start;
        if (direction < 0) {
            if (start == 0) moveCursor(0, selecting);
            else {
                int previousEnd = start - 1;
                int previousStart = lineStart(previousEnd);
                moveCursor(Math.min(previousStart + column, previousEnd), selecting);
            }
        } else {
            int end = lineEnd(cursor);
            if (end == text.length()) moveCursor(text.length(), selecting);
            else {
                int nextStart = end + 1;
                int nextEnd = lineEnd(nextStart);
                moveCursor(Math.min(nextStart + column, nextEnd), selecting);
            }
        }
    }

    private int lineStart(int position) {
        int safe = Math.max(0, Math.min(text.length(), position));
        return text.lastIndexOf('\n', Math.max(-1, safe - 1)) + 1;
    }

    private int lineEnd(int position) {
        int end = text.indexOf('\n', Math.max(0, Math.min(text.length(), position)));
        return end < 0 ? text.length() : end;
    }

    private int lineOf(int position) {
        int line = 0;
        int safe = Math.max(0, Math.min(text.length(), position));
        for (int index = 0; index < safe; index++) if (text.charAt(index) == '\n') line++;
        return line;
    }

    private int lineCount() {
        return lineOf(text.length()) + 1;
    }

    private int visibleLineCount() {
        return Math.max(1, (height - PADDING * 2) / textRenderer.fontHeight);
    }

    private void ensureCursorVisible() {
        int cursorLine = lineOf(cursor);
        int visibleLines = visibleLineCount();
        if (cursorLine < firstVisibleLine) firstVisibleLine = cursorLine;
        if (cursorLine >= firstVisibleLine + visibleLines) firstVisibleLine = cursorLine - visibleLines + 1;
        firstVisibleLine = Math.max(0, Math.min(firstVisibleLine, Math.max(0, lineCount() - visibleLines)));
    }

    private int cursorAt(double mouseX, double mouseY) {
        int visibleLines = visibleLineCount();
        int relativeLine = (int) Math.floor((mouseY - getY() - PADDING) / textRenderer.fontHeight);
        int targetLine = Math.max(0, Math.min(lineCount() - 1,
                firstVisibleLine + Math.max(0, Math.min(visibleLines - 1, relativeLine))));
        int start = lineStartByNumber(targetLine);
        int end = lineEnd(start);
        String line = text.substring(start, end);
        int horizontalStart = horizontalStart(line, targetLine == lineOf(cursor) ? cursor - lineStart(cursor) : 0);
        int pixel = Math.max(0, (int) mouseX - getX() - PADDING);
        String visible = line.substring(horizontalStart);
        int offset = textRenderer.trimToWidth(visible, pixel).length();
        return Math.min(end, start + horizontalStart + offset);
    }

    private int lineStartByNumber(int targetLine) {
        int start = 0;
        for (int line = 0; line < targetLine; line++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) return text.length();
            start = newline + 1;
        }
        return start;
    }

    private int horizontalStart(String line, int cursorColumn) {
        int innerWidth = Math.max(1, width - PADDING * 2);
        int start = 0;
        int safeColumn = Math.max(0, Math.min(line.length(), cursorColumn));
        while (start < safeColumn && textRenderer.getWidth(line.substring(start, safeColumn)) > innerWidth - 1) start++;
        return start;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int border = isFocused() ? FOCUSED_BORDER_COLOR : BORDER_COLOR;
        context.fill(getX(), getY(), getRight(), getBottom(), border);
        context.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, BACKGROUND_COLOR);

        int innerLeft = getX() + PADDING;
        int innerTop = getY() + PADDING;
        int innerRight = getRight() - PADDING;
        int innerBottom = getBottom() - PADDING;
        context.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
        try {
            if (text.isEmpty() && !isFocused()) {
                context.drawText(textRenderer, placeholder, innerLeft, innerTop, PLACEHOLDER_COLOR, false);
                return;
            }

            int cursorLine = lineOf(cursor);
            int lineStart = lineStartByNumber(firstVisibleLine);
            int visibleLines = visibleLineCount();
            int selectionStart = Math.min(cursor, selectionAnchor);
            int selectionEnd = Math.max(cursor, selectionAnchor);
            for (int row = 0; row < visibleLines && lineStart <= text.length(); row++) {
                int lineEnd = lineEnd(lineStart);
                String line = text.substring(lineStart, lineEnd);
                int lineNumber = firstVisibleLine + row;
                int cursorColumn = lineNumber == cursorLine ? cursor - lineStart : 0;
                int horizontalStart = horizontalStart(line, cursorColumn);
                int y = innerTop + row * textRenderer.fontHeight;

                int selectedFrom = Math.max(selectionStart, lineStart + horizontalStart);
                int selectedTo = Math.min(selectionEnd, lineEnd);
                if (selectedFrom < selectedTo) {
                    int selectionX = innerLeft + textRenderer.getWidth(
                            line.substring(horizontalStart, selectedFrom - lineStart));
                    int selectionRight = innerLeft + textRenderer.getWidth(
                            line.substring(horizontalStart, selectedTo - lineStart));
                    context.fill(selectionX, y, selectionRight, y + textRenderer.fontHeight, SELECTION_COLOR);
                }

                String visibleText = textRenderer.trimToWidth(line.substring(horizontalStart),
                        Math.max(1, innerRight - innerLeft));
                context.drawText(textRenderer, visibleText, innerLeft, y, TEXT_COLOR, false);

                if (isFocused() && lineNumber == cursorLine && (Util.getMeasuringTimeMs() / 300L) % 2L == 0L) {
                    int cursorX = innerLeft + textRenderer.getWidth(
                            line.substring(horizontalStart, Math.max(horizontalStart, cursorColumn)));
                    context.fill(cursorX, y - 1, cursorX + 1, y + textRenderer.fontHeight + 1, TEXT_COLOR);
                }

                if (lineEnd == text.length()) break;
                lineStart = lineEnd + 1;
            }
        } finally {
            context.disableScissor();
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
