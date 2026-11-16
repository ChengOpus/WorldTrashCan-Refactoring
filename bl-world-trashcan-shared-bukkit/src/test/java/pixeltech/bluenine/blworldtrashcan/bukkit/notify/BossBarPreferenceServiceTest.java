package pixeltech.bluenine.blworldtrashcan.bukkit.notify;

import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pixeltech.bluenine.blworldtrashcan.bukkit.command.BossBarCommand;
import pixeltech.bluenine.blworldtrashcan.bukkit.command.WorldListTrashCanCommandNames;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** 验证真实指令、持久化和观众行为；不依赖运行中的 Minecraft 服务器。 */
public class BossBarPreferenceServiceTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultEnabledAndPreferencesSurviveRestartByUuid() throws Exception {
        File file = new File(temporary.getRoot(), "data/preferences.yml");
        BossBarPreferenceService service = new BossBarPreferenceService(file, () -> true);
        Player first = player(UUID.randomUUID(), true, new ArrayList<>());
        Player second = player(UUID.randomUUID(), true, new ArrayList<>());
        assertTrue(service.isEnabled(first.getUniqueId()));
        service.setEnabled(first, false);
        BossBarPreferenceService restarted = new BossBarPreferenceService(file, () -> true);
        assertFalse(restarted.isEnabled(first.getUniqueId()));
        assertTrue(restarted.isEnabled(second.getUniqueId()));
        restarted.setEnabled(player(first.getUniqueId(), true, new ArrayList<>()), true);
        assertTrue(new BossBarPreferenceService(file, () -> true).isEnabled(first.getUniqueId()));
    }

    @Test
    public void hidingIsImmediateAndDoesNotAffectOtherPlayersOrReturnOnNextTick() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        Set<Player> viewers = new HashSet<>();
        BossBar bar = bar(viewers);
        Player a = player();
        Player b = player();
        service.activate(bar);
        service.refreshPlayer(bar, a);
        service.refreshPlayer(bar, b);
        service.setEnabled(a, false);
        assertFalse(viewers.contains(a));
        assertTrue(viewers.contains(b));
        service.refreshPlayer(bar, a); // Folia callback queued before off
        assertFalse(viewers.contains(a));
        service.setEnabled(a, true);
        assertTrue(viewers.contains(a));
        assertTrue(viewers.contains(b));
    }

    @Test
    public void reconnectHonorsPreferenceAndRemovesQuitPlayer() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        Set<Player> viewers = new HashSet<>();
        service.activate(bar(viewers));
        Player first = player();
        service.onJoin(new PlayerJoinEvent(first, ""));
        assertTrue(viewers.contains(first));
        service.onQuit(new PlayerQuitEvent(first, ""));
        assertFalse(viewers.contains(first));
        service.setEnabled(first, false);
        Player reconnect = player(first.getUniqueId(), true, new ArrayList<>());
        service.onJoin(new PlayerJoinEvent(reconnect, ""));
        assertFalse(viewers.contains(reconnect));
    }

    @Test
    public void completedOrReplacedBarCannotBeRevivedByQueuedCallbacks() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        Set<Player> oldViewers = new HashSet<>();
        BossBar old = bar(oldViewers);
        Player player = player();
        service.activate(old);
        service.deactivate();
        service.refreshPlayer(old, player);
        service.setEnabled(player, true);
        assertTrue(oldViewers.isEmpty());
        Set<Player> newViewers = new HashSet<>();
        BossBar current = bar(newViewers);
        service.activate(current);
        service.refreshPlayer(old, player);
        assertTrue(oldViewers.isEmpty());
        assertTrue(newViewers.isEmpty());
        service.refreshPlayer(current, player);
        assertTrue(newViewers.contains(player));
    }

    @Test
    public void globalSwitchOverridesPersonalOnWithoutLosingPreference() throws Exception {
        AtomicBoolean global = new AtomicBoolean(false);
        BossBarPreferenceService service = service(global::get);
        Set<Player> viewers = new HashSet<>();
        BossBar bar = bar(viewers);
        Player player = player();
        service.activate(bar);
        service.setEnabled(player, false);
        service.setEnabled(player, true);
        assertTrue(service.isEnabled(player.getUniqueId()));
        assertTrue(viewers.isEmpty());
        global.set(true);
        service.refreshPlayer(bar, player);
        assertTrue(viewers.contains(player));
    }

    @Test
    public void failedSavePreservesPreviousPreferenceAndDisplay() throws Exception {
        File notDirectory = temporary.newFile("not-a-directory");
        BossBarPreferenceService service = new BossBarPreferenceService(new File(notDirectory, "prefs.yml"), () -> true);
        Set<Player> viewers = new HashSet<>();
        Player player = player();
        service.activate(bar(viewers));
        service.refreshPlayer(player);
        try {
            service.setEnabled(player, false);
            fail("Writing below a regular file should fail");
        } catch (IOException expected) {
            assertTrue(service.isEnabled(player.getUniqueId()));
            assertTrue(viewers.contains(player));
        }
    }

    @Test
    public void corruptPreferencesAreNotOverwritten() throws Exception {
        File file = temporary.newFile("corrupt.yml");
        byte[] contents = "hidden-players: [unterminated".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), contents);
        try {
            new BossBarPreferenceService(file, () -> true);
            fail("Invalid YAML should fail visibly");
        } catch (IllegalStateException expected) {
            assertArrayEquals(contents, Files.readAllBytes(file.toPath()));
        }
    }

    @Test
    public void commandsToggleButExplicitOnAndOffAreIdempotent() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        Player player = player();
        command(service, player);
        assertFalse(service.isEnabled(player.getUniqueId()));
        command(service, player, "OFF");
        assertFalse(service.isEnabled(player.getUniqueId()));
        command(service, player, "On");
        command(service, player, "on");
        assertTrue(service.isEnabled(player.getUniqueId()));
        command(service, player, "toggle");
        assertFalse(service.isEnabled(player.getUniqueId()));
    }

    @Test
    public void statusAndInvalidArgumentsDoNotChangePreference() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        Player player = player();
        command(service, player, "status");
        command(service, player, "unknown");
        command(service, player, "off", "someone-else");
        assertTrue(service.isEnabled(player.getUniqueId()));
    }

    @Test
    public void commandRejectsConsoleAndDeniedPlayers() throws Exception {
        BossBarPreferenceService service = service(() -> true);
        List<String> messages = new ArrayList<>();
        Player denied = player(UUID.randomUUID(), false, messages);
        command(service, denied, "off");
        assertTrue(service.isEnabled(denied.getUniqueId()));
        assertTrue(messages.get(0).contains("权限"));
        CommandSender console = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) {
                        messages.add((String) args[0]);
                    }
                    return null;
                });
        command(service, console, "off");
        assertTrue(messages.get(1).contains("玩家"));
    }

    @Test
    public void globalDisabledFeedbackAndReservedCommandAreAvailable() throws Exception {
        BossBarPreferenceService service = service(() -> false);
        List<String> messages = new ArrayList<>();
        Player player = player(UUID.randomUUID(), true, messages);
        command(service, player, "on");
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("总开关"));
        assertTrue(WorldListTrashCanCommandNames.reserved().contains("bossbar"));
        assertTrue(WorldListTrashCanCommandNames.regular().contains("bossbar"));
    }

    private BossBarPreferenceService service(java.util.function.BooleanSupplier global) throws IOException {
        return new BossBarPreferenceService(new File(temporary.newFolder(), "preferences.yml"), global);
    }

    @Test
    public void existingCustomHelpGainsBossBarEntryWithoutDuplicates() throws Exception {
        BukkitMessageService messages = new BukkitMessageService(null);
        java.lang.reflect.Field active = BukkitMessageService.class.getDeclaredField("activeMessages");
        java.lang.reflect.Field bundled = BukkitMessageService.class.getDeclaredField("bundledActiveMessages");
        active.setAccessible(true);
        bundled.setAccessible(true);
        org.bukkit.configuration.file.YamlConfiguration external =
                (org.bukkit.configuration.file.YamlConfiguration) active.get(messages);
        org.bukkit.configuration.file.YamlConfiguration defaults =
                (org.bukkit.configuration.file.YamlConfiguration) bundled.get(messages);
        external.set("command.help", java.util.Collections.singletonList("Custom help"));
        defaults.set("command.help", java.util.Arrays.asList("Default help", "/wtc bossbar [on|off]"));
        List<String> help = messages.list("command.help", null);
        assertEquals(java.util.Arrays.asList("Custom help", "/wtc bossbar [on|off]"), help);
        external.set("command.help", help);
        assertEquals(help, messages.list("command.help", null));
    }

    private void command(BossBarPreferenceService service, CommandSender sender, String... arguments) {
        String[] args = new String[arguments.length + 1];
        args[0] = "bossbar";
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        assertTrue(BossBarCommand.execute(sender, args, service, new BukkitMessageService(null)));
    }

    private Player player() {
        return player(UUID.randomUUID(), true, new ArrayList<>());
    }

    private Player player(UUID id, boolean permitted, List<String> messages) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "isOnline": return true;
                        case "isOp": return false;
                        case "hasPermission": return permitted;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "sendMessage": messages.add((String) args[0]); return null;
                        default: return null;
                    }
                });
    }

    private BossBar bar(Set<Player> viewers) {
        return (BossBar) Proxy.newProxyInstance(BossBar.class.getClassLoader(), new Class<?>[]{BossBar.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPlayers": return new ArrayList<>(viewers);
                        case "addPlayer": viewers.add((Player) args[0]); return null;
                        case "removePlayer": viewers.remove((Player) args[0]); return null;
                        default: return null;
                    }
                });
    }
}
