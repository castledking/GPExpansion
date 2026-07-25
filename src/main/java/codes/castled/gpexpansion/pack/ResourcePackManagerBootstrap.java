package codes.castled.gpexpansion.pack;

import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;

import org.bukkit.plugin.Plugin;

/**
 * The single class that touches the ResourcePackManager API.
 *
 * <p>ResourcePackManager is a soft dependency, so its classes only exist when the plugin is
 * installed. Paper's plugin loader verifies the main plugin class before soft dependency
 * classloaders are linked, so any class referencing RSPM types must be a leaf that nothing on the
 * enable path loads eagerly. Keeping the reference here means {@link ClaimWaypointPackService} —
 * and through it the main class — never mentions an RSPM type, and this class is loaded only after
 * RSPM has been confirmed present.
 */
final class ResourcePackManagerBootstrap {

    private ResourcePackManagerBootstrap() {}

    /**
     * Hands the claim waypoint pack to ResourcePackManager so it is merged into the server's
     * combined pack instead of competing with it.
     *
     * @param plugin the GPExpansion plugin instance
     * @param localPath the pack path relative to the plugins directory
     * @return whether RSPM accepted the registration
     */
    static boolean register(Plugin plugin, String localPath) {
        try {
            ResourcePackManagerAPI.registerLocalResourcePack(
                plugin.getName(),
                localPath,
                // encrypts / distributes / reloadCommand are accepted but currently unused by RSPM.
                // zips=true because we ship an already-zipped pack.
                false,
                true,
                true,
                null);
            plugin.getLogger().info(
                "Registered the claim waypoint resource pack with ResourcePackManager (" + localPath + ").");
            return true;
        } catch (Throwable throwable) {
            // RSPM initializes asynchronously, so an early attempt can fail; the caller retries.
            plugin.getLogger().fine("ResourcePackManager registration not ready yet: " + throwable);
            return false;
        }
    }
}
