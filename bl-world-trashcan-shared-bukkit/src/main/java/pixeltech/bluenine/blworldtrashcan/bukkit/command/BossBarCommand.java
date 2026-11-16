package pixeltech.bluenine.blworldtrashcan.bukkit.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.notify.BossBarPreferenceService;

import java.io.IOException;
import java.util.Locale;

/** 五个平台共用的个人 BossBar 指令处理。 */
public final class BossBarCommand {
    private BossBarCommand() {
    }

    public static boolean execute(CommandSender sender, String[] args,
                                  BossBarPreferenceService preferences, BukkitMessageService messages) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.text("command.only-player", "{prefix}&c该命令只能由玩家执行。"));
            return true;
        }
        if (!sender.isOp() && !sender.hasPermission("WorldListTrashCan.BossBar")) {
            sender.sendMessage(messages.text("command.no-permission", "{prefix}&c你没有权限执行该命令。"));
            return true;
        }
        String action = args.length == 1 ? "toggle" : args[1].toLowerCase(Locale.ROOT);
        if (args.length > 2 || !("on".equals(action) || "off".equals(action)
                || "toggle".equals(action) || "status".equals(action))) {
            sender.sendMessage(messages.text("command.bossbar-usage", "{prefix}&e用法: /wtc bossbar [on|off|toggle|status]"));
            return true;
        }
        Player player = (Player) sender;
        boolean enabled = preferences.isEnabled(player.getUniqueId());
        if (!"status".equals(action)) {
            enabled = "on".equals(action) || ("toggle".equals(action) && !enabled);
            try {
                preferences.setEnabled(player, enabled);
            } catch (IOException exception) {
                sender.sendMessage(messages.text("command.bossbar-save-failed", "{prefix}&c保存 BossBar 设置失败，原设置未更改，请联系管理员检查数据目录权限。"));
                return true;
            }
        }
        sender.sendMessage(enabled
                ? messages.text("command.bossbar-countdown-enabled", "{prefix}&a你的常驻清理倒计时 BossBar 已开启。")
                : messages.text("command.bossbar-disabled", "{prefix}&e你的清理倒计时 BossBar 已关闭。"));
        if (!preferences.isGloballyEnabled()) {
            sender.sendMessage(messages.text("command.bossbar-global-disabled", "{prefix}&e服务器已关闭 BossBar 总开关；你的个人偏好会保留，总开关开启后生效。"));
        }
        return true;
    }
}
