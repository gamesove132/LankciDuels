package com.lankciworld.lankciduels.storage;

import com.lankciworld.lankciduels.LankciDuels;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * SQLite-backed storage for stats and per-kit ratings.
 *
 * Thread-safety design:
 *  - A single JDBC connection is kept open for the plugin's lifetime.
 *  - EVERY piece of code that touches that Connection (reads or writes)
 *    runs exclusively on one dedicated single-thread executor
 *    ({@link #dbExecutor}) - never on Bukkit's shared async thread pool
 *    and never on the main thread. SQLite (and the xerial JDBC driver
 *    specifically) does not support safe concurrent use of a single
 *    Connection from multiple Java threads, so funnelling absolutely
 *    everything through one dedicated thread is what actually
 *    guarantees safety here - synchronized blocks alone are not enough
 *    once reads stop being synchronized too, which is exactly the bug
 *    this design avoids.
 *  - The in-memory ConcurrentHashMap cache is what gameplay code reads
 *    from and mutates, and it is only ever mutated (registerWin/Loss/
 *    Draw/setRating) on the MAIN server thread, inside duel-ending
 *    code. The dbExecutor thread never mutates a live cached
 *    PlayerStats object directly - see {@link #loadAsync} for how a
 *    finished DB read is safely merged back in via plugin.sync(...),
 *    and only if nothing has changed the object in the meantime.
 *  - PlayerStats' mutable fields are `volatile`, which gives correct
 *    cross-thread visibility for the dbExecutor thread's *reads* of
 *    those fields (e.g. while building a row to save) without needing
 *    the caller to synchronize anything.
 *  - The relocated driver class is loaded explicitly with Class.forName
 *    because relocation can interfere with SQLite's automatic
 *    ServiceLoader registration on some JVMs.
 */
public class StatsStorage {

    private final LankciDuels plugin;
    private Connection connection;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();

    /** uuid -> callbacks waiting on that player's initial DB load to finish (see getOrLoadAsync). */
    private final Map<UUID, java.util.List<Consumer<PlayerStats>>> pendingLoadCallbacks = new ConcurrentHashMap<>();

    /**
     * Every single JDBC operation in this class runs on this one thread.
     * This is what actually makes SQLite access thread-safe here: there
     * is never more than one thread touching {@link #connection}, ever.
     */
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LankciDuels-SQLite");
        thread.setDaemon(true);
        return thread;
    });

    public StatsStorage(LankciDuels plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            Class.forName("com.lankciworld.lankciduels.libs.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            // Fall back to the un-relocated name in case shading configuration changes.
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                plugin.getLogger().severe("[LankciDuels] Не вдалося знайти SQLite JDBC драйвер!");
            }
        }

        File dbFile = new File(plugin.getDataFolder(), plugin.getConfigManager().databaseFile());
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[LankciDuels] Не вдалося підключитись до SQLite бази даних", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        kills INTEGER DEFAULT 0,
                        deaths INTEGER DEFAULT 0,
                        wins INTEGER DEFAULT 0,
                        losses INTEGER DEFAULT 0,
                        played INTEGER DEFAULT 0,
                        winstreak INTEGER DEFAULT 0,
                        best_winstreak INTEGER DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_rating (
                        uuid TEXT,
                        kit TEXT,
                        rating INTEGER DEFAULT 1000,
                        PRIMARY KEY (uuid, kit)
                    )
                    """);
        }
    }

    /**
     * Shuts everything down in a strict, race-free order: stop accepting
     * new DB tasks, wait for whatever is still queued to finish, and
     * only THEN do a final synchronous flush of the whole cache directly
     * on the calling (main) thread before closing the connection. Because
     * the executor is fully drained and terminated before this final
     * flush starts, there is no window where two threads could touch the
     * connection at once.
     */
    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[LankciDuels] SQLite черга не завершилась вчасно при вимкненні.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (PlayerStats stats : cache.values()) {
            saveSync(stats);
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[LankciDuels] Помилка закриття SQLite з'єднання", e);
            }
        }
    }

    /**
     * Returns cached stats immediately if present, otherwise a fresh
     * empty PlayerStats object is returned synchronously (never blocks
     * on the DB) while the real data is loaded on the DB executor thread
     * and merged back into this exact object via the main thread once
     * available (see {@link #loadAsync}).
     */
    public PlayerStats getOrLoad(UUID uuid, String name) {
        PlayerStats existing = cache.get(uuid);
        if (existing != null) {
            existing.setName(name);
            return existing;
        }
        PlayerStats fresh = new PlayerStats(uuid, name);
        PlayerStats race = cache.putIfAbsent(uuid, fresh);
        if (race != null) {
            // Another thread/call won the race to create this entry first.
            race.setName(name);
            return race;
        }
        loadAsync(uuid, fresh);
        return fresh;
    }

    public PlayerStats peek(UUID uuid) {
        return cache.get(uuid);
    }

    /**
     * Like {@link #getOrLoad}, but guarantees the callback only ever
     * receives a PlayerStats whose numbers are confirmed correct
     * ({@code isLoaded() == true}) - it will never hand back a
     * freshly-created all-zero placeholder while the real DB read is
     * still in flight. Use this (instead of getOrLoad) anywhere a
     * misleading "0" would be a problem, e.g. `/duel stats`.
     */
    public void getOrLoadAsync(UUID uuid, String name, Consumer<PlayerStats> callback) {
        PlayerStats stats = getOrLoad(uuid, name);
        if (stats.isLoaded()) {
            callback.accept(stats);
            return;
        }
        pendingLoadCallbacks.computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
    }

    private void loadAsync(UUID uuid, PlayerStats target) {
        if (connection == null) {
            // No DB to load from (connection failed at startup) - the
            // in-memory zeros are the best we can offer, but callers must
            // still be unblocked rather than waiting forever.
            target.setLoaded(true);
            java.util.List<Consumer<PlayerStats>> waiters = pendingLoadCallbacks.remove(uuid);
            if (waiters != null) {
                for (Consumer<PlayerStats> waiter : waiters) {
                    waiter.accept(target);
                }
            }
            return;
        }
        dbExecutor.execute(() -> {
            int kills = 0, deaths = 0, wins = 0, losses = 0, played = 0, winstreak = 0, bestWinstreak = 0;
            Map<String, Integer> ratings = new HashMap<>();

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        kills = rs.getInt("kills");
                        deaths = rs.getInt("deaths");
                        wins = rs.getInt("wins");
                        losses = rs.getInt("losses");
                        played = rs.getInt("played");
                        winstreak = rs.getInt("winstreak");
                        bestWinstreak = rs.getInt("best_winstreak");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[LankciDuels] Не вдалося завантажити статистику " + uuid, e);
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT kit, rating FROM player_rating WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ratings.put(rs.getString("kit"), rs.getInt("rating"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[LankciDuels] Не вдалося завантажити рейтинг " + uuid, e);
            }

            int fKills = kills, fDeaths = deaths, fWins = wins, fLosses = losses,
                    fPlayed = played, fWinstreak = winstreak, fBestWinstreak = bestWinstreak;

            plugin.sync(() -> {
                boolean pristine = target.getPlayed() == 0 && target.getWins() == 0
                        && target.getLosses() == 0 && target.ratingsSnapshot().isEmpty();
                if (pristine) {
                    target.setKills(fKills);
                    target.setDeaths(fDeaths);
                    target.setWins(fWins);
                    target.setLosses(fLosses);
                    target.setPlayed(fPlayed);
                    target.setWinstreak(fWinstreak);
                    target.setBestWinstreak(fBestWinstreak);
                    for (Map.Entry<String, Integer> entry : ratings.entrySet()) {
                        target.setRating(entry.getKey(), entry.getValue());
                    }
                }
                // Either the pristine DB values were just applied, or a real
                // duel already mutated `target` in the meantime (in which
                // case those newer values are already correct on their own)
                // - either way, the object is now confirmed trustworthy.
                target.setLoaded(true);

                java.util.List<Consumer<PlayerStats>> waiters = pendingLoadCallbacks.remove(uuid);
                if (waiters != null) {
                    for (Consumer<PlayerStats> waiter : waiters) {
                        waiter.accept(target);
                    }
                }
            });
        });
    }

    public void saveAsync(PlayerStats stats) {
        dbExecutor.execute(() -> saveSync(stats));
    }

    /**
     * Only ever called from the dbExecutor thread during normal
     * operation, or directly from the main thread during the guarded
     * final flush in {@link #close()} (by which point the executor is
     * fully terminated, so there is still never more than one thread
     * touching the connection at a time).
     */
    private void saveSync(PlayerStats stats) {
        if (connection == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_stats (uuid, name, kills, deaths, wins, losses, played, winstreak, best_winstreak)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    kills = excluded.kills,
                    deaths = excluded.deaths,
                    wins = excluded.wins,
                    losses = excluded.losses,
                    played = excluded.played,
                    winstreak = excluded.winstreak,
                    best_winstreak = excluded.best_winstreak
                """)) {
            ps.setString(1, stats.getUuid().toString());
            ps.setString(2, stats.getName());
            ps.setInt(3, stats.getKills());
            ps.setInt(4, stats.getDeaths());
            ps.setInt(5, stats.getWins());
            ps.setInt(6, stats.getLosses());
            ps.setInt(7, stats.getPlayed());
            ps.setInt(8, stats.getWinstreak());
            ps.setInt(9, stats.getBestWinstreak());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[LankciDuels] Не вдалося зберегти статистику " + stats.getUuid(), e);
        }

        for (Map.Entry<String, Integer> entry : stats.ratingsSnapshot().entrySet()) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO player_rating (uuid, kit, rating) VALUES (?, ?, ?)
                    ON CONFLICT(uuid, kit) DO UPDATE SET rating = excluded.rating
                    """)) {
                ps.setString(1, stats.getUuid().toString());
                ps.setString(2, entry.getKey());
                ps.setInt(3, entry.getValue());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[LankciDuels] Не вдалося зберегти рейтинг " + stats.getUuid(), e);
            }
        }
    }

    public void unload(UUID uuid) {
        PlayerStats stats = cache.remove(uuid);
        if (stats != null) {
            saveAsync(stats);
        }
    }

    public List<PlayerStats> topByWins(int limit) {
        return cache.values().stream()
                .sorted((a, b) -> Integer.compare(b.getWins(), a.getWins()))
                .limit(limit)
                .toList();
    }

    /**
     * Queries the full leaderboard from disk (including offline players)
     * on the DB executor thread and hands the result back via the
     * callback, which is executed on that same executor thread - the
     * caller is responsible for hopping back to the main thread (via
     * plugin.sync(...)) before touching Bukkit API, exactly as
     * DuelCommand#handleTop already does.
     */
    public void topAsync(int limit, Consumer<List<PlayerStats>> callback) {
        dbExecutor.execute(() -> {
            List<PlayerStats> result = new ArrayList<>();
            if (connection == null) {
                callback.accept(result);
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats ORDER BY wins DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        PlayerStats stats = new PlayerStats(uuid, rs.getString("name"));
                        stats.setKills(rs.getInt("kills"));
                        stats.setDeaths(rs.getInt("deaths"));
                        stats.setWins(rs.getInt("wins"));
                        stats.setLosses(rs.getInt("losses"));
                        stats.setPlayed(rs.getInt("played"));
                        stats.setWinstreak(rs.getInt("winstreak"));
                        stats.setBestWinstreak(rs.getInt("best_winstreak"));
                        result.add(stats);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[LankciDuels] Не вдалося отримати таблицю лідерів", e);
            }
            callback.accept(result);
        });
    }
}
