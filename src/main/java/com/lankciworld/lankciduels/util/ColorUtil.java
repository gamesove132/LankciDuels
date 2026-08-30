package com.lankciworld.lankciduels.util;

import net.md_5.bungee.api.ChatColor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles translation of '&' legacy color codes and '&#RRGGBB' hex codes
 * without requiring any external dependency (uses the Bungee ChatColor
 * class that already ships inside every modern Paper server).
 */
public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.of("#" + hex).toString());
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static List<String> color(List<String> input) {
        if (input == null) {
            return List.of();
        }
        return input.stream().map(ColorUtil::color).collect(Collectors.toList());
    }

    public static String strip(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.stripColor(color(input));
    }
}
