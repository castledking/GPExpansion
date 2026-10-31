package codes.castled.gpexpansion.sign;
import codes.castled.gpexpansion.util.Mini;
import codes.castled.gpexpansion.util.PdcCodec;

import codes.castled.gpexpansion.GPExpansionPlugin;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.storage.ClaimDataStore;
// Paper-only import is used via a guarded nested listener; do not import at class level
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Two-step managed-sign mover used by /claim movesign and /moveclaimsign. */
public final class SignMoveManager implements Listener, CommandExecutor {

    private static final long MOVE_TIMEOUT_MS = 120_000L;

    private final GPExpansionPlugin plugin;
    private final GPBridge gp = new GPBridge();
    private final ClaimDataStore dataStore;
    private final Map<UUID, PendingMove> pendingMoves = new ConcurrentHashMap<>();
    private final Map<UUID, Location> recentDestinations = new ConcurrentHashMap<>();

    private final NamespacedKey keyKind;
    private final NamespacedKey keyClaim;
    private final NamespacedKey keyCreator;
    private final NamespacedKey keyMailboxParent;
    private final NamespacedKey keyMailboxShared;
    private final NamespacedKey keyEcoAmount;

    public SignMoveManager(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.dataStore = plugin.getClaimDataStore();
        this.keyKind = new NamespacedKey(plugin, "sign.kind");
        this.keyClaim = new NamespacedKey(plugin, "sign.claimId");
        this.keyCreator = new NamespacedKey(plugin, "sign.creator");
        this.keyMailboxParent = new NamespacedKey(plugin, "sign.mailbox.parent");
        this.keyMailboxShared = new NamespacedKey(plugin, "sign.mailbox.shared");
        this.keyEcoAmount = new NamespacedKey(plugin, "sign.ecoAmt");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        return run(sender, args);
    }

    public boolean run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Mini.send(sender, plugin.getMessages().get("general.player-only"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            if (pendingMoves.remove(player.getUniqueId()) != null) {
                plugin.getMessages().send(player, "sign-move.cancelled");
            } else {
                plugin.getMessages().send(player, "sign-move.nothing-to-cancel");
            }
            return true;
        }

        pendingMoves.put(player.getUniqueId(), new PendingMove());
        plugin.getMessages().send(player, "sign-move.select-source");
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSourceSelect(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }

        Player player = event.getPlayer();
        PendingMove pending = activeMove(player);
        if (pending == null || pending.snapshot != null) return;

        event.setCancelled(true);
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) {
            plugin.getMessages().send(player, "sign-move.select-source-sign");
            return;
        }

        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String kind = pdc.get(keyKind, PersistentDataType.STRING);
        String claimId = pdc.get(keyClaim, PersistentDataType.STRING);
        MoveType type = resolveType(kind, claimId, pdc);
        if (type == null || claimId == null || claimId.isBlank()) {
            plugin.getMessages().send(player, "sign-move.not-managed");
            return;
        }

        boolean controller = isController(player.getUniqueId(), type, claimId,
            pdc.get(keyMailboxParent, PersistentDataType.STRING),
            pdc.get(keyMailboxShared, PersistentDataType.STRING),
            pdc.get(keyCreator, PersistentDataType.STRING));
        String permission = type.permission(controller);
        if (!player.hasPermission(permission)) {
            plugin.getMessages().send(player, "sign-move.no-permission", "{permission}", permission);
            return;
        }

        if (type == MoveType.GLOBAL && controller
                && !pdc.has(keyCreator, PersistentDataType.STRING)) {
            // Legacy global signs predate creator metadata. The current claim owner is the
            // only controller we can safely infer, so migrate the sign when first moved.
            pdc.set(keyCreator, PersistentDataType.STRING, player.getUniqueId().toString());
            sign.update(true, false);
        }

        try {
            pending.snapshot = snapshot(sign, type, claimId);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not snapshot managed sign at " + sign.getLocation() + ": " + exception.getMessage());
            plugin.getMessages().send(player, "sign-move.failed");
            pendingMoves.remove(player.getUniqueId(), pending);
            return;
        }

