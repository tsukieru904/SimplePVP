package me.tsukieru.simplepvp.command;

import me.tsukieru.simplepvp.SimplePvpPlugin;
import me.tsukieru.simplepvp.data.PvpDataStore;
import me.tsukieru.simplepvp.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PvpCommand implements CommandExecutor, TabCompleter {

    private static final long CONFIRM_TIMEOUT_MS = 30_000L;

    private final SimplePvpPlugin plugin;

    /** Players who typed /pvpProtect and are waiting to confirm. UUID -> timestamp */
    private final Map<UUID, Long> pendingConfirm = new ConcurrentHashMap<>();

    public PvpCommand(SimplePvpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        // /simplepvp — admin only, allows console
        if (cmd.equals("simplepvp")) {
            return handleSimplePvp(sender, args);
        }

        // All other commands require a player
        MessageManager messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.withPrefix("player-only"));
            return true;
        }
        if (!player.hasPermission("simplepvp.use") && !player.hasPermission("simplepvp.admin")) {
            player.sendMessage(messages.withPrefix("no-permission"));
            return true;
        }

        return switch (cmd) {
            case "pvp"        -> handlePvp(player, args);
            case "pvplist"    -> handlePvplist(player, args);
            case "pvpprotect" -> handlePvpProtect(player);
            default           -> true;
        };
    }

    // ── /simplepvp ────────────────────────────────────────────────────────────

    private boolean handleSimplePvp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplepvp.admin")) {
            sender.sendMessage(plugin.messages().withPrefix("no-permission"));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(plugin.messages().withPrefix("simplepvp-usage"));
            return true;
        }

        // reload
        try {
            plugin.reload();
            sender.sendMessage(plugin.messages().withPrefix("reload-success"));
        } catch (Exception e) {
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
            sender.sendMessage(plugin.messages().withPrefix("reload-failed"));
        }
        return true;
    }

    // ── /pvp ─────────────────────────────────────────────────────────────────

    private boolean handlePvp(Player player, String[] args) {
        MessageManager messages = plugin.messages();
        PvpDataStore store = plugin.dataStore();

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on"      -> { store.setStrangerPvp(player, true);  player.sendMessage(messages.withPrefix("pvp-enabled")); }
            case "off"     -> { store.setStrangerPvp(player, false); player.sendMessage(messages.withPrefix("pvp-disabled")); }
            case "editor"  -> plugin.gui().openEditor(player);
            case "help"    -> sendHelp(player);
            case "confirm" -> handleConfirm(player);
            default        -> player.sendMessage(messages.withPrefix("unknown-subcommand"));
        }
        return true;
    }

    private void sendHelp(Player player) {
        for (String line : plugin.messages().list("help")) {
            player.sendMessage(line);
        }
    }

    // ── /pvp confirm ─────────────────────────────────────────────────────────

    private void handleConfirm(Player player) {
        MessageManager messages = plugin.messages();
        UUID uuid = player.getUniqueId();

        Long timestamp = pendingConfirm.get(uuid);
        if (timestamp == null) {
            player.sendMessage(messages.withPrefix("protect-confirm-no-pending"));
            return;
        }
        if (System.currentTimeMillis() - timestamp > CONFIRM_TIMEOUT_MS) {
            pendingConfirm.remove(uuid);
            player.sendMessage(messages.withPrefix("protect-confirm-expired"));
            return;
        }
        pendingConfirm.remove(uuid);
        plugin.dataStore().removeProtection(player);
        player.sendMessage(messages.withPrefix("protect-removed"));
    }

    // ── /pvpProtect ──────────────────────────────────────────────────────────

    private boolean handlePvpProtect(Player player) {
        MessageManager messages = plugin.messages();

        if (!plugin.dataStore().isProtected(player)) {
            player.sendMessage(messages.withPrefix("protect-no-protection"));
            return true;
        }
        pendingConfirm.put(player.getUniqueId(), System.currentTimeMillis());
        for (String line : messages.list("protect-confirm-prompt")) {
            player.sendMessage(line);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                pendingConfirm.remove(player.getUniqueId()), CONFIRM_TIMEOUT_MS / 50L);
        return true;
    }

    // ── /pvplist ─────────────────────────────────────────────────────────────

    private boolean handlePvplist(Player player, String[] args) {
        MessageManager messages = plugin.messages();
        PvpDataStore store = plugin.dataStore();

        if (args.length == 0) {
            player.sendMessage(messages.withPrefix("unknown-subcommand"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on"  -> { store.setPvplistMode(player, true);  player.sendMessage(messages.withPrefix("pvplist-enabled")); }
            case "off" -> { store.setPvplistMode(player, false); player.sendMessage(messages.withPrefix("pvplist-disabled")); }
            case "add" -> {
                if (args.length < 2) { player.sendMessage(messages.withPrefix("unknown-subcommand")); return true; }
                OfflinePlayer target = resolvePlayer(args[1]);
                if (target == null) {
                    player.sendMessage(messages.withPrefix("player-not-found", Map.of("player", args[1]))); return true;
                }
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage(messages.withPrefix("self-target")); return true;
                }
                if (store.addWhitelist(player, target.getUniqueId())) {
                    player.sendMessage(messages.withPrefix("whitelist-added", Map.of("player", displayName(target))));
                } else {
                    player.sendMessage(messages.withPrefix("whitelist-already-added", Map.of("player", displayName(target))));
                }
            }
            case "remove" -> {
                if (args.length < 2) { player.sendMessage(messages.withPrefix("unknown-subcommand")); return true; }
                OfflinePlayer target = resolvePlayer(args[1]);
                if (target == null) {
                    player.sendMessage(messages.withPrefix("player-not-found", Map.of("player", args[1]))); return true;
                }
                if (store.removeWhitelist(player, target.getUniqueId())) {
                    player.sendMessage(messages.withPrefix("whitelist-removed", Map.of("player", displayName(target))));
                } else {
                    player.sendMessage(messages.withPrefix("whitelist-not-found", Map.of("player", displayName(target))));
                }
            }
            case "list" -> showList(player);
            default     -> player.sendMessage(messages.withPrefix("unknown-subcommand"));
        }
        return true;
    }

    private void showList(Player player) {
        MessageManager messages = plugin.messages();
        var list = plugin.dataStore().whitelistOf(player);
        player.sendMessage(messages.withPrefix("whitelist-header", Map.of("count", String.valueOf(list.size()))));
        if (list.isEmpty()) { player.sendMessage(messages.withPrefix("whitelist-empty")); return; }
        for (UUID uuid : list) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            player.sendMessage(messages.withPrefix("whitelist-line", Map.of("player", displayName(offline))));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;

        // Avoid Bukkit.getOfflinePlayer(String) here: it's deprecated because it can
        // trigger a synchronous, blocking Mojang API lookup on the main thread when
        // the name isn't already cached locally. getOfflinePlayers() only reads the
        // server's local player-data cache, so it's safe to call from the main thread.
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(offline.getName())) {
                return offline;
            }
        }
        return null;
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString().substring(0, 8);
    }

    // ── tab complete ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "pvp" -> {
                if (args.length == 1)
                    yield partial(args[0], List.of("on", "off", "editor", "help", "confirm"));
                yield List.of();
            }
            case "pvplist" -> {
                if (args.length == 1)
                    yield partial(args[0], List.of("on", "off", "add", "remove", "list"));
                if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")))
                    yield partial(args[1], Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName).collect(Collectors.toList()));
                yield List.of();
            }
            case "simplepvp" -> {
                if (args.length == 1) yield partial(args[0], List.of("reload"));
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> partial(String arg, List<String> options) {
        String lower = arg == null ? "" : arg.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
