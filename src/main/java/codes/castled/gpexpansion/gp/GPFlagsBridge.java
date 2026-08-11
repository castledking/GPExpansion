package codes.castled.gpexpansion.gp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

    /**
     * FlagManager#getEffectiveFlag(String, Claim, World) -- resolves claim -> parent ->
     * default -> world -> server, same as what the actual event listeners enforce.
     * Resolved lazily (needs a live Claim instance to look up its exact runtime class,
     * since GPExpansion has no compile-time dependency on GPFlags's Claim-typed signature)
     * and then cached, since exactly one GriefPrevention Claim class is loaded per server.
     */
    private static Method getEffectiveFlagMethod;

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
    @SuppressWarnings("all")
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
     * Get the effective state of a flag on a claim, i.e. what's actually being enforced
     * right now -- including inheritance from a parent claim, the default template, a
     * world flag, or a server flag. Unlike {@link #getFlagState}, which only looks at the
     * claim's own raw override and misreports flags as "disabled" when they're really
     * being enforced from a higher level, this mirrors FlagManager#getEffectiveFlag,
     * the same resolution the actual event listeners use.
     *
     * @param claim    Live Claim instance (needed to resolve its world for the world/server checks)
     * @param flagName The flag name
     * @return Optional containing the effective flag state, or empty if GPFlags/the flag definition is unavailable
     */
    public static Optional<ClaimFlagState> getEffectiveFlagState(Object claim, String flagName) {
        if (!isAvailable() || claim == null || flagName == null) return Optional.empty();

        try {
            World world = getClaimWorld(claim);
            if (world == null) return Optional.empty();

            Method effectiveMethod = resolveEffectiveFlagMethod(claim);
            if (effectiveMethod == null) return Optional.empty();

            Object flag = effectiveMethod.invoke(flagManager, flagName, claim, world);
            if (flag == null) {
                return Optional.of(new ClaimFlagState(flagName, false, "", isExplicitOnClaim(claim, flagName)));
            }

            String params = (String) flagGetParametersMethod.invoke(flag);
            return Optional.of(new ClaimFlagState(flagName, true, params, isExplicitOnClaim(claim, flagName)));
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error getting effective flag state: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Effective states (see {@link #getEffectiveFlagState}) for every claim-type flag
     * definition, keyed by lowercase flag name. Use this instead of {@link #getAllFlagStates}
     * for anything shown to a player -- that method only reflects the claim's own raw
     * override and will show "Disabled" for flags that are actually being enforced via a
     * world or server flag.
     */
    public static Map<String, ClaimFlagState> getAllEffectiveFlagStates(Object claim) {
        Map<String, ClaimFlagState> result = new HashMap<>();
        if (!isAvailable() || claim == null) return result;

        try {
            World world = getClaimWorld(claim);
            if (world == null) return result;

            Method effectiveMethod = resolveEffectiveFlagMethod(claim);
            if (effectiveMethod == null) return result;

            Set<String> explicitOnClaim = rawFlagNamesOnClaim(claim);

            for (FlagInfo info : getClaimFlagDefinitions()) {
                String lower = info.getName().toLowerCase();
                Object flag = effectiveMethod.invoke(flagManager, info.getName(), claim, world);
                boolean explicit = explicitOnClaim.contains(lower);
                if (flag != null) {
                    String params = (String) flagGetParametersMethod.invoke(flag);
                    result.put(lower, new ClaimFlagState(info.getName(), true, params, explicit));
                } else {
                    result.put(lower, new ClaimFlagState(info.getName(), false, "", explicit));
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error getting effective flag states: " + e.getMessage());
        }

        return result;
    }

    /**
     * Toggle a flag on a claim based on its effective (enforced) state, and always write
     * an explicit override rather than routing through {@code unSetFlag}. GPFlags's
     * {@code unSetFlag} deletes the claim's raw entry instead of overriding it to false
     * when the claim already has one, which lets an inherited parent/default/world/server
     * flag silently take back over. Writing the explicit state directly avoids that
     * without needing any change on the GPFlags side.
     *
     * @param claim    Live Claim instance
     * @param claimId  The claim ID
     * @param flagName The flag name
     * @return The new effective state (true = enabled, false = disabled), or null on error
     */
    public static Boolean toggleEffectiveFlag(Object claim, String claimId, String flagName) {
        if (!isAvailable() || claim == null || claimId == null || flagName == null) return null;

        boolean currentlyEnabled = getEffectiveFlagState(claim, flagName)
                .map(ClaimFlagState::isEnabled)
                .orElse(false);

        boolean success = setFlagState(claimId, flagName, !currentlyEnabled);
        return success ? !currentlyEnabled : null;
    }

    /**
     * Directly write an explicit true/false override for a flag on a claim, bypassing
     * {@link #unsetFlag}'s delete-instead-of-override behavior (see {@link #toggleEffectiveFlag}).
     */
    public static boolean setFlagState(String claimId, String flagName, boolean active, String... params) {
        if (!isAvailable() || claimId == null || flagName == null) return false;

        try {
            Object def = getFlagDefinitionByNameMethod.invoke(flagManager, flagName);
            if (def == null) return false;

            Object result = setFlagMethod.invoke(flagManager, claimId, def, active, null, params);

            Method isSuccessMethod = result.getClass().getMethod("isSuccess");
            boolean success = (Boolean) isSuccessMethod.invoke(result);

            if (success) {
                saveMethod.invoke(flagManager);
            }

            return success;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[GPExpansion] Error setting flag state: " + e.getMessage());
            return false;
        }
    }

    private static Method resolveEffectiveFlagMethod(Object claim) throws NoSuchMethodException {
        if (getEffectiveFlagMethod == null) {
            getEffectiveFlagMethod = flagManager.getClass().getMethod(
                    "getEffectiveFlag", String.class, claim.getClass(), World.class);
        }
        return getEffectiveFlagMethod;
    }

    private static World getClaimWorld(Object claim) throws Exception {
        Object lesserCorner = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
        return lesserCorner instanceof Location ? ((Location) lesserCorner).getWorld() : null;
    }

    private static Set<String> rawFlagNamesOnClaim(Object claim) throws Exception {
        Set<String> names = new HashSet<>();
        Object claimId = claim.getClass().getMethod("getID").invoke(claim);
        if (claimId == null) return names;

        @SuppressWarnings("unchecked")
        Collection<Object> flags = (Collection<Object>) getFlagsMethod.invoke(flagManager, claimId.toString());
        for (Object flag : flags) {
            Object def = flagGetDefinitionMethod.invoke(flag);
            names.add(((String) defGetNameMethod.invoke(def)).toLowerCase());
        }
        return names;
    }

    private static boolean isExplicitOnClaim(Object claim, String flagName) {
        try {
            return rawFlagNamesOnClaim(claim).contains(flagName.toLowerCase());
        } catch (Exception e) {
            return false;
        }
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
        private final boolean explicit;

        public ClaimFlagState(String flagName, boolean enabled, String parameters) {
            this(flagName, enabled, parameters, true);
        }

        public ClaimFlagState(String flagName, boolean enabled, String parameters, boolean explicit) {
            this.flagName = flagName;
            this.enabled = enabled;
            this.parameters = parameters;
            this.explicit = explicit;
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

        /**
         * False when this state was resolved (or is being enforced) via a parent claim,
         * default template, world flag, or server flag rather than the claim's own raw
         * override -- i.e. the claim itself has no entry for this flag.
         */
        public boolean isExplicit() {
            return explicit;
        }
    }
}
