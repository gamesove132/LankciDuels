package com.lankciworld.lankciduels.hook;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.duel.Duel;
import com.lankciworld.lankciduels.storage.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * This class references PlaceholderAPI classes directly, so it must only
 * ever be constructed after confirming the PlaceholderAPI plugin is
 * present (see LankciDuels#onEnable). That keeps the plugin fully
 * functional even when PlaceholderAPI is not installed.
 */
public class PlaceholderHook extends PlaceholderExpansion {

    private final LankciDuels plugin;

    public PlaceholderHook(LankciDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "lankciduels";
    }

    @Override
    public @NotNull String getAuthor() {
        return "LankciWorld";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            return "";
        }
        PlayerStats stats = plugin.getStatsStorage().getOrLoad(offlinePlayer.getUniqueId(),
                offlinePlayer.getName() != null ? offlinePlayer.getName() : "?");

        // PlaceholderAPI's API is synchronous, so we cannot await the async
        // SQLite load here the way /duel stats does - the best we can do is
        // avoid showing a misleading confirmed-looking "0" for a value that
        // simply hasn't loaded yet.
        if (!stats.isLoaded() && !params.equalsIgnoreCase("in_duel") && !params.equalsIgnoreCase("arena")) {
            return "...";
        }

        return switch (params.toLowerCase()) {
            case "wins" -> String.valueOf(stats.getWins());
            case "losses" -> String.valueOf(stats.getLosses());
            case "kills" -> String.valueOf(stats.getKills());
            case "deaths" -> String.valueOf(stats.getDeaths());
            case "played" -> String.valueOf(stats.getPlayed());
            case "winstreak" -> String.valueOf(stats.getWinstreak());
            case "best_winstreak" -> String.valueOf(stats.getBestWinstreak());
            case "rating" -> String.valueOf(stats.getRating("sword", plugin.getConfigManager().ratingStarting()));
            case "in_duel" -> {
                Duel duel = plugin.getDuelManager().getActiveDuel(offlinePlayer.getUniqueId());
                yield duel != null ? "yes" : "no";
            }
            case "arena" -> {
                Duel duel = plugin.getDuelManager().getActiveDuel(offlinePlayer.getUniqueId());
                yield duel != null ? duel.getArena().getName() : "-";
            }
            default -> {
                if (params.toLowerCase().startsWith("rating_")) {
                    String kit = params.substring("rating_".length());
                    yield String.valueOf(stats.getRating(kit, plugin.getConfigManager().ratingStarting()));
                }
                yield null;
            }
        };
    }
}
