package me.tsukieru.simplepvp.gui;

import me.tsukieru.simplepvp.SimplePvpPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class PvpGuiListener implements Listener {

    private final SimplePvpPlugin plugin;

    public PvpGuiListener(SimplePvpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof PvpGui.EditorHolder editorHolder) {
            if (!editorHolder.owner().equals(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            plugin.gui().handleEditorClick(player, event.getRawSlot());
            return;
        }

        if (holder instanceof PvpGui.WhitelistHolder whitelistHolder) {
            if (!whitelistHolder.owner().equals(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            plugin.gui().handleWhitelistClick(player, event.getRawSlot(), whitelistHolder, current);
        }
    }

    /**
     * InventoryClickEvent alone doesn't cover drag-placing an item across
     * multiple slots (dragging from the cursor). Without cancelling this too,
     * a player could drag items into/out of the editor or whitelist GUI,
     * bypassing the click-based protection above and risking item loss/dupes
     * or a corrupted GUI layout.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof PvpGui.EditorHolder || holder instanceof PvpGui.WhitelistHolder) {
            event.setCancelled(true);
        }
    }
}
