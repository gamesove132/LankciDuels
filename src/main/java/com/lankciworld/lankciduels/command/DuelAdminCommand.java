package com.lankciworld.lankciduels.command;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

public class DuelAdminCommand implements CommandExecutor, TabCompleter {

    private final LankciDuels plugin;

    public DuelAdminCommand(LankciDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lankciduels.admin")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getMessages().prefix() + "&e/dueladmin <setlobby|setarena|setspawn|createarena|deletearena|reload|forcestop>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setlobby" -> handleSetLobby(sender);
            case "createarena" -> handleCreateArena(sender, args);
            case "deletearena" -> handleDeleteArena(sender, args);
            case "setarena" -> handleSetArena(sender, args);
            case "setspawn" -> handleSetSpawn(sender, args);
            case "reload" -> handleReload(sender);
            case "forcestop" -> handleForceStop(sender, args);
            default -> sender.sendMessage(plugin.getMessages().prefix() + "&cНевідома підкоманда.");
        }
        return true;
    }

    private void handleSetLobby(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("lankciduels.admin.arena")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        plugin.getConfigManager().setLobbyLocation(player.getLocation());
        plugin.getMessages().send(sender, "lobby-set");
    }

    private void handleCreateArena(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("lankciduels.admin.arena")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin createarena <name>"));
            return;
        }
        String name = args[1];
        if (plugin.getArenaManager().getArena(name).isPresent()) {
            plugin.getMessages().send(sender, "arena-already-exists", Map.of("arena", name));
            return;
        }
        boolean created = plugin.getArenaManager().createArena(name, player.getWorld());
        if (created) {
            plugin.getMessages().send(sender, "arena-created", Map.of("arena", name));
        } else {
            plugin.getMessages().send(sender, "arena-already-exists", Map.of("arena", name));
        }
    }

    private void handleDeleteArena(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lankciduels.admin.arena")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin deletearena <name>"));
            return;
        }
        String name = args[1];
        var arenaOpt = plugin.getArenaManager().getArena(name);
        if (arenaOpt.isEmpty()) {
            plugin.getMessages().send(sender, "arena-not-found", Map.of("arena", name));
            return;
        }
        if (arenaOpt.get().getActiveDuelId() != null) {
            sender.sendMessage(plugin.getMessages().prefix() + "&cНе можна видалити арену, яка зараз використовується.");
            return;
        }
        plugin.getArenaManager().deleteArena(name);
        plugin.getMessages().send(sender, "arena-deleted", Map.of("arena", name));
    }

    private void handleSetArena(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("lankciduels.admin.arena")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin setarena <name>"));
            return;
        }
        String name = args[1];
        var arenaOpt = plugin.getArenaManager().getArena(name);
        if (arenaOpt.isEmpty()) {
            plugin.getMessages().send(sender, "arena-not-found", Map.of("arena", name));
            return;
        }
        // "setarena" (re)binds an existing arena's world to the admin's current
        // world - useful after copying/regenerating an arena into a new world.
        arenaOpt.get().setWorldName(player.getWorld().getName());
        plugin.getArenaManager().save();
        sender.sendMessage(plugin.getMessages().prefix() + "&aСвіт арени &e" + name + " &aвстановлено на &e" + player.getWorld().getName() + "&a.");
    }

    private void handleSetSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("lankciduels.admin.arena")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin setspawn <arena> <1|2>"));
            return;
        }
        String name = args[1];
        if (plugin.getArenaManager().getArena(name).isEmpty()) {
            plugin.getMessages().send(sender, "arena-not-found", Map.of("arena", name));
            return;
        }
        int point;
        try {
            point = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin setspawn <arena> <1|2>"));
            return;
        }
        if (point != 1 && point != 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin setspawn <arena> <1|2>"));
            return;
        }
        Location location = player.getLocation();
        plugin.getArenaManager().setSpawn(name, point, location);
        plugin.getMessages().send(sender, "arena-spawn-set", Map.of("arena", name, "point", String.valueOf(point)));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("lankciduels.admin.reload")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        plugin.reloadEverything();
        plugin.getMessages().send(sender, "reload-success");
    }

    private void handleForceStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lankciduels.admin.forcestop")) {
            plugin.getMessages().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.getMessages().send(sender, "invalid-usage", Map.of("usage", "/dueladmin forcestop <player>"));
            return;
        }
        OfflinePlayer target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayer(args[1]);
        }
        boolean stopped = plugin.getDuelManager().forceStop((sender instanceof Player p) ? p : null, target);
        if (stopped) {
            plugin.getMessages().send(sender, "duel-forcestopped-admin", Map.of("player", args[1]));
        } else {
            plugin.getMessages().send(sender, "not-in-duel");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("setlobby", "setarena", "setspawn", "createarena", "deletearena", "reload", "forcestop"));
            String partial = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(partial)).collect(Collectors.toList());
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "deletearena", "setarena" -> {
                    for (Arena arena : plugin.getArenaManager().getArenas()) {
                        options.add(arena.getName());
                    }
                }
                case "setspawn" -> {
                    for (Arena arena : plugin.getArenaManager().getArenas()) {
                        options.add(arena.getName());
                    }
                }
                case "forcestop" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        options.add(p.getName());
                    }
                }
                default -> {
                }
            }
            return options;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setspawn")) {
            return List.of("1", "2");
        }
        return options;
    }
}
