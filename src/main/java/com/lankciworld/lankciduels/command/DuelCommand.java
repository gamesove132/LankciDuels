package com.lankciworld.lankciduels.command;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.arena.Arena;
import com.lankciworld.lankciduels.duel.Duel;
import com.lankciworld.lankciduels.duel.DuelManager;
import com.lankciworld.lankciduels.kit.Kit;
import com.lankciworld.lankciduels.storage.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class DuelCommand implements CommandExecutor, TabCompleter {

    private final LankciDuels plugin;

    public DuelCommand(LankciDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lankciduels.use")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept" -> handleAccept(sender, args);
            case "deny" -> handleDeny(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "list" -> handleList(sender);
            case "arenas" -> handleArenas(sender);
            case "stats" -> handleStats(sender, args);
            case "top" -> handleTop(sender);
            default -> handleDuelRequest(sender, args);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessages().sendRaw(sender, "help-header");
        plugin.getMessages().sendRaw(sender, "help-duel");
        plugin.getMessages().sendRaw(sender, "help-accept");
        plugin.getMessages().sendRaw(sender, "help-deny");
        plugin.getMessages().sendRaw(sender, "help-cancel");
        plugin.getMessages().sendRaw(sender, "help-list");
        plugin.getMessages().sendRaw(sender, "help-arenas");
        plugin.getMessages().sendRaw(sender, "help-stats");
        plugin.getMessages().sendRaw(sender, "help-top");
    }

    private void handleDuelRequest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("lankciduels.duel")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            plugin.getMessages().send(sender, "player-not-found", Map.of("player", targetName));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessages().send(sender, "cant-duel-self");
            return;
        }
        if (plugin.getDuelManager().isInDuel(player.getUniqueId())) {
            plugin.getMessages().send(sender, "you-already-in-duel");
            return;
        }
        if (plugin.getDuelManager().isInDuel(target.getUniqueId())) {
            plugin.getMessages().send(sender, "already-in-duel", Map.of("player", target.getName()));
            return;
        }
        plugin.getModeSelectionGUI().open(player, target);
    }

    private void handleAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player target)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/duel accept <player>"));
            return;
        }
        Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) {
            plugin.getMessages().send(sender, "player-not-found", Map.of("player", args[1]));
            return;
        }
        DuelManager.AcceptResult result = plugin.getDuelManager().accept(target, requester);
        switch (result) {
            case NO_REQUEST -> plugin.getMessages().send(sender, "duel-accept-no-request");
            case ALREADY_IN_DUEL -> plugin.getMessages().send(sender, "you-already-in-duel");
            case NO_ARENA -> { /* messages already sent inside DuelManager */ }
            case BET_FUNDS -> { /* messages already sent inside DuelManager */ }
            case OK -> { /* messages already sent inside DuelManager */ }
        }
    }

    private void handleDeny(CommandSender sender, String[] args) {
        if (!(sender instanceof Player target)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/duel deny <player>"));
            return;
        }
        Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) {
            plugin.getMessages().send(sender, "player-not-found", Map.of("player", args[1]));
            return;
        }
        if (!plugin.getDuelManager().deny(target, requester)) {
            plugin.getMessages().send(sender, "duel-deny-no-request");
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (plugin.getDuelManager().getOutgoing(player.getUniqueId()).isEmpty()) {
            plugin.getMessages().send(sender, "duel-cancel-none");
            return;
        }
        plugin.getDuelManager().cancelAllOutgoing(player);
        plugin.getMessages().send(sender, "duel-cancelled");
    }

    private void handleList(CommandSender sender) {
        var duels = plugin.getDuelManager().getActiveDuels();
        if (duels.isEmpty()) {
            plugin.getMessages().sendRaw(sender, "duel-list-empty");
            return;
        }
        for (Duel duel : duels) {
            OfflinePlayer p1 = Bukkit.getOfflinePlayer(duel.getPlayer1());
            OfflinePlayer p2 = Bukkit.getOfflinePlayer(duel.getPlayer2());
            sender.sendMessage(plugin.getMessages().prefix() + com.lankciworld.lankciduels.util.ColorUtil.color(
                    "&7" + p1.getName() + " &fvs &7" + p2.getName()
                            + " &8(&e" + duel.getArena().getName() + "&8, &e" + duel.getKit().getDisplayName() + "&8)"));
        }
    }

    private void handleArenas(CommandSender sender) {
        var arenas = plugin.getArenaManager().getArenas();
        plugin.getMessages().sendRaw(sender, "arena-list-header");
        if (arenas.isEmpty()) {
            plugin.getMessages().sendRaw(sender, "arena-list-empty");
            return;
        }
        for (Arena arena : arenas) {
            plugin.getMessages().send(sender, "arena-list-entry", Map.of(
                    "arena", arena.getName(),
                    "status", arena.getStatus().name()));
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lankciduels.stats")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        OfflinePlayer target;
        if (args.length >= 2) {
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/duel stats <player>"));
            return;
        }

        plugin.getStatsStorage().getOrLoadAsync(target.getUniqueId(),
                target.getName() != null ? target.getName() : "?", stats -> {
            plugin.getMessages().send(sender, "stats-header", Map.of("player", stats.getName()));
            plugin.getMessages().send(sender, "stats-line", Map.of(
                    "wins", String.valueOf(stats.getWins()),
                    "losses", String.valueOf(stats.getLosses()),
                    "kills", String.valueOf(stats.getKills()),
                    "deaths", String.valueOf(stats.getDeaths())));
            plugin.getMessages().send(sender, "stats-line2", Map.of(
                    "played", String.valueOf(stats.getPlayed()),
                    "winstreak", String.valueOf(stats.getWinstreak()),
                    "best_winstreak", String.valueOf(stats.getBestWinstreak())));

            if (plugin.getConfigManager().ratingEnabled() && !stats.ratingsSnapshot().isEmpty()) {
                plugin.getMessages().sendRaw(sender, "stats-rating-header");
                for (Kit kit : plugin.getKitManager().getKits().values()) {
                    int rating = stats.getRating(kit.getId(), plugin.getConfigManager().ratingStarting());
                    plugin.getMessages().send(sender, "stats-rating-line", Map.of(
                            "kit", kit.getDisplayName(),
                            "rating", String.valueOf(rating)));
                }
            }
        });
    }

    private void handleTop(CommandSender sender) {
        plugin.getStatsStorage().topAsync(plugin.getConfigManager().leaderboardSize(), top -> plugin.sync(() -> {
            plugin.getMessages().sendRaw(sender, "top-header");
            if (top.isEmpty()) {
                plugin.getMessages().sendRaw(sender, "top-empty");
                return;
            }
            int place = 1;
            for (PlayerStats stats : top) {
                plugin.getMessages().send(sender, "top-entry", Map.of(
                        "place", String.valueOf(place++),
                        "player", stats.getName() != null ? stats.getName() : "?",
                        "wins", String.valueOf(stats.getWins())));
            }
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("accept", "deny", "cancel", "list", "arenas", "stats", "top"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
            String partial = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(partial)).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny") || args[0].equalsIgnoreCase("stats"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    options.add(p.getName());
                }
            }
            return options;
        }
        return options;
    }
}
