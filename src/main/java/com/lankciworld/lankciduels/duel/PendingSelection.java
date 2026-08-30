package com.lankciworld.lankciduels.duel;

import com.lankciworld.lankciduels.kit.Kit;

import java.util.UUID;

/**
 * Represents a duel request that the sender is still configuring
 * (picked a kit in the GUI, now typing a bet amount in chat before the
 * request is actually sent to the target).
 */
public class PendingSelection {

    private final UUID sender;
    private final UUID target;
    private final Kit kit;

    public PendingSelection(UUID sender, UUID target, Kit kit) {
        this.sender = sender;
        this.target = target;
        this.kit = kit;
    }

    public UUID getSender() {
        return sender;
    }

    public UUID getTarget() {
        return target;
    }

    public Kit getKit() {
        return kit;
    }
}
