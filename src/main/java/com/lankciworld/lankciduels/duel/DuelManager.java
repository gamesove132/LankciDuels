package com.lankciworld.lankciduels.duel;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.arena.Arena;
import com.lankciworld.lankciduels.arena.ArenaStatus;
import com.lankciworld.lankciduels.kit.Kit;
import com.lankciworld.lankciduels.util.ColorUtil;
import com.lankciworld.lankciduels.util.CooldownManager;
import com.lankciworld.lankciduels.util.SoundUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central brain of the plugin: duel requests, starting/ending duels,
 * countdowns, timeouts and everything that happens while a fight is in
 * progress. Everything in this class is expected to run on the main
 * server thread - Bukkit already guarantees that for commands, GUI
 * clicks and events, and any code that hops off-thread (chat input,
 * database calls) always schedules its continuation back with
 * plugin.sync(...).
 */
public class DuelManager {

    private final LankciDuels plugin;

    /** target uuid -> list of incoming requests */
    private final Map<UUID, List<DuelRequest>> incoming = new ConcurrentHashMap<>();
    /** sender uuid -> list of outgoing requests */
    private final Map<UUID, List<DuelRequest>> outgoing = new ConcurrentHashMap<>();

    /** player uuid -> active duel they are part of */
    private final Map<UUID, Duel> activeByPlayer = new ConcurrentHashMap<>();
    /** duel id -> duel, for admin/listing purposes */
    private final Map<UUID, Duel> duelsById = new ConcurrentHashMap<>();

    /** sender uuid -> selection awaiting a bet amount typed in chat */
    private final Map<UUID, PendingSelection> awaitingBet = new ConcurrentHashMap<>();

    private final CooldownManager requestCooldowns = new CooldownManager();

