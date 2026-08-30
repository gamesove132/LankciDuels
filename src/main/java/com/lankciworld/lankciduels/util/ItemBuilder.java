package com.lankciworld.lankciduels.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Small helper for turning configuration data into ItemStacks safely.
 * Never throws for a bad material name - falls back to BARRIER so a typo
 * in config.yml can never crash the plugin.
 */
public final class ItemBuilder {

    private ItemBuilder() {
    }

    public static Material safeMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase());
        return material != null ? material : fallback;
    }

    public static ItemStack fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new ItemStack(Material.BARRIER);
        }
        Material material = safeMaterial(section.getString("material"), Material.STONE);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = section.getString("name");
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(ColorUtil.color(name));
            }
            List<String> lore = section.getStringList("lore");
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(ColorUtil.color(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Parses entries of the form "MATERIAL:AMOUNT" used in kits.yml.
     */
    public static ItemStack fromShorthand(String shorthand) {
        if (shorthand == null || shorthand.isBlank()) {
            return null;
        }
        String[] parts = shorthand.split(":");
        Material material = safeMaterial(parts[0], null);
        if (material == null) {
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }
        return new ItemStack(material, amount);
    }

    public static ItemStack namedItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                meta.setDisplayName(ColorUtil.color(displayName));
            }
            if (lore != null) {
                meta.setLore(ColorUtil.color(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
