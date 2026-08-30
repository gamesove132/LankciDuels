package com.lankciworld.lankciduels.storage;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.duel.PlayerDataSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persists PlayerDataSnapshot objects to disk (playerdata/<uuid>.yml).
 * This is what allows LankciDuels to guarantee no lost items even if the
 * server crashes mid-duel: the snapshot is written to disk the moment a
 * duel starts and only deleted once it has been restored successfully.
 */
public class PlayerDataStore {

    private final LankciDuels plugin;
    private final File folder;

    public PlayerDataStore(LankciDuels plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playerdata");
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid.toString() + ".yml");
    }

    public void save(PlayerDataSnapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", snapshot.getUuid().toString());

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : snapshot.getInventoryContents()) {
            contents.add(item);
        }
        yaml.set("inventory", contents);

        List<ItemStack> armor = new ArrayList<>();
        for (ItemStack item : snapshot.getArmorContents()) {
            armor.add(item);
        }
        yaml.set("armor", armor);

        yaml.set("offhand", snapshot.getOffHand());
        yaml.set("exp", (double) snapshot.getExp());
        yaml.set("level", snapshot.getLevel());
        yaml.set("food", snapshot.getFoodLevel());
        yaml.set("health", snapshot.getHealth());
        yaml.set("gamemode", snapshot.getGameMode().name());

        Location loc = snapshot.getReturnLocation();
        if (loc != null && loc.getWorld() != null) {
            yaml.set("return.world", loc.getWorld().getName());
            yaml.set("return.x", loc.getX());
            yaml.set("return.y", loc.getY());
            yaml.set("return.z", loc.getZ());
            yaml.set("return.yaw", (double) loc.getYaw());
            yaml.set("return.pitch", (double) loc.getPitch());
        }

        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : snapshot.getPotionEffects()) {
            effects.add(effect.getType().getKey().getKey() + ";" + effect.getAmplifier() + ";" + effect.getDuration());
        }
        yaml.set("effects", effects);

        try {
            yaml.save(fileFor(snapshot.getUuid()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[LankciDuels] Не вдалося зберегти playerdata для " + snapshot.getUuid(), e);
        }
    }

    public boolean hasPending(UUID uuid) {
        return fileFor(uuid).exists();
    }

    public PlayerDataSnapshot load(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        PlayerDataSnapshot snapshot = new PlayerDataSnapshot();
        snapshot.setUuid(uuid);

        List<?> invRaw = yaml.getList("inventory");
        ItemStack[] inv = new ItemStack[invRaw != null ? invRaw.size() : 0];
        if (invRaw != null) {
            for (int i = 0; i < invRaw.size(); i++) {
                Object o = invRaw.get(i);
                inv[i] = (o instanceof ItemStack) ? (ItemStack) o : null;
            }
        }
        snapshot.setInventoryContents(inv);

        List<?> armorRaw = yaml.getList("armor");
        ItemStack[] armor = new ItemStack[armorRaw != null ? armorRaw.size() : 0];
        if (armorRaw != null) {
            for (int i = 0; i < armorRaw.size(); i++) {
                Object o = armorRaw.get(i);
                armor[i] = (o instanceof ItemStack) ? (ItemStack) o : null;
            }
        }
        snapshot.setArmorContents(armor);

        snapshot.setOffHand(yaml.getItemStack("offhand"));
        snapshot.setExp((float) yaml.getDouble("exp"));
        snapshot.setLevel(yaml.getInt("level"));
        snapshot.setFoodLevel(yaml.getInt("food"));
        snapshot.setHealth(yaml.getDouble("health", 20.0));
        try {
            snapshot.setGameMode(GameMode.valueOf(yaml.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException e) {
            snapshot.setGameMode(GameMode.SURVIVAL);
        }

        String worldName = yaml.getString("return.world");
        World world = worldName != null ? plugin.getServer().getWorld(worldName) : null;
        if (world != null) {
            Location loc = new Location(world,
                    yaml.getDouble("return.x"), yaml.getDouble("return.y"), yaml.getDouble("return.z"),
                    (float) yaml.getDouble("return.yaw"), (float) yaml.getDouble("return.pitch"));
            snapshot.setReturnLocation(loc);
        }

        List<PotionEffect> effects = new ArrayList<>();
        for (String raw : yaml.getStringList("effects")) {
            String[] parts = raw.split(";");
            if (parts.length != 3) {
                continue;
            }
            try {
                // Registry lookup replaces the deprecated
                // PotionEffectType.getByKey(NamespacedKey) static method.
                org.bukkit.potion.PotionEffectType type = org.bukkit.Registry.EFFECT.get(
                        org.bukkit.NamespacedKey.minecraft(parts[0]));
                if (type != null) {
                    effects.add(new PotionEffect(type, Integer.parseInt(parts[2]), Integer.parseInt(parts[1])));
                }
            } catch (Exception ignored) {
                // Corrupt entry - skip it rather than fail the whole restore.
            }
        }
        snapshot.setPotionEffects(effects);

        return snapshot;
    }

    /**
     * Deletes the on-disk snapshot for this player, once it is no longer
     * needed (i.e. AFTER {@link com.lankciworld.lankciduels.duel.PlayerDataSnapshot#restore}
     * has already confirmed success on a currently-online player - see the
     * call sites in DuelManager and PlayerListener).
     *
     * The delete itself runs on Bukkit's async scheduler rather than the
     * calling (normally main) thread: by the time this is called nothing
     * downstream depends on the file actually being gone yet - at worst,
     * if the server were to crash in the brief window before the delete
     * completes, the next join would harmlessly re-apply the exact same
     * (already-correct, already-applied) snapshot again. That trade-off
     * keeps this very frequent call (every duel ending, every successful
     * crash-recovery on join) off the main server thread, unlike
     * {@link #save}, which intentionally stays synchronous because it
     * MUST hit disk before the player is teleported (see DuelManager#startDuel).
     */
    public void clear(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) {
            return;
        }
        plugin.async(() -> {
            if (!file.delete()) {
                plugin.getLogger().warning("[LankciDuels] Не вдалося видалити playerdata файл для " + uuid);
            }
        });
    }

    public List<UUID> pendingUuids() {
        List<UUID> result = new ArrayList<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return result;
        }
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            try {
                result.add(UUID.fromString(name));
            } catch (IllegalArgumentException ignored) {
                // not a uuid file, skip
            }
        }
        return result;
    }
}
