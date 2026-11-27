package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.gp.GPFlagsBridge;
import codes.castled.gpexpansion.util.Mini;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 27-slot sub-menu for GPFlags that accept presets, locations, player lists, or free-form text/commands.
 */
public class GPFlagParameterGUI extends BaseGUI {

    static final int INFO_SLOT = 4;
    static final int BACK_SLOT = 18;
    static final int DISABLE_SLOT = 22;

    static final int LOCATION_CURRENT_SLOT = 11;
    static final int LOCATION_CHAT_SLOT = 15;

    static final int PLAYERS_ADD_SLOT = 11;
    static final int PLAYERS_REPLACE_SLOT = 13;
    static final int PLAYERS_REMOVE_SLOT = 15;

    static final int TEXT_SET_SLOT = 11;
    static final int TEXT_DISABLE_SLOT = 15;

    static final int PRESET_CUSTOM_SLOT = 16;

    public enum Kind {
        PRESETS,
        LOCATION_PRECISE,
        LOCATION_BLOCK,
        PLAYER_LIST,
        TEXT
    }

    public record Preset(String parameter, String displayName, Material material, int slot, String description) {}

    private final Object claim;
    private final String claimId;
    private final String flagName;
    private final String menuTitle;
    private final String summary;
    private final Kind kind;
    private final String chatPromptHint;
    private final boolean splitArgsOnSend;
    private final List<Preset> presets;
    private final int returnPage;

    private boolean flagEnabled;
    private boolean flagExplicit = true;
    private String activeValue = "";

    public GPFlagParameterGUI(
            GUIManager manager,
            Player player,
            Object claim,
            String claimId,
            String flagName,
            String menuTitle,
            String summary,
            Kind kind,
            String chatPromptHint,
            boolean splitArgsOnSend,
            List<Preset> presets,
            int returnPage
    ) {
        super(manager, player, "claim-flags-parameter");
        this.claim = claim;
        this.claimId = claimId;
        this.flagName = flagName;
        this.menuTitle = menuTitle;
        this.summary = summary;
        this.kind = kind;
        this.chatPromptHint = chatPromptHint;
        this.splitArgsOnSend = splitArgsOnSend;
        this.presets = presets != null ? List.copyOf(presets) : List.of();
        this.returnPage = Math.max(0, returnPage);
        loadState();
    }

    public String getFlagName() {
        return flagName;
    }

    public Kind getKind() {
        return kind;
    }

    private void loadState() {
        Optional<GPFlagsBridge.ClaimFlagState> stateOpt =
                GPFlagsBridge.getEffectiveFlagState(claim, flagName);
        if (stateOpt.isPresent()) {
            GPFlagsBridge.ClaimFlagState state = stateOpt.get();
            this.flagEnabled = state.isEnabled();
            this.flagExplicit = state.isExplicit();
            this.activeValue = state.getParameters() != null ? state.getParameters().trim() : "";
        } else {
            this.flagEnabled = false;
            this.flagExplicit = true;
            this.activeValue = "";
        }
    }

    @Override
    public Inventory createInventory() {
        inventory = Mini.createInventory(null, 27, colorize(menuTitle));
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        fillEmpty(createFiller());
        inventory.setItem(INFO_SLOT, createInfoItem());
        inventory.setItem(BACK_SLOT, createItem(Material.ARROW, "&c&lBack",
                List.of("&7Return to Claim Flags")));

        switch (kind) {
            case PRESETS -> populatePresets();
            case LOCATION_PRECISE, LOCATION_BLOCK -> populateLocation();
            case PLAYER_LIST -> populatePlayerList();
            case TEXT -> populateText();
        }
    }

