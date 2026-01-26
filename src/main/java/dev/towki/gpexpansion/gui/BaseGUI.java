package dev.towki.gpexpansion.gui;

import dev.towki.gpexpansion.GPExpansionPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * Base class for all GUIs with common functionality.
 */
public abstract class BaseGUI {
    
    protected final GUIManager manager;
    protected final GPExpansionPlugin plugin;
    protected final Player player;
    protected final FileConfiguration config;
    protected Inventory inventory;
    
    public BaseGUI(GUIManager manager, Player player, String configName) {
        this.manager = manager;
        this.plugin = manager.getPlugin();
        this.player = player;
        this.config = manager.getGUIConfig(configName);
    }
    
    /**
     * Create and populate the inventory.
     */
    public abstract Inventory createInventory();
    
    /**
     * Handle a click event in the GUI.
     */
    public abstract void handleClick(InventoryClickEvent event);
    
    /**
     * Called when the GUI is closed.
     */
    public void onClose(Player player) {
        // Override in subclasses if needed
    }
    
    public Inventory getInventory() {
        return inventory;
    }
    
    // === Utility Methods ===
    
    /**
     * Create an inventory with the configured title and size.
     */
    protected Inventory createBaseInventory(String defaultTitle, int defaultSize) {
        String title = config != null ? config.getString("title", defaultTitle) : defaultTitle;
        int size = config != null ? config.getInt("size", defaultSize) : defaultSize;
        
        // Ensure size is valid (multiple of 9, max 54)
        size = Math.min(54, Math.max(9, (size / 9) * 9));
        
        Component titleComponent = colorize(title);
        return Bukkit.createInventory(null, size, titleComponent);
    }
    
    /**
     * Create an inventory with a pre-processed title (placeholders already replaced).
     * Use this when the title has dynamic placeholders that need to be replaced before display.
     */
    protected Inventory createBaseInventoryWithTitle(String processedTitle, int defaultSize) {
        int size = config != null ? config.getInt("size", defaultSize) : defaultSize;
        
        // Ensure size is valid (multiple of 9, max 54)
        size = Math.min(54, Math.max(9, (size / 9) * 9));
        
        Component titleComponent = colorize(processedTitle);
        return Bukkit.createInventory(null, size, titleComponent);
    }
    
    /**
     * Create an item from config section.
     */
    protected ItemStack createItemFromConfig(String path, Map<String, String> placeholders) {
        if (config == null) return new ItemStack(Material.STONE);
        
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return new ItemStack(Material.STONE);
        
        return createItemFromSection(section, placeholders);
    }
    
    /**
     * Create an item from a configuration section.
     * Supports textured player heads via 'skull-texture' or 'skull-owner' keys.
     */
    protected ItemStack createItemFromSection(ConfigurationSection section, Map<String, String> placeholders) {
        String materialName = section.getString("material", "STONE");
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.STONE;
        
        int amount = section.getInt("amount", 1);
        ItemStack item = new ItemStack(material, amount);
        
        // Handle textured player heads
        if (material == Material.PLAYER_HEAD) {
            String texture = section.getString("skull-texture");
            String owner = section.getString("skull-owner");
            if (texture != null && !texture.isEmpty()) {
                // Apply base64 texture
                texture = applyPlaceholders(texture, placeholders);
                item = createTexturedSkull(texture);
                item.setAmount(amount);
            } else if (owner != null && !owner.isEmpty()) {
                // Apply player owner
                owner = applyPlaceholders(owner, placeholders);
                item = createPlayerSkull(owner);
                item.setAmount(amount);
            }
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Name
            String name = section.getString("name", "");
            if (!name.isEmpty()) {
                name = applyPlaceholders(name, placeholders);
                meta.displayName(colorize(name));
            }
            
            // Lore
            List<String> loreList = section.getStringList("lore");
            if (!loreList.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreList) {
                    line = applyPlaceholders(line, placeholders);
                    lore.add(colorize(line));
                }
                meta.lore(lore);
            }
            
            // Custom model data
            if (section.contains("custom-model-data")) {
                meta.setCustomModelData(section.getInt("custom-model-data"));
            }
            
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    /**
     * Create a player skull with a base64 texture.
     * This supports custom head textures from sites like minecraft-heads.com.
     * 
     * @param base64Texture The base64 encoded texture string (or URL to texture)
     * @return ItemStack with the custom texture applied
     */
    protected ItemStack createTexturedSkull(String base64Texture) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (base64Texture == null || base64Texture.isEmpty()) return skull;
        
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        
        try {
            // Parse the texture URL from base64 or use direct URL
            String textureUrl;
            if (base64Texture.startsWith("http")) {
                textureUrl = base64Texture;
            } else {
                // Decode base64 to get the texture URL
                // Format: {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/..."}}}
                String decoded = new String(Base64.getDecoder().decode(base64Texture));
                int urlStart = decoded.indexOf("http");
                if (urlStart == -1) return skull;
                int urlEnd = decoded.indexOf('"', urlStart);
                if (urlEnd == -1) urlEnd = decoded.indexOf('}', urlStart);
                textureUrl = decoded.substring(urlStart, urlEnd);
            }
            
            // Create a player profile with the texture
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(textureUrl));
            profile.setTextures(textures);
            
            meta.setOwnerProfile(profile);
            skull.setItemMeta(meta);
        } catch (MalformedURLException | IllegalArgumentException e) {
            plugin.getLogger().warning("Failed to parse skull texture: " + e.getMessage());
        }
        
