package pixeltech.bluenine.blworldtrashcan.bukkit.notify;

import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 按 UUID 持久化个人偏好，并同步当前清理 BossBar 的观众。 */
public final class BossBarPreferenceService implements Listener {
    private final File file;
    private final BooleanSupplier globallyEnabled;
    private final BooleanSupplier defaultEnabled;
    private final Map<UUID, Boolean> playerPreferences = new HashMap<>();
    private BossBar activeBar;
    private boolean registered;

    public BossBarPreferenceService(File file, BooleanSupplier globallyEnabled) {
        this(file, globallyEnabled, () -> false);
    }

    public BossBarPreferenceService(File file, BooleanSupplier globallyEnabled, BooleanSupplier defaultEnabled) {
        this.file = file;
        this.globallyEnabled = globallyEnabled;
        this.defaultEnabled = defaultEnabled;
        if (file.exists()) {
            YamlConfiguration data = new YamlConfiguration();
            try {
                data.load(file);
                for (String id : data.getStringList("hidden-players")) {
                    playerPreferences.put(UUID.fromString(id), false);
                }
                if (data.contains("players")) {
                    ConfigurationSection players = data.getConfigurationSection("players");
                    if (players == null) {
                        throw new IOException("players must be a UUID-to-boolean mapping");
                    }
                    for (String id : players.getKeys(false)) {
                        if (!players.isBoolean(id)) {
                            throw new IOException("Invalid BossBar preference for " + id);
                        }
                        playerPreferences.put(UUID.fromString(id), players.getBoolean(id));
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot load BossBar preferences: " + file, exception);
            }
        }
    }

    /** 重载功能时沿用同一份偏好，不重复注册监听。 */
    public synchronized void register(Plugin plugin) {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    public synchronized boolean isEnabled(UUID playerId) {
        Boolean preference = playerPreferences.get(playerId);
        return preference == null ? defaultEnabled.getAsBoolean() : preference.booleanValue();
    }

    public boolean isGloballyEnabled() {
        return globallyEnabled.getAsBoolean();
    }

    /** 先安全落盘，再改变内存及画面；失败时保留原设置。 */
    public synchronized void setEnabled(Player player, boolean enabled) throws IOException {
        Map<UUID, Boolean> updated = new HashMap<>(playerPreferences);
        updated.put(player.getUniqueId(), enabled);
        if (!updated.equals(playerPreferences)) {
            save(updated);
            playerPreferences.clear();
            playerPreferences.putAll(updated);
        }
        refreshPlayer(player);
    }

    private void save(Map<UUID, Boolean> preferences) throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        ArrayList<String> ids = new ArrayList<>();
        for (UUID id : preferences.keySet()) {
            ids.add(id.toString());
        }
        java.util.Collections.sort(ids);
        ArrayList<String> hidden = new ArrayList<>();
        for (String id : ids) {
            boolean enabled = preferences.get(UUID.fromString(id));
            data.set("players." + id, enabled);
            if (!enabled) {
                hidden.add(id);
            }
        }
        // 保留旧版关闭名单，便于回滚；新版明确记录 on/off，不受默认值变化影响。
        data.set("hidden-players", hidden);
        Path target = file.toPath().toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "bossbar-", ".tmp");
        try {
            Files.write(temporary, data.saveToString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 只在确实发出通知时激活，避免 on 指令复活已经结束的倒计时。 */
    public synchronized void activate(BossBar bar) {
        activeBar = bar;
    }

    /** 必须在玩家所属线程调用；延迟回调执行时重新检查偏好和生命周期。 */
    public synchronized void refreshPlayer(BossBar expectedBar, Player player) {
        if (activeBar == expectedBar) {
            refreshPlayer(player);
        }
    }

    public synchronized void refreshPlayer(Player player) {
        if (activeBar == null) {
            return;
        }
        if (player.isOnline() && isGloballyEnabled() && isEnabled(player.getUniqueId())) {
            if (!activeBar.getPlayers().contains(player)) {
                activeBar.addPlayer(player);
            }
        } else {
            activeBar.removePlayer(player);
        }
    }

    /** 先失效当前通知，使排队中的 Folia 加入任务无法重新加入观众。 */
    public synchronized void deactivate() {
        activeBar = null;
    }

    /** 清理任务仍负责在对应平台上下文移除全部观众。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public synchronized void onQuit(PlayerQuitEvent event) {
        if (activeBar != null) {
            activeBar.removePlayer(event.getPlayer());
        }
    }
}
