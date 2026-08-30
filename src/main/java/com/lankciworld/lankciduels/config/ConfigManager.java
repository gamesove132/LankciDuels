package com.lankciworld.lankciduels.config;

import com.lankciworld.lankciduels.LankciDuels;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Thin, typed wrapper around config.yml. Reloading simply re-reads the
 * file from disk; nothing here is cached beyond the FileConfiguration
 * itself so /dueladmin reload always reflects the file on disk.
 */
public class ConfigManager {

    private final LankciDuels plugin;

    public ConfigManager(LankciDuels plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    public void reload() {
        plugin.reloadConfig();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public String pluginName() {
        return cfg().getString("plugin-name", "LankciDuels");
    }

    public boolean debug() {
        return cfg().getBoolean("debug", false);
    }

    public String databaseType() {
        return cfg().getString("database.type", "sqlite");
    }

    public String databaseFile() {
        return cfg().getString("database.file", "data.db");
    }

    public int worldChangeDelayTicks() {
        return Math.max(1, cfg().getInt("compatibility.world-change-delay-ticks", 1));
    }

    public Location lobbyLocation() {
        ConfigurationSection section = cfg().getConfigurationSection("lobby");
        if (section == null) {
            return null;
        }
        String worldName = section.getString("world");
        World world = worldName != null ? plugin.getServer().getWorld(worldName) : null;
        if (world == null) {
            return null;
        }
        return new Location(world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch"));
    }

    public void setLobbyLocation(Location location) {
        cfg().set("lobby.world", location.getWorld().getName());
        cfg().set("lobby.x", location.getX());
        cfg().set("lobby.y", location.getY());
        cfg().set("lobby.z", location.getZ());
        cfg().set("lobby.yaw", (double) location.getYaw());
        cfg().set("lobby.pitch", (double) location.getPitch());
        plugin.saveConfig();
    }

    public int requestCooldownSeconds() {
        return cfg().getInt("request.cooldown-seconds", 5);
    }

    public int requestExpireSeconds() {
        return cfg().getInt("request.expire-seconds", 30);
    }

    public int maxPendingPerPlayer() {
        return cfg().getInt("request.max-pending-per-player", 3);
    }

    public int countdownSeconds() {
        return Math.max(0, cfg().getInt("countdown.seconds", 3));
    }

    public boolean countdownFreezeMovement() {
        return cfg().getBoolean("countdown.freeze-movement", true);
    }

    public boolean countdownDisableDamage() {
        return cfg().getBoolean("countdown.disable-damage", true);
    }

    public boolean countdownBlockCommands() {
        return cfg().getBoolean("countdown.block-commands", true);
    }

    public boolean timeoutEnabled() {
        return cfg().getBoolean("timeout.enabled", true);
    }

    public int timeoutSeconds() {
        return cfg().getInt("timeout.seconds", 300);
    }

    public String timeoutAction() {
        return cfg().getString("timeout.action", "draw");
    }

    public boolean protectionBlockTeleport() {
        return cfg().getBoolean("protection.block-teleport", true);
    }

    public boolean protectionBlockSpawn() {
        return cfg().getBoolean("protection.block-spawn", true);
    }

    public boolean protectionBlockHome() {
        return cfg().getBoolean("protection.block-home", true);
    }

    public boolean protectionBlockWarp() {
        return cfg().getBoolean("protection.block-warp", true);
    }

    public boolean protectionBlockTpa() {
        return cfg().getBoolean("protection.block-tpa", true);
    }

    public boolean protectionBlockWorldChange() {
        return cfg().getBoolean("protection.block-world-change", true);
    }

    public boolean protectArenaRadius() {
        return cfg().getBoolean("protection.protect-arena-radius", true);
    }

    public double arenaRadius() {
        return cfg().getDouble("protection.arena-radius", 15);
    }

    /**
     * Keeps duelists themselves from flying/falling out of their own arena
     * while actively fighting - an invisible barrier around the arena,
     * separate from {@link #protectArenaRadius()} which instead keeps
     * OUTSIDERS from walking in.
     */
    public boolean arenaBoundaryEnabled() {
        return cfg().getBoolean("protection.arena-boundary-enabled", true);
    }

    public double arenaBoundaryRadius() {
        return cfg().getDouble("protection.arena-boundary-radius", 20);
    }

    /**
     * How far below the lower of the arena's two spawn points a duelist
     * may drop before being stopped - guards against falling into the
     * void on elevated/floating arenas without needing a per-arena floor
     * to be configured separately.
     */
    public double arenaBoundaryMaxFall() {
        return cfg().getDouble("protection.arena-boundary-max-fall", 15);
    }

    public List<String> blockedCommands() {
        return cfg().getStringList("protection.blocked-commands");
    }

    public boolean saveInventory() {
        return cfg().getBoolean("inventory.save-inventory", true);
    }

    public boolean saveArmor() {
        return cfg().getBoolean("inventory.save-armor", true);
    }

    public boolean saveOffhand() {
        return cfg().getBoolean("inventory.save-offhand", true);
    }

    public boolean saveExperience() {
        return cfg().getBoolean("inventory.save-experience", true);
    }

    public boolean saveFood() {
        return cfg().getBoolean("inventory.save-food", true);
    }

    public boolean saveHealth() {
        return cfg().getBoolean("inventory.save-health", true);
    }

    public boolean clearBeforeKit() {
        return cfg().getBoolean("inventory.clear-before-kit", true);
    }

    public boolean restoreOnRejoin() {
        return cfg().getBoolean("inventory.restore-on-rejoin", true);
    }

    public boolean quitEnabled() {
        return cfg().getBoolean("quit.enabled", true);
    }

    public boolean quitOpponentWins() {
        return cfg().getBoolean("quit.opponent_wins", true);
    }

    public boolean statsEnabled() {
        return cfg().getBoolean("stats.enabled", true);
    }

    public int leaderboardSize() {
        return cfg().getInt("stats.leaderboard-size", 10);
    }

    public boolean ratingEnabled() {
        return cfg().getBoolean("rating.enabled", true);
    }

    public int ratingStarting() {
        return cfg().getInt("rating.starting", 1000);
    }

    public int ratingWinAmount() {
        return cfg().getInt("rating.win.amount", 25);
    }

    public int ratingLossAmount() {
        return cfg().getInt("rating.loss.amount", 20);
    }

    public boolean rewardsEnabled() {
        return cfg().getBoolean("rewards.enabled", true);
    }

    public List<String> rewardsWinCommands() {
        return cfg().getStringList("rewards.win.commands");
    }

    public List<String> rewardsLossCommands() {
        return cfg().getStringList("rewards.loss.commands");
    }

    public boolean bettingEnabled() {
        return cfg().getBoolean("betting.enabled", false);
    }

    public double bettingMinimum() {
        return cfg().getDouble("betting.minimum", 100);
    }

    public double bettingMaximum() {
        return cfg().getDouble("betting.maximum", 10000);
    }

    public ConfigurationSection titleSection(String key) {
        return cfg().getConfigurationSection("titles." + key);
    }

    public String sound(String key) {
        return cfg().getString("sounds." + key);
    }

    public ConfigurationSection guiModeSelectSection() {
        return cfg().getConfigurationSection("gui.mode-select");
    }
}
