package com.lankciworld.lankciduels.duel;

import java.util.UUID;

public class DuelRequest {

    private final UUID sender;
    private final UUID target;
    private final long createdAt;
    private final long expiresAt;
    private int expireTaskId = -1;

    public DuelRequest(UUID sender, UUID target, int expireSeconds) {
        this.sender = sender;
        this.target = target;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + (expireSeconds * 1000L);
    }

    public UUID getSender() {
        return sender;
    }

    public UUID getTarget() {
        return target;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public void setExpireTaskId(int expireTaskId) {
        this.expireTaskId = expireTaskId;
    }

    public int getExpireTaskId() {
        return expireTaskId;
    }
}
