package com.lankciworld.lankciduels.gui;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.kit.Kit;
import com.lankciworld.lankciduels.util.ColorUtil;
import com.lankciworld.lankciduels.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ModeSelectionGUI {

    private final LankciDuels plugin;

    public ModeSelectionGUI(LankciDuels plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Player target) {
        ConfigurationSection section = plugin.getConfigManager().guiModeSelectSection();
        int rows = section != null ? Math.max(1, Math.min(6, section.getInt("rows", 3))) : 3;
        String title = ColorUtil.color(section != null ? section.getString("title", "&8Виберіть режим") : "&8Виберіть режим");

        ModeSelectHolder holder = new ModeSelectHolder(viewer.getUniqueId(), target.getUniqueId());
        Inventory inventory = plugin.getServer().createInventory(holder, rows * 9, title);
        holder.setInventory(inventory);

        if (section != null) {
            ConfigurationSection filler = section.getConfigurationSection("filler");
            if (filler != null) {
                Material material = ItemBuilder.safeMaterial(filler.getString("material"), Material.AIR);
                if (material != Material.AIR && filler.getBoolean("slot-all-empty", true)) {
                    ItemStack fillerItem = new ItemStack(material);
                    ItemMeta meta = fillerItem.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ColorUtil.color(filler.getString("name", " ")));
                        fillerItem.setItemMeta(meta);
                    }
                    for (int i = 0; i < inventory.getSize(); i++) {
                        inventory.setItem(i, fillerItem);
                    }
                }
            }
        }

        for (Kit kit : plugin.getKitManager().getKits().values()) {
            if (!kit.isEnabled()) {
                continue;
            }
            if (kit.getGuiSlot() < 0 || kit.getGuiSlot() >= inventory.getSize()) {
                continue;
            }
            ItemStack item = ItemBuilder.namedItem(kit.getGuiMaterial(), kit.getGuiName(), kit.getGuiLore());
            inventory.setItem(kit.getGuiSlot(), item);
        }

        if (section != null) {
            ConfigurationSection cancel = section.getConfigurationSection("cancel-item");
            if (cancel != null) {
                int slot = cancel.getInt("slot", inventory.getSize() - 1);
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, ItemBuilder.fromConfig(cancel));
                }
            }
        }

        viewer.openInventory(inventory);
    }
}
