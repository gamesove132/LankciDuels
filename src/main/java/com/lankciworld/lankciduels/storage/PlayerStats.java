package com.lankciworld.lankciduels.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable per-player stats. Fields are `volatile` purely for safe
 * cross-thread *visibility* (so StatsStorage's dedicated DB thread
 * always sees the latest values when reading them to persist), but
 * every actual *mutation* (registerWin/Loss/Draw, setRating) happens
 * exclusively on the main server thread as part of ending a duel - see
 * DuelManager#applyResult/applyDraw and StatsStorage#loadAsync for how
 * that invariant is preserved even around asynchronous DB loads.
 */
public class PlayerStats {

    private final UUID uuid;
    private volatile String name;
    private volatile int kills;
    private volatile int deaths;
    private volatile int wins;
    private volatile int losses;
    private volatile int played;
    private volatile int winstreak;
    private volatile int bestWinstreak;
    /**
     * True once this object's numbers are known to be correct - either
     * because the initial SQLite read completed (whether or not a row
     * existed for this player), or because a duel already finished for
     * this player on the main thread (which makes the in-memory values
     * authoritative immediately, without waiting on the DB). False means
     * "still loading" - callers that must never show a misleading zero
     * (see DuelCommand#handleStats) should wait rather than display a
     * not-yet-loaded PlayerStats.
     */
    private volatile boolean loaded;
    private final Map<String, Integer> ratings = new ConcurrentHashMap<>();

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getKills() {
        return kills;
    }

    public void setKills(int kills) {
        this.kills = kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public int getLosses() {
        return losses;
    }

    public void setLosses(int losses) {
        this.losses = losses;
    }

    public int getPlayed() {
        return played;
    }

    public void setPlayed(int played) {
        this.played = played;
    }

    public int getWinstreak() {
        return winstreak;
    }

    public void setWinstreak(int winstreak) {
        this.winstreak = winstreak;
    }

    public int getBestWinstreak() {
        return bestWinstreak;
    }

    public void setBestWinstreak(int bestWinstreak) {
        this.bestWinstreak = bestWinstreak;
    }

    public Map<String, Integer> getRatings() {
        return ratings;
    }

    public int getRating(String kit, int starting) {
        return ratings.getOrDefault(kit.toLowerCase(), starting);
    }

    public void setRating(String kit, int value) {
        ratings.put(kit.toLowerCase(), value);
    }

    public void registerKill() {
        kills++;
    }

    public void registerDeath() {
        deaths++;
    }

    public void registerWin() {
        wins++;
        played++;
        winstreak++;
        if (winstreak > bestWinstreak) {
            bestWinstreak = winstreak;
        }
        loaded = true;
    }

    public void registerLoss() {
        losses++;
        played++;
        winstreak = 0;
        loaded = true;
    }

    public void registerDraw() {
        played++;
        loaded = true;
    }

    public Map<String, Integer> ratingsSnapshot() {
        return new HashMap<>(ratings);
    }
}
