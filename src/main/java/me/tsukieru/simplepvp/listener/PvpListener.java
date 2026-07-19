package me.tsukieru.simplepvp.listener;

import me.tsukieru.simplepvp.SimplePvpPlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class PvpListener implements Listener {

    private final SimplePvpPlugin plugin;

    public PvpListener(SimplePvpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.dataStore().handleJoin(event.getPlayer(), !event.getPlayer().hasPlayedBefore());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.dataStore().handleQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        var store = plugin.dataStore();
        if (store.isProtected(attacker)) {
            event.setCancelled(true);
            attacker.sendMessage(plugin.messages().withPrefix("attack-protected-attacker"));
            return;
        }
        if (store.isProtected(victim)) {
            event.setCancelled(true);
            attacker.sendMessage(plugin.messages().withPrefix("attack-protected-victim"));
            return;
        }

        var reason = store.getDamageBlockReason(victim, attacker);
        if (reason != null) {
            event.setCancelled(true);
            switch (reason) {
                case ATTACKER_PROTECTED -> attacker.sendMessage(plugin.messages().withPrefix("attack-protected-attacker"));
                case VICTIM_PROTECTED -> attacker.sendMessage(plugin.messages().withPrefix("attack-protected-victim"));
                case ATTACKER_BLOCKS_TARGET -> attacker.sendMessage(plugin.messages().withPrefix(
                        store.getDamageBlockMessageKey(attacker, victim)));
                case VICTIM_BLOCKS_ATTACKER -> attacker.sendMessage(plugin.messages().withPrefix(
                        store.getVictimBlockMessageKey(victim, attacker)));
            }
        }
    }

    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