    private void populatePresets() {
        for (Preset preset : presets) {
            boolean selected = flagEnabled && preset.parameter().equalsIgnoreCase(activeValue);
            String title = selected ? "&a&l✓ " + preset.displayName() : "&e&l" + preset.displayName();
            List<String> lore = new ArrayList<>();
            lore.add(selected ? "&aCurrently active" : "&7Preset option");
            if (preset.description() != null && !preset.description().isEmpty()) {
                lore.add("&7" + preset.description());
            }
            lore.add("");
            lore.add(selected && flagExplicit ? "&7Already selected" : "&eClick to select");
            inventory.setItem(preset.slot(), decoratedItem(preset.material(), title, lore, selected));
        }

        inventory.setItem(PRESET_CUSTOM_SLOT, createItem(Material.OAK_SIGN, "&e&lCustom Value (Chat)",
                List.of("&7Enter a custom value in chat:", "&f" + chatPromptHint, "", "&eClick to enter")));
        inventory.setItem(DISABLE_SLOT, createDisableItem());
    }

    private void populateLocation() {
        Location loc = player.getLocation();
        String preview = formatLocationPreview(loc);
        List<String> currentLore = new ArrayList<>();
        currentLore.add("&7Set " + flagName + " to where");
        currentLore.add("&7you are standing right now.");
        if (!preview.isEmpty()) {
            currentLore.add("");
            currentLore.add("&7Preview: &f" + preview);
        }
        currentLore.add("");
        currentLore.add("&eClick to use current location");
        inventory.setItem(LOCATION_CURRENT_SLOT, createItem(Material.COMPASS, "&a&lUse Current Location", currentLore));

        inventory.setItem(LOCATION_CHAT_SLOT, createItem(Material.OAK_SIGN, "&e&lEnter Coordinates (Chat)",
                List.of("&7Type coordinates directly in chat:", "&f" + chatPromptHint, "", "&eClick to enter")));
        inventory.setItem(DISABLE_SLOT, createDisableItem());
    }

    private void populatePlayerList() {
        inventory.setItem(PLAYERS_ADD_SLOT, createItem(Material.PLAYER_HEAD, "&a&lAdd Player(s)",
                List.of("&7Add one or more player names", "&7to the blocked list.", "", "&eClick to enter in chat")));
        inventory.setItem(PLAYERS_REPLACE_SLOT, createItem(Material.OAK_SIGN, "&e&lSet / Replace List",
                List.of("&7Replace the entire list with", "&7space-separated player names.", "", "&eClick to enter in chat")));

        if (flagEnabled && !activeValue.isEmpty()) {
            inventory.setItem(PLAYERS_REMOVE_SLOT, createItem(Material.REDSTONE, "&c&lRemove Player(s)",
                    List.of("&7Remove one or more player names", "&7from the blocked list.", "", "&cClick to enter in chat")));
        } else {
            inventory.setItem(PLAYERS_REMOVE_SLOT, createItem(Material.GRAY_DYE, "&7&lRemove Player(s)",
                    List.of("&7No players are currently blocked.")));
        }
        inventory.setItem(DISABLE_SLOT, createDisableItem());
    }

    private void populateText() {
        List<String> editLore = new ArrayList<>();
        editLore.add("&7Expected format:");
        wrapIntoLore(editLore, chatPromptHint, "&f");
        if (flagEnabled && !activeValue.isEmpty()) {
            editLore.add("");
            editLore.add("&7Current value:");
            wrapIntoLore(editLore, activeValue, "&a");
        }
        editLore.add("");
        editLore.add("&eClick to enter in chat");
        inventory.setItem(TEXT_SET_SLOT, createItem(Material.WRITABLE_BOOK,
                flagEnabled ? "&e&lEdit " + flagName : "&a&lSet " + flagName, editLore));

        inventory.setItem(TEXT_DISABLE_SLOT, createDisableItem());
    }

    private ItemStack createInfoItem() {
        String claimName = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim " + claimId);
        List<String> lore = new ArrayList<>();
        lore.add("&7Claim: &f" + claimName);
        if (flagEnabled && !activeValue.isEmpty()) {
            lore.add("&7Current:");
            wrapIntoLore(lore, activeValue, "&a");
        } else if (flagEnabled) {
            lore.add("&7Current: &aEnabled");
        } else {
            lore.add("&7Current: &cDisabled");
        }
        if (!flagExplicit) {
            lore.add("&7(inherited from world/server)");
        }
        lore.add("");
        wrapIntoLore(lore, summary, "&7");
        return createItem(Material.BOOK, "&b&l" + flagName + " Flag", lore);
    }

