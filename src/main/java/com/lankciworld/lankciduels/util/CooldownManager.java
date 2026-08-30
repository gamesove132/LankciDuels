package com.lankciworld.lankciduels.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cooldown tracker. Used both for the request-cooldown
 * (anti-spam) system.
 */
public final class CooldownManager {

    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID uuid, long cooldownMillis) {
        Long last = lastUse.get(uuid);
        if (last == null) {
            return false;
        }
        return (System.currentTimeMillis() - last) < cooldownMillis;
    }

    public long remainingSeconds(UUID uuid, long cooldownMillis) {
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long remaining = cooldownMillis - (System.currentTimeMillis() - last);
        return Math.max(0, remaining / 1000L + (remaining % 1000L > 0 ? 1 : 0));
    }

    public void trigger(UUID uuid) {
        lastUse.put(uuid, System.currentTimeMillis());
    }

    public void clear(UUID uuid) {
        lastUse.remove(uuid);
    }
}
