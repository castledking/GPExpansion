package codes.castled.gpexpansion.gui;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import codes.castled.gpexpansion.flags.ClaimFlag;
import codes.castled.gpexpansion.flags.ClaimFlagManager;
import codes.castled.gpexpansion.flags.ClaimFlagStore;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.gp.GPFlagsBridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Built-in claim flags menu, laid out like ExcellentClaims' rule menu: category tabs on top,
 * flags in the middle, navigation at the bottom. Works without GPFlags; when GPFlags is
 * installed a button opens its flag list as well.
 */
public class FlagsGUI extends BaseGUI {

    private static final String BASE_PERMISSION = "griefprevention.claim.gui.flags";
    private static final String OTHER_PERMISSION = "griefprevention.claim.gui.flags.other";

    private final Object claim;
    private final String claimId;
    private final GPBridge gp = new GPBridge();
    private final ClaimFlagManager flags;
    private final boolean canEdit;

    private ClaimFlag.Category category;
    private int page;
    private boolean resetArmed;
    private List<ClaimFlag> shown = List.of();

    private int infoSlot = 0;
    private int playerTabSlot = 2;
    private int entityTabSlot = 4;
    private int naturalTabSlot = 6;
    private int gpFlagsSlot = 8;
    private int prevSlot = 45;
    private int resetSlot = 47;
    private int backSlot = 49;
    private int nextSlot = 53;
    private int[] flagSlots;

    public FlagsGUI(GUIManager manager, Player player, Object claim, String claimId) {
        this(manager, player, claim, claimId, null, 0);
    }

