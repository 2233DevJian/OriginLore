package com.originlore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ConfigSnapshot;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.PresetLibrary;
import com.originlore.config.PresetLanguage;
import com.originlore.config.PresetLibrary.PresetInfo;
import com.originlore.config.PresetMerger;
import com.originlore.config.PresetMerger.MergeResult;
import com.originlore.config.PresetMerger.PresetStatus;
import com.originlore.config.PresetMerger.RevertResult;
import com.originlore.network.OriginLoreNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class OriginLoreCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("originlore")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("reload")
                    .executes(OriginLoreCommand::reload))
                .then(CommandManager.literal("preset")
                    .then(CommandManager.literal("list")
                        .executes(OriginLoreCommand::listPresets))
                    .then(CommandManager.literal("apply")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .suggests(OriginLoreCommand::suggestPresets)
                            .executes(context -> applyPreset(context, false))
                            .then(CommandManager.literal("--overwrite")
                                .executes(context -> applyPreset(context, true)))))
                    .then(CommandManager.literal("revert")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .suggests(OriginLoreCommand::suggestPresets)
                            .executes(OriginLoreCommand::revertPreset))))
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

    private static int listPresets(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<PresetInfo> presets = PresetLibrary.discover();
        if (presets.isEmpty()) {
            source.sendError(Text.literal("[OriginLore] 模组内没有找到任何预设"));
            return 0;
        }
        ConfigSnapshot current = Originlore.getSnapshot();
        if (current == null) return notReady(source);

        for (PresetInfo preset : presets) {
            ConfigSnapshot bundled = loadPreset(source, preset.id());
            if (bundled == null) continue;
            PresetStatus status = PresetMerger.status(current, bundled);
            source.sendFeedback(() -> Text.literal("[OriginLore] " + preset.id()
                    + "（语言 " + preset.language() + "，共 " + preset.itemCount() + " 条）: "
                    + describe(status) + "，其中 " + status.identical() + "/" + status.total() + " 条与当前配置完全一致"),
                    false);
        }
        return presets.size();
    }

    private static int applyPreset(CommandContext<ServerCommandSource> context, boolean overwrite) {
        ServerCommandSource source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        ConfigSnapshot current = Originlore.getSnapshot();
        if (current == null) return notReady(source);
        ConfigSnapshot preset = loadPreset(source, id);
        if (preset == null) return 0;

        MergeResult merged = PresetMerger.merge(current, preset, overwrite);
        if (merged.added() == 0 && merged.overwritten() == 0) {
            source.sendFeedback(() -> Text.literal("[OriginLore] 预设 " + id + " 的 " + merged.skipped()
                    + " 条规则都已存在，配置未改动；需要整条替换请加 --overwrite"), false);
            return 0;
        }
        if (!submit(source, current, merged.items(), "预设 " + id + (overwrite ? " 已覆盖导入" : " 已导入"))) return 0;

        source.sendFeedback(() -> Text.literal("[OriginLore] 预设 " + id + " 导入完成：新增 " + merged.added()
                + " 条，跳过 " + merged.skipped() + " 条，覆盖 " + merged.overwritten() + " 条"), true);
        Originlore.LOGGER.info("OriginLore preset {} applied by {}: added {}, skipped {}, overwritten {}",
                id, source.getName(), merged.added(), merged.skipped(), merged.overwritten());
        return merged.added() + merged.overwritten();
    }

    private static int revertPreset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        ConfigSnapshot current = Originlore.getSnapshot();
        if (current == null) return notReady(source);
        ConfigSnapshot preset = loadPreset(source, id);
        if (preset == null) return 0;

        RevertResult reverted = PresetMerger.revert(current, preset);
        if (reverted.removed() == 0) {
            source.sendFeedback(() -> Text.literal("[OriginLore] 预设 " + id
                    + " 没有可撤销的条目：它尚未应用，或它带来的规则都已被改过（改过的 " + reverted.keptModified()
                    + " 条一律保留），配置未改动"), false);
            return 0;
        }
        if (!submit(source, current, reverted.items(), "预设 " + id + " 已撤销")) return 0;

        source.sendFeedback(() -> Text.literal("[OriginLore] 预设 " + id + " 撤销完成：删除 " + reverted.removed()
                + " 条与预设完全一致的规则，保留改过的 " + reverted.keptModified() + " 条"), true);
        Originlore.LOGGER.info("OriginLore preset {} reverted by {}: removed {}, kept {} modified entries",
                id, source.getName(), reverted.removed(), reverted.keptModified());
        return reverted.removed();
    }

    private static CompletableFuture<Suggestions> suggestPresets(CommandContext<ServerCommandSource> context,
                                                                 SuggestionsBuilder builder) {
        for (PresetInfo preset : PresetLibrary.discover()) builder.suggest(preset.id());
        return builder.buildFuture();
    }

    /** Goes through the editor's own transaction so validation, revision locking and the atomic write stay in one place. */
    private static boolean submit(ServerCommandSource source, ConfigSnapshot current,
                                  Map<String, ItemEntry> items, String broadcastMessage) {
        String json = ItemComponentConfig.snapshotToJson(current.withItems(items));
        Originlore.SubmitResult result = Originlore.submitSnapshot(json, current.revision());
        if (result.success()) {
            OriginLoreNetworking.broadcastSnapshot(source.getServer(), "SNAPSHOT", broadcastMessage);
            return true;
        }
        if (result.conflict()) {
            source.sendError(Text.literal("[OriginLore] 配置刚被其他管理员改动（当前版本 " + result.revision()
                    + "），本次操作已取消，请重新执行"));
        } else {
            source.sendError(Text.literal("[OriginLore] " + result.message()
                    + (result.errors().isEmpty() ? "" : ": " + String.join("; ", result.errors()))));
        }
        Originlore.LOGGER.error("OriginLore preset change rejected: {} {}", result.message(), result.errors());
        return false;
    }

    private static ConfigSnapshot loadPreset(ServerCommandSource source, String id) {
        try {
            String language = id.endsWith("en_us") ? "en_us" : "zh_cn";
            return PresetLanguage.prepare(PresetLibrary.load(id), language);
        } catch (RuntimeException exception) {
            source.sendError(Text.literal("[OriginLore] 预设 " + id + " 不可用，用 /originlore preset list 查看可用预设"));
            Originlore.LOGGER.error("OriginLore preset {} could not be loaded", id, exception);
            return null;
        }
    }

    private static int notReady(ServerCommandSource source) {
        source.sendError(Text.literal("[OriginLore] 服务器配置尚未就绪"));
        return 0;
    }

    private static String describe(PresetStatus status) {
        return switch (status.state()) {
            case NOT_APPLIED -> "未应用";
            case APPLIED -> "已应用";
            case MODIFIED -> "已被修改";
        };
    }
}