    public DuelManager(LankciDuels plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────
    //  Basic lookups
    // ─────────────────────────────────────────────────────────────

    public boolean isInDuel(UUID uuid) {
        return activeByPlayer.containsKey(uuid);
    }

    public Duel getActiveDuel(UUID uuid) {
        return activeByPlayer.get(uuid);
    }

    public Collection<Duel> getActiveDuels() {
        return duelsById.values();
    }

    public Map<UUID, PendingSelection> getAwaitingBet() {
        return awaitingBet;
    }

    // ─────────────────────────────────────────────────────────────
    //  Requests
    // ─────────────────────────────────────────────────────────────

    public enum RequestResult {
        OK, SELF, TARGET_OFFLINE, TARGET_IN_DUEL, SENDER_IN_DUEL, COOLDOWN, TOO_MANY_PENDING, DUPLICATE, KIT_DISABLED
    }

    public long remainingCooldownSeconds(UUID sender) {
        return requestCooldowns.remainingSeconds(sender, plugin.getConfigManager().requestCooldownSeconds() * 1000L);
    }

    public RequestResult canSendRequest(Player sender, OfflinePlayer target, Kit kit) {
        if (target == null || target.getUniqueId().equals(sender.getUniqueId())) {
            return RequestResult.SELF;
        }
        if (!target.isOnline()) {
            return RequestResult.TARGET_OFFLINE;
        }
        if (kit != null && !kit.isEnabled()) {
            return RequestResult.KIT_DISABLED;
        }
        if (isInDuel(sender.getUniqueId())) {
            return RequestResult.SENDER_IN_DUEL;
        }
        if (isInDuel(target.getUniqueId())) {
            return RequestResult.TARGET_IN_DUEL;
        }
        if (requestCooldowns.isOnCooldown(sender.getUniqueId(), plugin.getConfigManager().requestCooldownSeconds() * 1000L)) {
            return RequestResult.COOLDOWN;
        }
        List<DuelRequest> pending = outgoing.get(sender.getUniqueId());
        if (pending != null) {
            if (pending.size() >= plugin.getConfigManager().maxPendingPerPlayer()) {
                return RequestResult.TOO_MANY_PENDING;
            }
            for (DuelRequest r : pending) {
                if (r.getTarget().equals(target.getUniqueId())) {
                    return RequestResult.DUPLICATE;
                }
            }
        }
        return RequestResult.OK;
    }

    public void sendRequest(Player sender, Player target, Kit kit, double bet) {
        requestCooldowns.trigger(sender.getUniqueId());

        DuelRequest request = new DuelRequest(sender.getUniqueId(), target.getUniqueId(),
                plugin.getConfigManager().requestExpireSeconds());

        outgoing.computeIfAbsent(sender.getUniqueId(), k -> new ArrayList<>()).add(request);
        incoming.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>()).add(request);

        // store kit + bet choice on the request via a side map keyed by (sender,target)
        pendingKit.put(requestKey(sender.getUniqueId(), target.getUniqueId()), kit);
        pendingBetAmount.put(requestKey(sender.getUniqueId(), target.getUniqueId()), bet);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request),
                plugin.getConfigManager().requestExpireSeconds() * 20L);
        request.setExpireTaskId(task.getTaskId());

        plugin.getMessages().send(sender, "duel-sent", Map.of("player", target.getName()));

        Component accept = Component.text("[ПРИЙНЯТИ]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/duel accept " + sender.getName()));
        Component space = Component.text("   ");
        Component deny = Component.text("[ВІДХИЛИТИ]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/duel deny " + sender.getName()));

        target.sendMessage(Component.text(plugin.getMessages().prefix()).append(
                Component.text(plugin.getMessages().get("duel-received", Map.of("player", sender.getName())))));
        target.sendMessage(accept.append(space).append(deny));

        SoundUtil.play(target, plugin.getConfigManager().sound("invite"), plugin.getLogger(), plugin.getConfigManager().debug());
    }

    private void expireRequest(DuelRequest request) {
        if (!removeRequest(request)) {
            return; // already handled (accepted/denied/cancelled)
        }
        Player sender = Bukkit.getPlayer(request.getSender());
        Player target = Bukkit.getPlayer(request.getTarget());
        if (target != null) {
            plugin.getMessages().send(target, "duel-expired", Map.of("player", sender != null ? sender.getName() : "?"));
        }
        if (sender != null) {
            plugin.getMessages().send(sender, "duel-expired-sender", Map.of("player", target != null ? target.getName() : "?"));
        }
    }

    private boolean removeRequest(DuelRequest request) {
        boolean removed = false;
        List<DuelRequest> in = incoming.get(request.getTarget());
        if (in != null) {
            removed = in.remove(request);
            if (in.isEmpty()) {
                incoming.remove(request.getTarget());
            }
        }
        List<DuelRequest> out = outgoing.get(request.getSender());
        if (out != null) {
            out.remove(request);
            if (out.isEmpty()) {
                outgoing.remove(request.getSender());
            }
        }
        pendingKit.remove(requestKey(request.getSender(), request.getTarget()));
        pendingBetAmount.remove(requestKey(request.getSender(), request.getTarget()));
        return removed;
    }

    private final Map<String, Kit> pendingKit = new ConcurrentHashMap<>();
    private final Map<String, Double> pendingBetAmount = new ConcurrentHashMap<>();

    private String requestKey(UUID sender, UUID target) {
        return sender + ":" + target;
    }

    public Optional<DuelRequest> findRequest(UUID target, UUID sender) {
        List<DuelRequest> list = incoming.get(target);
        if (list == null) {
            return Optional.empty();
        }
        return list.stream().filter(r -> r.getSender().equals(sender)).findFirst();
    }

    public List<DuelRequest> getOutgoing(UUID sender) {
        return outgoing.getOrDefault(sender, List.of());
    }

    public void cancelAllOutgoing(Player sender) {
        List<DuelRequest> list = new ArrayList<>(outgoing.getOrDefault(sender.getUniqueId(), List.of()));
        for (DuelRequest request : list) {
            if (request.getExpireTaskId() != -1) {
                Bukkit.getScheduler().cancelTask(request.getExpireTaskId());
            }
            removeRequest(request);
            Player target = Bukkit.getPlayer(request.getTarget());
            if (target != null) {
                plugin.getMessages().send(target, "duel-cancelled-target", Map.of("player", sender.getName()));
            }
        }
    }

    public boolean deny(Player target, Player sender) {
        Optional<DuelRequest> requestOpt = findRequest(target.getUniqueId(), sender.getUniqueId());
        if (requestOpt.isEmpty()) {
            return false;
        }
        DuelRequest request = requestOpt.get();
        if (request.getExpireTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(request.getExpireTaskId());
        }
        removeRequest(request);
        SoundUtil.play(target, plugin.getConfigManager().sound("deny"), plugin.getLogger(), plugin.getConfigManager().debug());
        plugin.getMessages().send(target, "duel-denied-self", Map.of("player", sender.getName()));
        plugin.getMessages().send(sender, "duel-denied-target", Map.of("player", target.getName()));
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    //  Accepting a request -> starting the duel
    // ─────────────────────────────────────────────────────────────

    public enum AcceptResult {
        OK, NO_REQUEST, NO_ARENA, ALREADY_IN_DUEL, BET_FUNDS
    }

    public AcceptResult accept(Player target, Player sender) {
        Optional<DuelRequest> requestOpt = findRequest(target.getUniqueId(), sender.getUniqueId());
        if (requestOpt.isEmpty()) {
            return AcceptResult.NO_REQUEST;
        }
        if (isInDuel(target.getUniqueId()) || isInDuel(sender.getUniqueId())) {
            removeRequest(requestOpt.get());
            return AcceptResult.ALREADY_IN_DUEL;
        }

        DuelRequest request = requestOpt.get();
        if (request.getExpireTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(request.getExpireTaskId());
        }

        Kit kit = pendingKit.get(requestKey(sender.getUniqueId(), target.getUniqueId()));
        double bet = pendingBetAmount.getOrDefault(requestKey(sender.getUniqueId(), target.getUniqueId()), 0.0);
        removeRequest(request);

        if (kit == null) {
            // Fallback: first enabled kit, in case data was lost somehow.
            kit = plugin.getKitManager().getKits().values().stream()
                    .filter(Kit::isEnabled).findFirst().orElse(null);
        }
        if (kit == null) {
            return AcceptResult.NO_ARENA;
        }

        Optional<Arena> arenaOpt = plugin.getArenaManager().findFreeArena();
        if (arenaOpt.isEmpty()) {
            plugin.getMessages().send(sender, "no-free-arena");
            plugin.getMessages().send(target, "no-free-arena");
            return AcceptResult.NO_ARENA;
        }

        if (plugin.getConfigManager().bettingEnabled() && bet > 0) {
            if (!plugin.getVaultHook().isEnabled()) {
                plugin.getMessages().send(sender, "bet-no-economy");
                plugin.getMessages().send(target, "bet-no-economy");
                return AcceptResult.BET_FUNDS;
            }
            if (!plugin.getVaultHook().has(sender, bet) || !plugin.getVaultHook().has(target, bet)) {
                plugin.getMessages().send(sender, "bet-not-enough-money");
                plugin.getMessages().send(target, "bet-not-enough-money");
                return AcceptResult.BET_FUNDS;
            }
        }

        SoundUtil.play(sender, plugin.getConfigManager().sound("accept"), plugin.getLogger(), plugin.getConfigManager().debug());
        SoundUtil.play(target, plugin.getConfigManager().sound("accept"), plugin.getLogger(), plugin.getConfigManager().debug());
        plugin.getMessages().send(sender, "duel-accepted-target", Map.of("player", target.getName()));
        plugin.getMessages().send(target, "duel-accepted-self", Map.of("player", sender.getName()));

        startDuel(sender, target, arenaOpt.get(), kit, bet);
        return AcceptResult.OK;
    }

    // ─────────────────────────────────────────────────────────────
    //  Starting a duel
    // ─────────────────────────────────────────────────────────────

    private void startDuel(Player p1, Player p2, Arena arena, Kit kit, double bet) {
        arena.setStatus(ArenaStatus.STARTING);

        Duel duel = new Duel(p1.getUniqueId(), p2.getUniqueId(), arena, kit);
        arena.setActiveDuelId(duel.getId());

        if (plugin.getConfigManager().bettingEnabled() && bet > 0 && plugin.getVaultHook().isEnabled()) {
            // Persist the PENDING record BEFORE moving any money - this is
            // exactly what lets a crash between "money withdrawn" and
            // "duel settled" be recovered safely on the next server start
            // (see BetLedger#recoverPendingOnStartup). If either write
            // fails, we must NOT withdraw anything for this bet at all -
            // an untracked withdrawal could never be recovered from a
            // crash, so we deliberately fail safe here.
            boolean recorded1 = plugin.getBetLedger().recordPending(duel.getId(), p1.getUniqueId(), bet);
            boolean recorded2 = plugin.getBetLedger().recordPending(duel.getId(), p2.getUniqueId(), bet);

            if (recorded1 && recorded2) {
                boolean w1 = plugin.getVaultHook().withdraw(p1, bet);
                boolean w2 = plugin.getVaultHook().withdraw(p2, bet);
                if (w1 && w2) {
                    duel.setBet(bet);
                    duel.setBetPaid(true);
                    // Stays PENDING on disk - correctly settled/refunded (and the
                    // ledger entry cleared) once this duel actually ends, see
                    // #handleBet.
                } else {
                    // Refund whichever succeeded - never keep money if we can't collect from both.
                    if (w1) plugin.getVaultHook().deposit(p1, bet);
                    if (w2) plugin.getVaultHook().deposit(p2, bet);
                    // Nothing is actually owed anymore either way - retire both
                    // speculative PENDING records immediately so crash-recovery
                    // never tries to refund money that was already returned (or
                    // never taken in the first place).
                    plugin.getBetLedger().clear(duel.getId(), p1.getUniqueId());
                    plugin.getBetLedger().clear(duel.getId(), p2.getUniqueId());
                }
            } else {
                // Could not safely record intent to disk - clean up whichever
                // record DID get written, and start the duel WITHOUT a bet
                // rather than ever withdraw money with no crash-safe record
                // of it existing.
                plugin.getBetLedger().clear(duel.getId(), p1.getUniqueId());
                plugin.getBetLedger().clear(duel.getId(), p2.getUniqueId());
                plugin.getLogger().severe("[LankciDuels] Не вдалося безпечно зареєструвати ставку на диску - дуель почнеться БЕЗ ставки.");
                plugin.getMessages().send(p1, "bet-ledger-failed");
                plugin.getMessages().send(p2, "bet-ledger-failed");
            }
        }

        // IMPORTANT ordering note (Multiverse-Inventories compatibility):
        // the snapshot MUST be captured here, before either player is
        // teleported anywhere - this is still the player's true pre-duel
        // (lobby world-group) inventory/state, untouched by any
        // world-change-triggered inventory swap.
        var lobby = plugin.getConfigManager().lobbyLocation();
        PlayerDataSnapshot snap1 = PlayerDataSnapshot.capture(p1, lobby != null ? lobby : p1.getLocation());
        PlayerDataSnapshot snap2 = PlayerDataSnapshot.capture(p2, lobby != null ? lobby : p2.getLocation());
        duel.setSnapshot(p1.getUniqueId(), snap1);
        duel.setSnapshot(p2.getUniqueId(), snap2);

        // Persist to disk immediately so a crash mid-duel never loses items.
        plugin.getPlayerDataStore().save(duel.getSnapshot(p1.getUniqueId()));
        plugin.getPlayerDataStore().save(duel.getSnapshot(p2.getUniqueId()));

        activeByPlayer.put(p1.getUniqueId(), duel);
        activeByPlayer.put(p2.getUniqueId(), duel);
        duelsById.put(duel.getId(), duel);

        // Step 1: teleport only. If the arena world belongs to a different
        // Multiverse world group than the lobby, Multiverse-Inventories
        // reacts to the resulting PlayerChangedWorldEvent and may swap the
        // player's inventory to that group's stored profile. We deliberately
        // do NOT touch the inventory (no kit yet) in this same tick.
        p1.teleport(arena.getSpawn1());
        p2.teleport(arena.getSpawn2());

        // Step 2 (next tick, or later if configured): every plugin listening
        // to PlayerChangedWorldEvent - including Multiverse-Inventories - has
        // already run by the time this task executes, so applying the kit
        // here is guaranteed to be the last write and cannot be clobbered.
        int delay = plugin.getConfigManager().worldChangeDelayTicks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishStartDuel(duel), delay);
    }

    private void finishStartDuel(Duel duel) {
        if (duel.getState() != DuelState.COUNTDOWN) {
            return; // already aborted/ended (e.g. force-stopped in the meantime)
        }
        Player p1 = Bukkit.getPlayer(duel.getPlayer1());
        Player p2 = Bukkit.getPlayer(duel.getPlayer2());

        if (p1 == null || !p1.isOnline() || p2 == null || !p2.isOnline()) {
            // A player disconnected in the brief window between the teleport
            // and this delayed callback - handle it exactly like a normal
            // mid-duel quit instead of leaving the duel half-started.
            UUID stillOnline = (p1 != null && p1.isOnline()) ? duel.getPlayer1()
                    : (p2 != null && p2.isOnline()) ? duel.getPlayer2() : null;
            if (plugin.getConfigManager().quitEnabled() && plugin.getConfigManager().quitOpponentWins() && stillOnline != null) {
                endDuel(duel, stillOnline, EndReason.QUIT);
            } else {
                endDuel(duel, null, EndReason.QUIT);
            }
            return;
        }

        plugin.getKitManager().apply(p1, duel.getKit());
        plugin.getKitManager().apply(p2, duel.getKit());

        runCountdown(duel, p1, p2);
    }

    private void runCountdown(Duel duel, Player p1, Player p2) {
        int seconds = plugin.getConfigManager().countdownSeconds();
        duel.setState(DuelState.COUNTDOWN);

        BukkitRunnable runnable = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                Player a = Bukkit.getPlayer(duel.getPlayer1());
                Player b = Bukkit.getPlayer(duel.getPlayer2());
                if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
                    // A player disconnected mid-countdown - handled by the quit listener,
                    // just make sure this task stops running.
                    cancel();
                    return;
                }
                if (remaining > 0) {
                    String text = plugin.getMessages().get("countdown-tick", Map.of("seconds", String.valueOf(remaining)));
                    a.sendTitle(text, "", 0, 25, 5);
                    b.sendTitle(text, "", 0, 25, 5);
                    SoundUtil.play(a, plugin.getConfigManager().sound("countdown"), plugin.getLogger(), plugin.getConfigManager().debug());
                    SoundUtil.play(b, plugin.getConfigManager().sound("countdown"), plugin.getLogger(), plugin.getConfigManager().debug());
                    remaining--;
                } else {
                    String go = plugin.getMessages().get("countdown-go");
                    a.sendTitle(go, "", 0, 20, 5);
                    b.sendTitle(go, "", 0, 20, 5);
                    SoundUtil.play(a, plugin.getConfigManager().sound("start"), plugin.getLogger(), plugin.getConfigManager().debug());
                    SoundUtil.play(b, plugin.getConfigManager().sound("start"), plugin.getLogger(), plugin.getConfigManager().debug());

                    duel.setState(DuelState.FIGHTING);
                    duel.getArena().setStatus(ArenaStatus.IN_USE);
                    duel.setStartTime(System.currentTimeMillis());

                    plugin.getMessages().send(a, "duel-start", Map.of("opponent", b.getName()));
                    plugin.getMessages().send(b, "duel-start", Map.of("opponent", a.getName()));

                    startTimeoutIfNeeded(duel);
                    cancel();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, 20L);
        duel.setCountdownTaskId(task.getTaskId());
    }

    private void startTimeoutIfNeeded(Duel duel) {
        if (!plugin.getConfigManager().timeoutEnabled()) {
            return;
        }
        long delay = plugin.getConfigManager().timeoutSeconds() * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> onTimeout(duel), delay);
        duel.setTimeoutTaskId(task.getTaskId());
    }

    private void onTimeout(Duel duel) {
        if (duel.getState() != DuelState.FIGHTING) {
            return;
        }
        String action = plugin.getConfigManager().timeoutAction();
        if ("end_current_hp".equalsIgnoreCase(action)) {
            Player p1 = Bukkit.getPlayer(duel.getPlayer1());
            Player p2 = Bukkit.getPlayer(duel.getPlayer2());
            if (p1 != null && p2 != null) {
                UUID winner = p1.getHealth() >= p2.getHealth() ? p1.getUniqueId() : p2.getUniqueId();
                endDuel(duel, winner, EndReason.TIMEOUT);
                return;
            }
        }
        endDuel(duel, null, EndReason.TIMEOUT);
    }

    // ─────────────────────────────────────────────────────────────
    //  Ending a duel
    // ─────────────────────────────────────────────────────────────

    public enum EndReason {
        DEATH, QUIT, FORCESTOP, TIMEOUT
    }

    /**
     * Generic duel-ending entry point used by quit/forcestop/timeout - it
     * ends the duel right away. Death is handled differently (see
     * {@link #onDeath} / {@link #onRespawn}): the outcome is decided
     * immediately but the actual teleport/inventory work is deferred
     * until the dead player's respawn has completed. winner == null means
     * a draw (used for forcestop and some timeout configurations) - no
     * stats/rating changes are applied in that case, and any bet is
     * refunded rather than paid out.
     */
    public void endDuel(Duel duel, UUID winner, EndReason reason) {
        if (duel.getState() == DuelState.ENDING) {
            return; // already being handled - avoids double-processing on rapid events
        }
        duel.setState(DuelState.ENDING);
        beginEnding(duel, winner, reason);
    }

    /**
     * Does the actual work of ending a duel: frees the arena, teleports
     * both players back, and schedules the delayed inventory restore.
     * Called exactly once per duel, either directly from {@link #endDuel}
     * (quit/forcestop/timeout) or from {@link #finalizePendingDeath}
     * (death, after the respawn has completed) - both call sites first
     * transition the duel to DuelState.ENDING, which is what actually
     * guarantees "exactly once": every other trigger checks that state
     * before doing anything.
     */
    private void beginEnding(Duel duel, UUID winner, EndReason reason) {
        if (!duel.markEndingBegun()) {
            // Defensive guard: beginEnding must run at most once per duel.
            // Every call site already transitions the duel's DuelState to
            // ENDING before calling this, which is normally what prevents a
            // second call - this flag is a second, independent safety net
            // directly on the Duel object itself.
            plugin.getLogger().warning("[LankciDuels] beginEnding() викликано повторно для дуелі " + duel.getId() + " - проігноровано.");
            return;
        }

        if (duel.getCountdownTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(duel.getCountdownTaskId());
        }
        if (duel.getTimeoutTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(duel.getTimeoutTaskId());
        }

        duel.getArena().setStatus(ArenaStatus.FREE);
        duel.getArena().setActiveDuelId(null);

        UUID loser = winner != null ? duel.getOpponent(winner) : null;

        // Step 1: teleport back to the lobby/return-location immediately.
        // We deliberately do NOT touch inventory/health/exp here - if the
        // return world belongs to a different Multiverse world group than
        // the arena, Multiverse-Inventories will react to the
        // PlayerChangedWorldEvent fired by this teleport and may swap the
        // player's inventory to that group's stored profile.
        teleportBack(duel, duel.getPlayer1());
        teleportBack(duel, duel.getPlayer2());

        // Step 2 (next tick, or later if configured): every plugin that
        // reacts to the world change - including Multiverse-Inventories -
        // has already run by the time this executes, so restoring the
        // snapshot here is guaranteed to be the last write and cannot be
        // silently overwritten by a world-group inventory swap.
        int delay = plugin.getConfigManager().worldChangeDelayTicks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishEndDuel(duel, winner, loser, reason), delay);
    }

    private void teleportBack(Duel duel, UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        PlayerDataSnapshot snapshot = duel.getSnapshot(uuid);
        if (player == null || !player.isOnline() || snapshot == null) {
            return; // offline - PlayerDataStore/PlayerListener#onJoin handles this player on rejoin
        }
        var loc = snapshot.getReturnLocation();
        if (loc == null || loc.getWorld() == null) {
            // Return world not currently loaded (e.g. Multiverse hasn't
            // finished loading it back after a restart) - do NOT teleport.
            // The on-disk snapshot is untouched, so PlayerListener#onJoin
            // will still recover this player correctly on their next login,
            // once the world is available.
            plugin.getLogger().warning("[LankciDuels] Світ повернення для " + uuid + " недоступний - teleport відкладено.");
            return;
        }
        player.teleport(loc);
    }

    private void finishEndDuel(Duel duel, UUID winner, UUID loser, EndReason reason) {
        restoreSnapshotOnly(duel, duel.getPlayer1());
        restoreSnapshotOnly(duel, duel.getPlayer2());

        handleBet(duel, winner);

        if (winner != null) {
            applyResult(duel, winner, loser, reason);
        } else {
            applyDraw(duel);
        }

        // Note: dropping an offline participant from the stats cache now
        // happens INSIDE applyResult/applyDraw, right after their stat
        // mutation+save actually completes (which is usually immediate,
        // but can be deferred - see StatsStorage#getOrLoadAsync). Doing it
        // here unconditionally would race ahead of a deferred mutation in
        // that rare case and silently overwrite real historical stats.

        activeByPlayer.remove(duel.getPlayer1());
        activeByPlayer.remove(duel.getPlayer2());
        duelsById.remove(duel.getId());
    }

    private void unloadStatsIfOffline(UUID uuid) {
        if (Bukkit.getPlayer(uuid) == null) {
            plugin.getStatsStorage().unload(uuid);
        }
    }

    /**
     * Restores inventory/armor/offhand/exp/food/health/potions/gamemode
     * only - the teleport back to the lobby already happened in
     * {@link #teleportBack} one (or more) ticks earlier, so this call is
     * the deliberate final write that wins over any Multiverse-Inventories
     * world-group swap triggered by that teleport.
     *
     * Crash-safety: the on-disk snapshot is only cleared once restore()
     * has confirmed success. If it fails (or the player went offline in
     * the meantime), the snapshot is left in place and a retry is
     * scheduled shortly after - worst case, PlayerListener#onJoin still
     * picks it up on their next login, so items are never lost.
     */
    private void restoreSnapshotOnly(Duel duel, UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        PlayerDataSnapshot snapshot = duel.getSnapshot(uuid);
        if (player == null || !player.isOnline() || snapshot == null) {
            // Offline (or nothing to restore) - PlayerListener#onJoin
            // handles restoration from disk on their next login.
            return;
        }

        boolean restored = snapshot.restore(player);
        if (restored) {
            plugin.getPlayerDataStore().clear(uuid);
            return;
        }

        plugin.getLogger().severe("[LankciDuels] Не вдалося одразу відновити стан гравця " + uuid
                + " - буде здійснена повторна спроба через 2 секунди. Snapshot залишається на диску.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> retryRestoreSnapshot(snapshot, uuid), 40L);
    }

    private void retryRestoreSnapshot(PlayerDataSnapshot snapshot, UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return; // they're gone - PlayerListener#onJoin will handle it whenever they come back
        }
        if (snapshot.restore(player)) {
            plugin.getPlayerDataStore().clear(uuid);
        } else {
            plugin.getLogger().severe("[LankciDuels] Повторна спроба відновлення для " + uuid
                    + " також не вдалася. Snapshot залишається на диску; PlayerListener#onJoin спробує ще раз при наступному вході.");
        }
    }

    private void handleBet(Duel duel, UUID winner) {
        if (!duel.isBetPaid() || duel.getBet() <= 0) {
            return;
        }
        double totalPot = duel.getBet() * 2.0;
        if (winner != null) {
            OfflinePlayer winnerPlayer = Bukkit.getOfflinePlayer(winner);
            boolean paid = plugin.getVaultHook().deposit(winnerPlayer, totalPot);
            if (paid) {
                // The winner received the full pot - both players' stakes
                // have been accounted for, retire both ledger records.
                plugin.getBetLedger().clear(duel.getId(), duel.getPlayer1());
                plugin.getBetLedger().clear(duel.getId(), duel.getPlayer2());
            } else {
                plugin.getLogger().severe("[LankciDuels] Не вдалося виплатити банк ставки гравцю " + winner
                        + " - bet-ledger записи лишаються PENDING; кошти будуть повернені кожному гравцю "
                        + "автоматично при наступному запуску сервера (BetLedger#recoverPendingOnStartup).");
                // Deliberately do NOT clear - never pretend a failed
                // financial operation succeeded.
            }
        } else {
            OfflinePlayer p1 = Bukkit.getOfflinePlayer(duel.getPlayer1());
            OfflinePlayer p2 = Bukkit.getOfflinePlayer(duel.getPlayer2());
            boolean refunded1 = plugin.getVaultHook().deposit(p1, duel.getBet());
            boolean refunded2 = plugin.getVaultHook().deposit(p2, duel.getBet());
            Player op1 = p1.getPlayer();
            Player op2 = p2.getPlayer();

            if (refunded1) {
                plugin.getBetLedger().clear(duel.getId(), duel.getPlayer1());
                if (op1 != null) plugin.getMessages().send(op1, "bet-refunded");
            } else {
                plugin.getLogger().severe("[LankciDuels] Не вдалося повернути ставку гравцю " + duel.getPlayer1()
                        + " - запис лишається PENDING для crash-recovery при наступному запуску сервера.");
            }
            if (refunded2) {
                plugin.getBetLedger().clear(duel.getId(), duel.getPlayer2());
                if (op2 != null) plugin.getMessages().send(op2, "bet-refunded");
            } else {
                plugin.getLogger().severe("[LankciDuels] Не вдалося повернути ставку гравцю " + duel.getPlayer2()
                        + " - запис лишається PENDING для crash-recovery при наступному запуску сервера.");
            }
        }
    }

    private void applyResult(Duel duel, UUID winnerUuid, UUID loserUuid, EndReason reason) {
        Player winner = Bukkit.getPlayer(winnerUuid);
        Player loser = Bukkit.getPlayer(loserUuid);
        String winnerName = winner != null ? winner.getName() : Bukkit.getOfflinePlayer(winnerUuid).getName();
        String loserName = loser != null ? loser.getName() : Bukkit.getOfflinePlayer(loserUuid).getName();

        if (plugin.getConfigManager().statsEnabled()) {
            // getOrLoadAsync (rather than getOrLoad) guarantees registerWin/
            // registerLoss/setRating below only ever mutate a CONFIRMED-
            // loaded PlayerStats object - never a freshly-created, still-
            // zeroed placeholder whose real historical totals haven't come
            // back from SQLite yet. In the overwhelming majority of cases
            // (the player has been online at least the countdown duration)
            // this callback fires immediately/synchronously; it only
            // genuinely defers in the rare case of a brand-new cache entry.
            plugin.getStatsStorage().getOrLoadAsync(winnerUuid, winnerName != null ? winnerName : "?", winnerStats ->
                    plugin.getStatsStorage().getOrLoadAsync(loserUuid, loserName != null ? loserName : "?", loserStats -> {
                        winnerStats.registerWin();
                        loserStats.registerLoss();
                        if (reason == EndReason.DEATH) {
                            winnerStats.registerKill();
                            loserStats.registerDeath();
                        }

                        if (plugin.getConfigManager().ratingEnabled()) {
                            String kitId = duel.getKit().getId();
                            int wRating = winnerStats.getRating(kitId, plugin.getConfigManager().ratingStarting());
                            int lRating = loserStats.getRating(kitId, plugin.getConfigManager().ratingStarting());
                            winnerStats.setRating(kitId, wRating + plugin.getConfigManager().ratingWinAmount());
                            loserStats.setRating(kitId, Math.max(0, lRating - plugin.getConfigManager().ratingLossAmount()));
                        }

                        plugin.getStatsStorage().saveAsync(winnerStats);
                        plugin.getStatsStorage().saveAsync(loserStats);

                        // Only now that the mutation has actually happened is
                        // it safe to drop an offline participant from the
                        // stats cache - see PlayerListener#onQuit for the
                        // other half of this guarantee.
                        unloadStatsIfOffline(winnerUuid);
                        unloadStatsIfOffline(loserUuid);
                    }));
        } else {
            // Stats tracking disabled entirely - nothing was ever cached
            // for this duel via getOrLoad, so this is a harmless no-op,
            // but keeps cache hygiene consistent either way.
            unloadStatsIfOffline(winnerUuid);
            unloadStatsIfOffline(loserUuid);
        }

        if (plugin.getConfigManager().rewardsEnabled() && winner != null) {
            for (String cmd : plugin.getConfigManager().rewardsWinCommands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", winner.getName()));
            }
        }
        if (plugin.getConfigManager().rewardsEnabled() && loser != null) {
            for (String cmd : plugin.getConfigManager().rewardsLossCommands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", loser.getName()));
            }
        }

        if (winner != null) {
            showResultTitle(winner, "win", loserName);
            plugin.getMessages().send(winner, "win", Map.of("opponent", loserName != null ? loserName : "?"));
            SoundUtil.play(winner, plugin.getConfigManager().sound("win"), plugin.getLogger(), plugin.getConfigManager().debug());
        }
        if (loser != null) {
            if (reason == EndReason.QUIT) {
                // The disconnect message already explained what happened - no extra lose title spam.
            } else {
                showResultTitle(loser, "lose", winnerName);
            }
            plugin.getMessages().send(loser, "lose", Map.of("opponent", winnerName != null ? winnerName : "?"));
            SoundUtil.play(loser, plugin.getConfigManager().sound("lose"), plugin.getLogger(), plugin.getConfigManager().debug());
        }

        Bukkit.broadcastMessage(plugin.getMessages().prefix() + plugin.getMessages().get("win-broadcast", Map.of(
                "winner", winnerName != null ? winnerName : "?",
                "loser", loserName != null ? loserName : "?",
                "kit", duel.getKit().getDisplayName())));
    }

    private void applyDraw(Duel duel) {
        Player p1 = Bukkit.getPlayer(duel.getPlayer1());
        Player p2 = Bukkit.getPlayer(duel.getPlayer2());

        if (plugin.getConfigManager().statsEnabled()) {
            if (p1 != null) {
                plugin.getStatsStorage().getOrLoadAsync(p1.getUniqueId(), p1.getName(), stats -> {
                    stats.registerDraw();
                    plugin.getStatsStorage().saveAsync(stats);
                    unloadStatsIfOffline(duel.getPlayer1());
                });
            } else {
                unloadStatsIfOffline(duel.getPlayer1());
            }
            if (p2 != null) {
                plugin.getStatsStorage().getOrLoadAsync(p2.getUniqueId(), p2.getName(), stats -> {
                    stats.registerDraw();
                    plugin.getStatsStorage().saveAsync(stats);
                    unloadStatsIfOffline(duel.getPlayer2());
                });
            } else {
                unloadStatsIfOffline(duel.getPlayer2());
            }
        } else {
            unloadStatsIfOffline(duel.getPlayer1());
            unloadStatsIfOffline(duel.getPlayer2());
        }

        if (p1 != null) {
            showResultTitle(p1, "draw", "");
            plugin.getMessages().send(p1, "draw");
        }
        if (p2 != null) {
            showResultTitle(p2, "draw", "");
            plugin.getMessages().send(p2, "draw");
        }
    }

    private void showResultTitle(Player player, String key, String opponentName) {
        ConfigurationSection section = plugin.getConfigManager().titleSection(key);
        if (section == null) {
            return;
        }
        String titleText = ColorUtil.color(section.getString("title", "").replace("%opponent%", opponentName == null ? "" : opponentName));
        String subtitleText = ColorUtil.color(section.getString("subtitle", "").replace("%opponent%", opponentName == null ? "" : opponentName));
        Title.Times times = Title.Times.times(
                Duration.ofMillis(section.getInt("fade-in", 10) * 50L),
                Duration.ofMillis(section.getInt("stay", 60) * 50L),
                Duration.ofMillis(section.getInt("fade-out", 10) * 50L));
        player.showTitle(Title.title(Component.text(titleText), Component.text(subtitleText), times));
    }

    // ─────────────────────────────────────────────────────────────
    //  External triggers: death, quit, forcestop
    // ─────────────────────────────────────────────────────────────

    public void onDeath(Player dead) {
        Duel duel = getActiveDuel(dead.getUniqueId());
        if (duel == null || duel.getState() != DuelState.FIGHTING) {
            return;
        }
        // The outcome is decided right now (whoever died lost), but we
        // deliberately do NOT teleport or touch inventory yet: the death/
        // respawn cycle (drops, the death screen, keepInventory handling,
        // the client's own respawn) has not finished, and Multiverse-
        // Inventories hasn't had a chance to react to anything yet either.
        // We wait for PlayerRespawnEvent - see #onRespawn.
        if (duel.getTimeoutTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(duel.getTimeoutTaskId());
            duel.setTimeoutTaskId(-1);
        }
        UUID winner = duel.getOpponent(dead.getUniqueId());
        duel.setPendingWinner(winner);
        duel.setPendingReason(EndReason.DEATH);
        duel.setState(DuelState.DEATH_PENDING);
    }

    /**
     * Called once the dead player's respawn has actually happened
     * (PlayerListener defers this by scheduling it for the very next
     * tick after PlayerRespawnEvent, so the server has fully applied the
     * respawn before we do anything). This is where the duel that was
     * put on hold in {@link #onDeath} finally gets teleported back and
     * restored, using exactly the same safe two-phase sequence as every
     * other duel ending.
     */
    public void onRespawn(Player respawned) {
        Duel duel = getActiveDuel(respawned.getUniqueId());
        if (duel == null) {
            return;
        }
        finalizePendingDeath(duel);
    }

    /**
     * Finalizes a duel that is sitting in DEATH_PENDING, using the
     * winner/reason recorded by {@link #onDeath}. Safe to call more than
     * once (e.g. once from #onRespawn and, in the edge case where the
     * dead player disconnects from the death screen before respawning,
     * once from #onQuit) - only the first call actually does anything,
     * because it immediately flips the state to ENDING.
     */
    private void finalizePendingDeath(Duel duel) {
        if (duel.getState() != DuelState.DEATH_PENDING) {
            return;
        }
        duel.setState(DuelState.ENDING);
        beginEnding(duel, duel.getPendingWinner(), duel.getPendingReason());
    }

    public void onQuit(Player quitter) {
        // Cancel any pending requests involving this player so they don't linger.
        cancelAllOutgoing(quitter);
        List<DuelRequest> theirIncoming = new ArrayList<>(incoming.getOrDefault(quitter.getUniqueId(), List.of()));
        for (DuelRequest r : theirIncoming) {
            removeRequest(r);
        }

        Duel duel = getActiveDuel(quitter.getUniqueId());
        if (duel == null) {
            return;
        }

        switch (duel.getState()) {
            case COUNTDOWN, FIGHTING -> {
                UUID opponentUuid = duel.getOpponent(quitter.getUniqueId());
                Player opponent = Bukkit.getPlayer(opponentUuid);
                if (opponent != null) {
                    plugin.getMessages().send(opponent, "opponent-disconnected", Map.of("player",
                            quitter.getName() != null ? quitter.getName() : "?"));
                }
                if (plugin.getConfigManager().quitEnabled() && plugin.getConfigManager().quitOpponentWins()) {
                    endDuel(duel, opponentUuid, EndReason.QUIT);
                } else {
                    endDuel(duel, null, EndReason.QUIT);
                }
            }
            case DEATH_PENDING -> {
                // The dead player disconnected from the death screen without
                // ever respawning - no PlayerRespawnEvent will ever come for
                // them, so finalize using the outcome that death already
                // decided instead of waiting forever.
                finalizePendingDeath(duel);
            }
            default -> {
                // ENDING - already being handled elsewhere, nothing to do.
            }
        }
    }

    public boolean forceStop(Player admin, OfflinePlayer target) {
        Duel duel = getActiveDuel(target.getUniqueId());
        if (duel == null) {
            return false;
        }
        Player p1 = Bukkit.getPlayer(duel.getPlayer1());
        Player p2 = Bukkit.getPlayer(duel.getPlayer2());
        if (p1 != null) {
            plugin.getMessages().send(p1, "duel-forcestopped");
        }
        if (p2 != null) {
            plugin.getMessages().send(p2, "duel-forcestopped");
        }
        endDuel(duel, null, EndReason.FORCESTOP);
        return true;
    }
}
