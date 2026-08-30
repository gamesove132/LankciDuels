package com.lankciworld.lankciduels.hook;

import com.lankciworld.lankciduels.LankciDuels;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Optional Vault integration. If Vault (or an economy plugin behind it)
 * is not installed, every method here degrades gracefully and the
 * betting system simply refuses to operate (see config "betting.enabled"
 * + the "bet-no-economy" message) instead of throwing.
 */
public class VaultHook {

    private Economy economy;

    public VaultHook(LankciDuels plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> provider = plugin.getServer()
                .getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            economy = provider.getProvider();
        }
    }

    public boolean isEnabled() {
        return economy != null;
    }

    public double getBalance(OfflinePlayer player) {
        if (economy == null) {
            return 0;
        }
        return economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) {
            return false;
        }
        return economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) {
            return false;
        }
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) {
            return false;
        }
        return economy.depositPlayer(player, amount).transactionSuccess();
    }
}
