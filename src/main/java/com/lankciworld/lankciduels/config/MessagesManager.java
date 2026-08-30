package com.lankciworld.lankciduels.config;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.util.ColorUtil;
import org.bukkit.command.CommandSender;

import java.util.Map;

public class MessagesManager {

    private final LankciDuels plugin;

    public MessagesManager(LankciDuels plugin) {
        this.plugin = plugin;
    }

    public String prefix() {
        return ColorUtil.color(plugin.getConfig().getString("messages.prefix", ""));
    }

    private String raw(String key) {
        String value = plugin.getConfig().getString("messages." + key);
        if (value == null) {
            return "&c[missing message: " + key + "]";
        }
        return value;
    }

    public String get(String key) {
        return ColorUtil.color(raw(key));
    }

    public String get(String key, Map<String, String> placeholders) {
        String value = raw(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                value = value.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return ColorUtil.color(value);
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(prefix() + get(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(prefix() + get(key, placeholders));
    }

    public void sendRaw(CommandSender sender, String key) {
        sender.sendMessage(get(key));
    }

    public void sendRaw(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(get(key, placeholders));
    }
}