        plugin.getMessages().send(player, "sign-move.place-destination", "{type}", type.node);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDestinationPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        PendingMove pending = activeMove(player);
        if (pending == null || pending.snapshot == null || pending.destination != null) return;
        if (!(event.getBlockPlaced().getState() instanceof Sign)) return;

        Location destination = event.getBlockPlaced().getLocation();
        if (sameBlock(destination, pending.snapshot.source)) {
            plugin.getMessages().send(player, "sign-move.same-location");
            return;
        }

        pending.destination = destination.clone();
        SchedulerAdapter.runAtLocationLater(plugin, destination,
            () -> completeMove(player, pending, destination), 1L);
    }

    /** Fallback for clients which submit sign text without a PlayerOpenSignEvent. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDestinationText(SignChangeEvent event) {
        PendingMove pending = pendingMoves.get(event.getPlayer().getUniqueId());
        Location recent = recentDestinations.get(event.getPlayer().getUniqueId());
        if ((pending != null && pending.destination != null
                && sameBlock(pending.destination, event.getBlock().getLocation()))
                || sameBlock(recent, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingMoves.remove(event.getPlayer().getUniqueId());
        recentDestinations.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Paper-only listener for PlayerOpenSignEvent, registered only when the class exists.
     * This avoids NoClassDefFoundError on Spigot which lacks this event.
     */
    public static final class PaperSignMoveListener implements Listener {
        private final SignMoveManager manager;

        public PaperSignMoveListener(SignMoveManager manager) {
            this.manager = manager;
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
        public void onDestinationEditor(io.papermc.paper.event.player.PlayerOpenSignEvent event) {
            PendingMove pending = manager.pendingMoves.get(event.getPlayer().getUniqueId());
            Location recent = manager.recentDestinations.get(event.getPlayer().getUniqueId());
            if ((pending != null && pending.destination != null
                    && manager.sameBlock(pending.destination, event.getSign().getLocation()))
                    || manager.sameBlock(recent, event.getSign().getLocation())) {
                event.setCancelled(true);
            }
        }
    }

    private void completeMove(Player player, PendingMove pending, Location destination) {
        if (pendingMoves.get(player.getUniqueId()) != pending
                || pending.snapshot == null
                || !sameBlock(pending.destination, destination)) {
            return;
        }

        SignSnapshot snapshot = pending.snapshot;
        Block destinationBlock = destination.getBlock();
        if (!(destinationBlock.getState() instanceof Sign destinationSign)) {
            pending.destination = null;
            plugin.getMessages().send(player, "sign-move.destination-failed");
            return;
        }

        boolean controller = isController(player.getUniqueId(), snapshot.type, snapshot.claimId,
            snapshot.mailboxParentClaimId, snapshot.mailboxShared, snapshot.creator);
        String permission = snapshot.type.permission(controller);
        if (!player.hasPermission(permission)) {
            pendingMoves.remove(player.getUniqueId(), pending);
            plugin.getMessages().send(player, "sign-move.no-permission", "{permission}", permission);
            return;
        }

        try {
            codes.castled.gpexpansion.util.PdcCodec.decode(snapshot.persistentData,
                destinationSign.getPersistentDataContainer());
            restoreSide(destinationSign.getSide(Side.FRONT), snapshot.front);
            restoreSide(destinationSign.getSide(Side.BACK), snapshot.back);
            destinationSign.setWaxed(snapshot.waxed);
            destinationSign.update(true, false);
        } catch (IOException exception) {
            pending.destination = null;
            plugin.getLogger().warning("Could not restore managed sign at " + destination + ": " + exception.getMessage());
            plugin.getMessages().send(player, "sign-move.destination-failed");
            return;
        }

        updateStoredLocation(snapshot, destination);
        if (plugin.getMarketManager() != null) {
            plugin.getMarketManager().relocateListing(snapshot.source, destination);
        }
        pendingMoves.remove(player.getUniqueId(), pending);

        Location recentDestination = destination.clone();
        recentDestinations.put(player.getUniqueId(), recentDestination);
        SchedulerAdapter.runAtLocationLater(plugin, destination,
            () -> recentDestinations.remove(player.getUniqueId(), recentDestination), 100L);

        SchedulerAdapter.runAtLocation(plugin, snapshot.source, () -> clearSource(snapshot));
        plugin.getMessages().send(player, "sign-move.success", "{type}", snapshot.type.node);
    }

    private PendingMove activeMove(Player player) {
        PendingMove pending = pendingMoves.get(player.getUniqueId());
        if (pending == null) return null;
        if (System.currentTimeMillis() - pending.createdAt <= MOVE_TIMEOUT_MS) return pending;

        pendingMoves.remove(player.getUniqueId(), pending);
        plugin.getMessages().send(player, "sign-move.expired");
        return null;
    }

    private SignSnapshot snapshot(Sign sign, MoveType type, String claimId) throws IOException {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        return new SignSnapshot(
            sign.getLocation().clone(),
            type,
            claimId,
            pdc.get(keyMailboxParent, PersistentDataType.STRING),
            pdc.get(keyMailboxShared, PersistentDataType.STRING),
            pdc.get(keyCreator, PersistentDataType.STRING),
            sideSnapshot(sign.getSide(Side.FRONT)),
            sideSnapshot(sign.getSide(Side.BACK)),
            sign.isWaxed(),
            serializeManagedData(pdc)
        );
    }

    private byte[] serializeManagedData(PersistentDataContainer source) throws IOException {
        PersistentDataContainer managed = source.getAdapterContext().newPersistentDataContainer();
        source.copyTo(managed, true);
        String pluginNamespace = plugin.getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey key : List.copyOf(managed.getKeys())) {
            if (!key.getNamespace().equals(pluginNamespace)) {
                managed.remove(key);
            }
        }
        return PdcCodec.encode(managed);
    }

    private SideSnapshot sideSnapshot(SignSide side) {
        return new SideSnapshot(
            List.of(Mini.signLine(side, 0), Mini.signLine(side, 1),
                Mini.signLine(side, 2), Mini.signLine(side, 3)),
            side.getColor(),
            side.isGlowingText()
        );
    }

    private void restoreSide(SignSide side, SideSnapshot snapshot) {
        for (int line = 0; line < 4; line++) {
            Mini.signLine(side, line, snapshot.lines.get(line));
        }
        side.setColor(snapshot.color);
        side.setGlowingText(snapshot.glowing);
    }

    private void clearSource(SignSnapshot snapshot) {
        if (!(snapshot.source.getBlock().getState() instanceof Sign sourceSign)) return;

        PersistentDataContainer pdc = sourceSign.getPersistentDataContainer();
        String currentKind = pdc.get(keyKind, PersistentDataType.STRING);
        String currentClaim = pdc.get(keyClaim, PersistentDataType.STRING);
        String expectedKind = switch (snapshot.type) {
            case RENT -> "RENT";
            case SELL -> "SELL";
            case GLOBAL -> "GLOBAL";
            case MAILBOX, SELF_MAILBOX -> "MAILBOX";
        };
        if (!expectedKind.equals(currentKind) || !snapshot.claimId.equals(currentClaim)) return;

        for (int line = 0; line < 4; line++) {
            Mini.signLine(sourceSign.getSide(Side.FRONT), line, Component.empty());
            Mini.signLine(sourceSign.getSide(Side.BACK), line, Component.empty());
        }
        String pluginNamespace = plugin.getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey key : List.copyOf(pdc.getKeys())) {
            if (key.getNamespace().equals(pluginNamespace)) {
                pdc.remove(key);
            }
        }
        sourceSign.update(true, false);
    }

    private void updateStoredLocation(SignSnapshot snapshot, Location destination) {
        switch (snapshot.type) {
            case RENT -> dataStore.getRental(snapshot.claimId)
                .ifPresent(rental -> rental.signLocation = destination.clone());
            case MAILBOX, SELF_MAILBOX -> dataStore.setMailboxSignLocation(snapshot.claimId, destination.clone());
            case GLOBAL -> dataStore.getSpawn(snapshot.claimId)
                .filter(spawn -> sameBlock(spawn, snapshot.source))
                .ifPresent(ignored -> dataStore.setSpawn(snapshot.claimId, destination.clone()));
            case SELL -> { }
        }
        dataStore.save();
    }

    private MoveType resolveType(String kind, String claimId, PersistentDataContainer pdc) {
        if (kind == null) return null;
        return switch (kind.toUpperCase(Locale.ROOT)) {
            case "RENT" -> MoveType.RENT;
            case "SELL" -> MoveType.SELL;
            case "GLOBAL" -> MoveType.GLOBAL;
            case "MAILBOX" -> isSelfMailbox(claimId, pdc) ? MoveType.SELF_MAILBOX : MoveType.MAILBOX;
            default -> null;
        };
    }

    private boolean isSelfMailbox(String claimId, PersistentDataContainer pdc) {
        if (claimId == null || pdc.has(keyMailboxParent, PersistentDataType.STRING)) return false;
        String amount = pdc.get(keyEcoAmount, PersistentDataType.STRING);
        UUID mailboxOwner = dataStore.getMailboxOwner(claimId).orElse(null);
        String creator = pdc.get(keyCreator, PersistentDataType.STRING);
        return mailboxOwner != null
            && (amount == null || amount.equals("0"))
            && (creator == null || creator.equals(mailboxOwner.toString()));
    }

    private boolean isController(UUID playerId, MoveType type, String claimId,
                                 String mailboxParentClaimId, String mailboxShared, String creator) {
        return switch (type) {
            case RENT -> isClaimOwner(claimId, playerId) || isActiveTenant(claimId, playerId);
            case SELL -> isClaimOwner(claimId, playerId);
            case GLOBAL -> creator == null || creator.isBlank()
                ? isClaimOwner(claimId, playerId)
                : playerId.toString().equals(creator);
            case MAILBOX -> playerId.equals(dataStore.getMailboxOwner(claimId).orElse(null))
                || isClaimOwner(claimId, playerId)
                || isClaimOwner(mailboxParentClaimId, playerId)
                || playerId.toString().equals(creator);
            case SELF_MAILBOX -> (mailboxShared == null || mailboxShared.isBlank())
                && playerId.equals(dataStore.getMailboxOwner(claimId).orElse(null));
        };
    }

    private boolean isClaimOwner(String claimId, UUID playerId) {
        if (claimId == null || claimId.isBlank()) return false;
        return gp.findClaimById(claimId)
            .map(claim -> gp.isOwner(claim, playerId))
            .orElse(false);
    }

    private boolean isActiveTenant(String claimId, UUID playerId) {
        ClaimDataStore.RentalData rental = dataStore.getRental(claimId).orElse(null);
        return rental != null && rental.expiry > System.currentTimeMillis()
            && playerId.equals(rental.renter);
    }

    private static boolean sameBlock(Location first, Location second) {
        return first != null && second != null
            && first.getWorld() != null && second.getWorld() != null
            && first.getWorld().getUID().equals(second.getWorld().getUID())
            && first.getBlockX() == second.getBlockX()
            && first.getBlockY() == second.getBlockY()
            && first.getBlockZ() == second.getBlockZ();
    }

    private enum MoveType {
        RENT("rent"),
        SELL("sell"),
        MAILBOX("mailbox"),
        GLOBAL("global"),
        SELF_MAILBOX("self-mailbox");

        private final String node;

        MoveType(String node) {
            this.node = node;
        }

        private String permission(boolean controller) {
            return "griefprevention.sign.move." + node + (controller ? "" : ".other");
        }
    }

    private static final class PendingMove {
        private final long createdAt = System.currentTimeMillis();
        private volatile SignSnapshot snapshot;
        private volatile Location destination;
    }

    private record SignSnapshot(Location source, MoveType type, String claimId,
                                String mailboxParentClaimId, String mailboxShared, String creator,
                                SideSnapshot front, SideSnapshot back, boolean waxed,
                                byte[] persistentData) { }

    private record SideSnapshot(List<Component> lines, DyeColor color, boolean glowing) { }
}
