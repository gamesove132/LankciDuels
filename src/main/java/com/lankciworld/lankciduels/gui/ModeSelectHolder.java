package com.lankciworld.lankciduels.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an Inventory as being LankciDuels' mode-selection GUI and
 * remembers which player it was opened for and which target it is for,
 * so the click listener knows exactly what to do without any additional
 * lookups or fragile inventory-title parsing.
 */
public class ModeSelectHolder implements InventoryHolder {

    private final UUID viewer;
    private final UUID target;
    private Inventory inventory;

    public ModeSelectHolder(UUID viewer, UUID target) {
        this.viewer = viewer;
        this.target = target;
    }

    public UUID getViewer() {
        return viewer;
    }

    public UUID getTarget() {
        return target;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
