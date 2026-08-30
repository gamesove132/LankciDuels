package com.lankciworld.lankciduels.listener;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.duel.DuelManager;
import com.lankciworld.lankciduels.duel.PendingSelection;
import com.lankciworld.lankciduels.gui.ModeSelectHolder;
import com.lankciworld.lankciduels.kit.Kit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;

public class GUIListener implements Listener {

    private final LankciDuels plugin;

    public GUIListener(LankciDuels plugin) {
        this.plugin = plugin;
    }

    /**
     * Without this, a player could drag an item from their own inventory
     * into the mode-select GUI (InventoryClickEvent alone does not cover
     * drag actions). Since this GUI's Inventory is a short-lived object
     * with no close-handler that returns leftover contents, any item
     * placed into it that way would simply be lost the moment the GUI
     * closes - so drags into (or within) this inventory must always be
     * blocked, exactly like clicks already are.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ModeSelectHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ModeSelectHolder holder)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        int slot = event.getSlot();

        var cancelSection = plugin.getConfigManager().guiModeSelectSection() != null
                ? plugin.getConfigManager().guiModeSelectSection().getConfigurationSection("cancel-item") : null;
        if (cancelSection != null && slot == cancelSection.getInt("slot", -1)) {
            viewer.closeInventory();
            return;
        }

        Optional<Kit> kitOpt = plugin.getKitManager().getKits().values().stream()
                .filter(Kit::isEnabled)
                .filter(k -> k.getGuiSlot() == slot)
                .findFirst();
        if (kitOpt.isEmpty()) {
            return;
        }
        Kit kit = kitOpt.get();

        Player target = plugin.getServer().getPlayer(holder.getTarget());
        viewer.closeInventory();

        if (target == null || !target.isOnline()) {
            plugin.getMessages().send(viewer, "player-not-found", Map.of("player", "?"));
            return;
        }

        DuelManager duelManager = plugin.getDuelManager();
        DuelManager.RequestResult check = duelManager.canSendRequest(viewer, target, kit);
        if (!handleResult(viewer, target, check)) {
            return;
        }

        if (plugin.getConfigManager().bettingEnabled()) {
            duelManager.getAwaitingBet().put(viewer.getUniqueId(), new PendingSelection(viewer.getUniqueId(), target.getUniqueId(), kit));
            plugin.getMessages().send(viewer, "bet-prompt", Map.of(
                    "min", String.valueOf((long) plugin.getConfigManager().bettingMinimum()),
                    "max", String.valueOf((long) plugin.getConfigManager().bettingMaximum())));
        } else {
            duelManager.sendRequest(viewer, target, kit, 0);
        }
    }

    private boolean handleResult(Player sender, Player target, DuelManager.RequestResult result) {
        switch (result) {
            case OK -> {
                return true;
            }
            case SELF -> plugin.getMessages().send(sender, "cant-duel-self");
            case TARGET_OFFLINE -> plugin.getMessages().send(sender, "cant-duel-offline", Map.of("player", target.getName()));
            case TARGET_IN_DUEL -> plugin.getMessages().send(sender, "already-in-duel", Map.of("player", target.getName()));
            case SENDER_IN_DUEL -> plugin.getMessages().send(sender, "you-already-in-duel");
            case COOLDOWN -> plugin.getMessages().send(sender, "request-cooldown",
                    Map.of("seconds", String.valueOf(plugin.getDuelManager().remainingCooldownSeconds(sender.getUniqueId()))));
            case TOO_MANY_PENDING -> plugin.getMessages().send(sender, "too-many-pending");
            case DUPLICATE -> plugin.getMessages().send(sender, "duplicate-request");
            case KIT_DISABLED -> plugin.getMessages().send(sender, "kit-disabled");
        }
        return false;
    }
}
