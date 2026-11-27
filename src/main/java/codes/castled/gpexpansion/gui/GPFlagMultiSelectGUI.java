package codes.castled.gpexpansion.gui;

import codes.castled.gpexpansion.gp.GPFlagsBridge;
import codes.castled.gpexpansion.util.Mini;
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
 * Multi-select toggle sub-menu for GPFlags that accept a list of discrete values
 * ({@code NoOpenDoors}, {@code NoPotionEffects}, {@code SpawnReasonWhitelist}, {@code NoMobSpawnsType}).
 * Uses a compact 27-slot layout when 4 or fewer options exist, and a paginated 54-slot layout otherwise.
 */
public class GPFlagMultiSelectGUI extends BaseGUI {

    static final int COMPACT_INFO_SLOT = 4;
    static final int COMPACT_BACK_SLOT = 18;
    static final int COMPACT_DISABLE_SLOT = 22;

    static final int PAGED_ALL_SLOT = 2;
    static final int PAGED_INFO_SLOT = 4;
    static final int PAGED_CHAT_SLOT = 6;
    static final int PAGED_PREV_SLOT = 45;
    static final int PAGED_BACK_SLOT = 48;
    static final int PAGED_DISABLE_SLOT = 50;
    static final int PAGED_NEXT_SLOT = 53;
    private static final int ITEMS_PER_PAGE = 36;

    public record Option(String token, String displayName, Material material, String description) {}

    public record AllOption(String parameter, String displayName, Material material, String description) {}

    private final Object claim;
    private final String claimId;
    private final String flagName;
    private final String menuTitle;
    private final String summary;
    private final String delimiter;
    private final boolean splitArgsOnSend;
    private final List<Option> options;
    private final AllOption allOption;
    private final String chatPromptHint;
    private final String itemPermissionPrefix;
    private final int returnPage;

    private final boolean compact;
    private int currentPage = 0;

    private boolean flagEnabled;
    private boolean flagExplicit = true;
    private boolean allSelected;
    private Set<String> selectedTokens = new LinkedHashSet<>();

    public GPFlagMultiSelectGUI(
            GUIManager manager,
            Player player,
            Object claim,
            String claimId,
            String flagName,
            String menuTitle,
            String summary,
            String delimiter,
            boolean splitArgsOnSend,
            List<Option> options,
            AllOption allOption,
            String chatPromptHint,
            String itemPermissionPrefix,
            int returnPage
    ) {
        super(manager, player, "claim-flags-multiselect");
        this.claim = claim;
        this.claimId = claimId;
        this.flagName = flagName;
        this.menuTitle = menuTitle;
        this.summary = summary;
        this.delimiter = delimiter;
        this.splitArgsOnSend = splitArgsOnSend;
        this.options = List.copyOf(options);
        this.allOption = allOption;
        this.chatPromptHint = chatPromptHint;
        this.itemPermissionPrefix = itemPermissionPrefix;
        this.returnPage = Math.max(0, returnPage);
        this.compact = (this.options.size() + (this.allOption != null ? 1 : 0)) <= 4;
        loadState();
    }

    public String getFlagName() {
        return flagName;
    }

