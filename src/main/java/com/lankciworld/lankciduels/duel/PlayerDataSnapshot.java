package com.lankciworld.lankciduels.duel;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A full snapshot of everything a duel might temporarily change about a
 * player, so it can be restored exactly afterwards - even across a
 * server restart (see PlayerDataStore).
 */
public class PlayerDataSnapshot {

    private UUID uuid;
    private ItemStack[] inventoryContents;
    private ItemStack[] armorContents;
    private ItemStack offHand;
    private float exp;
    private int level;
    private int foodLevel;
    private double health;
    private double maxHealth;
    private GameMode gameMode;
    private Location returnLocation;
    private List<PotionEffect> potionEffects = new ArrayList<>();

    public static PlayerDataSnapshot capture(Player player, Location returnLocation) {
        PlayerDataSnapshot snap = new PlayerDataSnapshot();
        snap.uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        snap.inventoryContents = inv.getContents().clone();
        snap.armorContents = inv.getArmorContents().clone();
        snap.offHand = inv.getItemInOffHand().clone();
        snap.exp = player.getExp();
        snap.level = player.getLevel();
        snap.foodLevel = player.getFoodLevel();
        // Paper's 1.21.1-R0.1-SNAPSHOT API pinned in pom.xml still ships the
        // pre-rename attribute constant (GENERIC_MAX_HEALTH) - the shorter
        // MAX_HEALTH name only exists in later Paper snapshots, so using it
        // here fails to compile against this project's actual API version.
        snap.maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
        snap.health = Math.min(player.getHealth(), snap.maxHealth);
        snap.gameMode = player.getGameMode();
        snap.returnLocation = returnLocation != null ? returnLocation.clone() : player.getLocation().clone();
        snap.potionEffects = new ArrayList<>(player.getActivePotionEffects());
        return snap;
    }

    /**
     * Restores inventory/armor/offhand/exp/food/health/potion-effects/
     * gamemode onto the given (already-online) player. Returns true only
     * if every step completed without throwing - callers MUST NOT delete
     * the on-disk copy of this snapshot unless this returns true, so a
     * partial failure never results in silently losing the player's
     * original items.
     */
    public boolean restore(Player player) {
        try {
            PlayerInventory inv = player.getInventory();
            inv.setContents(inventoryContents);
            inv.setArmorContents(armorContents);
            inv.setItemInOffHand(offHand);
            player.setExp(Math.max(0f, Math.min(1f, exp)));
            player.setLevel(Math.max(0, level));
            player.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            for (PotionEffect effect : potionEffects) {
                player.addPotionEffect(effect);
            }
            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue() : 20.0;
            double hp = Math.max(1.0, Math.min(health, maxHealth));
            player.setHealth(hp);
            player.setGameMode(gameMode);
            player.updateInventory();
            return true;
        } catch (Exception e) {
            player.getServer().getLogger().severe("[LankciDuels] Виняток під час відновлення стану гравця "
                    + player.getName() + ": " + e);
            return false;
        }
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public ItemStack[] getInventoryContents() {
        return inventoryContents;
    }

    public void setInventoryContents(ItemStack[] inventoryContents) {
        this.inventoryContents = inventoryContents;
    }

    public ItemStack[] getArmorContents() {
        return armorContents;
    }

    public void setArmorContents(ItemStack[] armorContents) {
        this.armorContents = armorContents;
    }

    public ItemStack getOffHand() {
        return offHand;
    }

    public void setOffHand(ItemStack offHand) {
        this.offHand = offHand;
    }

    public float getExp() {
        return exp;
    }

    public void setExp(float exp) {
        this.exp = exp;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    public void setFoodLevel(int foodLevel) {
        this.foodLevel = foodLevel;
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public void setGameMode(GameMode gameMode) {
        this.gameMode = gameMode;
    }

    public Location getReturnLocation() {
        return returnLocation;
    }

    public void setReturnLocation(Location returnLocation) {
        this.returnLocation = returnLocation;
    }

    public List<PotionEffect> getPotionEffects() {
        return potionEffects;
    }

    public void setPotionEffects(List<PotionEffect> potionEffects) {
        this.potionEffects = potionEffects;
    }
}
