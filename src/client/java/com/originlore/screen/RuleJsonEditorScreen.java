package com.originlore.screen;

import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ComponentRule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** Transactional raw JSON editor for one base, source, or variant rule. */
public final class RuleJsonEditorScreen extends Screen {
    private final Screen parent;
    private final Consumer<ComponentRule> onApply;
    private String draft;
    private LoreTextAreaWidget editor;
    private String status = "";

    public RuleJsonEditorScreen(Screen parent, ComponentRule rule, Consumer<ComponentRule> onApply) {
        super(Text.literal("完整规则 JSON"));
        this.parent = parent;
        this.onApply = onApply;
        this.draft = ItemComponentConfig.componentRuleToJson(rule == null ? new ComponentRule() : rule.copy());
    }

    @Override
    protected void init() {
        int editorWidth = Math.min(700, Math.max(280, width - 24));
        int left = (width - editorWidth) / 2;
        int editorTop = 34;
        int editorHeight = Math.max(80, height - 100);

        editor = new LoreTextAreaWidget(textRenderer, left, editorTop, editorWidth, editorHeight,
                Text.literal("ComponentRule JSON"), Text.literal("ComponentRule JSON"));
        editor.setMaxLength(262_144);
        editor.setText(draft);
        editor.setChangeListener(value -> {
            draft = value;
            status = "";
        });
        addDrawableChild(editor);

        addDrawableChild(ButtonWidget.builder(Text.literal("应用"), button -> apply())
                .dimensions(width / 2 - 96, height - 27, 92, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(width / 2 + 4, height - 27, 92, 20).build());
        setInitialFocus(editor);
    }

    private void apply() {
        try {
            ComponentRule parsed = ItemComponentConfig.componentRuleFromJson(draft);
            onApply.accept(parsed.copy());
            if (client != null) client.setScreen(parent);
        } catch (RuntimeException exception) {
            status = compactMessage(exception);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && hasControlDown()) {
            apply();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        if (!status.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2,
                    height - 44, 0xFF7777);
        }
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
