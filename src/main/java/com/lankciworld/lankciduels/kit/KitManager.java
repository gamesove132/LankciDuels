package com.lankciworld.lankciduels.kit;

import com.lankciworld.lankciduels.LankciDuels;
import com.lankciworld.lankciduels.util.ColorUtil;
import com.lankciworld.lankciduels.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class KitManager {

    private final LankciDuels plugin;
    private final File file;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitManager(LankciDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kits.yml");
        if (!file.exists()) {
            plugin.saveResource("kits.yml", false);
        }
        load();
    }

    public void load() {
        kits.clear();
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection kitsSection = yaml.getConfigurationSection("kits");
        if (kitsSection == null) {
            return;
        }
        for (String id : kitsSection.getKeys(false)) {
            ConfigurationSection section = kitsSection.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            Kit kit = new Kit(id);
            kit.setEnabled(section.getBoolean("enabled", true));
            kit.setDisplayName(ColorUtil.color(section.getString("display-name", id)));

            ConfigurationSection gui = section.getConfigurationSection("gui");
            if (gui != null) {
                kit.setGuiSlot(gui.getInt("slot", 0));
                kit.setGuiMaterial(ItemBuilder.safeMaterial(gui.getString("material"), Material.PAPER));
                kit.setGuiName(gui.getString("name", kit.getDisplayName()));
                kit.setGuiLore(gui.getStringList("lore"));
            } else {
                kit.setGuiMaterial(Material.PAPER);
                kit.setGuiName(kit.getDisplayName());
            }

            ConfigurationSection itemsSection = section.getConfigurationSection("items");
            if (itemsSection != null) {
                for (String slotKey : itemsSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotKey);
                        ItemStack item = ItemBuilder.fromShorthand(itemsSection.getString(slotKey));
                        if (item != null && slot >= 0 && slot < 41) {
                            kit.getItems().put(slot, item);
                        }
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("[LankciDuels] Некоректний слот '" + slotKey + "' у kits.yml (" + id + ")");
                    }
                }
            }

            ConfigurationSection armor = section.getConfigurationSection("armor");
            if (armor != null) {
                kit.setHelmet(ItemBuilder.fromShorthand(armor.getString("helmet")));
                kit.setChestplate(ItemBuilder.fromShorthand(armor.getString("chestplate")));
                kit.setLeggings(ItemBuilder.fromShorthand(armor.getString("leggings")));
                kit.setBoots(ItemBuilder.fromShorthand(armor.getString("boots")));
            }

            kit.setOffhand(ItemBuilder.fromShorthand(section.getString("offhand")));

            kits.put(id.toLowerCase(), kit);
        }
    }

    public Optional<Kit> getKit(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(kits.get(id.toLowerCase()));
    }

    public Map<String, Kit> getKits() {
        return kits;
    }

    /**
     * Clears the player's inventory (if configured) and gives them the
     * kit's items/armor/offhand. Must be called on the main thread.
     */
    public void apply(Player player, Kit kit) {
        PlayerInventory inv = player.getInventory();
        if (plugin.getConfigManager().clearBeforeKit()) {
            inv.clear();
            inv.setArmorContents(null);
            inv.setItemInOffHand(new ItemStack(Material.AIR));
        }
        for (Map.Entry<Integer, ItemStack> entry : kit.getItems().entrySet()) {
            inv.setItem(entry.getKey(), entry.getValue().clone());
        }
        if (kit.getHelmet() != null) {
            inv.setHelmet(kit.getHelmet().clone());
        }
        if (kit.getChestplate() != null) {
            inv.setChestplate(kit.getChestplate().clone());
        }
        if (kit.getLeggings() != null) {
            inv.setLeggings(kit.getLeggings().clone());
        }
        if (kit.getBoots() != null) {
            inv.setBoots(kit.getBoots().clone());
        }
        if (kit.getOffhand() != null) {
            inv.setItemInOffHand(kit.getOffhand().clone());
        }
        player.updateInventory();
    }
}
