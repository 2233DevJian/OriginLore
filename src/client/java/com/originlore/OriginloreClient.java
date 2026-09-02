package com.originlore.client;

import com.originlore.screen.ItemListScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
                if (client.currentScreen == null) {
                    ClientConfigSession.requestSnapshot();
                    client.setScreen(new ItemListScreen(null));
                }
            }
        });
    }
}