    private ItemStack createDisableItem() {
        List<String> lore = new ArrayList<>();
        if (flagEnabled) {
            lore.add("&7Disables the " + flagName + " flag");
            lore.add("&7for this claim.");
            lore.add("");
            lore.add("&cClick to disable");
            return createItem(Material.BARRIER, "&c&lDisable " + flagName, lore);
        }
        lore.add("&7" + flagName + " is currently disabled.");
        return createItem(Material.GRAY_DYE, "&7&l" + flagName + " Disabled", lore);
    }

    private ItemStack decoratedItem(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack item = createItem(mat, name, lore, glow);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;

        playClickSound();

        if (slot == BACK_SLOT) {
            manager.openGUI(player, new ClaimFlagsGUI(manager, player, claim, claimId, returnPage));
            return;
        }

        switch (kind) {
            case PRESETS -> handlePresetsClick(slot);
            case LOCATION_PRECISE, LOCATION_BLOCK -> handleLocationClick(slot);
            case PLAYER_LIST -> handlePlayerListClick(slot);
            case TEXT -> handleTextClick(slot);
        }
    }

    private void handlePresetsClick(int slot) {
        if (slot == DISABLE_SLOT) {
            disableFlag();
            return;
        }
        if (slot == PRESET_CUSTOM_SLOT) {
            promptReplaceValue(chatPromptHint);
            return;
        }
        for (Preset preset : presets) {
            if (preset.slot() == slot) {
                applyParameterString(preset.parameter());
                return;
            }
        }
    }

    private void handleLocationClick(int slot) {
        if (slot == DISABLE_SLOT) {
            disableFlag();
            return;
        }
        if (slot == LOCATION_CURRENT_SLOT) {
            if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
            Location loc = player.getLocation();
            if (loc == null || loc.getWorld() == null) {
                plugin.getMessages().send(player, "flags.parameter-failed",
                        "{flag}", flagName,
                        "{reason}", "could not read your current location");
                return;
            }
            String formatted = formatLocationPreview(loc);
            applyParameterString(formatted);
            return;
        }
        if (slot == LOCATION_CHAT_SLOT) {
            promptReplaceValue(chatPromptHint);
        }
    }

    private void handlePlayerListClick(int slot) {
        if (slot == DISABLE_SLOT) {
            disableFlag();
            return;
        }
        if (slot == PLAYERS_ADD_SLOT) {
            if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
            player.closeInventory();
            plugin.getChatPromptManager().begin(
                    player,
                    "flags.parameter-prompt",
                    new String[]{"{flag}", flagName, "{hint}", "player name(s) to add"},
                    input -> {
                        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
                        Set<String> merged = parseExistingPlayers();
                        for (String name : input.trim().split("\\s+")) {
                            if (!name.isEmpty()) addIgnoreCase(merged, name);
                        }
                        if (!merged.isEmpty()) {
                            applyParameterArgs(merged.toArray(String[]::new), String.join(" ", merged));
                        }
                        reopenSelf();
                    },
                    this::reopenSelf
            );
            return;
        }
        if (slot == PLAYERS_REPLACE_SLOT) {
            promptReplaceValue(chatPromptHint);
            return;
        }
        if (slot == PLAYERS_REMOVE_SLOT && flagEnabled && !activeValue.isEmpty()) {
            if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
            player.closeInventory();
            plugin.getChatPromptManager().begin(
                    player,
                    "flags.parameter-prompt",
                    new String[]{"{flag}", flagName, "{hint}", "player name(s) to remove"},
                    input -> {
                        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
                        Set<String> remaining = parseExistingPlayers();
                        for (String name : input.trim().split("\\s+")) {
                            if (!name.isEmpty()) {
                                remaining.removeIf(existing -> existing.equalsIgnoreCase(name));
                            }
                        }
                        if (remaining.isEmpty()) {
                            if (GPFlagsBridge.setFlagState(claimId, flagName, false, player)) {
                                plugin.getMessages().send(player, "flags.toggled",
                                        "{flag}", flagName, "{state}", "disabled");
                            } else {
                                plugin.getMessages().send(player, "flags.toggle-failed", "{flag}", flagName);
                            }
                        } else {
                            applyParameterArgs(remaining.toArray(String[]::new), String.join(" ", remaining));
                        }
                        reopenSelf();
                    },
                    this::reopenSelf
            );
        }
    }

