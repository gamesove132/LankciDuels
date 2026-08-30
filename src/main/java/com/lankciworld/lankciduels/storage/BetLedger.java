package com.lankciworld.lankciduels.storage;

import com.lankciworld.lankciduels.LankciDuels;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Crash-safe bookkeeping for duel bets, independent of PlayerDataSnapshot.
 *
 * Every staked amount goes through exactly one of these states:
 * <pre>
 *   PENDING   - money has been (or is about to be) withdrawn from the
 *               player, the duel has not concluded yet.
 *   SETTLED   - the bet was paid out to a winner as part of a normal
 *               duel ending.
 *   REFUNDED  - the bet was returned to its owner (draw, forcestop,
 *               failed start, or crash-recovery on the next boot).
 * </pre>
 *
 * The record for a bet is written to disk with status PENDING *before*
 * the corresponding Vault withdrawal is attempted (see
 * DuelManager#startDuel), which is what makes recovery possible: on the
 * next server start, {@link #recoverPendingOnStartup()} finds every
 * record still sitting at PENDING - by definition, a duel whose bet was
 * withdrawn but never settled/refunded, i.e. exactly the crash scenario
 * - and refunds it exactly once, deleting the record as it goes so a
 * second recovery pass (or any other future call) can never re-process
 * it.
 *
 * This class only tracks state; it never calls into Vault itself except
 * from {@link #recoverPendingOnStartup()}. Every other financial
 * operation (paying a winner, refunding a draw) happens in DuelManager,
 * which then calls {@link #clear} to retire the record - keeping "move
 * the money" and "track whether the money has been moved" as two
 * separate, easy-to-reason-about responsibilities.
 */
public class BetLedger {

    public enum Status {
        PENDING, SETTLED, REFUNDED
    }

    public record PendingBet(UUID duelId, UUID player, double amount) {
    }

    private final LankciDuels plugin;
    private final File folder;

    public BetLedger(LankciDuels plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "bets");
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private File fileFor(UUID duelId, UUID player) {
        return new File(folder, duelId + "_" + player + ".yml");
    }

    /**
     * Writes a PENDING record to disk. Must be called BEFORE the
     * corresponding Vault withdrawal is attempted - that ordering is the
     * entire point: if the server dies right after the withdrawal, this
     * record is how we know money is owed back to the player.
     *
     * Returns false if the record could NOT be safely written - callers
     * MUST treat that as "do not withdraw any money for this bet", since
     * a withdrawal with no corresponding on-disk record would be
     * unrecoverable if the server crashed immediately after.
     */
    public boolean recordPending(UUID duelId, UUID player, double amount) {
        if (amount <= 0) {
            return true; // nothing to record, not an error
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("duel", duelId.toString());
        yaml.set("player", player.toString());
        yaml.set("amount", amount);
        yaml.set("status", Status.PENDING.name());
        try {
            yaml.save(fileFor(duelId, player));
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[LankciDuels] Не вдалося записати bet-ledger запис для " + player, e);
            return false;
        }
    }

    /**
     * Retires a bet record once the corresponding money has actually
     * been moved (paid to a winner or refunded) by the caller. Safe to
     * call even if no record exists (e.g. betting was disabled) - it's a
     * no-op in that case.
     */
    public void clear(UUID duelId, UUID player) {
        File file = fileFor(duelId, player);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("[LankciDuels] Не вдалося видалити bet-ledger запис " + file.getName());
        }
    }

    /**
     * Called once at startup, before any player can join a new duel.
     * Refunds every bet that is still sitting at PENDING - i.e. a duel
     * that withdrew money but crashed/restarted before it could settle
     * or refund it through the normal flow.
     *
     * The record is only deleted once the Vault deposit has CONFIRMED
     * success. If the deposit fails (economy plugin unavailable during
     * boot, etc.), the record is deliberately left at PENDING so this
     * same method retries it again on the next server start - it must
     * never be silently dropped just because one attempt failed.
     */
    public void recoverPendingOnStartup() {
        for (PendingBet pending : loadAllPending()) {
            File file = fileFor(pending.duelId(), pending.player());

            org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(pending.player());
            boolean paid = plugin.getVaultHook().deposit(offline, pending.amount());
            if (paid) {
                plugin.getLogger().info("[LankciDuels] Відновлено ставку " + pending.amount()
                        + " гравцю " + pending.player() + " після незавершеної дуелі (crash-recovery).");
                file.delete();
            } else {
                plugin.getLogger().severe("[LankciDuels] Не вдалося повернути ставку " + pending.amount()
                        + " гравцю " + pending.player() + " - Vault/economy недоступний. "
                        + "Запис лишається PENDING на диску і буде повторно оброблений при наступному запуску сервера.");
                // Deliberately leave the file untouched at PENDING - do not
                // delete, do not mark REFUNDED, so the next boot retries it.
            }
        }
    }

    private List<PendingBet> loadAllPending() {
        List<PendingBet> result = new ArrayList<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return result;
        }
        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String status = yaml.getString("status", Status.PENDING.name());
            if (!Status.PENDING.name().equals(status)) {
                // Leftover SETTLED/REFUNDED file that failed to delete earlier -
                // safe to clean up now, nothing owed.
                file.delete();
                continue;
            }
            try {
                UUID duelId = UUID.fromString(yaml.getString("duel"));
                UUID player = UUID.fromString(yaml.getString("player"));
                double amount = yaml.getDouble("amount");
                result.add(new PendingBet(duelId, player, amount));
            } catch (Exception e) {
                plugin.getLogger().warning("[LankciDuels] Пошкоджений bet-ledger файл проігноровано: " + file.getName());
                file.delete();
            }
        }
        return result;
    }
}
