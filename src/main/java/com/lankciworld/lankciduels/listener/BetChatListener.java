package com.lankciworld.lankciduels.listener;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.duel.PendingSelection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;

/**
 * Captures the bet amount a player types in chat right after choosing a
 * kit in the mode-selection GUI. This event fires asynchronously, so all
 * follow-up work (messages, opening the actual duel request) is bounced
 * back to the main thread via plugin.sync(...).
 */
public class BetChatListener implements Listener {

    private final LankciDuels plugin;

    public BetChatListener(LankciDuels plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        PendingSelection selection = plugin.getDuelManager().getAwaitingBet().get(sender.getUniqueId());
        if (selection == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage().trim();

        plugin.sync(() -> handleInput(sender, selection, message));
    }

    private void handleInput(Player sender, PendingSelection selection, String message) {
        // The selection might have been cleared/expired between the async chat
        // event firing and this sync callback running - re-check before use.
        if (plugin.getDuelManager().getAwaitingBet().get(sender.getUniqueId()) != selection) {
            return;
        }

        if (message.equalsIgnoreCase("cancel")) {
            plugin.getDuelManager().getAwaitingBet().remove(sender.getUniqueId());
            plugin.getMessages().send(sender, "bet-cancelled");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(message.replace(",", "."));
        } catch (NumberFormatException e) {
            plugin.getMessages().send(sender, "bet-invalid");
            return;
        }

        double min = plugin.getConfigManager().bettingMinimum();
        double max = plugin.getConfigManager().bettingMaximum();
        if (amount < min) {
            plugin.getMessages().send(sender, "bet-too-low", Map.of("min", String.valueOf((long) min)));
            return;
        }
        if (amount > max) {
            plugin.getMessages().send(sender, "bet-too-high", Map.of("max", String.valueOf((long) max)));
            return;
        }
        if (plugin.getVaultHook().isEnabled() && !plugin.getVaultHook().has(sender, amount)) {
            plugin.getMessages().send(sender, "bet-not-enough-money");
            return;
        }

        plugin.getDuelManager().getAwaitingBet().remove(sender.getUniqueId());
        plugin.getMessages().send(sender, "bet-set", Map.of("amount", String.valueOf(amount)));

        Player target = plugin.getServer().getPlayer(selection.getTarget());
        if (target == null || !target.isOnline()) {
            plugin.getMessages().send(sender, "cant-duel-offline", Map.of("player", "?"));
            return;
        }

        var check = plugin.getDuelManager().canSendRequest(sender, target, selection.getKit());
        if (check != com.lankciworld.lankciduels.duel.DuelManager.RequestResult.OK) {
            return; // Player state changed while typing (e.g. now in a duel) - silently abort.
        }

        plugin.getDuelManager().sendRequest(sender, target, selection.getKit(), amount);
    }
}