    private void loadState() {
        selectedTokens = new LinkedHashSet<>();
        allSelected = false;

        Optional<GPFlagsBridge.ClaimFlagState> stateOpt =
                GPFlagsBridge.getEffectiveFlagState(claim, flagName);
        if (stateOpt.isEmpty()) {
            flagEnabled = false;
            flagExplicit = true;
            return;
        }
        GPFlagsBridge.ClaimFlagState state = stateOpt.get();
        flagEnabled = state.isEnabled();
        flagExplicit = state.isExplicit();
        String raw = state.getParameters() != null ? state.getParameters().trim() : "";

        if (!flagEnabled) {
            return;
        }
        if (allOption != null && allOption.parameter().equalsIgnoreCase(raw)) {
            allSelected = true;
            return;
        }
        if (!raw.isEmpty()) {
            String splitRegex = " ".equals(delimiter) ? "\\s+" : java.util.regex.Pattern.quote(delimiter);
            for (String part : raw.split(splitRegex)) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    selectedTokens.add(canonicalToken(trimmed));
                }
            }
        }
    }

    private String canonicalToken(String raw) {
        for (Option opt : options) {
            if (opt.token().equalsIgnoreCase(raw)) {
                return opt.token();
            }
        }
        return raw;
    }

    @Override
    public Inventory createInventory() {
        inventory = Mini.createInventory(null, compact ? 27 : 54, colorize(menuTitle));
        populateInventory();
        return inventory;
    }

    private void populateInventory() {
        fillEmpty(createFiller());
        if (compact) {
            populateCompact();
        } else {
            populatePaged();
        }
    }

    private void populateCompact() {
        inventory.setItem(COMPACT_INFO_SLOT, createInfoItem());
        int[] slots = compactSlots(options.size() + (allOption != null ? 1 : 0));
        int idx = 0;
        if (allOption != null && idx < slots.length) {
            inventory.setItem(slots[idx++], createAllItem());
        }
        for (Option opt : options) {
            if (idx < slots.length) {
                inventory.setItem(slots[idx++], createOptionItem(opt));
            }
        }
        inventory.setItem(COMPACT_BACK_SLOT, createItem(Material.ARROW, "&c&lBack",
                List.of("&7Return to Claim Flags")));
        inventory.setItem(COMPACT_DISABLE_SLOT, createDisableItem());
    }

    private void populatePaged() {
        inventory.setItem(PAGED_INFO_SLOT, createInfoItem());
        if (allOption != null) {
            inventory.setItem(PAGED_ALL_SLOT, createAllItem());
        }
        if (chatPromptHint != null && !chatPromptHint.isBlank()) {
            inventory.setItem(PAGED_CHAT_SLOT, createItem(Material.OAK_SIGN, "&e&lEnter via Chat",
                    List.of("&7Type value(s) directly in chat:", "&f" + chatPromptHint, "", "&eClick to enter")));
        }

        int totalPages = totalPages();
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, options.size());
        int slot = 9;
        for (int i = start; i < end && slot < 45; i++) {
            inventory.setItem(slot++, createOptionItem(options.get(i)));
        }

        if (currentPage > 0) {
            inventory.setItem(PAGED_PREV_SLOT, createItem(Material.ARROW, "&e&l« Previous Page",
                    List.of("&7Page " + (currentPage + 1) + "/" + totalPages)));
        }
        inventory.setItem(PAGED_BACK_SLOT, createItem(Material.ARROW, "&c&lBack",
                List.of("&7Return to Claim Flags")));
        inventory.setItem(PAGED_DISABLE_SLOT, createDisableItem());
        if (currentPage < totalPages - 1) {
            inventory.setItem(PAGED_NEXT_SLOT, createItem(Material.ARROW, "&e&lNext Page »",
                    List.of("&7Page " + (currentPage + 1) + "/" + totalPages)));
        }
    }

    private ItemStack createInfoItem() {
        String claimName = plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim " + claimId);
        List<String> lore = new ArrayList<>();
        lore.add("&7Claim: &f" + claimName);
        if (!flagEnabled) {
            lore.add("&7Current: &cDisabled");
        } else if (allSelected && allOption != null) {
            lore.add("&7Current: &a" + allOption.displayName());
        } else if (!selectedTokens.isEmpty()) {
            lore.add("&7Selected (&f" + selectedTokens.size() + "&7):");
            String joined = String.join(", ", selectedTokens);
            wrapIntoLore(lore, joined, "&f");
        } else {
            lore.add("&7Current: &aEnabled");
        }
        if (!flagExplicit) {
            lore.add("&7(inherited from world/server)");
        }
        lore.add("");
        lore.add("&7" + summary);
        return createItem(Material.BOOK, "&b&l" + flagName + " Flag", lore);
    }

    private ItemStack createAllItem() {
        boolean active = flagEnabled && allSelected;
        String title = active ? "&a&l✓ " + allOption.displayName() : "&e&l" + allOption.displayName();
        List<String> lore = new ArrayList<>();
        lore.add(active ? "&aCurrently active" : "&7Not selected");
        lore.add("");
        lore.add("&7" + allOption.description());
        lore.add("");
        lore.add(active ? "&7Already active" : "&eClick to select all");
        return decoratedItem(allOption.material(), title, lore, active);
    }

    private ItemStack createOptionItem(Option opt) {
        boolean includedViaAll = flagEnabled && allSelected && allOption != null && allOption.parameter().isEmpty();
        boolean selected = flagEnabled && (selectedTokens.contains(opt.token()) || includedViaAll);
        String title = selected ? "&a✓ " + opt.displayName() : "&7" + opt.displayName();
        List<String> lore = new ArrayList<>();
        if (includedViaAll) {
            lore.add("&aIncluded (All active)");
        } else if (selected) {
            lore.add("&aSelected");
        } else {
            lore.add("&cNot selected");
        }
        if (opt.description() != null && !opt.description().isEmpty()) {
            lore.add("&7" + opt.description());
        }
        lore.add("");
        if (flagEnabled && allSelected && allOption != null && !allOption.parameter().isEmpty()) {
            lore.add("&eClick to switch from All to " + opt.displayName());
        } else {
            lore.add(selected ? "&eClick to remove" : "&eClick to add");
        }
        return decoratedItem(opt.material(), title, lore, selected);
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

        if (compact) {
            handleCompactClick(slot);
        } else {
            handlePagedClick(slot);
        }
    }

    private void handleCompactClick(int slot) {
        if (slot == COMPACT_BACK_SLOT) {
            manager.openGUI(player, new ClaimFlagsGUI(manager, player, claim, claimId, returnPage));
            return;
        }
        if (slot == COMPACT_DISABLE_SLOT) {
            disableFlag();
            return;
        }
        int[] slots = compactSlots(options.size() + (allOption != null ? 1 : 0));
        int idx = 0;
        if (allOption != null && idx < slots.length) {
            if (slot == slots[idx++]) {
                selectAllOption();
                return;
            }
        }
        for (Option opt : options) {
            if (idx < slots.length && slot == slots[idx++]) {
                toggleOption(opt);
                return;
            }
        }
    }

    private void handlePagedClick(int slot) {
        if (slot == PAGED_BACK_SLOT) {
            manager.openGUI(player, new ClaimFlagsGUI(manager, player, claim, claimId, returnPage));
            return;
        }
        if (slot == PAGED_DISABLE_SLOT) {
            disableFlag();
            return;
        }
        if (slot == PAGED_ALL_SLOT && allOption != null) {
            selectAllOption();
            return;
        }
        if (slot == PAGED_CHAT_SLOT && chatPromptHint != null && !chatPromptHint.isBlank()) {
            promptChatInput();
            return;
        }
        if (slot == PAGED_PREV_SLOT && currentPage > 0) {
            currentPage--;
            refresh();
            return;
        }
        if (slot == PAGED_NEXT_SLOT && currentPage < totalPages() - 1) {
            currentPage++;
            refresh();
            return;
        }
        if (slot >= 9 && slot < 45) {
            int index = currentPage * ITEMS_PER_PAGE + (slot - 9);
            if (index >= 0 && index < options.size()) {
                toggleOption(options.get(index));
            }
        }
    }

    private void selectAllOption() {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        String[] args = allOption.parameter().isEmpty()
                ? new String[0]
                : new String[]{allOption.parameter()};
        if (GPFlagsBridge.setFlagState(claimId, flagName, true, player, args)) {
            plugin.getMessages().send(player, "flags.toggled",
                    "{flag}", flagName,
                    "{state}", "enabled (" + allOption.displayName() + ")");
            refresh();
        } else {
            plugin.getMessages().send(player, "flags.toggle-failed", "{flag}", flagName);
        }
    }

    private void toggleOption(Option opt) {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        if (!hasItemPermission(opt.token())) {
            plugin.getMessages().send(player, "flags.no-permission-flag",
                    "{flag}", flagName + " (" + opt.displayName() + ")");
            return;
        }

        Set<String> next = new LinkedHashSet<>(selectedTokens);
        if (flagEnabled && allSelected && allOption != null) {
            if (allOption.parameter().isEmpty()) {
                // Empty parameter meant all compact items were active -> start from all and remove clicked
                for (Option allOpt : options) {
                    next.add(allOpt.token());
                }
                next.remove(opt.token());
            } else {
                // Explicit "all" token -> switch to just this single item
                next.clear();
                next.add(opt.token());
            }
        } else if (next.contains(opt.token())) {
            next.remove(opt.token());
        } else {
            next.add(opt.token());
        }

        if (next.isEmpty()) {
            disableFlag();
            return;
        }

        String[] args = splitArgsOnSend
                ? next.toArray(String[]::new)
                : new String[]{String.join(delimiter, next)};
        if (GPFlagsBridge.setFlagState(claimId, flagName, true, player, args)) {
            plugin.getMessages().send(player, "flags.toggled",
                    "{flag}", flagName,
                    "{state}", "updated (" + String.join(", ", next) + ")");
            refresh();
        } else {
            plugin.getMessages().send(player, "flags.toggle-failed", "{flag}", flagName);
        }
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

    private void promptChatInput() {
        if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
        player.closeInventory();
        plugin.getChatPromptManager().begin(
                player,
                "flags.parameter-prompt",
                new String[]{"{flag}", flagName, "{hint}", chatPromptHint},
                input -> {
                    if (!ClaimFlagsGUI.checkModifyPermission(plugin, player, claim, flagName)) return;
                    String trimmed = input.trim();
                    if (itemPermissionPrefix != null) {
                        String splitRegex = " ".equals(delimiter) ? "\\s+" : java.util.regex.Pattern.quote(delimiter);
                        for (String token : trimmed.split(splitRegex)) {
                            String t = token.trim();
                            if (!t.isEmpty() && !hasItemPermission(t)) {
                                plugin.getMessages().send(player, "flags.no-permission-flag",
                                        "{flag}", flagName + " (" + t + ")");
                                reopenSelf();
                                return;
                            }
                        }
                    }
                    String[] args = splitArgsOnSend ? trimmed.split("\\s+") : new String[]{trimmed};
                    if (GPFlagsBridge.setFlagState(claimId, flagName, true, player, args)) {
                        plugin.getMessages().send(player, "flags.toggled",
                                "{flag}", flagName,
                                "{state}", "enabled (" + trimmed + ")");
                    } else {
                        plugin.getMessages().send(player, "flags.parameter-failed",
                                "{flag}", flagName,
                                "{reason}", "expected " + chatPromptHint);
                    }
                    reopenSelf();
                },
                this::reopenSelf
        );
    }

    private void reopenSelf() {
        GPFlagMultiSelectGUI reopened = new GPFlagMultiSelectGUI(
                manager, player, claim, claimId, flagName, menuTitle, summary,
                delimiter, splitArgsOnSend, options, allOption, chatPromptHint,
                itemPermissionPrefix, returnPage
        );
        reopened.currentPage = this.currentPage;
        manager.openGUI(player, reopened);
    }

    private boolean hasItemPermission(String token) {
        if (itemPermissionPrefix == null || itemPermissionPrefix.isEmpty()) return true;
        return player.isOp()
                || player.hasPermission(itemPermissionPrefix + token.toLowerCase(Locale.ROOT))
                || player.hasPermission(itemPermissionPrefix + token.toUpperCase(Locale.ROOT))
                || player.hasPermission(itemPermissionPrefix + "*");
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil((double) options.size() / ITEMS_PER_PAGE));
    }

    private void refresh() {
        loadState();
        inventory.clear();
        populateInventory();
    }

    static int[] compactSlots(int count) {
        return switch (count) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{11, 13, 15};
            default -> new int[]{10, 12, 14, 16};
        };
    }

    private static void wrapIntoLore(List<String> lore, String text, String colorPrefix) {
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
