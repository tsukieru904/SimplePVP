package me.tsukieru.simplepvp;

import me.tsukieru.simplepvp.command.PvpCommand;
import me.tsukieru.simplepvp.data.PvpDataStore;
import me.tsukieru.simplepvp.gui.PvpGui;
import me.tsukieru.simplepvp.gui.PvpGuiListener;
import me.tsukieru.simplepvp.listener.PvpListener;
import me.tsukieru.simplepvp.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class SimplePvpPlugin extends JavaPlugin {

    private MessageManager messages;
    private PvpDataStore dataStore;
    private PvpGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("message.yml", false);

        this.messages  = new MessageManager(this);
        this.dataStore = new PvpDataStore(this);
        this.gui       = new PvpGui(this);

        dataStore.load();
        dataStore.bootstrapOnlinePlayers();
        dataStore.startBossbarTask();

        PvpCommand command = new PvpCommand(this);
        registerCommand("pvp",        command);
        registerCommand("pvplist",    command);
        registerCommand("pvpprotect", command);
        registerCommand("simplepvp",  command);

        Bukkit.getPluginManager().registerEvents(new PvpListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PvpGuiListener(this), this);

        getLogger().info("SimplePvP enabled. Storage: "
                + getConfig().getString("storage.type", "sqlite"));
    }

    @Override
    public void onDisable() {
        if (dataStore != null) {
            dataStore.markShuttingDown();
            dataStore.stopBossbarTask();
            dataStore.save();
        }
    }

    /**
     * Hot-reload: config → messages → dataStore (saves current data first).
     * Called by /simplepvp reload.
     */
    public void reload() {
        reloadConfig();
        messages.reload();
        dataStore.reload();
        getLogger().info("SimplePvP reloaded. Storage: "
                + getConfig().getString("storage.type", "sqlite"));
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    public MessageManager messages() { return messages; }
    public PvpDataStore   dataStore() { return dataStore; }
    public PvpGui         gui()       { return gui; }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void registerCommand(String name, PvpCommand executor) {
        var cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }
}
