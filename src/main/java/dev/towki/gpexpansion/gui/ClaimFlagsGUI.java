package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.gp.GPFlagsBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * GUI for managing claim flags via GPFlags integration.
 * Allows toggling flags on/off for a specific claim.
 */
public class ClaimFlagsGUI extends BaseGUI {

    private static final String BASE_PERMISSION = "gpflags.command.setclaimflag";
    private static final String OWN_PERMISSION = "griefprevention.claim.gui.setclaimflag.own";
    private static final String ANYWHERE_PERMISSION = "griefprevention.claim.gui.setclaimflag.anywhere";

    private final Object claim;
    private final String claimId;
    private final boolean isOwner;
    
    private int currentPage = 0;
    private static final int FLAGS_PER_PAGE = 36; // 4 rows of 9
    private List<FlagDisplayInfo> displayFlags;
    
    // Slot positions
    private int backSlot = 49;
    private int prevPageSlot = 45;
    private int nextPageSlot = 53;
    private int infoSlot = 4;

    public ClaimFlagsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        super(manager, player, "claim-flags");
        this.claim = claim;
        this.claimId = claimId;
        
        GPBridge gp = new GPBridge();
        this.isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
        
        // Load slot positions from config
        if (config != null) {
            backSlot = config.getInt("slots.back", 49);
            prevPageSlot = config.getInt("slots.prev-page", 45);
            nextPageSlot = config.getInt("slots.next-page", 53);
            infoSlot = config.getInt("slots.info", 4);
        }
        