    private void handleTextClick(int slot) {
        if (slot == TEXT_DISABLE_SLOT) {
            disableFlag();
            return;
        }
        if (slot == TEXT_SET_SLOT) {
            promptReplaceValue(chatPromptHint);
        }
    }

    private void promptReplaceValue(String hint) {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        player.closeInventory();
        plugin.getChatPromptManager().begin(
                player,
                "flags.parameter-prompt",
                new String[]{"{flag}", flagName, "{hint}", hint},
                input -> {
                    if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
                    String trimmed = input.trim();
                    String[] args = splitArgsOnSend ? trimmed.split("\\s+") : new String[]{trimmed};
                    applyParameterArgs(args, trimmed);
                    reopenSelf();
                },
                this::reopenSelf
        );
    }

    private void applyParameterString(String value) {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        String trimmed = value.trim();
        String[] args = splitArgsOnSend ? trimmed.split("\\s+") : new String[]{trimmed};
        if (applyParameterArgs(args, trimmed)) {
            refresh();
        }
    }

    private boolean applyParameterArgs(String[] args, String displayValue) {
        if (GPFlagsBridge.setFlagState(claimId, flagName, true, player, args)) {
            plugin.getMessages().send(player, "flags.toggled",
                    "{flag}", flagName,
                    "{state}", "enabled (" + displayValue + ")");
            return true;
        }
        plugin.getMessages().send(player, "flags.parameter-failed",
                "{flag}", flagName,
                "{reason}", "expected " + chatPromptHint);
        return false;
    }

    private void disableFlag() {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        if (!flagEnabled) return;
        if (GPFlagsBridge.setFlagState(claimId, flagName, false, player)) {
            plugin.getMessages().send(player, "flags.toggled",
                    "{flag}", flagName,
                    "{state}", "disabled");
            refresh();
        } else {
            plugin.getMessages().send(player, "flags.toggle-failed", "{flag}", flagName);
        }
    }

    private Set<String> parseExistingPlayers() {
        Set<String> players = new LinkedHashSet<>();
        if (flagEnabled && !activeValue.isEmpty()) {
            for (String token : activeValue.split("\\s+")) {
                if (!token.isEmpty()) addIgnoreCase(players, token);
            }
        }
        return players;
    }

    private static void addIgnoreCase(Set<String> set, String candidate) {
        for (String existing : set) {
            if (existing.equalsIgnoreCase(candidate)) return;
        }
        set.add(candidate);
    }

    private String formatLocationPreview(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        if (kind == Kind.LOCATION_BLOCK) {
            return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
        }
        return String.format(Locale.US, "%s %.2f %.2f %.2f %.1f %.1f",
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    private void reopenSelf() {
        manager.openGUI(player, new GPFlagParameterGUI(
                manager, player, claim, claimId, flagName, menuTitle, summary,
                kind, chatPromptHint, splitArgsOnSend, presets, returnPage
        ));
    }

    private void refresh() {
        loadState();
        inventory.clear();
        populateInventory();
    }

    private static void wrapIntoLore(List<String> lore, String text, String colorPrefix) {
        if (text == null || text.isEmpty()) return;
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder(colorPrefix);
        for (String word : words) {
            if (line.length() + word.length() > 40 && line.length() > colorPrefix.length()) {
                lore.add(line.toString().trim());
                line = new StringBuilder(colorPrefix);
            }
            line.append(word).append(' ');
        }
        if (line.length() > colorPrefix.length()) {
            lore.add(line.toString().trim());
        }
    }
}
