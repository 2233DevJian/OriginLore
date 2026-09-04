package com.originlore.client;

import com.originlore.screen.ComponentEditorScreen;
import com.originlore.screen.ItemListScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.lwjgl.glfw.GLFW;

public class OriginloreClient implements ClientModInitializer {
    
    private static KeyBinding openGuiKey;
    
    @Override
    public void onInitializeClient() {
        ClientConfigSession.initialize();

        // 注册快捷键
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.originlore.open_gui",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "category.originlore"
        ));
        
        // 注册快捷键事件
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen != null) continue;
                open(client);
            }
        });
    }

    private static void open(MinecraftClient client) {
        String heldItemId = heldItemId(client);
        // 快照已就绪时直接开编辑器；否则编辑器会抓到 revision 0 的空快照，保存键永久失效
        if (heldItemId != null && ClientConfigSession.canEdit()) {
            client.setScreen(new ComponentEditorScreen(null, heldItemId));
            return;
        }
        ClientConfigSession.requestSnapshot();
        client.setScreen(new ItemListScreen(null, heldItemId));
    }

    private static String heldItemId(MinecraftClient client) {
        if (client.player == null) return null;
        ItemStack held = client.player.getMainHandStack();
        return held.isEmpty() ? null : Registries.ITEM.getId(held.getItem()).toString();
    }
}
