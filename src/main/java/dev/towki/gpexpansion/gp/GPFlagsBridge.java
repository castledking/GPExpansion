package dev.towki.gpexpansion.gp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Bridge to interact with GPFlags plugin via reflection.
 * Allows setting/unsetting flags on claims without standing in them.
 */
public class GPFlagsBridge {

    private static Plugin gpFlagsPlugin;
    private static Object flagManager;
    private static boolean initialized = false;
    private static boolean available = false;

    // Cache reflection methods
    private static Method getFlagManagerMethod;
    private static Method getFlagDefinitionsMethod;
    private static Method getFlagDefinitionByNameMethod;
    private static Method setFlagMethod;
    private static Method unSetFlagMethod;
    private static Method getFlagsMethod;
    private static Method saveMethod;
    
    // Flag class methods
    private static Method flagGetSetMethod;
    private static Method flagGetDefinitionMethod;
    private static Method flagGetParametersMethod;
    
    // FlagDefinition methods
    private static Method defGetNameMethod;
    private static Method defGetFlagTypeMethod;

    static {
        init();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        try {
            gpFlagsPlugin = Bukkit.getPluginManager().getPlugin("GPFlags");
            if (gpFlagsPlugin == null || !gpFlagsPlugin.isEnabled()) {
                available = false;
                return;
            }

            // Get FlagManager instance
            getFlagManagerMethod = gpFlagsPlugin.getClass().getMethod("getFlagManager");
            flagManager = getFlagManagerMethod.invoke(gpFlagsPlugin);

            if (flagManager == null) {
                available = false;
                return;
            }

            Class<?> flagManagerClass = flagManager.getClass();
            
            // Cache FlagManager methods
            getFlagDefinitionsMethod = flagManagerClass.getMethod("getFlagDefinitions");
            getFlagDefinitionByNameMethod = flagManagerClass.getMethod("getFlagDefinitionByName", String.class);
            getFlagsMethod = flagManagerClass.getMethod("getFlags", String.class);
            saveMethod = flagManagerClass.getMethod("save");
            
            // setFlag(String claimId, FlagDefinition def, boolean isActive, CommandSender sender, String... args)
            Class<?> flagDefClass = Class.forName("me.ryanhamshire.GPFlags.flags.FlagDefinition", true, gpFlagsPlugin.getClass().getClassLoader());
            setFlagMethod = flagManagerClass.getMethod("setFlag", String.class, flagDefClass, boolean.class, CommandSender.class, String[].class);
            unSetFlagMethod = flagManagerClass.getMethod("unSetFlag", String.class, flagDefClass);

            // Cache Flag class methods
            Class<?> flagClass = Class.forName("me.ryanhamshire.GPFlags.Flag", true, gpFlagsPlugin.getClass().getClassLoader());
            flagGetSetMethod = flagClass.getMethod("getSet");
            flagGetDefinitionMethod = flagClass.getMethod("getFlagDefinition");
            flagGetParametersMethod = flagClass.getMethod("getParameters");
            
            // Cache FlagDefinition methods
            defGetNameMethod = flagDefClass.getMethod("getName");
            defGetFlagTypeMethod = flagDefClass.getMethod("getFlagType");

            available = true;
        } catch (Exception e) {
            available = false;
            Bukkit.getLogger().warning("[GPExpansion] Failed to initialize GPFlags bridge: " + e.getMessage());
        }
    }

    /**
     * Check if GPFlags is available
     */
    public static boolean isAvailable() {
        return available && gpFlagsPlugin != null && gpFlagsPlugin.isEnabled();
    }