    public FlagsGUI(GUIManager manager, Player player, Object claim, String claimId, ClaimFlag.Category category, int page) {
        super(manager, player, "flags");
        this.claim = claim;
        this.claimId = claimId;
        this.flags = plugin.getClaimFlagManager();
        this.category = category;
        this.page = page;
        this.canEdit = canEdit(player, claim, gp);

        if (config != null) {
            infoSlot = config.getInt("slots.info", infoSlot);
            playerTabSlot = config.getInt("slots.category-player", playerTabSlot);
            entityTabSlot = config.getInt("slots.category-entity", entityTabSlot);
            naturalTabSlot = config.getInt("slots.category-natural", naturalTabSlot);
            gpFlagsSlot = config.getInt("slots.gpflags", gpFlagsSlot);
            prevSlot = config.getInt("slots.prev-page", prevSlot);
            resetSlot = config.getInt("slots.reset-all", resetSlot);
            backSlot = config.getInt("slots.back", backSlot);
            nextSlot = config.getInt("slots.next-page", nextSlot);
            List<Integer> configured = config.getIntegerList("flag-slots");
            if (!configured.isEmpty()) {
                flagSlots = configured.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        if (flagSlots == null) {
            flagSlots = new int[36];
            for (int i = 0; i < 36; i++) flagSlots[i] = 9 + i;
        }
    }

    // ===================================== access =====================================

    /** Can open the menu for this claim: base permission plus owner, manager trust or .other. */
    public static boolean canAccess(codes.castled.gpexpansion.GPExpansionPlugin plugin, Player player, Object claim, GPBridge gp) {
        ClaimFlagManager manager = plugin.getClaimFlagManager();
        if (manager == null || !manager.isEnabled()) {
            return ClaimFlagsGUI.canAccess(player, claim, gp);
        }
        if (!player.hasPermission(BASE_PERMISSION)) return false;
        return canEdit(player, claim, gp);
    }

    private static boolean canEdit(Player player, Object claim, GPBridge gp) {
        if (gp.isAdminClaim(claim)) {
            return player.hasPermission("griefprevention.adminclaims") || player.hasPermission("griefprevention.admin")
                || player.hasPermission(OTHER_PERMISSION);
        }
        if (gp.isOwner(claim, player.getUniqueId()) || player.hasPermission("griefprevention.admin")) return true;
        if (gp.getTrustLevels(claim, player.getUniqueId()).contains(GPBridge.TrustLevel.MANAGE)) return true;
        return player.hasPermission(OTHER_PERMISSION);
    }

    // ===================================== layout =====================================

    @Override
    public Inventory createInventory() {
        String categoryName = category == null
            ? getString("text.category-all", "All")
            : getString("categories." + categoryKey(category) + ".title", defaultCategoryName(category));
        String title = getString("title", "&8Flags &7- &f{name} &8({category})")
            .replace("{name}", claimName())
            .replace("{id}", claimId)
            .replace("{category}", categoryName);
        inventory = createBaseInventoryWithTitle(title, 54);
        populate();
        return inventory;
    }

    private void populate() {
        inventory.clear();
        fillEmpty(createFiller());

        shown = collectFlags();
        int perPage = flagSlots.length;
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        inventory.setItem(infoSlot, createInfoItem());
        inventory.setItem(playerTabSlot, createCategoryItem(ClaimFlag.Category.PLAYER));
        inventory.setItem(entityTabSlot, createCategoryItem(ClaimFlag.Category.ENTITY));
        inventory.setItem(naturalTabSlot, createCategoryItem(ClaimFlag.Category.NATURAL));
        if (showGPFlagsButton()) {
            inventory.setItem(gpFlagsSlot, createGPFlagsItem());
        }

        int start = page * perPage;
        Claim gpClaim = gpClaim();
        for (int i = 0; i < perPage && start + i < shown.size(); i++) {
            inventory.setItem(flagSlots[i], createFlagItem(shown.get(start + i), gpClaim));
        }

        if (page > 0) {
            inventory.setItem(prevSlot, createItem(Material.ARROW, getString("items.prev-page.name", "&e&l« Previous Page"),
                List.of(getString("text.page", "&7Page {current}/{total}")
                    .replace("{current}", String.valueOf(page)).replace("{total}", String.valueOf(pages)))));
        }
        if (page < pages - 1) {
            inventory.setItem(nextSlot, createItem(Material.ARROW, getString("items.next-page.name", "&e&lNext Page »"),
                List.of(getString("text.page", "&7Page {current}/{total}")
                    .replace("{current}", String.valueOf(page + 2)).replace("{total}", String.valueOf(pages)))));
        }
        if (canEdit && plugin.getClaimFlagStore().countSet(claimId) > 0) {
            inventory.setItem(resetSlot, createResetItem());
        }
        inventory.setItem(backSlot, createItem(Material.ARROW, getString("items.back.name", "&c&lBack"),
            List.of(getString("items.back.lore", "&7Return to claim options"))));
    }

    private List<ClaimFlag> collectFlags() {
        List<ClaimFlag> list = new ArrayList<>();
        for (ClaimFlag.Category cat : ClaimFlag.Category.values()) {
            if (category != null && cat != category) continue;
            for (ClaimFlag flag : flags.availableFlags(cat)) {
                if (player.hasPermission(flag.permission()) || player.hasPermission("griefprevention.admin")) {
                    list.add(flag);
                }
            }
        }
        list.sort(Comparator.comparing(flag -> stripColor(flags.displayName(flag)).toLowerCase(Locale.ROOT)));
        return list;
    }

    private ItemStack createInfoItem() {
        List<String> lore = new ArrayList<>();
        for (String line : infoLore()) {
            lore.add(line.replace("{name}", claimName())
                .replace("{id}", claimId)
                .replace("{set}", String.valueOf(plugin.getClaimFlagStore().countSet(claimId))));
        }
        return createItem(Material.matchMaterial(getString("items.info.material", "BOOK")) != null
                ? Material.matchMaterial(getString("items.info.material", "BOOK")) : Material.BOOK,
            getString("items.info.name", "&b&lClaim Flags"), lore);
    }

    private List<String> infoLore() {
        List<String> configured = getStringList("items.info.lore");
        if (!configured.isEmpty()) return configured;
        return List.of(
            "&7Claim: &f{name} &8(#{id})",
            "&7Flags set here: &f{set}",
            "",
            "&aAllow &7lets outsiders do it, even",
            "&7where GriefPrevention would stop them.",
            "&cDeny &7stops outsiders, mobs or nature.",
            "&7Unset flags follow GriefPrevention.",
            "",
            "&7Players with the listed trust are",
            "&7never affected (except by PvP).");
    }

    private ItemStack createCategoryItem(ClaimFlag.Category cat) {
        boolean selected = category == cat;
        String key = categoryKey(cat);
        String materialName = getString("categories." + key + (selected ? ".selected-material" : ".material"),
            defaultCategoryMaterial(cat, selected));
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.BOOK;
        String name = getString("categories." + key + ".name", (selected ? "&a&l" : "&f&l") + defaultCategoryName(cat));
        int count = flags.availableFlags(cat).size();
        List<String> lore = new ArrayList<>();
        lore.add(getString("text.category-count", "&7{count} flags").replace("{count}", String.valueOf(count)));
        lore.add("");
        lore.add(selected ? getString("text.category-selected", "&eClick to show all flags")
            : getString("text.category-select", "&eClick to show only these"));
        return createItem(material, name, lore, selected);
    }

    private ItemStack createGPFlagsItem() {
        return createItem(Material.matchMaterial(getString("items.gpflags.material", "WRITABLE_BOOK")) != null
                ? Material.matchMaterial(getString("items.gpflags.material", "WRITABLE_BOOK")) : Material.WRITABLE_BOOK,
            getString("items.gpflags.name", "&d&lGPFlags"),
            getStringList("items.gpflags.lore").isEmpty()
                ? List.of("&7Open GPFlags' own flag list", "&7for this claim.", "", "&eClick to open")
                : getStringList("items.gpflags.lore"));
    }

    private ItemStack createResetItem() {
        List<String> lore = resetArmed
            ? List.of(getString("text.reset-armed", "&cClick again to clear every flag on this claim"))
            : List.of(getString("text.reset", "&7Clear every flag set on this claim."), "",
                getString("text.reset-action", "&eClick to arm, click again to confirm"));
        return createItem(Material.matchMaterial(getString("items.reset-all.material", "LAVA_BUCKET")) != null
                ? Material.matchMaterial(getString("items.reset-all.material", "LAVA_BUCKET")) : Material.LAVA_BUCKET,
            resetArmed ? getString("items.reset-all.armed-name", "&4&lConfirm Reset") : getString("items.reset-all.name", "&c&lReset All Flags"),
            lore, resetArmed);
    }

    private ItemStack createFlagItem(ClaimFlag flag, Claim gpClaim) {
        String source = gpClaim != null ? flags.resolveSource(flag, gpClaim) : null;
        boolean ownValue = "claim".equals(source);
        List<String> lore = new ArrayList<>();
        String stateColor;

        if (flag.isFilter()) {
            ClaimFlagStore.FilterSetting setting = gpClaim != null ? flags.resolveFilter(flag, gpClaim) : null;
            if (setting == null) {
                stateColor = "&7";
                lore.add(getString("text.current", "&8» &7Current: {value}").replace("{value}", getString("states.unset", "&7Not set")));
            } else {
                stateColor = setting.mode() == ClaimFlag.FilterMode.WHITELIST ? "&a" : "&c";
                String mode = setting.mode() == ClaimFlag.FilterMode.WHITELIST
                    ? getString("states.whitelist", "&aWhitelist")
                    : getString("states.blacklist", "&cBlacklist");
                lore.add(getString("text.current", "&8» &7Current: {value}").replace("{value}", mode));
                lore.add(getString("text.entries", "&8» &7Entries: &f{count}").replace("{count}", String.valueOf(setting.entries().size())));
                int listed = 0;
                for (String entry : setting.entries()) {
                    if (listed++ >= 6) {
                        lore.add(getString("text.entries-more", "&8   ...and {more} more").replace("{more}", String.valueOf(setting.entries().size() - 6)));
                        break;
                    }
                    lore.add("&8   - &f" + entry);
                }
            }
        } else {
            Boolean value = gpClaim != null ? flags.resolve(flag, gpClaim) : null;
            String valueText = value == null ? getString("states.unset", "&7Not set")
                : value ? getString("states.allowed", "&aAllowed") : getString("states.denied", "&cDenied");
            stateColor = value == null ? "&7" : value ? "&a" : "&c";
            lore.add(getString("text.current", "&8» &7Current: {value}").replace("{value}", valueText));
        }
        if ("parent".equals(source)) {
            lore.add(getString("text.from-parent", "&8   (inherited from the parent claim)"));
        } else if ("default".equals(source)) {
            lore.add(getString("text.from-default", "&8   (server default)"));
        } else if (source == null) {
            lore.add(getString("text.unset-hint", "&8   GriefPrevention and vanilla decide"));
        }

        lore.add("");
        for (String line : wrap(description(flag), 34)) {
            lore.add("&7" + line);
        }
        if (flag.category() == ClaimFlag.Category.PLAYER || flag == ClaimFlag.BLOCK_TRAMP || flag == ClaimFlag.BLOCK_FERTILIZE) {
            lore.add("");
            lore.add(getString("text.applies-to", "&8Applies to: &7{who}").replace("{who}", appliesTo(flag)));
        }

        lore.add("");
        if (!canEdit || !player.hasPermission(flag.permission())) {
            lore.add(getString("text.no-edit", "&8You can't change this flag"));
        } else if (flag.isFilter()) {
            lore.add(getString("text.filter-edit", "&eLeft-click: &7edit entries"));
            lore.add(getString("text.filter-mode", "&eRight-click: &7switch whitelist/blacklist"));
            lore.add(getString("text.reset-flag", "&eQ / Shift-click: &7reset to default"));
        } else {
            lore.add(getString("text.allow", "&eLeft-click: &aAllow"));
            lore.add(getString("text.deny", "&eRight-click: &cDeny"));
            lore.add(getString("text.reset-flag", "&eQ / Shift-click: &7reset to default"));
        }

        String name = getString("text.flag-name", "{color}&l{flag}")
            .replace("{color}", stateColor)
            .replace("{flag}", flags.displayName(flag));
        return createFlagIcon(flag, name, lore, ownValue);
    }

    private ItemStack createFlagIcon(ClaimFlag flag, String name, List<String> lore, boolean glow) {
        String materialOverride = getString("flags." + flag.id() + ".material", null);
        String texture = getString("flags." + flag.id() + ".texture", flag.headTexture());
        if (materialOverride == null && texture != null && !texture.isBlank()) {
            String url = texture.startsWith("http") || texture.length() > 80 ? texture
                : "http://textures.minecraft.net/texture/" + texture;
            return restyleItem(createTexturedSkull(url), name, lore, glow);
        }
        Material material = materialOverride != null ? Material.matchMaterial(materialOverride) : null;
        if (material == null || !material.isItem()) material = flag.iconMaterial();
        ItemStack item = createItem(material, name, lore, glow);
        // Hide weapon damage, potion effects and the like so only the flag text shows.
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String appliesTo(ClaimFlag flag) {
        return switch (flag.trust()) {
            case NONE -> getString("text.who-everyone", "&feveryone in the claim");
            case ACCESS -> getString("text.who-access", "&fplayers without access trust");
            case CONTAINER -> getString("text.who-container", "&fplayers without container trust");
            case BUILD -> getString("text.who-build", "&fplayers without build trust");
        };
    }

    private String description(ClaimFlag flag) {
        String configured = getString("flags." + flag.id() + ".description", null);
        return configured != null ? configured : flag.description();
    }

    // ===================================== clicks =====================================

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        playClickSound();

        if (slot != resetSlot && resetArmed) {
            resetArmed = false;
        }
        if (slot == backSlot) {
            manager.openClaimOptions(player, claim, claimId);
            return;
        }
        if (slot == playerTabSlot || slot == entityTabSlot || slot == naturalTabSlot) {
            ClaimFlag.Category clicked = slot == playerTabSlot ? ClaimFlag.Category.PLAYER
                : slot == entityTabSlot ? ClaimFlag.Category.ENTITY : ClaimFlag.Category.NATURAL;
            // The title shows the category, so reopen rather than repaint.
            manager.openGUI(player, new FlagsGUI(manager, player, claim, claimId, category == clicked ? null : clicked, 0));
            return;
        }
        if (slot == gpFlagsSlot && showGPFlagsButton()) {
            manager.openGPFlags(player, claim, claimId);
            return;
        }
        if (slot == prevSlot && page > 0) {
            page--;
            populate();
            return;
        }
        if (slot == nextSlot) {
            page++;
            populate();
            return;
        }
        if (slot == resetSlot && canEdit && plugin.getClaimFlagStore().countSet(claimId) > 0) {
            if (!resetArmed) {
                resetArmed = true;
                populate();
                return;
            }
            resetArmed = false;
            plugin.getClaimFlagStore().removeClaim(claimId);
            plugin.getClaimFlagStore().save();
            plugin.getMessages().send(player, "flags.reset-all", "{id}", claimId);
            populate();
            return;
        }

        for (int i = 0; i < flagSlots.length; i++) {
            if (flagSlots[i] != slot) continue;
            int index = page * flagSlots.length + i;
            if (index < shown.size()) {
                handleFlagClick(event, shown.get(index));
            }
            return;
        }
    }

    private void handleFlagClick(InventoryClickEvent event, ClaimFlag flag) {
        if (!canEdit) {
            plugin.getMessages().send(player, "flags.not-owner");
            return;
        }
        if (!player.hasPermission(flag.permission())) {
            plugin.getMessages().send(player, "flags.no-permission-flag", "{flag}", flags.displayName(flag));
            return;
        }
        ClickType click = event.getClick();
        boolean reset = click == ClickType.DROP || click == ClickType.CONTROL_DROP || event.isShiftClick();
        ClaimFlagStore store = plugin.getClaimFlagStore();

        if (flag.isFilter()) {
            if (reset) {
                store.setFilter(claimId, flag, null);
                store.save();
                plugin.getMessages().send(player, "flags.reset", "{flag}", flags.displayName(flag));
                populate();
            } else if (event.isRightClick()) {
                ClaimFlagStore.FilterSetting current = store.getFilter(claimId, flag);
                ClaimFlag.FilterMode next = current == null
                    ? flag.defaultFilterMode()
                    : current.mode() == ClaimFlag.FilterMode.WHITELIST ? ClaimFlag.FilterMode.BLACKLIST : ClaimFlag.FilterMode.WHITELIST;
                store.setFilter(claimId, flag, new ClaimFlagStore.FilterSetting(next, current == null ? Set.of() : current.entries()));
                store.save();
                plugin.getMessages().send(player, "flags.filter-mode", "{flag}", flags.displayName(flag),
                    "{mode}", next == ClaimFlag.FilterMode.WHITELIST ? "whitelist" : "blacklist");
                populate();
            } else if (event.isLeftClick()) {
                promptFilterEntries(flag);
            }
            return;
        }

        Boolean value = reset ? null : event.isLeftClick() ? Boolean.TRUE : event.isRightClick() ? Boolean.FALSE : null;
        if (!reset && value == null) return;
        store.set(claimId, flag, value);
        store.save();
        if (value == null) {
            plugin.getMessages().send(player, "flags.reset", "{flag}", flags.displayName(flag));
        } else {
            plugin.getMessages().send(player, "flags.toggled", "{flag}", flags.displayName(flag),
                "{state}", value ? plugin.getMessages().getRaw("flags.state-allowed") : plugin.getMessages().getRaw("flags.state-denied"));
        }
        populate();
    }

    /** Chat input: "a b c" adds entries, "-a" removes one, "clear" empties the list. */
    private void promptFilterEntries(ClaimFlag flag) {
        ClaimFlag.Category returnCategory = category;
        int returnPage = page;
        player.closeInventory();
        plugin.getChatPromptManager().begin(player, "flags.filter-prompt",
            new String[]{"{flag}", flags.displayName(flag), "{kind}", filterKindName(flag)},
            input -> {
                ClaimFlagStore store = plugin.getClaimFlagStore();
                ClaimFlagStore.FilterSetting current = store.getFilter(claimId, flag);
                ClaimFlag.FilterMode mode = current != null ? current.mode() : flag.defaultFilterMode();
                Set<String> entries = new LinkedHashSet<>(current != null ? current.entries() : Set.of());
                List<String> rejected = new ArrayList<>();
                if (input.equalsIgnoreCase("clear")) {
                    entries.clear();
                } else {
                    for (String token : input.split("[,\\s]+")) {
                        if (token.isBlank()) continue;
                        boolean remove = token.startsWith("-");
                        String normalized = normalizeEntry(flag, remove ? token.substring(1) : token);
                        if (normalized == null) {
                            rejected.add(token);
                        } else if (remove) {
                            entries.remove(normalized);
                        } else {
                            entries.add(normalized);
                        }
                    }
                }
                store.setFilter(claimId, flag, new ClaimFlagStore.FilterSetting(mode, entries));
                store.save();
                if (!rejected.isEmpty()) {
                    plugin.getMessages().send(player, "flags.filter-rejected", "{entries}", String.join(", ", rejected));
                }
                plugin.getMessages().send(player, "flags.filter-updated", "{flag}", flags.displayName(flag),
                    "{count}", String.valueOf(entries.size()));
                manager.openGUI(player, new FlagsGUI(manager, player, claim, claimId, returnCategory, returnPage));
            },
            () -> manager.openGUI(player, new FlagsGUI(manager, player, claim, claimId, returnCategory, returnPage)));
    }

    private String filterKindName(ClaimFlag flag) {
        return switch (flag.filter()) {
            case MATERIAL -> "block names (e.g. crafting_table)";
            case ENTITY_TYPE -> "mob names (e.g. horse)";
            case COMMAND -> "commands (e.g. home)";
            case DAMAGE_CAUSE -> "damage causes (e.g. fall)";
            default -> "entries";
        };
    }

    /** Validate and normalize one filter entry, or null when it isn't recognised. */
    static String normalizeEntry(ClaimFlag flag, String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("/")) value = value.substring(1);
        if (value.startsWith("minecraft:")) value = value.substring("minecraft:".length());
        if (value.isEmpty()) return null;
        switch (flag.filter()) {
            case MATERIAL -> {
                Material material = Material.matchMaterial(value);
                return material != null && material.isBlock() ? material.name().toLowerCase(Locale.ROOT) : null;
            }
            case ENTITY_TYPE -> {
                for (EntityType type : EntityType.values()) {
                    if (type.name().equalsIgnoreCase(value)) return type.name().toLowerCase(Locale.ROOT);
                }
                return null;
            }
            case DAMAGE_CAUSE -> {
                for (EntityDamageEvent.DamageCause cause : EntityDamageEvent.DamageCause.values()) {
                    if (cause.name().equalsIgnoreCase(value)) return cause.name().toLowerCase(Locale.ROOT);
                }
                return null;
            }
            case COMMAND -> {
                String label = value.split(" ", 2)[0];
                int namespace = label.indexOf(':');
                return namespace >= 0 ? label.substring(namespace + 1) : label;
            }
            default -> {
                return null;
            }
        }
    }

    // ===================================== helpers =====================================

    private boolean showGPFlagsButton() {
        return GPFlagsBridge.isAvailable()
            && plugin.getConfigManager().getConfig().getBoolean("claim-flags.show-gpflags-button", true)
            && ClaimFlagsGUI.canAccess(player, claim, gp);
    }

    private Claim gpClaim() {
        return claim instanceof Claim gpClaim ? gpClaim : null;
    }

    private String claimName() {
        return plugin.getClaimDataStore().getCustomName(claimId).orElse("Claim #" + claimId);
    }

    private static String categoryKey(ClaimFlag.Category category) {
        return switch (category) {
            case PLAYER -> "player";
            case ENTITY -> "entity";
            case NATURAL -> "natural";
        };
    }

    private static String defaultCategoryName(ClaimFlag.Category category) {
        return switch (category) {
            case PLAYER -> "Player";
            case ENTITY -> "Mobs & Entities";
            case NATURAL -> "Environment";
        };
    }

    private static String defaultCategoryMaterial(ClaimFlag.Category category, boolean selected) {
        return switch (category) {
            case PLAYER -> selected ? "PLAYER_HEAD" : "SKELETON_SKULL";
            case ENTITY -> selected ? "CREEPER_SPAWN_EGG" : "EGG";
            case NATURAL -> selected ? "OAK_SAPLING" : "DEAD_BUSH";
        };
    }

    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("(?i)[&§][0-9a-fk-orx]", "");
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}