        return skull;
    }
    
    /**
     * Create a player skull from a player name or UUID.
     * 
     * @param playerIdentifier Player name or UUID string
     * @return ItemStack with the player's skin
     */
    protected ItemStack createPlayerSkull(String playerIdentifier) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (playerIdentifier == null || playerIdentifier.isEmpty()) return skull;
        
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        
        // Handle special placeholder for current player
        if (playerIdentifier.equalsIgnoreCase("{player}") || playerIdentifier.equalsIgnoreCase("%player%")) {
            meta.setOwningPlayer(player);
        } else {
            // Try to parse as UUID first
            try {
                UUID uuid = UUID.fromString(playerIdentifier);
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            } catch (IllegalArgumentException e) {
                // Not a UUID, treat as player name
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerIdentifier));
            }
        }
        
        skull.setItemMeta(meta);
        return skull;
    }
    
    /**
     * Create a simple item with name and lore.
     */
    protected ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isEmpty()) {
                meta.displayName(colorize(name));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>();
                for (String line : lore) {
                    loreComponents.add(colorize(line));
                }
                meta.lore(loreComponents);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Create a simple item with name only.
     */
    protected ItemStack createItem(Material material, String name) {
        return createItem(material, name, null);
    }
    
    /**
     * Add dynamic lore lines based on permissions.
     */
    protected List<String> addDynamicLore(List<String> baseLore, String permission, String... lines) {
        List<String> result = new ArrayList<>(baseLore);
        if (player.hasPermission(permission)) {
            for (String line : lines) {
                result.add(line);
            }
        }
        return result;
    }
    
    /**
     * Add lore lines that show grayed out if player lacks permission.
     */
    protected List<String> addPermissionLore(List<String> baseLore, String permission, String enabledLine, String disabledLine) {
        List<String> result = new ArrayList<>(baseLore);
        if (player.hasPermission(permission)) {
            result.add(enabledLine);
        } else {
            result.add(disabledLine);
        }
        return result;
    }
    
    /**
     * Fill empty slots with a filler item.
     */
    protected void fillEmpty(ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }
    
    /**
     * Fill border slots with a filler item.
     */
    protected void fillBorder(ItemStack filler) {
        int size = inventory.getSize();
        int rows = size / 9;
        
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler); // Top row
            inventory.setItem(size - 9 + i, filler); // Bottom row
        }
        
        for (int row = 1; row < rows - 1; row++) {
            inventory.setItem(row * 9, filler); // Left column
            inventory.setItem(row * 9 + 8, filler); // Right column
        }
    }
    
    /**
     * Create a glass pane filler.
     */
    protected ItemStack createFiller() {
        String materialName = config != null ? config.getString("filler.material", "GRAY_STAINED_GLASS_PANE") : "GRAY_STAINED_GLASS_PANE";
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.GRAY_STAINED_GLASS_PANE;
        
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
        }
        return filler;
    }
    
    /**
     * Colorize a string using legacy color codes.
     * Disables italic by default (Minecraft italicizes lore by default).
     * Also parses PlaceholderAPI placeholders and Oraxen/Nexo glyph tags.
     */
    protected Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        
        // Parse PAPI placeholders first
        text = parsePlaceholders(text);
        
        // Check for Oraxen/Nexo glyph tags and parse with MiniMessage if present
        if (text.contains("<glyph:") || text.contains("<font:")) {
            text = parseGlyphTags(text);
        }
        
        // Convert legacy color codes and return
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
    
    /**
     * Parse Oraxen/Nexo glyph tags using their APIs.
     * Converts <glyph:name> to the actual unicode character.
     */
    private String parseGlyphTags(String text) {
        // Try Oraxen first
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
            try {
                text = parseOraxenGlyphs(text);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse Oraxen glyphs: " + e.getMessage());
            }
        }
        
        // Try Nexo
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            try {
                text = parseNexoGlyphs(text);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse Nexo glyphs: " + e.getMessage());
            }
        }
        
        return text;
    }
    
    /**
     * Parse Oraxen glyph tags using reflection to avoid hard dependency.
     */
    private String parseOraxenGlyphs(String text) {
        try {
            // Use Oraxen's Font API to get glyph characters
            Class<?> glyphClass = Class.forName("io.th0rgal.oraxen.font.Glyph");
            
            // Parse <glyph:name> tags manually
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<glyph:([^>]+)>");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            StringBuffer sb = new StringBuffer();
            
            while (matcher.find()) {
                String glyphId = matcher.group(1);
                try {
                    // Get glyph from Oraxen
                    Class<?> glyphsClass = Class.forName("io.th0rgal.oraxen.font.FontManager");
                    Object fontManager = glyphsClass.getMethod("getInstance").invoke(null);
                    Object glyph = glyphsClass.getMethod("getGlyphFromID", String.class).invoke(fontManager, glyphId);
                    
                    if (glyph != null) {
                        String character = (String) glyphClass.getMethod("getCharacter").invoke(glyph);
                        matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(character));
                    } else {
                        matcher.appendReplacement(sb, ""); // Remove tag if glyph not found
                    }
                } catch (Exception e) {
                    matcher.appendReplacement(sb, ""); // Remove tag on error
                }
            }
            matcher.appendTail(sb);
            return sb.toString();
        } catch (ClassNotFoundException e) {
            // Oraxen API not available, return original
            return text;
        }
    }
    
    /**
     * Parse Nexo glyph tags using reflection to avoid hard dependency.
     */
    private String parseNexoGlyphs(String text) {
        // Parse <glyph:name> tags for Nexo
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<glyph:([^>]+)>");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String glyphId = matcher.group(1);
            try {
                // Get glyph from Nexo
                Class<?> nexoGlyphsClass = Class.forName("com.nexomc.nexo.fonts.FontManager");
                Object fontManager = nexoGlyphsClass.getMethod("getInstance").invoke(null);
                Object glyph = nexoGlyphsClass.getMethod("getGlyph", String.class).invoke(fontManager, glyphId);
                
                if (glyph != null) {
                    Class<?> glyphClass = glyph.getClass();
                    String character = (String) glyphClass.getMethod("getCharacter").invoke(glyph);
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(character));
                } else {
                    matcher.appendReplacement(sb, "");
                }
            } catch (Exception e) {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * Parse PlaceholderAPI placeholders in a string.
     * Supports font images from ItemsAdder (%img_name%), Oraxen (%oraxen_id%), Nexo (%nexo_id%).
     */
    protected String parsePlaceholders(String text) {
        if (text == null) return null;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (Exception e) {
                // PlaceholderAPI not available or error - return original text
            }
        }
        return text;
    }
    
    /**
     * Apply placeholders to a string.
     */
    protected String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) return text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }
    
    /**
     * Get string from config with default.
     */
    protected String getString(String path, String defaultValue) {
        return config != null ? config.getString(path, defaultValue) : defaultValue;
    }
    
    /**
     * Get int from config with default.
     */
    protected int getInt(String path, int defaultValue) {
        return config != null ? config.getInt(path, defaultValue) : defaultValue;
    }
    
    /**
     * Get string list from config.
     */
    protected List<String> getStringList(String path) {
        return config != null ? config.getStringList(path) : new ArrayList<>();
    }
    
    /**
     * Check if click is left click.
     */
    protected boolean isLeftClick(InventoryClickEvent event) {
        return event.getClick() == ClickType.LEFT;
    }
    
    /**
     * Check if click is right click.
     */
    protected boolean isRightClick(InventoryClickEvent event) {
        return event.getClick() == ClickType.RIGHT;
    }
    
    /**
     * Check if click is shift + left click.
     */
    protected boolean isShiftLeftClick(InventoryClickEvent event) {
        return event.getClick() == ClickType.SHIFT_LEFT;
    }
    
    /**
     * Check if click is shift + right click.
     */
    protected boolean isShiftRightClick(InventoryClickEvent event) {
        return event.getClick() == ClickType.SHIFT_RIGHT;
    }
    
    /**
     * Play a click sound to the player.
     */
    protected void playClickSound() {
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
    
    /**
     * Close the GUI and run a task after.
     */
    protected void closeAndRun(Runnable task) {
        player.closeInventory();
        runLater(task, 1L);
    }
    
    /**
     * Run a task later with Folia compatibility.
     */
    protected void runLater(Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterEntity(plugin, player, task, delayTicks);
    }

    /**
     * Close the GUI and run a task immediately.
     * Use this for operations that need to run after inventory close.
     */
    protected void closeAndRunOnMainThread(Runnable task) {
        player.closeInventory();
        task.run();
    }

    /**
     * Close the GUI and run a command immediately.
     * Use this for operations that need to run after inventory close.
     */
    protected void closeAndRunOnMainThread(String command) {
        player.closeInventory();
        // On Folia, commands with a player sender must be dispatched on the entity's region thread
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runEntity(plugin, player,
            () -> org.bukkit.Bukkit.dispatchCommand(player, command), null);
    }
}
