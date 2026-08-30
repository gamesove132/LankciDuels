package com.lankciworld.lankciduels;

import com.lankciworld.lankciduels.arena.Arena;
import com.lankciworld.lankciduels.arena.ArenaManager;
import com.lankciworld.lankciduels.command.DuelAdminCommand;
import com.lankciworld.lankciduels.command.DuelCommand;
import com.lankciworld.lankciduels.config.ConfigManager;
import com.lankciworld.lankciduels.config.MessagesManager;
import com.lankciworld.lankciduels.duel.Duel;
import com.lankciworld.lankciduels.duel.DuelManager;
import com.lankciworld.lankciduels.duel.DuelState;
import com.lankciworld.lankciduels.gui.ModeSelectionGUI;
import com.lankciworld.lankciduels.hook.PlaceholderHook;
import com.lankciworld.lankciduels.hook.VaultHook;
import com.lankciworld.lankciduels.kit.KitManager;
import com.lankciworld.lankciduels.listener.BetChatListener;
import com.lankciworld.lankciduels.listener.GUIListener;
import com.lankciworld.lankciduels.listener.PlayerListener;
import com.lankciworld.lankciduels.storage.BetLedger;
import com.lankciworld.lankciduels.storage.PlayerDataStore;
import com.lankciworld.lankciduels.storage.StatsStorage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class LankciDuels extends JavaPlugin {

    private static LankciDuels instance;

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private KitManager kitManager;
    private ArenaManager arenaManager;
    private DuelManager duelManager;
    private StatsStorage statsStorage;
    private PlayerDataStore playerDataStore;
    private VaultHook vaultHook;
    private BetLedger betLedger;
    private ModeSelectionGUI modeSelectionGUI;

    private boolean placeholderApiHooked = false;

    @Override
    public void onEnable() {
        instance = this;

        // Config / data files first - everything else depends on them.
        this.configManager = new ConfigManager(this);
        this.messagesManager = new MessagesManager(this);
        this.kitManager = new KitManager(this);
        this.arenaManager = new ArenaManager(this);
        this.playerDataStore = new PlayerDataStore(this);

        this.statsStorage = new StatsStorage(this);
        this.statsStorage.init();

        this.vaultHook = new VaultHook(this);
        this.betLedger = new BetLedger(this);
        // Crash recovery for bets (item 1): refund every bet still sitting
        // at PENDING - i.e. money that was withdrawn for a duel that never
        // got to settle/refund normally because the server went down.
        // Must run before duelManager starts accepting new duels/bets.
        betLedger.recoverPendingOnStartup();

        this.duelManager = new DuelManager(this);
        this.modeSelectionGUI = new ModeSelectionGUI(this);

        // Commands
        DuelCommand duelCommand = new DuelCommand(this);
        getCommand("duel").setExecutor(duelCommand);
        getCommand("duel").setTabCompleter(duelCommand);

        DuelAdminCommand duelAdminCommand = new DuelAdminCommand(this);
        getCommand("dueladmin").setExecutor(duelAdminCommand);
        getCommand("dueladmin").setTabCompleter(duelAdminCommand);

        // Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new BetChatListener(this), this);

        // Optional soft dependencies - only touched if actually present.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PlaceholderHook(this).register();
                placeholderApiHooked = true;
                getLogger().info("PlaceholderAPI знайдено - плейсхолдери %lankciduels_...% активовано.");
            } catch (Throwable t) {
                getLogger().warning("Не вдалося зареєструвати PlaceholderAPI розширення: " + t.getMessage());
            }
        }

        if (vaultHook.isEnabled()) {
            getLogger().info("Vault знайдено - система ставок (якщо увімкнена) буде працювати.");
        } else if (configManager.bettingEnabled()) {
            getLogger().warning("betting.enabled = true, але Vault/economy не знайдено. Ставки будуть відхилятися.");
        }

        boolean mvCore = getServer().getPluginManager().getPlugin("Multiverse-Core") != null;
        boolean mvInv = getServer().getPluginManager().getPlugin("Multiverse-Inventories") != null;
        if (mvCore || mvInv) {
            getLogger().info("Виявлено " + (mvCore ? "Multiverse-Core " : "") + (mvInv ? "Multiverse-Inventories " : "")
                    + "- teleport, застосування kit-ів та відновлення інвентарю рознесені по тіках "
                    + "(compatibility.world-change-delay-ticks = " + configManager.worldChangeDelayTicks()
                    + "), щоб уникнути конфлікту зі зміною world group.");
        }

        // Recover any duel snapshots left on disk from a crash/restart that
        // happened while their owner was offline (online players are handled
        // by PlayerListener#onJoin the moment they log back in).
        for (var uuid : playerDataStore.pendingUuids()) {
            Player online = getServer().getPlayer(uuid);
            if (online != null) {
                // Will be picked up by the join-restore path already fired for
                // players who were online when the plugin (re)loaded.
                continue;
            }
            // Nothing to do right now for offline players - PlayerListener#onJoin
            // restores them automatically the moment they reconnect.
        }

        getLogger().info(configManager.pluginName() + " увімкнено.");
    }

    @Override
    public void onDisable() {
        // Best-effort graceful shutdown: return every player still mid-duel to
        // their pre-duel state instead of leaving them stuck on an arena.
        if (duelManager != null) {
            for (Duel duel : java.util.List.copyOf(duelManager.getActiveDuels())) {
                if (duel.getState() != DuelState.ENDING) {
                    duelManager.endDuel(duel, null, DuelManager.EndReason.FORCESTOP);
                }
            }
        }

        if (arenaManager != null) {
            for (Arena arena : arenaManager.getArenas()) {
                arena.setStatus(com.lankciworld.lankciduels.arena.ArenaStatus.FREE);
                arena.setActiveDuelId(null);
            }
        }

        if (statsStorage != null) {
            statsStorage.close();
        }

        getLogger().info("LankciDuels вимкнено.");
        instance = null;
    }

    /**
     * Fully reloads config.yml, kits.yml and arenas.yml without touching
     * any duel currently in progress - active Duel objects hold direct
     * references to their Kit/Arena instances rather than looking them
     * up by name each tick, so a reload never disrupts a running fight.
     */
    public void reloadEverything() {
        configManager.reload();
        kitManager.load();
        arenaManager.load();
    }

    public static LankciDuels getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessagesManager getMessages() {
        return messagesManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public DuelManager getDuelManager() {
        return duelManager;
    }

    public StatsStorage getStatsStorage() {
        return statsStorage;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public BetLedger getBetLedger() {
        return betLedger;
    }

    public ModeSelectionGUI getModeSelectionGUI() {
        return modeSelectionGUI;
    }

    public boolean isPlaceholderApiHooked() {
        return placeholderApiHooked;
    }

    /** Runs a task asynchronously - use for anything that touches the database or the filesystem. */
    public void async(Runnable runnable) {
        getServer().getScheduler().runTaskAsynchronously(this, runnable);
    }

    /** Runs a task on the main server thread - use before touching any Bukkit API from an async context. */
    public void sync(Runnable runnable) {
        getServer().getScheduler().runTask(this, runnable);
    }
}
