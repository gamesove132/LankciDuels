package com.lankciworld.lankciduels.duel;

import com.lankciworld.lankciduels.arena.Arena;
import com.lankciworld.lankciduels.kit.Kit;

import java.util.UUID;

public class Duel {

    private final UUID id = UUID.randomUUID();
    private final UUID player1;
    private final UUID player2;
    private final Arena arena;
    private final Kit kit;

    private volatile DuelState state = DuelState.COUNTDOWN;

    private PlayerDataSnapshot snapshot1;
    private PlayerDataSnapshot snapshot2;

    private double bet = 0;
    private boolean betPaid = false;

    private int countdownTaskId = -1;
    private int timeoutTaskId = -1;
    private long startTime;

    /**
     * Set by DuelManager#onDeath the moment a player dies, while the duel
     * sits in DuelState.DEATH_PENDING waiting for that player's
     * PlayerRespawnEvent. Consumed exactly once by
     * DuelManager#finalizePendingDeath.
     */
    private UUID pendingWinner;
    private DuelManager.EndReason pendingReason;

    /**
     * Independent, cheap idempotency guard for DuelManager#beginEnding -
     * see item 6 (duplicate-end prevention). Backed by AtomicBoolean
     * rather than a plain boolean purely so the "check and flip" is a
     * single atomic operation, even though in practice every mutation
     * already happens on the single-threaded Bukkit main thread.
     */
    private final java.util.concurrent.atomic.AtomicBoolean endingBegun = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Returns true the first time this is called for this duel, and
     * false on every subsequent call - exactly the semantics needed to
     * guarantee beginEnding() can never run twice for the same duel.
     */
    public boolean markEndingBegun() {
        return endingBegun.compareAndSet(false, true);
    }

    public Duel(UUID player1, UUID player2, Arena arena, Kit kit) {
        this.player1 = player1;
        this.player2 = player2;
        this.arena = arena;
        this.kit = kit;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlayer1() {
        return player1;
    }

    public UUID getPlayer2() {
        return player2;
    }

    public UUID getOpponent(UUID uuid) {
        if (uuid.equals(player1)) {
            return player2;
        }
        if (uuid.equals(player2)) {
            return player1;
        }
        return null;
    }

    public boolean involves(UUID uuid) {
        return uuid.equals(player1) || uuid.equals(player2);
    }

    public Arena getArena() {
        return arena;
    }

    public Kit getKit() {
        return kit;
    }

    public DuelState getState() {
        return state;
    }

    public void setState(DuelState state) {
        this.state = state;
    }

    public PlayerDataSnapshot getSnapshot(UUID uuid) {
        if (uuid.equals(player1)) {
            return snapshot1;
        }
        if (uuid.equals(player2)) {
            return snapshot2;
        }
        return null;
    }

    public void setSnapshot(UUID uuid, PlayerDataSnapshot snapshot) {
        if (uuid.equals(player1)) {
            snapshot1 = snapshot;
        } else if (uuid.equals(player2)) {
            snapshot2 = snapshot;
        }
    }

    public double getBet() {
        return bet;
    }

    public void setBet(double bet) {
        this.bet = bet;
    }

    public boolean isBetPaid() {
        return betPaid;
    }

    public void setBetPaid(boolean betPaid) {
        this.betPaid = betPaid;
    }

    public int getCountdownTaskId() {
        return countdownTaskId;
    }

    public void setCountdownTaskId(int countdownTaskId) {
        this.countdownTaskId = countdownTaskId;
    }

    public int getTimeoutTaskId() {
        return timeoutTaskId;
    }

    public void setTimeoutTaskId(int timeoutTaskId) {
        this.timeoutTaskId = timeoutTaskId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public UUID getPendingWinner() {
        return pendingWinner;
    }

    public void setPendingWinner(UUID pendingWinner) {
        this.pendingWinner = pendingWinner;
    }

    public DuelManager.EndReason getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(DuelManager.EndReason pendingReason) {
        this.pendingReason = pendingReason;
    }
}
