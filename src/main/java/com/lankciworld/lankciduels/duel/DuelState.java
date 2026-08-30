package com.lankciworld.lankciduels.duel;

public enum DuelState {
    COUNTDOWN,
    FIGHTING,
    /**
     * A player has died and the winner is already determined, but we are
     * deliberately waiting for {@code PlayerRespawnEvent} before doing
     * any teleport/inventory work - see DuelManager#onDeath /
     * #onRespawn. Nothing about the duel's outcome can change once this
     * state is entered; ENDING is only reached from here.
     */
    DEATH_PENDING,
    ENDING
}
