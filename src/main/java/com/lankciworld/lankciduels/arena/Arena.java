package com.lankciworld.lankciduels.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public class Arena {

    private final String name;
    private String worldName;
    private Location spawn1;
    private Location spawn2;
    private volatile ArenaStatus status = ArenaStatus.FREE;
    private volatile UUID activeDuelId = null;

    public Arena(String name, String worldName) {
        this.name = name;
        this.worldName = worldName;
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public World getWorld() {
        return worldName != null ? Bukkit.getWorld(worldName) : null;
    }

    public boolean isWorldLoaded() {
        return getWorld() != null;
    }

    public Location getSpawn1() {
        return spawn1;
    }

    public void setSpawn1(Location spawn1) {
        this.spawn1 = spawn1;
    }

    public Location getSpawn2() {
        return spawn2;
    }

    public void setSpawn2(Location spawn2) {
        this.spawn2 = spawn2;
    }

    public boolean isReady() {
        return spawn1 != null && spawn2 != null && isWorldLoaded();
    }

    public ArenaStatus getStatus() {
        return status;
    }

    public void setStatus(ArenaStatus status) {
        this.status = status;
    }

    public UUID getActiveDuelId() {
        return activeDuelId;
    }

    public void setActiveDuelId(UUID activeDuelId) {
        this.activeDuelId = activeDuelId;
    }

    public boolean isInsideRadius(Location location, double radius) {
        if (location == null || spawn1 == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().equals(spawn1.getWorld())) {
            return false;
        }
        double midX = (spawn1.getX() + (spawn2 != null ? spawn2.getX() : spawn1.getX())) / 2.0;
        double midZ = (spawn1.getZ() + (spawn2 != null ? spawn2.getZ() : spawn1.getZ())) / 2.0;
        double dx = location.getX() - midX;
        double dz = location.getZ() - midZ;
        return (dx * dx + dz * dz) <= (radius * radius);
    }
}