        loadDisplayFlags();
    }

    private void loadDisplayFlags() {
        displayFlags = new ArrayList<>();
        
        if (!GPFlagsBridge.isAvailable()) return;
        
        // Get all claim flag definitions
        List<GPFlagsBridge.FlagInfo> allFlags = GPFlagsBridge.getClaimFlagDefinitions();
        
        // Get current flag states for this claim
        Map<String, GPFlagsBridge.ClaimFlagState> currentStates = GPFlagsBridge.getAllFlagStates(claimId);
        
        // Build display info for each flag the player has permission for
        for (GPFlagsBridge.FlagInfo flag : allFlags) {
            String flagName = flag.getName();
            String permissionNode = "gpflags.flag." + flagName.toLowerCase();
            
            // Check if player has permission for this flag
            if (!player.hasPermission(permissionNode)) continue;
            
            GPFlagsBridge.ClaimFlagState state = currentStates.get(flagName.toLowerCase());
            boolean isEnabled = state != null && state.isEnabled();
            String params = state != null ? state.getParameters() : "";
            
            displayFlags.add(new FlagDisplayInfo(flagName, isEnabled, params));
        }
        
        // Sort alphabetically
        displayFlags.sort(Comparator.comparing(f -> f.name.toLowerCase()));
    }

    @Override
    public Inventory createInventory() {
        inventory = createBaseInventory("&b&lClaim Flags", 54);
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        // Fill with glass panes
        fillEmpty(createFiller());
        
        if (!GPFlagsBridge.isAvailable()) {
            // Show error message
            ItemStack errorItem = createItem(Material.BARRIER, "&c&lGPFlags Not Available",
                    Arrays.asList("&7GPFlags plugin is not installed", "&7or not properly configured."));
            inventory.setItem(22, errorItem);
            
            // Back button
            inventory.setItem(backSlot, createBackItem());
            return;
        }
        
        // Info item at top
        inventory.setItem(infoSlot, createInfoItem());
        
        // Calculate pagination
        int totalPages = Math.max(1, (int) Math.ceil((double) displayFlags.size() / FLAGS_PER_PAGE));
        int startIndex = currentPage * FLAGS_PER_PAGE;
        int endIndex = Math.min(startIndex + FLAGS_PER_PAGE, displayFlags.size());
        
        // Display flags (slots 9-44)
        int slot = 9;
        for (int i = startIndex; i < endIndex && slot < 45; i++) {
            FlagDisplayInfo flag = displayFlags.get(i);
            inventory.setItem(slot, createFlagItem(flag));
            slot++;
        }
        
        // Navigation row (45-53)
        inventory.setItem(backSlot, createBackItem());
        
        if (currentPage > 0) {
            inventory.setItem(prevPageSlot, createPrevPageItem(currentPage + 1, totalPages));
        }
        
        if (currentPage < totalPages - 1) {
            inventory.setItem(nextPageSlot, createNextPageItem(currentPage + 1, totalPages));
        }
    }

    private ItemStack createInfoItem() {
        String claimName = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim " + claimId);
        return createItem(Material.BOOK, "&b&lClaim Flags",
                Arrays.asList(
                        "&7Managing flags for:",
                        "&f" + claimName,
                        "",
                        "&7Click flags to toggle them",
                        "&7on or off for this claim."
                ));
    }

    private ItemStack createFlagItem(FlagDisplayInfo flag) {
        // Get custom material from config or use default
        Material material = getFlagMaterial(flag.name, flag.enabled);
        
        String displayName = flag.enabled ? "&a✓ " + flag.name : "&c✗ " + flag.name;
        
        List<String> lore = new ArrayList<>();
        lore.add(flag.enabled ? "&aEnabled" : "&cDisabled");
        
        // Add description from config if available
        String description = getFlagDescription(flag.name);
        if (description != null && !description.isEmpty()) {
            lore.add("");
            // Word wrap description
            String[] words = description.split(" ");
            StringBuilder line = new StringBuilder("&7");
            for (String word : words) {
                if (line.length() + word.length() > 40) {
                    lore.add(line.toString());
                    line = new StringBuilder("&7");
                }
                line.append(word).append(" ");
            }
            if (line.length() > 2) {
                lore.add(line.toString().trim());
            }
        }
        
        if (flag.parameters != null && !flag.parameters.isEmpty()) {
            lore.add("");
            lore.add("&7Parameters: &f" + flag.parameters);
        }
        
        lore.add("");
        lore.add("&eClick to toggle");
        
        return createItem(material, displayName, lore);
    }

    private Material getFlagMaterial(String flagName, boolean enabled) {
        // Try to get from config
        if (config != null) {
            String configKey = "flags." + flagName.toLowerCase() + ".material";
            String materialName = config.getString(configKey);
            if (materialName != null) {
                try {
                    return Material.valueOf(materialName.toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        
        // Default materials based on flag name
        return getDefaultFlagMaterial(flagName, enabled);
    }

    private Material getDefaultFlagMaterial(String flagName, boolean enabled) {
        String lower = flagName.toLowerCase();
        
        // Map flags to sensible materials
        if (lower.contains("pvp")) return enabled ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD;
        if (lower.contains("explosion") || lower.contains("wither")) return enabled ? Material.TNT : Material.COBBLESTONE;
        if (lower.contains("fly") || lower.contains("flight")) return enabled ? Material.ELYTRA : Material.FEATHER;
        if (lower.contains("monster") || lower.contains("mob")) return enabled ? Material.ZOMBIE_HEAD : Material.ROTTEN_FLESH;
        if (lower.contains("enter")) return enabled ? Material.OAK_DOOR : Material.IRON_DOOR;
        if (lower.contains("exit")) return enabled ? Material.OAK_DOOR : Material.IRON_DOOR;
        if (lower.contains("message") || lower.contains("actionbar")) return Material.OAK_SIGN;
        if (lower.contains("command")) return Material.COMMAND_BLOCK;
        if (lower.contains("keep") && lower.contains("inventory")) return Material.CHEST;
        if (lower.contains("keep") && lower.contains("level")) return Material.EXPERIENCE_BOTTLE;
        if (lower.contains("health") || lower.contains("regen")) return Material.GOLDEN_APPLE;
        if (lower.contains("hunger")) return Material.COOKED_BEEF;
        if (lower.contains("damage")) return Material.SHIELD;
        if (lower.contains("item") && lower.contains("drop")) return Material.DROPPER;
        if (lower.contains("item") && lower.contains("pickup")) return Material.HOPPER;
        if (lower.contains("leaf") || lower.contains("decay")) return Material.OAK_LEAVES;
        if (lower.contains("fire")) return Material.FLINT_AND_STEEL;
        if (lower.contains("fluid") || lower.contains("water") || lower.contains("lava")) return Material.WATER_BUCKET;
        if (lower.contains("growth") || lower.contains("grow")) return Material.WHEAT_SEEDS;
        if (lower.contains("ice") || lower.contains("snow")) return Material.ICE;
        if (lower.contains("weather")) return Material.SUNFLOWER;
        if (lower.contains("portal")) return Material.OBSIDIAN;
        if (lower.contains("respawn")) return Material.RED_BED;
        if (lower.contains("trust") || lower.contains("buy")) return Material.GOLD_INGOT;
        if (lower.contains("villager") || lower.contains("trading")) return Material.EMERALD;
        if (lower.contains("pet")) return Material.BONE;
        if (lower.contains("arrow")) return Material.ARROW;
        if (lower.contains("ender") || lower.contains("pearl")) return Material.ENDER_PEARL;
        if (lower.contains("chorus")) return Material.CHORUS_FRUIT;
        if (lower.contains("vehicle") || lower.contains("boat") || lower.contains("minecart")) return Material.MINECART;
        if (lower.contains("lectern") || lower.contains("read")) return Material.LECTERN;
        if (lower.contains("container") || lower.contains("view")) return Material.BARREL;
        if (lower.contains("notify")) return Material.BELL;
        if (lower.contains("biome")) return Material.GRASS_BLOCK;
        if (lower.contains("time")) return Material.CLOCK;
        if (lower.contains("elytra")) return Material.ELYTRA;
        if (lower.contains("coral")) return Material.BRAIN_CORAL;
        if (lower.contains("vine")) return Material.VINE;
        if (lower.contains("anvil")) return Material.ANVIL;
        if (lower.contains("expiration")) return Material.PAPER;
        if (lower.contains("loot")) return Material.GOLD_NUGGET;
        if (lower.contains("spleef")) return Material.SNOW_BLOCK;
        if (lower.contains("raid")) return Material.CROSSBOW;
        if (lower.contains("infest") || lower.contains("silverfish")) return Material.INFESTED_STONE;
        if (lower.contains("block") && lower.contains("gravity")) return Material.SAND;
        if (lower.contains("crop") || lower.contains("trample")) return Material.FARMLAND;
        if (lower.contains("map")) return Material.MAP;
        if (lower.contains("mcmmo")) return Material.DIAMOND_PICKAXE;
        if (lower.contains("loaded") || lower.contains("chunk")) return Material.ENDER_EYE;
        
        // Default based on enabled state
        return enabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
    }

    private String getFlagDescription(String flagName) {
        if (config != null) {
            String configKey = "flags." + flagName.toLowerCase() + ".description";
            return config.getString(configKey);
        }
        return null;
    }

    private ItemStack createBackItem() {
        return createItem(Material.ARROW, "&c&lBack", 
                Collections.singletonList("&7Return to claim settings"));
    }

    private ItemStack createPrevPageItem(int current, int total) {
        return createItem(Material.ARROW, "&e&l« Previous Page",
                Collections.singletonList("&7Page " + current + "/" + total));
    }

    private ItemStack createNextPageItem(int current, int total) {
        return createItem(Material.ARROW, "&e&lNext Page »",
                Collections.singletonList("&7Page " + current + "/" + total));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;
        
        playClickSound();
        
        // Navigation
        if (slot == backSlot) {
            // Use GUI state tracker to go back to the previous menu
            if (GUIStateTracker.hasState(player.getUniqueId())) {
                GUIStateTracker.restoreLastGUI(manager, player);
            } else {
                // Fallback to claim settings if no previous state
                manager.openClaimSettings(player, claim, claimId);
            }
            return;
        }
        
        if (slot == prevPageSlot && currentPage > 0) {
            currentPage--;
            refresh();
            return;
        }
        
        int totalPages = Math.max(1, (int) Math.ceil((double) displayFlags.size() / FLAGS_PER_PAGE));
        if (slot == nextPageSlot && currentPage < totalPages - 1) {
            currentPage++;
            refresh();
            return;
        }
        
        // Check if clicking a flag (slots 9-44)
        if (slot >= 9 && slot < 45) {
            int flagIndex = currentPage * FLAGS_PER_PAGE + (slot - 9);
            
            if (flagIndex >= 0 && flagIndex < displayFlags.size()) {
                FlagDisplayInfo flag = displayFlags.get(flagIndex);
                toggleFlag(flag);
            }
        }
    }

    private void toggleFlag(FlagDisplayInfo flag) {
        // Check permissions
        if (!player.hasPermission(BASE_PERMISSION)) {
            plugin.getMessages().send(player, "flags.no-permission");
            return;
        }
        
        if (!player.hasPermission("gpflags.flag." + flag.name.toLowerCase())) {
            plugin.getMessages().send(player, "flags.no-permission-flag", "{flag}", flag.name);
            return;
        }
        
        // Check own/anywhere permission
        if (!isOwner && !player.hasPermission(ANYWHERE_PERMISSION)) {
            plugin.getMessages().send(player, "flags.not-owner");
            return;
        }
        
        // Toggle the flag
        Boolean newState = GPFlagsBridge.toggleFlag(claimId, flag.name);
        
        if (newState != null) {
            flag.enabled = newState;
            String stateText = newState ? "enabled" : "disabled";
            plugin.getMessages().send(player, "flags.toggled",
                "{flag}", flag.name,
                "{state}", stateText);
            refresh();
        } else {
            plugin.getMessages().send(player, "flags.toggle-failed", "{flag}", flag.name);
        }
    }

    private void refresh() {
        loadDisplayFlags();
        inventory.clear();
        populateInventory();
    }

    /**
     * Check if a player can access the claim flags GUI for a claim
     */
    public static boolean canAccess(Player player, Object claim, GPBridge gp) {
        if (!GPFlagsBridge.isAvailable()) return false;
        if (!player.hasPermission(BASE_PERMISSION)) return false;
        
        boolean isOwner = gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin");
        
        if (isOwner) {
            return player.hasPermission(OWN_PERMISSION);
        } else {
            return player.hasPermission(ANYWHERE_PERMISSION);
        }
    }

    /**
     * Helper class to hold flag display info
     */
    private static class FlagDisplayInfo {
        String name;
        boolean enabled;
        String parameters;

        FlagDisplayInfo(String name, boolean enabled, String parameters) {
            this.name = name;
            this.enabled = enabled;
            this.parameters = parameters;
        }
    }
}
