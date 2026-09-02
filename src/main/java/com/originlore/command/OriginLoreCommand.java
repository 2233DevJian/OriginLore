package com.originlore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.network.OriginLoreNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class OriginLoreCommand {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("originlore")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("reload")
                    .executes(OriginLoreCommand::reload))
        );
    }
    
    private static int reload(CommandContext<ServerCommandSource> context) {
        ItemComponentConfig.LoadResult result = Originlore.reloadFromDisk();
        if (!result.success()) {
            context.getSource().sendError(Text.literal("[OriginLore] 配置重载失败，仍在使用上一份有效配置: "
                    + result.message()));
            Originlore.LOGGER.error("OriginLore reload requested by {} failed: {}",
                    context.getSource().getName(), result.message());
            return 0;
        }
        context.getSource().sendFeedback(
            () -> Text.literal("[OriginLore] 配置已重新加载，版本 " + result.revision()),
            true
        );
        OriginLoreNetworking.broadcastSnapshot(context.getSource().getServer(), "RELOADED", "配置已从磁盘重载");
        return 1;
    }
}
