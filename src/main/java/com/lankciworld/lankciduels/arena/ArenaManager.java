package com.lankciworld.lankciduels.arena;

import com.lankciworld.lankciduels.LankciDuels;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Owns arenas.yml. All mutating operations are expected to run on the
 * main server thread (commands/GUI clicks already run there), so a
 * simple synchronized map is enough to guard against accidental
 * concurrent access.
 */
public class ArenaManager {

    private final LankciDuels plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(LankciDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) {
            plugin.saveResource("arenas.yml", false);
        }
        load();
    }

    public synchronized void load() {
        arenas.clear();
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("arenas");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection arenaSection = section.getConfigurationSection(name);
            if (arenaSection == null) {
                continue;
            }
            Arena arena = new Arena(name, arenaSection.getString("world", "world"));
            arena.setSpawn1(readLocation(arenaSection.getConfigurationSection("spawn1"), arena.getWorldName()));
            arena.setSpawn2(readLocation(arenaSection.getConfigurationSection("spawn2"), arena.getWorldName()));
            arenas.put(name.toLowerCase(), arena);
        }
    }

    private Location readLocation(ConfigurationSection section, String worldName) {
        if (section == null) {
            return null;
        }
        // Always resolve the World dynamically (rather than only when it
        // happens to be missing at parse time): Multiverse-Core can load
        // its worlds after LankciDuels has already read arenas.yml, and a
        // world can also be unloaded/reloaded later (e.g. /mv unload +
        // /mv load, or a server restart with a different plugin load
        // order) - an UnresolvedLocation keeps re-checking Bukkit.getWorld
        // every time, so the arena "comes back to life" on its own the
        // moment its world becomes available, with no reload needed.
        return new UnresolvedLocation(worldName,
                section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    public synchronized void save() {
        FileConfiguration yaml = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            yaml.set(path + ".world", arena.getWorldName());
            writeLocation(yaml, path + ".spawn1", arena.getSpawn1());
            writeLocation(yaml, path + ".spawn2", arena.getSpawn2());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти arenas.yml", e);
        }
    }

    private void writeLocation(FileConfiguration yaml, String path, Location location) {
        if (location == null) {
            return;
        }
        yaml.set(path + ".x", location.getX());
        yaml.set(path + ".y", location.getY());
        yaml.set(path + ".z", location.getZ());
        yaml.set(path + ".yaw", (double) location.getYaw());
        yaml.set(path + ".pitch", (double) location.getPitch());
    }

    public synchronized boolean createArena(String name, World world) {
        String key = name.toLowerCase();
        if (arenas.containsKey(key)) {
            return false;
        }
        Arena arena = new Arena(name, world.getName());
        arenas.put(key, arena);
        save();
        return true;
    }

    public synchronized boolean deleteArena(String name) {
        Arena removed = arenas.remove(name.toLowerCase());
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    public synchronized Optional<Arena> getArena(String name) {
        return Optional.ofNullable(arenas.get(name.toLowerCase()));
    }

    public synchronized Collection<Arena> getArenas() {
        return arenas.values();
    }

    public synchronized boolean setSpawn(String name, int point, Location location) {
        Arena arena = arenas.get(name.toLowerCase());
        if (arena == null) {
            return false;
        }
        arena.setWorldName(location.getWorld().getName());
        if (point == 1) {
            arena.setSpawn1(location.clone());
        } else {
            arena.setSpawn2(location.clone());
        }
        save();
        return true;
    }

    /**
     * Finds the first arena that is fully configured, has its world
     * loaded and is currently FREE. Returns empty if none match.
     */
    public synchronized Optional<Arena> findFreeArena() {
        for (Arena arena : arenas.values()) {
            if (arena.getStatus() == ArenaStatus.FREE && arena.isReady()) {
                return Optional.of(arena);
            }
        }
        return Optional.empty();
    }

    /**
     * A location placeholder used when an arena's world is not currently
     * loaded. It resolves the real Bukkit World lazily the moment the
     * world is loaded, instead of crashing at startup.
     */
    public static class UnresolvedLocation extends Location {
        private final String worldName;

        public UnresolvedLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
            super(null, x, y, z, yaw, pitch);
            this.worldName = worldName;
        }

        @Override
        public World getWorld() {
            return Bukkit.getWorld(worldName);
        }
    }
}
