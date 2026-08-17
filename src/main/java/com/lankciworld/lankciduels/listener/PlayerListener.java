package com.lankciworld.lankciduels.listener;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.duel.Duel;
import com.lankciworld.lankciduels.duel.DuelState;
import com.lankciworld.lankciduels.duel.PlayerDataSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class PlayerListener implements Listener {

    private final LankciDuels plugin;

    public PlayerListener(LankciDuels plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfigManager().restoreOnRejoin() || !plugin.getPlayerDataStore().hasPending(player.getUniqueId())) {
            return;
        }
        PlayerDataSnapshot snapshot = plugin.getPlayerDataStore().load(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        // Run one tick later so the player has fully finished joining
        // before we touch their inventory/location.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> attemptCrashRecovery(player, snapshot), 5L);
    }

    /**
     * Crash-safe recovery order: confirm the return world is actually
     * loaded, THEN teleport, THEN restore inventory/armor/offhand/XP/
     * health/effects/gamemode, and only clear the on-disk snapshot once
     * that restore is confirmed successful. If anything is missing or
     * fails, the snapshot is deliberately left on disk so the next join
     * (or a future server start) gets another chance instead of the
     * player's original items being lost.
     */
    private void attemptCrashRecovery(Player player, PlayerDataSnapshot snapshot) {
        if (!player.isOnline()) {
            return; // they left again before this task ran - try again on their next join
        }

        Location returnLoc = snapshot.getReturnLocation();
        if (returnLoc == null || returnLoc.getWorld() == null) {
            plugin.getLogger().warning("[LankciDuels] Не вдалося відновити " + player.getName()
                    + ": світ повернення ще не завантажений. Спроба буде повторена при наступному вході.");
            return; // snapshot stays on disk - do not lose it
        }

        player.teleport(returnLoc);

        boolean restored = snapshot.restore(player);
        if (!restored) {
            plugin.getLogger().severe("[LankciDuels] Відновлення стану гравця " + player.getName()
                    + " після незавершеної дуелі не вдалося - snapshot залишається на диску для повторної спроби.");
            return; // do NOT clear - keep the snapshot for a retry
        }

        plugin.getPlayerDataStore().clear(player.getUniqueId());
        plugin.getMessages().send(player, "playerdata-restored");
        // Any bet tied to this duel is handled separately and independently
        // by BetLedger#recoverPendingOnStartup at server boot - not here.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean hadActiveDuel = plugin.getDuelManager().isInDuel(player.getUniqueId());

        plugin.getDuelManager().onQuit(player);

        if (!hadActiveDuel) {
            // No duel-ending stat mutation is pending for this player -
            // safe to drop them from the stats cache right away.
            plugin.getStatsStorage().unload(player.getUniqueId());
        }
        // If they WERE in an active duel, DuelManager#finishEndDuel unloads
        // them itself, strictly after their duel's result has been applied
        // and saved (world-change-delay-ticks later) - unloading here would
        // race ahead of that mutation and silently overwrite their real
        // historical stats with a fresh, not-yet-loaded zeroed object.
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Duel duel = plugin.getDuelManager().getActiveDuel(dead.getUniqueId());
        if (duel == null || duel.getState() != DuelState.FIGHTING) {
            return;
        }
        // The duel kit must never drop on the ground and the player's real
        // belongings are safely held in the pre-duel snapshot (untouched by
        // death), so there is nothing worth keeping here - just make sure
        // nothing spills out for other players to loot.
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);

        // Decide the winner now, but do NOT teleport or touch inventory
        // yet - see DuelManager#onDeath for why (death/respawn cycle,
        // Multiverse-Inventories). That happens once #onRespawn fires.
        plugin.getDuelManager().onDeath(dead);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Duel duel = plugin.getDuelManager().getActiveDuel(player.getUniqueId());
        if (duel == null || duel.getState() != DuelState.DEATH_PENDING) {
            return;
        }

        // Keep the respawn inside the arena's own world for this one tick
        // instead of letting it fall back to a bed/world spawn (which could
        // itself be in yet another Multiverse world group). LankciDuels
        // performs its own controlled teleport back to the lobby right
        // after this, through the same safe, delay-ticks-aware path every
        // other duel ending uses.
        Location arenaSpawn = duel.getPlayer1().equals(player.getUniqueId())
                ? duel.getArena().getSpawn1() : duel.getArena().getSpawn2();
        if (arenaSpawn != null && arenaSpawn.getWorld() != null) {
            event.setRespawnLocation(arenaSpawn);
        }

        // Defer to the next tick so the server has fully applied the
        // respawn (health, gamemode, the reset/empty inventory) before
        // LankciDuels does anything at all with this player.
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getDuelManager().onRespawn(player));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Duel duel = plugin.getDuelManager().getActiveDuel(player.getUniqueId());
        if (duel == null) {
            return;
        }
        boolean duringCountdown = duel.getState() == DuelState.COUNTDOWN && plugin.getConfigManager().countdownDisableDamage();
        // DEATH_PENDING: the winner is already decided and the dead player
        // hasn't respawned/been moved yet - damage no longer matters and
        // must not be able to affect the outcome further, so always block
        // it (not config-gated like the countdown case).
        boolean duringDeathPending = duel.getState() == DuelState.DEATH_PENDING;
        if (duringCountdown || duringDeathPending) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().countdownFreezeMovement()) {
            checkArenaProtection(event);
            return;
        }
        Player player = event.getPlayer();
        Duel duel = plugin.getDuelManager().getActiveDuel(player.getUniqueId());
        if (duel != null && (duel.getState() == DuelState.COUNTDOWN || duel.getState() == DuelState.DEATH_PENDING)) {
            if (hasMovedHorizontally(event)) {
                event.setTo(event.getFrom());
            }
            return;
        }
        checkArenaProtection(event);
    }

    private boolean hasMovedHorizontally(PlayerMoveEvent event) {
        return event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getZ() != event.getTo().getZ()
                || event.getFrom().getY() != event.getTo().getY();
    }

    private void checkArenaProtection(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().protectArenaRadius()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            return; // duelists themselves are obviously allowed on their own arena
        }
        for (var arena : plugin.getArenaManager().getArenas()) {
            if (arena.getActiveDuelId() == null) {
                continue;
            }
            if (arena.isInsideRadius(event.getTo(), plugin.getConfigManager().arenaRadius())
                    && !arena.isInsideRadius(event.getFrom(), plugin.getConfigManager().arenaRadius())) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "arena-area-protected");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Duel duel = plugin.getDuelManager().getActiveDuel(player.getUniqueId());
        if (duel == null) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        // Plugin-initiated teleports (start of duel, restoring at the end) use
        // the PLUGIN cause and must always be allowed through.
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        boolean changingWorld = event.getTo() != null && event.getFrom().getWorld() != null
                && !event.getFrom().getWorld().equals(event.getTo().getWorld());

        if (plugin.getConfigManager().protectionBlockTeleport()
                || (changingWorld && plugin.getConfigManager().protectionBlockWorldChange())) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "action-blocked-in-duel");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Duel duel = plugin.getDuelManager().getActiveDuel(player.getUniqueId());
        if (duel == null) {
            return;
        }

        String raw = event.getMessage().substring(1); // strip leading '/'
        String label = raw.split(" ")[0].toLowerCase();

        // Always allow LankciDuels' own commands (e.g. so an admin/self can still
        // reach /duel or /dueladmin commands while flagged as "in a duel").
        if (label.equals("duel") || label.equals("dueladmin")) {
            return;
        }

        boolean countdownBlocked = duel.getState() == DuelState.COUNTDOWN && plugin.getConfigManager().countdownBlockCommands();
        boolean deathPendingBlocked = duel.getState() == DuelState.DEATH_PENDING;
        if (countdownBlocked || deathPendingBlocked) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "command-blocked-in-duel");
            return;
        }

        for (String blocked : plugin.getConfigManager().blockedCommands()) {
            if (label.equalsIgnoreCase(blocked)) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "command-blocked-in-duel");
                return;
            }
        }

        if (isToggleBlocked(label)) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "command-blocked-in-duel");
        }
    }

    /**
     * The protection.block-* config toggles are a convenience layer on
     * top of protection.blocked-commands: they cover the common aliases
     * for spawn/home/warp/tpa even if the server admin forgot to list
     * them explicitly.
     */
    private boolean isToggleBlocked(String label) {
        if (plugin.getConfigManager().protectionBlockSpawn() && (label.equals("spawn") || label.equals("hub"))) {
            return true;
        }
        if (plugin.getConfigManager().protectionBlockHome() && (label.equals("home") || label.equals("homes"))) {
            return true;
        }
        if (plugin.getConfigManager().protectionBlockWarp() && (label.equals("warp") || label.equals("warps"))) {
            return true;
        }
        if (plugin.getConfigManager().protectionBlockTpa()
                && (label.equals("tpa") || label.equals("tpaccept") || label.equals("tpahere") || label.equals("tpdeny"))) {
            return true;
        }
        return false;
    }
}
