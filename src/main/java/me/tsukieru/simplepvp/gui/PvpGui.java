package me.tsukieru.simplepvp.gui;

import me.tsukieru.simplepvp.SimplePvpPlugin;
import me.tsukieru.simplepvp.data.PvpDataStore;
import me.tsukieru.simplepvp.data.PvpDataStore.PlayerData;
import me.tsukieru.simplepvp.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PvpGui {

    public static final String KEY_UUID = "simplepvp-uuid";

    private final SimplePvpPlugin plugin;
    private final NamespacedKey uuidKey;

    public PvpGui(SimplePvpPlugin plugin) {
        this.plugin = plugin;
        this.uuidKey = new NamespacedKey(plugin, KEY_UUID);
    }

    public void openEditor(Player player) {
        MessageManager messages = plugin.messages();
        PvpDataStore store = plugin.dataStore();
        PlayerData data = store.getOrCreate(player.getUniqueId());

        String title = plugin.getConfig().getString("gui.editor-title", messages.text("gui-editor-title"));
        Inventory inv = Bukkit.createInventory(new EditorHolder(player.getUniqueId()), 27, MessageManager.color(title));

        fill(inv, "§8");

        inv.setItem(11, toggleItem(
                data.isStrangerPvp(),
                messages.text(data.isStrangerPvp() ? "gui-button-pvp-on" : "gui-button-pvp-off"),
                messages.list(data.isStrangerPvp() ? "gui-editor-lore-pvp-opened" : "gui-editor-lore-pvp-closed")
        ));

        inv.setItem(13, toggleItem(
                data.isPvplistMode(),
                messages.text(data.isPvplistMode() ? "gui-button-list-on" : "gui-button-list-off"),
                messages.list(data.isPvplistMode() ? "gui-editor-lore-list-opened" : "gui-editor-lore-list-closed")
        ));

        inv.setItem(15, infoItem(
                Material.PLAYER_HEAD,
                messages.text("gui-button-view-list"),
                messages.list("gui-editor-lore-view-list")
        ));

        long remain = store.protectionRemainingMillis(player);
        inv.setItem(22, infoItem(
                Material.CLOCK,
                messages.text("gui-button-newbie"),
                messages.list("gui-editor-lore-newbie", java.util.Map.of("time", remain > 0 ? PvpDataStore.formatDuration(remain) : "&7未開啟"))
        ));

        player.openInventory(inv);
    }

    public void openWhitelist(Player player, int page) {
        PvpDataStore store = plugin.dataStore();
        MessageManager messages = plugin.messages();

        List<UUID> list = new ArrayList<>(store.whitelistOf(player));
        list.sort(Comparator.comparing(uuid -> Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString())));

        int perPage = 45;
        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * perPage;
        int to = Math.min(list.size(), from + perPage);

        String title = plugin.getConfig().getString("gui.whitelist-title", messages.text("gui-whitelist-title"))
                .replace("{page}", String.valueOf(safePage + 1))
                .replace("{pages}", String.valueOf(totalPages));

        Inventory inv = Bukkit.createInventory(new WhitelistHolder(player.getUniqueId(), safePage), 54, MessageManager.color(title));
        fill(inv, "§8");

        for (int i = from; i < to; i++) {
            UUID uuid = list.get(i);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            inv.setItem(i - from, whitelistEntry(uuid, offline, messages));
        }

        inv.setItem(45, navItem(Material.BARRIER, messages.text("gui-button-back"), messages.list("gui-whitelist-back-lore")));
        inv.setItem(49, navItem(Material.ARROW, messages.text("gui-button-prev"), messages.list("gui-whitelist-prev-lore")));
        inv.setItem(53, navItem(Material.ARROW, messages.text("gui-button-next"), messages.list("gui-whitelist-next-lore")));

        player.openInventory(inv);
    }

    public void handleEditorClick(Player player, int slot) {
        PvpDataStore store = plugin.dataStore();
        PlayerData data = store.getOrCreate(player.getUniqueId());

        switch (slot) {
            case 11 -> {
                store.setStrangerPvp(player, !data.isStrangerPvp());
                player.closeInventory();
                openEditor(player);
            }
            case 13 -> {
                store.setPvplistMode(player, !data.isPvplistMode());
                player.closeInventory();
                openEditor(player);
            }
            case 15 -> {
                player.closeInventory();
                openWhitelist(player, 0);
            }
            default -> {
            }
        }
    }

    public void handleWhitelistClick(Player player, int slot, WhitelistHolder holder, ItemStack current) {
        if (slot >= 0 && slot <= 44 && current != null && current.hasItemMeta()) {
            ItemMeta meta = current.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(uuidKey, PersistentDataType.STRING)) {
                String raw = meta.getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
                if (raw != null) {
                    UUID uuid = UUID.fromString(raw);
                    plugin.dataStore().removeWhitelist(player, uuid);
                    player.closeInventory();
                    openWhitelist(player, holder.page());
                }
            }
            return;
        }

        if (slot == 45) {
            player.closeInventory();
            openEditor(player);
        } else if (slot == 49) {
            player.closeInventory();
            openWhitelist(player, holder.page() - 1);
        } else if (slot == 53) {
            player.closeInventory();
            openWhitelist(player, holder.page() + 1);
        }
    }

    private ItemStack toggleItem(boolean enabled, String name, List<String> lore) {
        return infoItem(
                enabled ? Material.LIME_WOOL : Material.RED_WOOL,
                name,
                lore
        );
    }

    private ItemStack navItem(Material material, String name, List<String> lore) {
        return infoItem(material, name, lore);
    }

    private ItemStack infoItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(MessageManager.color(name));
        meta.setLore(lore.stream().map(MessageManager::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack whitelistEntry(UUID uuid, OfflinePlayer offline, MessageManager messages) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (offline != null) {
            meta.setOwningPlayer(offline);
        }
        String display = offline != null && offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
        meta.setDisplayName("§e" + display);
        meta.setLore(List.of(
                "§7UUID: §f" + uuid,
                "§8",
                messages.text("gui-button-remove-lore")
        ));
        meta.getPersistentDataContainer().set(uuidKey, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv, String name) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            pane.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
    }

    public record EditorHolder(UUID owner) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public record WhitelistHolder(UUID owner, int page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
