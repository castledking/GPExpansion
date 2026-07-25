package codes.castled.gpexpansion.pack;

import codes.castled.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;

/**
 * Owns how the claim waypoint resource pack reaches players.
 *
 * <p>The pack contains one file: an override of {@code minecraft:bowtie} whose {@code near_distance}
 * is raised so the bowtie never degrades into a plain dot. Sprite selection happens entirely on the
 * client, so this is the only way to give unmodified clients a full-size claim marker at range —
 * no server command or API call can reach that decision. CrowBar clients pin the sprite themselves
 * and do not need the pack.
 *
 * <p>Two delivery modes, mirroring how other pack-shipping plugins behave:
 *
 * <ul>
 *   <li><b>ResourcePackManager</b> — when RSPM is installed, GPExpansion registers the pack file
 *       with RSPM and sends nothing itself. A client applies only one server pack at a time, so on
 *       a server also running Nexo, ItemsAdder, or similar, a direct send would replace their pack
 *       or be replaced by it. RSPM merges every registered plugin's pack into one.
 *   <li><b>Direct send</b> — with no RSPM installed and a URL configured, the pack is pushed to
 *       each player on join. Requires hosting the file yourself, because a client downloads it over
 *       HTTP rather than receiving it from the server.
 * </ul>
 *
 * <p>With neither available the feature degrades to vanilla behaviour: the bowtie shows within 64
 * blocks and becomes a dot beyond it.
 *
 * <p>All RSPM API calls are isolated in {@link ResourcePackManagerBootstrap}; this class only asks
 * the plugin manager whether RSPM exists, so it stays safe to load when RSPM is absent.
 */
public final class ClaimWaypointPackService {

    /** Name of the pack inside the plugin jar and inside the plugin's data folder. */
    public static final String PACK_FILE_NAME = "resourcepack.zip";

    /** The ResourcePackManager plugin name, as it appears in the plugin list. */
    private static final String RSPM_PLUGIN_NAME = "ResourcePackManager";

    /** Ticks to wait before the first RSPM registration attempt. */
    private static final long FIRST_ATTEMPT_DELAY_TICKS = 1L;

    /** Give up retrying once the backoff would exceed this many ticks (~10 seconds). */
    private static final long MAX_ATTEMPT_DELAY_TICKS = 200L;

    private final GPExpansionPlugin plugin;

    /** True while RSPM owns pack distribution, so we must not push a pack ourselves. */
    private volatile boolean managedByResourcePackManager;

    public ClaimWaypointPackService(GPExpansionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Extracts the bundled pack and, when ResourcePackManager is installed, hands it over to RSPM.
     * Call once from {@code onEnable}.
     */
    public void setup() {
        Path packFile = exportBundledPack();

        if (Bukkit.getPluginManager().getPlugin(RSPM_PLUGIN_NAME) == null) {
            if (configuredUrl().isEmpty()) {
                plugin.getLogger().info(
                    "ResourcePackManager not installed and claim-waypoints.resource-pack.url is unset; "
                        + "vanilla clients will see claim bowties shrink to a dot past 64 blocks. "
                        + "The pack was written to " + PACK_FILE_NAME + " if you want to host it.");
            } else {
                plugin.getLogger().info("ResourcePackManager not installed; sending the claim waypoint pack directly.");
            }
            return;
        }
        if (packFile == null) {
            plugin.getLogger().warning(
                "ResourcePackManager is installed but this build ships no " + PACK_FILE_NAME
                    + "; falling back to sending the claim waypoint pack directly.");
            return;
        }

        // Claim RSPM ownership up front rather than when registration succeeds: a player who joins
        // during the retry window must not be sent a competing pack that RSPM's merged pack would
        // then immediately replace. Undone only if we give up entirely.
        managedByResourcePackManager = true;
        scheduleRegistration(FIRST_ATTEMPT_DELAY_TICKS);
    }

    /**
     * Pushes the pack to a joining player, when we own distribution and a URL is configured.
     *
     * <p>Skipped for CrowBar clients: the pack exists purely to stop the bowtie shrinking to a dot
     * on unmodified clients, and CrowBar pins the sprite itself. Sending it anyway would prompt
     * those players for a download that changes nothing they can see.
     *
     * <p>No-op under RSPM, which owns distribution for the whole server and sends one merged pack
     * to everyone. There is no per-player opt-out in that mode, but the pack is harmless to a
     * CrowBar client either way.
     */
    public void sendTo(Player player) {
        if (managedByResourcePackManager) return;
        if (isCrowBarClient(player)) return;

        String url = configuredUrl();
        if (url.isEmpty()) return;

        String hash = plugin.getConfig().getString("claim-waypoints.resource-pack.sha1", "").trim();
        try {
            if (hash.isEmpty()) {
                // Without a hash the client re-downloads on every join instead of using its cache.
                player.setResourcePack(url);
            } else {
                player.setResourcePack(url, HexFormat.of().parseHex(hash));
            }
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(
                "claim-waypoints.resource-pack.sha1 is not a valid hex SHA-1: " + exception.getMessage());
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not send the claim waypoint pack: " + exception.getMessage());
        }
    }

    /**
     * Whether this player has CrowBar installed.
     *
     * <p>Detected from the plugin channels the client registered on join. CrowBar registers a
     * receiver for the claim data channel, and only a client with the mod will have done so.
     */
    private boolean isCrowBarClient(Player player) {
        return player.getListeningPluginChannels()
            .contains(codes.castled.gpexpansion.waypoint.ClaimWaypointManager.CLAIM_DATA_CHANNEL);
    }

    private String configuredUrl() {
        String url = plugin.getConfig().getString("claim-waypoints.resource-pack.url", "");
        return url == null ? "" : url.trim();
    }

    private void scheduleRegistration(long delayTicks) {
        codes.castled.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(plugin, () -> {
            if (Bukkit.getPluginManager().isPluginEnabled(RSPM_PLUGIN_NAME)) {
                // Path relative to the plugins directory, which is what RSPM resolves against.
                String localPath = plugin.getDataFolder().getName() + "/" + PACK_FILE_NAME;
                if (ResourcePackManagerBootstrap.register(plugin, localPath)) {
                    return;
                }
            }
            long nextDelay = delayTicks * 2;
            if (nextDelay > MAX_ATTEMPT_DELAY_TICKS) {
                managedByResourcePackManager = false;
                plugin.getLogger().warning(
                    "Gave up registering the claim waypoint pack with ResourcePackManager; "
                        + "sending it directly instead.");
                return;
            }
            scheduleRegistration(nextDelay);
        }, delayTicks);
    }

    /**
     * Writes the pack bundled in the plugin jar to the data folder, overwriting any previous copy so
     * a plugin update also updates the pack.
     *
     * @return the pack file on disk, or null when there is none to give RSPM
     */
    private Path exportBundledPack() {
        Path target = plugin.getDataFolder().toPath().resolve(PACK_FILE_NAME);

        try (InputStream bundled = plugin.getResource(PACK_FILE_NAME)) {
            if (bundled == null) {
                return Files.exists(target) ? target : null;
            }
            Files.createDirectories(target.getParent());
            // Overwriting with identical bytes leaves the SHA-1 unchanged, so RSPM (which
            // fingerprints pack contents, not timestamps) will not needlessly re-mix on restart.
            Files.copy(bundled, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not write " + target + ": " + exception.getMessage());
            return Files.exists(target) ? target : null;
        }
    }
}
