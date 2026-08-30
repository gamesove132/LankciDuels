package com.lankciworld.lankciduels.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

public final class SoundUtil {

    private SoundUtil() {
    }

    public static void play(Player player, String soundName, Logger logger, boolean debug) {
        if (player == null || soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.trim().toUpperCase());
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ex) {
            if (debug && logger != null) {
                logger.warning("[LankciDuels] Невідомий звук у config.yml: " + soundName);
            }
        }
    }
}
