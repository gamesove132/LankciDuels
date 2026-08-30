package com.lankciworld.lankciduels.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Kit {

    private final String id;
    private boolean enabled;
    private String displayName;

    private int guiSlot;
    private Material guiMaterial;
    private String guiName;
    private List<String> guiLore;

    private final Map<Integer, ItemStack> items = new HashMap<>();
    private ItemStack helmet;
    private ItemStack chestplate;
    private ItemStack leggings;
    private ItemStack boots;
    private ItemStack offhand;

    public Kit(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getGuiSlot() {
        return guiSlot;
    }

    public void setGuiSlot(int guiSlot) {
        this.guiSlot = guiSlot;
    }

    public Material getGuiMaterial() {
        return guiMaterial;
    }

    public void setGuiMaterial(Material guiMaterial) {
        this.guiMaterial = guiMaterial;
    }

    public String getGuiName() {
        return guiName;
    }

    public void setGuiName(String guiName) {
        this.guiName = guiName;
    }

    public List<String> getGuiLore() {
        return guiLore;
    }

    public void setGuiLore(List<String> guiLore) {
        this.guiLore = guiLore;
    }

    public Map<Integer, ItemStack> getItems() {
        return items;
    }

    public ItemStack getHelmet() {
        return helmet;
    }

    public void setHelmet(ItemStack helmet) {
        this.helmet = helmet;
    }

    public ItemStack getChestplate() {
        return chestplate;
    }

    public void setChestplate(ItemStack chestplate) {
        this.chestplate = chestplate;
    }

    public ItemStack getLeggings() {
        return leggings;
    }

    public void setLeggings(ItemStack leggings) {
        this.leggings = leggings;
    }

    public ItemStack getBoots() {
        return boots;
    }

    public void setBoots(ItemStack boots) {
        this.boots = boots;
    }

    public ItemStack getOffhand() {
        return offhand;
    }

    public void setOffhand(ItemStack offhand) {
        this.offhand = offhand;
    }
}
