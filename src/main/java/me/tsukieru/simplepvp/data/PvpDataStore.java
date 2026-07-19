package me.tsukieru.simplepvp.data;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import me.tsukieru.simplepvp.SimplePvpPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PvpDataStore {

    public enum DamageBlockReason {
        ATTACKER_PROTECTED,
        VICTIM_PROTECTED,
        ATTACKER_BLOCKS_TARGET,
        VICTIM_BLOCKS_ATTACKER
    }

    private final SimplePvpPlugin plugin;

    // ── storage fields ────────────────────────────────────────────────────────
    /** YAML backend */
    private File yamlFile;
    private YamlConfiguration yaml;

    /** SQLite backend */
    private Connection sqliteConn;

    // ── runtime state ─────────────────────────────────────────────────────────
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private int bossbarTask = -1;

    // ── constructor ───────────────────────────────────────────────────────────

    public PvpDataStore(SimplePvpPlugin plugin) {
        this.plugin = plugin;
        this.yamlFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ── public lifecycle ──────────────────────────────────────────────────────

    // Serializes every access to the yaml/sqlite backend. saveAsync() runs on
    // background threads, so without this lock two saves (or a save racing
    // with reload()'s close+reload sequence) could touch the same JDBC
    // Connection / YamlConfiguration concurrently — JDBC Connections aren't
    // thread-safe, so that can corrupt data or spam "database is locked".
    private final Object ioLock = new Object();

    public void load() {
        synchronized (ioLock) {
            cache.clear();
            String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
            if (type.equals("sqlite")) {
                loadSqlite();
            } else {
                loadYaml();
            }
        }
    }

    public void save() {
        synchronized (ioLock) {
            String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
            if (type.equals("sqlite")) {
                saveSqlite();
            } else {
                saveYaml();
            }
        }
    }

    private volatile boolean shuttingDown = false;

    /** Called from {@code onDisable()} before the final synchronous save. */
    public void markShuttingDown() {
        shuttingDown = true;
    }

    /**
     * Same as {@link #save()} but runs the actual disk/DB write off the main
     * thread. Use this from event handlers and commands so a busy server
     * (lots of players, slow disk) doesn't stall the main thread on every
     * join/quit/toggle. Do NOT use this in onDisable() or reload() — the
     * scheduler stops running async tasks once the server begins shutting
     * down / reloading, so those paths must call save() directly and wait
     * for it to finish.
     */
    public void saveAsync() {
        if (shuttingDown || !plugin.isEnabled()) {
            // The scheduler may silently drop async tasks once shutdown/disable
            // is underway, so fall back to a direct, blocking save here instead
            // of risking a dropped write (e.g. a quit event firing mid-shutdown).
            save();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    /**
     * Called by /simplepvp reload. Saves current data, tears down connections,
     * then re-loads from the (possibly changed) storage type.
     */
    public void reload() {
        synchronized (ioLock) {
            save();
            closeSqlite();
            load();
        }
    }

    // ── YAML backend ──────────────────────────────────────────────────────────

    private void loadYaml() {
        if (!yamlFile.exists()) {
            try {
                yamlFile.getParentFile().mkdirs();
                yamlFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Unable to create data.yml", e);
            }
        }
        this.yaml = YamlConfiguration.loadConfiguration(yamlFile);

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;

        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                cache.put(uuid, PlayerData.fromSection(players.getConfigurationSection(key)));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveYaml() {
        if (yaml == null) yaml = new YamlConfiguration();

        yaml.set("players", null);
        for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerData data = entry.getValue();
            yaml.set(path + ".stranger-pvp",    data.isStrangerPvp());
            yaml.set(path + ".pvplist-mode",    data.isPvplistMode());
            yaml.set(path + ".protection-end",  data.getProtectionEnd());
            yaml.set(path + ".last-known-name", data.getLastKnownName());
            yaml.set(path + ".whitelist",       data.getWhitelistAsStrings());
        }
        try {
            yaml.save(yamlFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    // ── SQLite backend ────────────────────────────────────────────────────────

    private Connection getSqliteConnection() {
        try {
            if (sqliteConn != null && !sqliteConn.isClosed()) return sqliteConn;

            plugin.getDataFolder().mkdirs();
            File db = new File(plugin.getDataFolder(), "data.db");
            // Use relocated driver class so shade doesn't break Class.forName
            Class.forName("me.tsukieru.simplepvp.libs.sqlite.JDBC");
            sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());

            try (Statement st = sqliteConn.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pvp_players (
                        uuid            TEXT PRIMARY KEY,
                        stranger_pvp    INTEGER NOT NULL DEFAULT 0,
                        pvplist_mode    INTEGER NOT NULL DEFAULT 0,
                        protection_end  INTEGER NOT NULL DEFAULT 0,
                        last_known_name TEXT,
                        whitelist       TEXT NOT NULL DEFAULT ''
                    )
                    """);
            }
            return sqliteConn;
        } catch (Exception e) {
            throw new RuntimeException("Failed to open SQLite database", e);
        }
    }

    private void loadSqlite() {
        try {
            Connection conn = getSqliteConnection();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM pvp_players")) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    boolean strangerPvp   = rs.getInt("stranger_pvp")  == 1;
                    boolean pvplistMode   = rs.getInt("pvplist_mode")  == 1;
                    long protectionEnd    = rs.getLong("protection_end");
                    String lastKnownName  = rs.getString("last_known_name");
                    String whitelistRaw   = rs.getString("whitelist");

                    Set<UUID> whitelist = new HashSet<>();
                    if (whitelistRaw != null && !whitelistRaw.isBlank()) {
                        for (String part : whitelistRaw.split(",")) {
                            try { whitelist.add(UUID.fromString(part.trim())); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }
                    cache.put(uuid, new PlayerData(strangerPvp, pvplistMode, whitelist, protectionEnd, lastKnownName));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQLite data: " + e.getMessage());
        }
    }

    private void saveSqlite() {
        Connection conn;
        try {
            conn = getSqliteConnection();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save SQLite data: " + e.getMessage());
            return;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO pvp_players
                        (uuid, stranger_pvp, pvplist_mode, protection_end, last_known_name, whitelist)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
                    PlayerData d = entry.getValue();
                    String whitelist = String.join(",", d.getWhitelistAsStrings());
                    ps.setString(1, entry.getKey().toString());
                    ps.setInt(2,    d.isStrangerPvp()  ? 1 : 0);
                    ps.setInt(3,    d.isPvplistMode()   ? 1 : 0);
                    ps.setLong(4,   d.getProtectionEnd());
                    ps.setString(5, d.getLastKnownName());
                    ps.setString(6, whitelist);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save SQLite data: " + e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    private void closeSqlite() {
        synchronized (ioLock) {
            if (sqliteConn != null) {
                try { sqliteConn.close(); } catch (Exception ignored) {}
                sqliteConn = null;
            }
        }
    }

    // ── player data helpers ───────────────────────────────────────────────────

    public PlayerData getOrCreate(UUID uuid) {
        return cache.computeIfAbsent(uuid, ignored -> new PlayerData(
                plugin.getConfig().getBoolean("settings.default-stranger-pvp", false),
                plugin.getConfig().getBoolean("settings.default-pvplist-mode", false),
                new HashSet<>(),
                0L,
                null
        ));
    }

    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    public void bootstrapOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            getOrCreate(player.getUniqueId()).setLastKnownName(player.getName());
            maybeApplyNewbieProtection(player, false);
            refreshBossBar(player);
        }
    }

    public void handleJoin(Player player, boolean firstJoin) {
        PlayerData data = getOrCreate(player.getUniqueId());
        data.setLastKnownName(player.getName());
        maybeApplyNewbieProtection(player, firstJoin);
        refreshBossBar(player);
        saveAsync();
    }

    public void handleQuit(Player player) {
        removeBossBar(player);
        saveAsync();
    }

    public boolean isProtected(Player player) {
        PlayerData data = get(player.getUniqueId());
        return data != null && data.getProtectionEnd() > System.currentTimeMillis();
    }

    public long protectionRemainingMillis(Player player) {
        PlayerData data = get(player.getUniqueId());
        if (data == null) return 0L;
        return Math.max(0L, data.getProtectionEnd() - System.currentTimeMillis());
    }

    /**
     * Whether {@code attacker} is allowed to damage {@code victim}.
     * <p>
     * This is deliberately checked from BOTH sides: a player who has turned
     * their own PvP off (or is not on the victim's whitelist) must not be
     * able to attack someone else just because the other person happens to
     * have stranger-PvP enabled. Each player's own toggle only ever grants
     * permission for damage flowing in their direction; both directions must
     * be permitted for the hit to go through.
     */
    public boolean canBeDamagedBy(Player victim, Player attacker) {
        return getDamageBlockReason(victim, attacker) == null;
    }

    public DamageBlockReason getDamageBlockReason(Player victim, Player attacker) {
        if (isProtected(attacker)) return DamageBlockReason.ATTACKER_PROTECTED;
        if (isProtected(victim)) return DamageBlockReason.VICTIM_PROTECTED;
        if (!isDamageAllowedOneWay(attacker, victim)) return DamageBlockReason.ATTACKER_BLOCKS_TARGET;
        if (!isDamageAllowedOneWay(victim, attacker)) return DamageBlockReason.VICTIM_BLOCKS_ATTACKER;
        return null;
    }

    public String getDamageBlockMessageKey(Player target, Player other) {
        PlayerData targetData = getOrCreate(target.getUniqueId());
        if (targetData.isPvplistMode()) {
            return targetData.getWhitelist().contains(other.getUniqueId())
                    ? "attack-disallowed"
                    : "attack-disallowed-pvplist";
        }
        return targetData.isStrangerPvp()
                ? "attack-disallowed"
                : "attack-disallowed-no-open";
    }

    public String getVictimBlockMessageKey(Player victim, Player attacker) {
        PlayerData victimData = getOrCreate(victim.getUniqueId());
        if (victimData.isPvplistMode()) {
            return victimData.getWhitelist().contains(attacker.getUniqueId())
                    ? "attack-disallowed"
                    : "attack-disallowed-victim-pvplist";
        }
        return victimData.isStrangerPvp()
                ? "attack-disallowed"
                : "attack-disallowed-victim-no-open";
    }

    /** Whether {@code target}'s own settings permit {@code other} to fight them. */
    private boolean isDamageAllowedOneWay(Player target, Player other) {
        PlayerData targetData = getOrCreate(target.getUniqueId());
        if (targetData.isPvplistMode()) {
            return targetData.getWhitelist().contains(other.getUniqueId());
        }
        return targetData.isStrangerPvp();
    }

    public void setStrangerPvp(Player player, boolean enabled) {
        PlayerData data = getOrCreate(player.getUniqueId());
        data.setStrangerPvp(enabled);
        if (enabled) {
            data.setPvplistMode(false);
        }
        saveAsync();
    }

    public void setPvplistMode(Player player, boolean enabled) {
        PlayerData data = getOrCreate(player.getUniqueId());
        data.setPvplistMode(enabled);
        if (enabled) {
            data.setStrangerPvp(false);
        }
        saveAsync();
    }

    public boolean addWhitelist(Player owner, UUID target) {
        boolean added = getOrCreate(owner.getUniqueId()).getWhitelist().add(target);
        saveAsync();
        return added;
    }

    public boolean removeWhitelist(Player owner, UUID target) {
        boolean removed = getOrCreate(owner.getUniqueId()).getWhitelist().remove(target);
        saveAsync();
        return removed;
    }

    public Set<UUID> whitelistOf(Player player) {
        return Set.copyOf(getOrCreate(player.getUniqueId()).getWhitelist());
    }

    public boolean isStrangerPvpEnabled(Player player) {
        return getOrCreate(player.getUniqueId()).isStrangerPvp();
    }

    public boolean isPvplistModeEnabled(Player player) {
        return getOrCreate(player.getUniqueId()).isPvplistMode();
    }

    // ── bossbar ───────────────────────────────────────────────────────────────

    public void refreshBossBar(Player player) {
        if (!plugin.getConfig().getBoolean("bossbar.enabled", true)) {
            removeBossBar(player);
            return;
        }
        long remaining = protectionRemainingMillis(player);
        if (remaining <= 0) {
            removeBossBar(player);
            return;
        }
        long duration = getProtectionDurationMillis();
        double progress = Math.max(0.0, Math.min(1.0, remaining / (double) duration));

        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> Bukkit.createBossBar(
                bossbarTitle(remaining), parseBarColor(), parseBarStyle()
        ));
        bar.setTitle(bossbarTitle(remaining));
        bar.setProgress(progress);
        bar.setVisible(true);
        bar.addPlayer(player);
    }

    public void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    public void startBossbarTask() {
        stopBossbarTask();
        bossbarTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = get(player.getUniqueId());
                if (data == null) { removeBossBar(player); continue; }
                if (data.getProtectionEnd() > 0 && data.getProtectionEnd() <= now) {
                    data.setProtectionEnd(0L);
                    removeBossBar(player);
                    player.sendMessage(plugin.messages().withPrefix("newbie-end"));
                    continue;
                }
                if (data.getProtectionEnd() > now) {
                    refreshBossBar(player);
                } else {
                    removeBossBar(player);
                }
            }
        }, 20L, 20L);
    }

    public void stopBossbarTask() {
        if (bossbarTask != -1) {
            Bukkit.getScheduler().cancelTask(bossbarTask);
            bossbarTask = -1;
        }
        for (BossBar bar : bossBars.values()) bar.removeAll();
        bossBars.clear();
    }

    // ── newbie protection ─────────────────────────────────────────────────────

    public void maybeApplyNewbieProtection(Player player, boolean firstJoin) {
        if (!plugin.getConfig().getBoolean("newbie-protection.enabled", true)) return;
        if (getOrCreate(player.getUniqueId()).getProtectionEnd() > 0) return;
        boolean firstJoinOnly = plugin.getConfig().getBoolean("newbie-protection.first-join-only", true);
        if (firstJoinOnly && !firstJoin) return;

        long end = System.currentTimeMillis() + getProtectionDurationMillis();
        getOrCreate(player.getUniqueId()).setProtectionEnd(end);
        player.sendMessage(plugin.messages().withPrefix("newbie-start",
                Map.of("time", formatDuration(end - System.currentTimeMillis()))));
    }

    public void removeProtection(Player player) {
        PlayerData data = get(player.getUniqueId());
        if (data != null) {
            data.setProtectionEnd(0L);
            removeBossBar(player);
            saveAsync();
        }
    }

    /** Reads duration-seconds from config (falls back to duration-minutes for old configs). */
    public long getProtectionDurationMillis() {
        // New key: duration-seconds
        if (plugin.getConfig().contains("newbie-protection.duration-seconds")) {
            long seconds = Math.max(1L, plugin.getConfig().getLong("newbie-protection.duration-seconds", 3600L));
            return seconds * 1_000L;
        }
        // Legacy fallback: duration-minutes
        long minutes = Math.max(1L, plugin.getConfig().getLong("newbie-protection.duration-minutes", 60L));
        return minutes * 60_000L;
    }

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours   = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ── bossbar helpers ───────────────────────────────────────────────────────

    private String bossbarTitle(long remainingMillis) {
        String raw = plugin.getConfig().getString("bossbar.title", "&a新手保護期 &7| 剩餘 &f{time}");
        return me.tsukieru.simplepvp.message.MessageManager.color(
                raw.replace("{time}", formatDuration(remainingMillis)));
    }

    private BarColor parseBarColor() {
        try {
            return BarColor.valueOf(plugin.getConfig().getString("bossbar.color", "GREEN").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) { return BarColor.GREEN; }
    }

    private BarStyle parseBarStyle() {
        try {
            return BarStyle.valueOf(plugin.getConfig().getString("bossbar.style", "SOLID").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) { return BarStyle.SOLID; }
    }

    // ── PlayerData ─────────────────────────────────────────────────────────────

    public static final class PlayerData {
        private boolean strangerPvp;
        private boolean pvplistMode;
        private final Set<UUID> whitelist;
        private long protectionEnd;
        private String lastKnownName;

        public PlayerData(boolean strangerPvp, boolean pvplistMode, Set<UUID> whitelist,
                          long protectionEnd, String lastKnownName) {
            this.strangerPvp  = strangerPvp;
            this.pvplistMode  = pvplistMode;
            this.whitelist    = whitelist == null ? new HashSet<>() : new HashSet<>(whitelist);
            this.protectionEnd = protectionEnd;
            this.lastKnownName = lastKnownName;
        }

        public static PlayerData fromSection(ConfigurationSection section) {
            if (section == null) return new PlayerData(false, false, new HashSet<>(), 0L, null);
            boolean stranger = section.getBoolean("stranger-pvp", false);
            boolean listMode = section.getBoolean("pvplist-mode", false);
            long end         = section.getLong("protection-end", 0L);
            String name      = section.getString("last-known-name", null);
            Set<UUID> list   = new HashSet<>();
            for (String raw : section.getStringList("whitelist")) {
                try { list.add(UUID.fromString(raw)); }
                catch (IllegalArgumentException ignored) {}
            }
            return new PlayerData(stranger, listMode, list, end, name);
        }

        public boolean isStrangerPvp()               { return strangerPvp; }
        public void    setStrangerPvp(boolean v)     { this.strangerPvp = v; }
        public boolean isPvplistMode()               { return pvplistMode; }
        public void    setPvplistMode(boolean v)     { this.pvplistMode = v; }
        public Set<UUID> getWhitelist()              { return whitelist; }
        public long    getProtectionEnd()            { return protectionEnd; }
        public void    setProtectionEnd(long v)      { this.protectionEnd = v; }
        public String  getLastKnownName()            { return lastKnownName; }
        public void    setLastKnownName(String v)    { this.lastKnownName = v; }

        public List<String> getWhitelistAsStrings() {
            return whitelist.stream().map(UUID::toString).toList();
        }
    }
}
