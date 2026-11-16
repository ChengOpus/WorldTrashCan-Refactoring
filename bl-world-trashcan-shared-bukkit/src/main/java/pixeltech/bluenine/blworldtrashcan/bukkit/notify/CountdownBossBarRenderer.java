package pixeltech.bluenine.blworldtrashcan.bukkit.notify;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.RichTextRenderer;
import pixeltech.bluenine.blworldtrashcan.config.NotifyConfig;

import java.util.Locale;

/** 只显示实际清理计时器的状态，不启动独立的显示计时器。 */
public final class CountdownBossBarRenderer {
    private CountdownBossBarRenderer() {
    }

    public static void update(BossBar bar, NotifyConfig config, int remaining, int interval, boolean cleaning) {
        int seconds = Math.max(0, remaining);
        String time = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        String title = cleaning ? config.getBossBarCleaningTitle() : config.getBossBarCountdownTitle();
        title = title.replace("{time}", time)
                .replace("{seconds}", Integer.toString(seconds))
                .replace("{interval}", Integer.toString(interval));
        bar.setTitle(RichTextRenderer.color(title));
        bar.setStyle(BarStyle.SOLID);
        bar.setColor(cleaning || seconds <= 5 ? BarColor.RED : seconds <= 30 ? BarColor.YELLOW : BarColor.BLUE);
        bar.setProgress(cleaning || interval <= 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, (double) seconds / interval)));
    }
}