    /**
     * Get all registered flag definitions that can be used in claims
     * @return List of FlagInfo objects
     */
    public static List<FlagInfo> getClaimFlagDefinitions() {
        List<FlagInfo> result = new ArrayList<>();
        if (!isAvailable()) return result;

        try {
            @SuppressWarnings("unchecked")
            Collection<Object> definitions = (Collection<Object>) getFlagDefinitionsMethod.invoke(flagManager);
            
            for (Object def : definitions) {
                String name = (String) defGetNameMethod.invoke(def);
                
                // Check if this flag can be used in claims
                @SuppressWarnings("unchecked")
                List<Object> flagTypes = (List<Object>) defGetFlagTypeMethod.invoke(def);
                boolean canUseInClaim = false;
                for (Object type : flagTypes) {
                    if (type.toString().contains("CLAIM")) {
                        canUseInClaim = true;
                        break;
                    }
                }
                
                if (canUseInClaim) {
                    result.add(new FlagInfo(name, def));
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error getting flag definitions: " + e.getMessage());
        }

        return result;
    }

    /**
     * Get the current state of a specific flag on a claim
     * @param claimId The claim ID
     * @param flagName The flag name
     * @return Optional containing the flag state, or empty if not set
     */
    public static Optional<ClaimFlagState> getFlagState(String claimId, String flagName) {
        if (!isAvailable() || claimId == null || flagName == null) return Optional.empty();

        try {
            @SuppressWarnings("unchecked")
            Collection<Object> flags = (Collection<Object>) getFlagsMethod.invoke(flagManager, claimId);
            
            for (Object flag : flags) {
                Object def = flagGetDefinitionMethod.invoke(flag);
                String name = (String) defGetNameMethod.invoke(def);
                
                if (name.equalsIgnoreCase(flagName)) {
                    boolean isSet = (Boolean) flagGetSetMethod.invoke(flag);
                    String params = (String) flagGetParametersMethod.invoke(flag);
                    return Optional.of(new ClaimFlagState(name, isSet, params));
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error getting flag state: " + e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Get all flags currently set on a claim
     * @param claimId The claim ID
     * @return Map of flag name to ClaimFlagState
     */
    public static Map<String, ClaimFlagState> getAllFlagStates(String claimId) {
        Map<String, ClaimFlagState> result = new HashMap<>();
        if (!isAvailable() || claimId == null) return result;

        try {
            @SuppressWarnings("unchecked")
            Collection<Object> flags = (Collection<Object>) getFlagsMethod.invoke(flagManager, claimId);
            
            for (Object flag : flags) {
                Object def = flagGetDefinitionMethod.invoke(flag);
                String name = (String) defGetNameMethod.invoke(def);
                boolean isSet = (Boolean) flagGetSetMethod.invoke(flag);
                String params = (String) flagGetParametersMethod.invoke(flag);
                result.put(name.toLowerCase(), new ClaimFlagState(name, isSet, params));
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error getting all flag states: " + e.getMessage());
        }

        return result;
    }

    /**
     * Set a flag on a claim (enables it)
     * @param claimId The claim ID
     * @param flagName The flag name
     * @param params Optional parameters for the flag
     * @return true if successful
     */
    public static boolean setFlag(String claimId, String flagName, String... params) {
        if (!isAvailable() || claimId == null || flagName == null) return false;

        try {
            Object def = getFlagDefinitionByNameMethod.invoke(flagManager, flagName);
            if (def == null) return false;

            Object result = setFlagMethod.invoke(flagManager, claimId, def, true, null, params);
            
            // Check if result was successful (SetFlagResult has isSuccess() method)
            Method isSuccessMethod = result.getClass().getMethod("isSuccess");
            boolean success = (Boolean) isSuccessMethod.invoke(result);
            
            if (success) {
                saveMethod.invoke(flagManager);
            }
            
            return success;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error setting flag: " + e.getMessage());
            return false;
        }
    }

    /**
     * Unset a flag on a claim (removes/disables it)
     * @param claimId The claim ID
     * @param flagName The flag name
     * @return true if successful
     */
    public static boolean unsetFlag(String claimId, String flagName) {
        if (!isAvailable() || claimId == null || flagName == null) return false;

        try {
            Object def = getFlagDefinitionByNameMethod.invoke(flagManager, flagName);
            if (def == null) return false;

            Object result = unSetFlagMethod.invoke(flagManager, claimId, def);
            
            // Check if result was successful
            Method isSuccessMethod = result.getClass().getMethod("isSuccess");
            boolean success = (Boolean) isSuccessMethod.invoke(result);
            
            if (success) {
                saveMethod.invoke(flagManager);
            }
            
            return success;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error unsetting flag: " + e.getMessage());
            return false;
        }
    }

    /**
     * Toggle a flag on a claim
     * @param claimId The claim ID
     * @param flagName The flag name
     * @return The new state (true = enabled, false = disabled), or null on error
     */
    public static Boolean toggleFlag(String claimId, String flagName) {
        if (!isAvailable() || claimId == null || flagName == null) return null;

        Optional<ClaimFlagState> current = getFlagState(claimId, flagName);
        
        if (current.isPresent() && current.get().isEnabled()) {
            // Flag is currently set, unset it
            if (unsetFlag(claimId, flagName)) {
                return false;
            }
        } else {
            // Flag is not set or disabled, set it
            if (setFlag(claimId, flagName)) {
                return true;
            }
        }
        
        return null;
    }

    /**
     * Information about a flag definition
     */
    public static class FlagInfo {
        private final String name;
        private final Object definition;

        public FlagInfo(String name, Object definition) {
            this.name = name;
            this.definition = definition;
        }

        public String getName() {
            return name;
        }

        public Object getDefinition() {
            return definition;
        }
    }

    /**
     * State of a flag on a specific claim
     */
    public static class ClaimFlagState {
        private final String flagName;
        private final boolean enabled;
        private final String parameters;

        public ClaimFlagState(String flagName, boolean enabled, String parameters) {
            this.flagName = flagName;
            this.enabled = enabled;
            this.parameters = parameters;
        }

        public String getFlagName() {
            return flagName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getParameters() {
            return parameters;
        }
    }
}
