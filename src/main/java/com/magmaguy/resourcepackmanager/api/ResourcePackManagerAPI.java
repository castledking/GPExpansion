package com.magmaguy.resourcepackmanager.api;

/**
 * Compile-only stub of ResourcePackManager's public API.
 *
 * <p>The signatures mirror {@code com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI}
 * exactly so GPExpansion can call it without depending on an RSPM artifact. This class is
 * <strong>excluded from the shaded jar</strong> (see the shade plugin filter in {@code pom.xml}):
 * RSPM is a soft dependency and supplies the real implementation at runtime, and shipping the stub
 * would let it shadow the real class. Every method throws so an accidental call against the stub
 * fails loudly rather than silently doing nothing.
 */
public class ResourcePackManagerAPI {

    private ResourcePackManagerAPI() {}

    /**
     * Registers a resource pack that lives on disk under the plugins directory.
     *
     * @param pluginName the registering plugin's name as it appears in the plugin list
     * @param localPath the pack path relative to the plugins directory
     * @param encrypts whether the pack may be encrypted (currently unused by RSPM)
     * @param distributes whether the plugin may distribute the pack (currently unused by RSPM)
     * @param zips whether the pack is already zipped; when false RSPM zips it
     * @param reloadCommand the registering plugin's reload command (currently unused by RSPM)
     */
    public static void registerLocalResourcePack(
            String pluginName,
            String localPath,
            boolean encrypts,
            boolean distributes,
            boolean zips,
            String reloadCommand) {
        throw new UnsupportedOperationException("ResourcePackManager stub");
    }
}
