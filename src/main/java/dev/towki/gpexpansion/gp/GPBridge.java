package dev.towki.gpexpansion.gp;

import dev.towki.gpexpansion.GPExpansionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Bridge to GriefPrevention main fork (compile-time dependency, provided scope).
 */
public class GPBridge {
    private static final double DOMINANT_CELL_COVERAGE_THRESHOLD = 0.50D;

    private enum GetClaimAtMode {
        TWO_ARG,
        THREE_ARG_PLAYER,
        THREE_ARG_PLAYER_DATA,
        THREE_ARG_CACHED_CLAIM,
        FOUR_ARG
    }


    // Debug logging toggle (verbose). Defaults to false.
    private static volatile boolean DEBUG = false;
    private static volatile long lastAvailWarn = 0L;
    public static void setDebug(boolean enabled) { DEBUG = enabled; }
    
    // Cache for GP3D detection
    private static volatile boolean isGP3D = false;
    private static volatile boolean gp3dChecked = false;

    private Class<?> gpClass;
    private Class<?> claimClass;
    private Object gpInstance;
    private Object dataStore;
    private volatile Method cachedGetClaimAtMethod;
    private volatile GetClaimAtMode cachedGetClaimAtMode;
    private volatile boolean cachedGetClaimAtResolved;
    private volatile Method cachedGetPlayerDataMethod;

    // Cached reflective accessors for OrthogonalPolygon/OrthogonalPoint2i. Resolving
    // Method handles via Paper's reflection remapper is expensive, so we resolve them
    // once per polygon class/point class and reuse them for every subsequent query.
    private volatile Class<?> cachedPolygonClass;
    private volatile Method cachedPolygonCornersMethod;
    private volatile Class<?> cachedPointClass;
    private volatile Method cachedPointXMethod;
    private volatile Method cachedPointZMethod;

    public GPBridge() {
        // Try both CamelCase and lowercase packages for compatibility across forks
        String[] gpCandidates = new String[]{
                "me.ryanhamshire.GriefPrevention.GriefPrevention",
                "me.ryanhamshire.griefprevention.GriefPrevention"
        };
        String[] claimCandidates = new String[]{
                "me.ryanhamshire.GriefPrevention.Claim",
                "me.ryanhamshire.griefprevention.Claim"
        };
        for (String cname : gpCandidates) {
            try {
                gpClass = Class.forName(cname);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        for (String cname : claimCandidates) {
            try {
                claimClass = Class.forName(cname);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        try {
            if (gpClass != null && gpInstance == null) {
                // Try public field first
                try {
                    gpInstance = gpClass.getField("instance").get(null);
                } catch (NoSuchFieldException e) {
                    // Try declared (private) field
                    try {
                        java.lang.reflect.Field f = gpClass.getDeclaredField("instance");
                        f.setAccessible(true);
                        gpInstance = f.get(null);
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (gpInstance == null) {
                // Fallback: use actual plugin instance from PluginManager
                org.bukkit.plugin.Plugin gpPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("GriefPrevention");
                if (gpPlugin != null) {
                    gpInstance = gpPlugin;
                    gpClass = gpPlugin.getClass();
                }
            }
            if (gpInstance != null && dataStore == null) {
                // Try to resolve dataStore from gpInstance class
                Class<?> cls = gpInstance.getClass();
                try {
                    dataStore = cls.getField("dataStore").get(gpInstance);
                } catch (NoSuchFieldException e) {
                    try {
                        java.lang.reflect.Field f = cls.getDeclaredField("dataStore");
                        f.setAccessible(true);
                        dataStore = f.get(gpInstance);
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (DEBUG) {
                try {
                    Bukkit.getLogger().warning("[GPExpansion][debug] GP class=" + (gpClass!=null?gpClass.getName():"null") + ", dataStore=" + (dataStore != null ? dataStore.getClass().getName() : "null"));
                } catch (Throwable ignored) {}
            }
        } catch (ReflectiveOperationException ignored) {
            gpInstance = null;
            dataStore = null;
        }
    }
    
    /**
     * Check if the Claim class has 3D (Y-level) methods (getMinY/getMaxY or getMinimumY/getMaximumY).
     */
    private static boolean claimClassHas3DMethods(Class<?> claimCls) {
        if (claimCls == null) return false;
        String[] minNames = {"getMinY", "getMinimumY", "getMinHeight"};
        String[] maxNames = {"getMaxY", "getMaximumY", "getMaxHeight"};
        for (String minName : minNames) {
            for (String maxName : maxNames) {
                try {
                    claimCls.getMethod(minName);
                    claimCls.getMethod(maxName);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return false;
    }

    /**
     * Check if running on GP3D fork (3D subdivisions, Y-level boundaries).
     */
    public boolean isGP3D() {
        if (!gp3dChecked) {
            gp3dChecked = true;
            if (gpInstance != null || gpClass != null) {
                // 1) Check Claim class from the actual loaded plugin (fork uses same package name)
                try {
                    ClassLoader loader = gpInstance != null ? gpInstance.getClass().getClassLoader() : (gpClass != null ? gpClass.getClassLoader() : null);
                    if (loader != null) {
                        for (String claimName : new String[]{"me.ryanhamshire.GriefPrevention.Claim", "me.ryanhamshire.griefprevention.Claim"}) {
                            try {
                                Class<?> loadedClaim = loader.loadClass(claimName);
                                if (claimClassHas3DMethods(loadedClaim)) {
                                    isGP3D = true;
                                    if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D detected: Claim class has 3D methods (" + claimName + ")");
                                    return true;
                                }
                            } catch (ClassNotFoundException ignored) {}
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D plugin classloader check: " + e.getMessage());
                }

                // 2) Check claimClass we already have (from Class.forName)
                if (claimClass != null && claimClassHas3DMethods(claimClass)) {
                    isGP3D = true;
                    if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D detected: claimClass has 3D methods");
                    return true;
                }

                // 3) Try to get any claim instance and check its class
                try {
                    if (dataStore != null) {
                        Method getClaims = dataStore.getClass().getMethod("getClaims");
                        Object claims = getClaims.invoke(dataStore);
                        if (claims instanceof Collection && !((Collection<?>) claims).isEmpty()) {
                            Object firstClaim = ((Collection<?>) claims).iterator().next();
                            if (claimClassHas3DMethods(firstClaim.getClass())) {
                                isGP3D = true;
                                if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D detected: claim instance has 3D methods");
                                return true;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D getClaims check: " + e.getMessage());
                }

                // 4) Fallback: package or class name hints
                Class<?> c = gpInstance != null ? gpInstance.getClass() : gpClass;
                if (c != null) {
                    String packageName = c.getPackage().getName();
                    if (packageName.contains("gp3d") || packageName.contains("3d") || c.getSimpleName().contains("3D")) {
                        isGP3D = true;
                        if (DEBUG) Bukkit.getLogger().info("[GPBridge] GP3D detected: package/class name hint");
                    }
                }

                if (DEBUG) {
                    Bukkit.getLogger().info("[GPBridge] GP3D detection result: " + isGP3D);
                }
            }
        }
        return isGP3D;
    }

    public static class ClaimStats {
        public final int accrued;
        public final int bonus;
        public final int total;
        public final int remaining;
        public final int used;

        public ClaimStats(int accrued, int bonus, int total, int remaining, int used) {
            this.accrued = accrued;
            this.bonus = bonus;
            this.total = total;
            this.remaining = remaining;
            this.used = used;
        }
    }

    public Optional<ClaimStats> getPlayerClaimStats(Player player) {
        if (!isAvailable()) return Optional.empty();
        try {
            Method getPlayerData = dataStore.getClass().getMethod("getPlayerData", UUID.class);
            Object pd = getPlayerData.invoke(dataStore, player.getUniqueId());
            if (pd == null) return Optional.empty();

            Integer accrued = invokeInt(pd, "getAccruedClaimBlocks");
            Integer bonus = invokeInt(pd, "getBonusClaimBlocks");
            Integer total = invokeInt(pd, "getTotalClaimBlocks");
            Integer remaining = invokeInt(pd, "getRemainingClaimBlocks");

            if (accrued == null) accrued = 0;
            if (bonus == null) bonus = 0;
            if (total == null) total = accrued + bonus;

            int used;
            if (remaining != null) {
                used = Math.max(0, total - remaining);
            } else {
                // Fallback: sum claim areas
                int sum = 0;
                for (Object c : getClaimsFor(player)) {
                    sum += getClaimAreaSafe(c);
                }
                used = sum;
                remaining = Math.max(0, total - used);
            }

            return Optional.of(new ClaimStats(accrued, bonus, total, remaining, used));
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    private Integer invokeInt(Object target, String method) {
        try {
            Object res = target.getClass().getMethod(method).invoke(target);
            if (res instanceof Number) return ((Number) res).intValue();
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    public int getClaimAreaSafe(Object claim) {
        if (claim == null) return 0;
        try {
            Method getArea = claim.getClass().getMethod("getArea");
            Object val = getArea.invoke(claim);
            if (val instanceof Number) return ((Number) val).intValue();
        } catch (ReflectiveOperationException ignored) {}
        try {
            Method getLesser = claim.getClass().getMethod("getLesserBoundaryCorner");
            Method getGreater = claim.getClass().getMethod("getGreaterBoundaryCorner");
            Object lesser = getLesser.invoke(claim);
            Object greater = getGreater.invoke(claim);
            Method getBlockX = lesser.getClass().getMethod("getBlockX");
            Method getBlockZ = lesser.getClass().getMethod("getBlockZ");
            int x1 = (Integer) getBlockX.invoke(lesser);
            int z1 = (Integer) getBlockZ.invoke(lesser);
            int x2 = (Integer) getBlockX.invoke(greater);
            int z2 = (Integer) getBlockZ.invoke(greater);
            return Math.abs((x2 - x1 + 1) * (z2 - z1 + 1));
        } catch (ReflectiveOperationException ignored) {}
        return 0;
    }

    private void ensureInit() {
        if (gpInstance != null && dataStore != null && gpClass != null) return;
        // Try both CamelCase and lowercase packages for compatibility across forks
        String[] gpCandidates = new String[]{
                "me.ryanhamshire.GriefPrevention.GriefPrevention",
                "me.ryanhamshire.griefprevention.GriefPrevention"
        };
        String[] claimCandidates = new String[]{
                "me.ryanhamshire.GriefPrevention.Claim",
                "me.ryanhamshire.griefprevention.Claim"
        };
        if (gpClass == null) {
            for (String cname : gpCandidates) {
                try {
                    gpClass = Class.forName(cname);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
        }
        if (claimClass == null) {
            for (String cname : claimCandidates) {
                try {
                    claimClass = Class.forName(cname);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
        }
        try {
            if (gpClass != null) {
                gpInstance = gpClass.getField("instance").get(null);
                dataStore = gpClass.getField("dataStore").get(gpInstance);
            }
        } catch (ReflectiveOperationException ignored) {
            gpInstance = null;
            dataStore = null;
        }
    }

    public boolean isAvailable() {
        ensureInit();
        boolean available = Bukkit.getPluginManager().getPlugin("GriefPrevention") != null
                && gpClass != null
                && gpInstance != null
                && dataStore != null;
        if (!available) {
            long now = System.currentTimeMillis();
            if (now - lastAvailWarn > 30000) { // warn at most every 30s
                lastAvailWarn = now;
                try {
                    Bukkit.getLogger().warning("[GPExpansion] GP not available: " + availabilityDiag());
                } catch (Throwable ignored) {}
            }
        }
        return available;
    }

    public String availabilityDiag() {
        String pluginPresent = String.valueOf(Bukkit.getPluginManager().getPlugin("GriefPrevention") != null);
        String gpCls = gpClass != null ? gpClass.getName() : "null";
        String gpInst = gpInstance != null ? gpInstance.getClass().getName() : "null";
        String ds = dataStore != null ? dataStore.getClass().getName() : "null";
        return "plugin=" + pluginPresent + ", gpClass=" + gpCls + ", gpInstance=" + gpInst + ", dataStore=" + ds;
    }

    /**
     * Check whether a claim contains a specific X/Z column (ignore Y bounds).
     */
    public boolean claimContains(Object claim, World world, int x, int y, int z) {
        if (!isAvailable() || claim == null || world == null) {
            return false;
        }

        Location probe = new Location(world, x + 0.5D, y, z + 0.5D);
        try {
            for (Method method : claim.getClass().getMethods()) {
                if (!method.getName().equals("contains")) continue;
                Class<?>[] params = method.getParameterTypes();

                Object result = null;
                if (params.length == 3
                        && Location.class.isAssignableFrom(params[0])
                        && params[1] == boolean.class
                        && params[2] == boolean.class) {
                    result = method.invoke(claim, probe, true, false);
                } else if (params.length == 2
                        && Location.class.isAssignableFrom(params[0])
                        && params[1] == boolean.class) {
                    result = method.invoke(claim, probe, true);
                } else if (params.length == 1
                        && Location.class.isAssignableFrom(params[0])) {
                    result = method.invoke(claim, probe);
                }

                if (result instanceof Boolean b) {
                    return b;
                }
            }
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
        }

        // Fallback: compare claim identity from lookup API.
        Object lookedUp = getClaimAt(probe, null).orElse(null);
        if (lookedUp == null) {
            return false;
        }
        String expectedId = getClaimId(claim).orElse(null);
        String actualId = getClaimId(lookedUp).orElse(null);
        return expectedId != null && expectedId.equals(actualId);
    }

    public int getClaimCoverageInCell(
            Object claim,
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        if (!isAvailable() || claim == null || world == null) {
            return 0;
        }

        int lowX = Math.min(minX, maxX);
        int highX = Math.max(minX, maxX);
        int lowZ = Math.min(minZ, maxZ);
        int highZ = Math.max(minZ, maxZ);

        Object polygon = resolveClaimBoundaryPolygon(claim);
        if (polygon != null) {
            try {
                return countPolygonCoverageInCell(polygon, lowX, highX, lowZ, highZ);
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        ClaimCorners corners = getClaimCorners(claim).orElse(null);
        if (corners == null) {
            return 0;
        }

        if (!isShapedClaim(claim)) {
            int overlapMinX = Math.max(lowX, Math.min(corners.x1, corners.x2));
            int overlapMaxX = Math.min(highX, Math.max(corners.x1, corners.x2));
            int overlapMinZ = Math.max(lowZ, Math.min(corners.z1, corners.z2));
            int overlapMaxZ = Math.min(highZ, Math.max(corners.z1, corners.z2));
            if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
                return 0;
            }
            return (overlapMaxX - overlapMinX + 1) * (overlapMaxZ - overlapMinZ + 1);
        }

        int sampleY = world.getMinHeight() + 1;
        int covered = 0;
        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                if (claimContains(claim, world, x, sampleY, z)) {
                    covered++;
                }
            }
        }
        return covered;
    }

    public int getCellArea(int minX, int maxX, int minZ, int maxZ) {
        int width = Math.abs(maxX - minX) + 1;
        int depth = Math.abs(maxZ - minZ) + 1;
        return width * depth;
    }

    /**
     * Re-show GP's native claim boundary visualization for the player.
     * Useful after map/GUI edits so the player immediately sees updated geometry.
     */
    public boolean refreshClaimVisualization(Player player, Object claim) {
        if (!isAvailable() || player == null || claim == null) {
            return false;
        }

        try {
            ClassLoader loader = claim.getClass().getClassLoader();
            Class<?> typeClass = loadFirstClass(loader,
                    "com.griefprevention.visualization.VisualizationType",
                    "me.ryanhamshire.GriefPrevention.VisualizationType",
                    "me.ryanhamshire.griefprevention.VisualizationType");
            Class<?> visualizationClass = loadFirstClass(loader,
                    "com.griefprevention.visualization.BoundaryVisualization",
                    "me.ryanhamshire.GriefPrevention.BoundaryVisualization",
                    "me.ryanhamshire.griefprevention.BoundaryVisualization");
            if (typeClass == null || visualizationClass == null) {
                return false;
            }

            Object visualizationType = resolveVisualizationType(typeClass, claim);
            if (visualizationType == null) {
                return false;
            }

            Method visualize = findVisualizationMethod(visualizationClass, claim.getClass(), typeClass);
            if (visualize == null) {
                return false;
            }

            visualize.invoke(null, player, claim, visualizationType);
            return true;
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return false;
        }
    }

    public Optional<Object> getClaimAt(Location location) {
        return getClaimAt(location, null);
    }

    /**
     * Resolve the most representative claim within a map cell rectangle by sampling
     * multiple points across the cell (instead of only center point lookups).
     */
    public Optional<Object> getDominantClaimInCell(
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Player player
    ) {
        if (!isAvailable() || world == null) {
            return Optional.empty();
        }

        int lowX = Math.min(minX, maxX);
        int highX = Math.max(minX, maxX);
        int lowZ = Math.min(minZ, maxZ);
        int highZ = Math.max(minZ, maxZ);
        int width = highX - lowX + 1;
        int depth = highZ - lowZ + 1;
        int stepX = width <= 20 ? 1 : Math.max(1, width / 10);
        int stepZ = depth <= 20 ? 1 : Math.max(1, depth / 10);
        int sampleY = world.getMinHeight() + 1;

        Set<Long> samplePoints = new LinkedHashSet<>();
        for (int x = lowX; x <= highX; x += stepX) {
            for (int z = lowZ; z <= highZ; z += stepZ) {
                samplePoints.add(packXZ(x, z));
            }
        }
        // Ensure right/bottom boundaries and center are always sampled.
        for (int x = lowX; x <= highX; x += stepX) {
            samplePoints.add(packXZ(x, highZ));
        }
        for (int z = lowZ; z <= highZ; z += stepZ) {
            samplePoints.add(packXZ(highX, z));
        }
        samplePoints.add(packXZ((lowX + highX) / 2, (lowZ + highZ) / 2));
        samplePoints.add(packXZ(lowX, lowZ));
        samplePoints.add(packXZ(lowX, highZ));
        samplePoints.add(packXZ(highX, lowZ));
        samplePoints.add(packXZ(highX, highZ));

        record ClaimHit(Object claim, int hits, int area) {}
        Map<String, ClaimHit> hitMap = new LinkedHashMap<>();
        for (long packed : samplePoints) {
            int x = unpackX(packed);
            int z = unpackZ(packed);
            Location probe = new Location(world, x + 0.5, sampleY, z + 0.5);
            Object claim = getClaimAt(probe, player).orElse(null);
            if (claim == null) continue;

            String key = getClaimId(claim).orElse("identity:" + System.identityHashCode(claim));
            ClaimHit current = hitMap.get(key);
            int area = getClaimAreaSafe(claim);
            if (current == null) {
                hitMap.put(key, new ClaimHit(claim, 1, area));
            } else {
                hitMap.put(key, new ClaimHit(current.claim(), current.hits() + 1, current.area()));
            }
        }

        if (hitMap.isEmpty()) {
            return Optional.empty();
        }

        ClaimHit best = null;
        for (ClaimHit hit : hitMap.values()) {
            if (best == null) {
                best = hit;
                continue;
            }

            if (hit.hits() > best.hits()) {
                best = hit;
                continue;
            }
            if (hit.hits() == best.hits() && hit.area() > 0 && best.area() > 0 && hit.area() < best.area()) {
                best = hit;
            }
        }

        if (best == null || samplePoints.isEmpty()) {
            return Optional.empty();
        }

        double coverage = best.hits() / (double) samplePoints.size();
        if (coverage < DOMINANT_CELL_COVERAGE_THRESHOLD) {
            return Optional.empty();
        }

        return Optional.of(best.claim());
    }

    public Optional<Object> getClaimAt(Location location, Player player) {
        if (!isAvailable()) {
            if (DEBUG) {
                try { Bukkit.getLogger().warning("[GPExpansion][debug] getClaimAt aborted: GP not available: " + availabilityDiag()); } catch (Throwable ignored) {}
            }
            return Optional.empty();
        }
        try {
            Method target = resolveCachedGetClaimAtMethod();
            if (target == null || cachedGetClaimAtMode == null) return Optional.empty();

            Object thirdArg = switch (cachedGetClaimAtMode) {
                case THREE_ARG_PLAYER -> player;
                case THREE_ARG_PLAYER_DATA -> player != null ? resolvePlayerData(player.getUniqueId()) : null;
                case THREE_ARG_CACHED_CLAIM -> null;
                default -> null;
            };

            // Try ignoreHeight = true first, then false
            if (DEBUG) {
                try {
                    // Debug logging removed
                } catch (Throwable ignored) {}
            }
            Object claim;
            if (cachedGetClaimAtMode == GetClaimAtMode.FOUR_ARG) {
                // Signature: (Location, boolean ignoreHeight, boolean ignoreSubclaims, Claim cached)
                claim = target.invoke(dataStore, location, true, false, null);
            } else if (cachedGetClaimAtMode != GetClaimAtMode.TWO_ARG) {
                claim = target.invoke(dataStore, location, true, thirdArg);
            } else {
                claim = target.invoke(dataStore, location, true);
            }
            if (claim == null) {
                if (cachedGetClaimAtMode == GetClaimAtMode.FOUR_ARG) {
                    claim = target.invoke(dataStore, location, false, false, null);
                } else if (cachedGetClaimAtMode != GetClaimAtMode.TWO_ARG) {
                    claim = target.invoke(dataStore, location, false, thirdArg);
                } else {
                    claim = target.invoke(dataStore, location, false);
                }
            }
            if (DEBUG) {
                try {
                    // Debug logging removed
                } catch (Throwable ignored) {}
            }
            if (claim == null) {
                // Fallback: brute-force scan with 3D-aware selection
                if (DEBUG) {
                    try {
                        // Debug logging removed
                    } catch (Throwable ignored) {}
                }
                claim = bruteForceFindClaim(location, true);
                if (claim == null) {
                    claim = bruteForceFindClaim(location, false);
                }
                if (DEBUG) {
                    try {
                        // Debug logging removed
                    } catch (Throwable ignored) {}
                }
            }
            return Optional.ofNullable(claim);
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    private Method resolveCachedGetClaimAtMethod() {
        if (cachedGetClaimAtResolved) {
            return cachedGetClaimAtMethod;
        }

        synchronized (this) {
            if (cachedGetClaimAtResolved) {
                return cachedGetClaimAtMethod;
            }

            Method resolved = null;
            GetClaimAtMode resolvedMode = null;

            for (Method method : dataStore.getClass().getMethods()) {
                if (!method.getName().equals("getClaimAt")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 4
                        && Location.class.isAssignableFrom(params[0])
                        && params[1] == boolean.class
                        && params[2] == boolean.class) {
                    resolved = method;
                    resolvedMode = GetClaimAtMode.FOUR_ARG;
                    break;
                }
                if (params.length == 3
                        && Location.class.isAssignableFrom(params[0])
                        && params[1] == boolean.class) {
                    resolved = method;
                    Class<?> third = params[2];
                    if (Player.class.isAssignableFrom(third)) {
                        resolvedMode = GetClaimAtMode.THREE_ARG_PLAYER;
                    } else if (third.getSimpleName().equals("PlayerData") || third.getName().endsWith(".PlayerData")) {
                        resolvedMode = GetClaimAtMode.THREE_ARG_PLAYER_DATA;
                    } else {
                        resolvedMode = GetClaimAtMode.THREE_ARG_CACHED_CLAIM;
                    }
                    continue;
                }
                if (resolved == null
                        && params.length == 2
                        && Location.class.isAssignableFrom(params[0])
                        && params[1] == boolean.class) {
                    resolved = method;
                    resolvedMode = GetClaimAtMode.TWO_ARG;
                }
            }

            if (resolved == null && claimClass != null) {
                try {
                    resolved = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
                    resolvedMode = GetClaimAtMode.THREE_ARG_CACHED_CLAIM;
                } catch (NoSuchMethodException ignored) {}
            }

            cachedGetClaimAtMethod = resolved;
            cachedGetClaimAtMode = resolvedMode;
            cachedGetClaimAtResolved = true;
            return cachedGetClaimAtMethod;
        }
    }

    private Class<?> loadFirstClass(ClassLoader loader, String... classNames) {
        if (loader == null) {
            return null;
        }

        for (String className : classNames) {
            try {
                return loader.loadClass(className);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private Object resolveVisualizationType(Class<?> typeClass, Object claim) {
        if (!typeClass.isEnum()) {
            return null;
        }

        String primary = isAdminClaim(claim) ? "ADMIN_CLAIM" : "CLAIM";
        Object resolved = enumConstant(typeClass, primary);
        if (resolved != null) {
            return resolved;
        }

        return enumConstant(typeClass, "CLAIM");
    }

    private Object enumConstant(Class<?> enumClass, String name) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Enum> cast = (Class<? extends Enum>) enumClass.asSubclass(Enum.class);
            return Enum.valueOf(cast, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Method findVisualizationMethod(Class<?> visualizationClass, Class<?> claimRuntimeClass, Class<?> typeClass) {
        for (Method method : visualizationClass.getMethods()) {
            if (!method.getName().equals("visualizeClaim")) continue;
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) continue;
            if (!Player.class.isAssignableFrom(params[0])) continue;
            if (!params[1].isAssignableFrom(claimRuntimeClass)) continue;
            if (!params[2].isAssignableFrom(typeClass)) continue;
            return method;
        }
        return null;
    }

    private Object bruteForceFindClaim(Location location, boolean ignoreHeight) {
        try {
            Method getClaims = dataStore.getClass().getMethod("getClaims");
            Object raw = getClaims.invoke(dataStore);
            if (raw == null) return null;
            java.util.List<Object> claims = new java.util.ArrayList<>();
            if (raw instanceof java.util.Map<?, ?>) {
                claims.addAll(((java.util.Map<?, ?>) raw).values());
            } else if (raw instanceof java.util.Collection<?>) {
                claims.addAll((java.util.Collection<?>) raw);
            } else if (raw instanceof Iterable<?>) {
                for (Object c : (Iterable<?>) raw) claims.add(c);
            } else if (raw.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(raw);
                for (int i = 0; i < len; i++) claims.add(java.lang.reflect.Array.get(raw, i));
            }
            if (claims.isEmpty()) return null;

            Object smallestClaim = null;
            Object smallest3DClaim = null;

            Method contains = null;
            Method is3D = null;
            Method containsY = null;
            Method getGreater = null;
            Method getLesser = null;
            Method getArea = null;
            Method getBlockY = null;

            for (Object claim : claims) {
                Class<?> cc = claim.getClass();
                if (contains == null) {
                    contains = cc.getMethod("contains", Location.class, boolean.class, boolean.class);
                }
                if (is3D == null) {
                    try { is3D = cc.getMethod("is3D"); } catch (NoSuchMethodException ignored) {}
                }
                if (containsY == null) {
                    try { containsY = cc.getMethod("containsY", int.class); } catch (NoSuchMethodException ignored) {}
                }
                if (getGreater == null || getLesser == null) {
                    try {
                        getGreater = cc.getMethod("getGreaterBoundaryCorner");
                        getLesser = cc.getMethod("getLesserBoundaryCorner");
                        Class<?> cornerType = getGreater.getReturnType();
                        getBlockY = cornerType.getMethod("getBlockY");
                    } catch (NoSuchMethodException ignored) {}
                }
                if (getArea == null) {
                    try { getArea = cc.getMethod("getArea"); } catch (NoSuchMethodException ignored) {}
                }

                Boolean containsLoc = (Boolean) contains.invoke(claim, location, ignoreHeight, false);
                if (!containsLoc) continue;

                boolean claimIs3D = false;
                if (is3D != null) {
                    claimIs3D = (Boolean) is3D.invoke(claim);
                }

                if (claimIs3D && containsY != null) {
                    boolean yok = (Boolean) containsY.invoke(claim, location.getBlockY());
                    if (yok) {
                        if (smallest3DClaim == null) {
                            smallest3DClaim = claim;
                        } else {
                            // Compare by Y-range then area
                            int currentYRange = 0;
                            int bestYRange = 0;
                            if (getGreater != null && getLesser != null && getBlockY != null) {
                                Object g = getGreater.invoke(claim);
                                Object l = getLesser.invoke(claim);
                                currentYRange = (Integer) getBlockY.invoke(g) - (Integer) getBlockY.invoke(l);
                                Object gBest = getGreater.invoke(smallest3DClaim);
                                Object lBest = getLesser.invoke(smallest3DClaim);
                                bestYRange = (Integer) getBlockY.invoke(gBest) - (Integer) getBlockY.invoke(lBest);
                            }
                            boolean better = false;
                            if (currentYRange != 0 && bestYRange != 0) {
                                better = currentYRange < bestYRange;
                            }
                            if (!better && getArea != null) {
                                int curArea = (Integer) getArea.invoke(claim);
                                int bestArea = (Integer) getArea.invoke(smallest3DClaim);
                                better = curArea < bestArea;
                            }
                            if (better) smallest3DClaim = claim;
                        }
                        continue;
                    }
                    // fall through to consider as non-3D fallback if Y doesn't match
                }

                // Non-3D or 3D without Y match: choose smallest area
                if (smallestClaim == null) {
                    smallestClaim = claim;
                } else if (getArea != null) {
                    int curArea = (Integer) getArea.invoke(claim);
                    int bestArea = (Integer) getArea.invoke(smallestClaim);
                    if (curArea < bestArea) smallestClaim = claim;
                }
            }

            return smallest3DClaim != null ? smallest3DClaim : smallestClaim;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public List<Object> getClaimsFor(Player player) {
        List<Object> results = new ArrayList<>();
        if (!isAvailable()) return results;
        try {
            Method getClaims = dataStore.getClass().getMethod("getClaims");
            Object raw = getClaims.invoke(dataStore);

            java.util.function.Consumer<Object> consider = claim -> {
                try {
                    Method getOwnerID = claim.getClass().getMethod("getOwnerID");
                    Object owner = getOwnerID.invoke(claim);
                    if (owner != null && owner.equals(player.getUniqueId())) {
                        results.add(claim);
                    }
                } catch (ReflectiveOperationException ignored2) {}
            };

            if (raw == null) return results;
            if (raw instanceof java.util.Map<?, ?>) {
                for (Object claim : ((java.util.Map<?, ?>) raw).values()) consider.accept(claim);
            } else if (raw instanceof java.util.Collection<?>) {
                for (Object claim : (java.util.Collection<?>) raw) consider.accept(claim);
            } else if (raw instanceof Iterable<?>) {
                for (Object claim : (Iterable<?>) raw) consider.accept(claim);
            } else if (raw.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(raw);
                for (int i = 0; i < len; i++) {
                    Object claim = java.lang.reflect.Array.get(raw, i);
                    consider.accept(claim);
                }
            }
        } catch (ReflectiveOperationException ignored) {}
        return results;
    }

    public Optional<String> getClaimId(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            // Newer GP forks expose a numeric id
            Object id = claim.getClass().getMethod("getID").invoke(claim);
            return Optional.ofNullable(id).map(Object::toString);
        } catch (ReflectiveOperationException ignored) {
            // Fallback: synthesize from owner + corners if IDs aren't available
            try {
                Object owner = claim.getClass().getMethod("getOwnerID").invoke(claim);
                Object lesser = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
                Object greater = claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
                String synthetic = owner + ":" + lesser.getClass().getMethod("toVector").invoke(lesser) + ":" + greater.getClass().getMethod("toVector").invoke(greater);
                return Optional.of(synthetic);
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
    }

    public Optional<Object> findClaimById(String id) {
        if (!isAvailable() || id == null) return Optional.empty();
        try {
            Method getClaims = dataStore.getClass().getMethod("getClaims");
            Object raw = getClaims.invoke(dataStore);
            java.util.List<Object> claims = new java.util.ArrayList<>();
            if (raw instanceof java.util.Map<?, ?>) claims.addAll(((java.util.Map<?, ?>) raw).values());
            else if (raw instanceof java.util.Collection<?>) claims.addAll((java.util.Collection<?>) raw);
            else if (raw instanceof Iterable<?>) for (Object c : (Iterable<?>) raw) claims.add(c);
            else if (raw != null && raw.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(raw);
                for (int i = 0; i < len; i++) claims.add(java.lang.reflect.Array.get(raw, i));
            }
            // DFS over top-level and all subclaims
            java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>(claims);
            while (!stack.isEmpty()) {
                Object c = stack.pop();
                try {
                    Object cid = c.getClass().getMethod("getID").invoke(c);
                    if (cid != null && id.equals(cid.toString())) return Optional.of(c);
                } catch (ReflectiveOperationException ignoredInner) {}

                // push children
                for (Object child : getChildrenOfClaim(c)) {
                    if (child != null) stack.push(child);
                }
            }
        } catch (ReflectiveOperationException ignored) {}
        return Optional.empty();
    }

    private java.util.Collection<Object> getChildrenOfClaim(Object claim) {
        if (claim == null) return java.util.Collections.emptyList();
        
        // First try the new GP3D approach with Children
        String[] newMethodNames = {"getChildren", "getChildClaims"};
        for (String methodName : newMethodNames) {
            try {
                Method m = claim.getClass().getMethod(methodName);
                Object res = m.invoke(claim);
                if (res instanceof java.util.Collection<?>) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Object> collection = (java.util.Collection<Object>) res;
                    return collection;
                }
                if (res != null && res.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(res);
                    java.util.List<Object> list = new java.util.ArrayList<>(len);
                    for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(res, i));
                    return list;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        
        // Try field access for Children
        String[] newFieldNames = {"children", "childClaims"};
        for (String fieldName : newFieldNames) {
            try {
                Field f = claim.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object res = f.get(claim);
                if (res instanceof java.util.Collection<?>) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Object> collection = (java.util.Collection<Object>) res;
                    return collection;
                }
                if (res != null && res.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(res);
                    java.util.List<Object> list = new java.util.ArrayList<>(len);
                    for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(res, i));
                    return list;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        
        // Fallback to old GP approach
        String[] oldMethodNames = {"getSubclaims", "getSubClaims"};
        for (String methodName : oldMethodNames) {
            try {
                Method m = claim.getClass().getMethod(methodName);
                Object res = m.invoke(claim);
                if (res instanceof java.util.Collection<?>) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Object> collection = (java.util.Collection<Object>) res;
                    return collection;
                }
                if (res != null && res.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(res);
                    java.util.List<Object> list = new java.util.ArrayList<>(len);
                    for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(res, i));
                    return list;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        
        // Try old field names as final fallback
        String[] oldFieldNames = {"subclaims", "subClaims"};
        for (String fieldName : oldFieldNames) {
            try {
                Field f = claim.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object res = f.get(claim);
                if (res instanceof java.util.Collection<?>) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Object> collection = (java.util.Collection<Object>) res;
                    return collection;
                }
                if (res != null && res.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(res);
                    java.util.List<Object> list = new java.util.ArrayList<>(len);
                    for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(res, i));
                    return list;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
        
        return java.util.Collections.emptyList();
    }

    public Optional<Location> getClaimCenter(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            Location lesser = (Location) claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Location greater = (Location) claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
            if (lesser == null || greater == null || lesser.getWorld() == null) return Optional.empty();
            int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
            int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
            int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
            int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());
            int cx = (minX + maxX) / 2;
            int cz = (minZ + maxZ) / 2;
            int y = lesser.getWorld().getHighestBlockYAt(cx, cz) + 1;
            return Optional.of(new Location(lesser.getWorld(), cx + 0.5, y, cz + 0.5));
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    /**
     * Get the claim center XZ coordinates without accessing chunk data.
     * Returns a location with Y=0; caller must get the correct Y on the region thread.
     */
    public Optional<Location> getClaimCenterXZ(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            Location lesser = (Location) claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Location greater = (Location) claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
            if (lesser == null || greater == null || lesser.getWorld() == null) return Optional.empty();
            int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
            int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
            int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
            int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());
            int cx = (minX + maxX) / 2;
            int cz = (minZ + maxZ) / 2;
            return Optional.of(new Location(lesser.getWorld(), cx + 0.5, 0, cz + 0.5));
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    /**
     * Get the search radius for safe teleportation based on claim dimensions.
     * Returns half the smaller of width/depth, capped at a reasonable max.
     */
    public int getClaimSearchRadius(Object claim) {
        if (claim == null) return 3;
        try {
            Location lesser = (Location) claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
            Location greater = (Location) claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
            if (lesser == null || greater == null) return 3;
            
            int width = Math.abs(greater.getBlockX() - lesser.getBlockX());
            int depth = Math.abs(greater.getBlockZ() - lesser.getBlockZ());
            int minDimension = Math.min(width, depth);
            
            // Return half the claim size, capped at 16 (reasonable for very large claims)
            return Math.min(minDimension / 2, 16);
        } catch (ReflectiveOperationException e) {
            return 3;
        }
    }

    /**
     * Get GriefPrevention's safe teleport location for a claim.
     */
    public Optional<Location> getSafeTeleportLocation(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            Method m = claim.getClass().getMethod("getSafeTeleportLocation");
            Object loc = m.invoke(claim);
            if (loc instanceof Location) return Optional.of((Location) loc);
        } catch (ReflectiveOperationException ignored) {}
        return Optional.empty();
    }

    public boolean trust(Player executor, String target, Object claim) {
        if (!isAvailable() || claim == null || target == null) return false;

        try {
            // Get the target player's UUID
            UUID targetUUID = null;
            org.bukkit.entity.Player targetPlayer = Bukkit.getPlayer(target);
            if (targetPlayer != null) {
                targetUUID = targetPlayer.getUniqueId();
            } else {
                // Try to resolve offline player
                try {
                    targetUUID = Bukkit.getOfflinePlayer(target).getUniqueId();
                } catch (Exception e) {
                    return false;
                }
            }

            if (targetUUID == null) return false;

            // Try to find trust management methods in the claim
            Class<?> claimClass = claim.getClass();

            // Try to add build trust (most common)
            try {
                Method addBuildTrust = claimClass.getMethod("addBuildTrust", UUID.class);
                addBuildTrust.invoke(claim, targetUUID);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Try alternative method names
            } catch (ReflectiveOperationException e) {
                return false;
            }

            // Try setBuildTrust with a collection
            try {
                Method getBuildTrust = claimClass.getMethod("getBuildTrust");
                Object buildTrust = getBuildTrust.invoke(claim);

                if (buildTrust instanceof java.util.Collection) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<UUID> trustList = (java.util.Collection<UUID>) buildTrust;

                    // Create a new list with the target added
                    java.util.List<UUID> newTrustList = new java.util.ArrayList<>(trustList);
                    if (!newTrustList.contains(targetUUID)) {
                        newTrustList.add(targetUUID);

                        // Try to set the trust list back
                        try {
                            Method setBuildTrust = claimClass.getMethod("setBuildTrust", java.util.Collection.class);
                            setBuildTrust.invoke(claim, newTrustList);
                            return true;
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }

            // Try GP3D fork's setPermission(String, ClaimPermission) method
            try {
                // Find ClaimPermission enum using the claim's classloader
                Class<?> claimPermClass = null;
                ClassLoader gpClassLoader = claimClass.getClassLoader();
                String[] permCandidates = new String[]{
                    "me.ryanhamshire.GriefPrevention.ClaimPermission",
                    "me.ryanhamshire.griefprevention.ClaimPermission"
                };
                for (String cname : permCandidates) {
                    try {
                        claimPermClass = gpClassLoader.loadClass(cname);
                        Bukkit.getLogger().info("[GPBridge] Found ClaimPermission class: " + cname);
                        break;
                    } catch (ClassNotFoundException ignored) {}
                }
                
                if (claimPermClass != null) {
                    // Get ClaimPermission.Build enum constant
                    Object buildPerm = null;
                    for (Object enumConst : claimPermClass.getEnumConstants()) {
                        if (enumConst.toString().equalsIgnoreCase("Build")) {
                            buildPerm = enumConst;
                            break;
                        }
                    }
                    
                    if (buildPerm != null) {
                        // Call setPermission(String playerID, ClaimPermission permissionLevel)
                        Bukkit.getLogger().info("[GPBridge] Attempting setPermission(" + targetUUID.toString() + ", " + buildPerm + ") on claim class: " + claimClass.getName());
                        Method setPermission = claimClass.getMethod("setPermission", String.class, claimPermClass);
                        setPermission.invoke(claim, targetUUID.toString(), buildPerm);
                        Bukkit.getLogger().info("[GPBridge] Successfully granted Build permission via setPermission for " + target);
                        return true;
                    } else {
                        Bukkit.getLogger().warning("[GPBridge] Could not find Build enum constant in ClaimPermission");
                    }
                } else {
                    Bukkit.getLogger().warning("[GPBridge] Could not find ClaimPermission class");
                }
            } catch (ReflectiveOperationException e) {
                Bukkit.getLogger().warning("[GPBridge] setPermission failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (DEBUG) e.printStackTrace();
            }

        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }

        return false;
    }

    /**
     * Remove all trust permissions for a player from a specific claim
     */
    public boolean untrust(String target, Object claim) {
        if (!isAvailable() || claim == null || target == null) return false;

        try {
            // Get the target player's UUID
            UUID targetUUID = null;
            org.bukkit.entity.Player targetPlayer = Bukkit.getPlayer(target);
            if (targetPlayer != null) {
                targetUUID = targetPlayer.getUniqueId();
            } else {
                try {
                    targetUUID = Bukkit.getOfflinePlayer(target).getUniqueId();
                } catch (Exception e) {
                    return false;
                }
            }

            if (targetUUID == null) return false;

            Class<?> claimClass = claim.getClass();

            // Try GP3D's dropPermission(String playerID) method
            try {
                Method dropPermission = claimClass.getMethod("dropPermission", String.class);
                dropPermission.invoke(claim, targetUUID.toString());
                if (DEBUG) Bukkit.getLogger().info("[GPBridge] Successfully removed trust via dropPermission for " + target);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                if (DEBUG) Bukkit.getLogger().warning("[GPBridge] dropPermission failed: " + e.getMessage());
            }

            // Try removing from trust collections directly
            String[] trustMethods = {"getBuildTrust", "getContainerTrust", "getAccessTrust", "getManagerTrust"};
            boolean removed = false;
            for (String methodName : trustMethods) {
                try {
                    Method getTrust = claimClass.getMethod(methodName);
                    Object trustList = getTrust.invoke(claim);
                    if (trustList instanceof java.util.Collection) {
                        @SuppressWarnings("unchecked")
                        java.util.Collection<UUID> collection = (java.util.Collection<UUID>) trustList;
                        if (collection.remove(targetUUID)) {
                            removed = true;
                        }
                    }
                } catch (ReflectiveOperationException ignored) {}
            }
            
            if (removed) {
                if (DEBUG) Bukkit.getLogger().info("[GPBridge] Successfully removed trust from collections for " + target);
                return true;
            }

        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }

        return false;
    }

    public boolean grantInventoryTrust(Player executor, String target, Object claim) {
        if (!isAvailable() || claim == null || target == null) return false;

        try {
            // Get the target player's UUID
            UUID targetUUID = null;
            org.bukkit.entity.Player targetPlayer = Bukkit.getPlayer(target);
            if (targetPlayer != null) {
                targetUUID = targetPlayer.getUniqueId();
            } else {
                // Try to resolve offline player
                try {
                    targetUUID = Bukkit.getOfflinePlayer(target).getUniqueId();
                } catch (Exception e) {
                    return false;
                }
            }

            if (targetUUID == null) return false;

            // Try to find trust management methods in the claim
            Class<?> claimClass = claim.getClass();

            // Try to add inventory trust directly
            try {
                Method addInventoryTrust = claimClass.getMethod("addInventoryTrust", UUID.class);
                addInventoryTrust.invoke(claim, targetUUID);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Try alternative method names
            } catch (ReflectiveOperationException e) {
                return false;
            }

            // Try addContainerTrust (alternative name)
            try {
                Method addContainerTrust = claimClass.getMethod("addContainerTrust", UUID.class);
                addContainerTrust.invoke(claim, targetUUID);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                return false;
            }

            // Try setInventoryTrust with a collection
            try {
                Method getInventoryTrust = claimClass.getMethod("getInventoryTrust");
                Object inventoryTrust = getInventoryTrust.invoke(claim);

                if (inventoryTrust instanceof java.util.Collection) {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<UUID> trustList = (java.util.Collection<UUID>) inventoryTrust;

                    // Create a new list with the target added
                    java.util.List<UUID> newTrustList = new java.util.ArrayList<>(trustList);
                    if (!newTrustList.contains(targetUUID)) {
                        newTrustList.add(targetUUID);

                        // Try to set the trust list back
                        try {
                            Method setInventoryTrust = claimClass.getMethod("setInventoryTrust", java.util.Collection.class);
                            setInventoryTrust.invoke(claim, newTrustList);
                            return true;
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }

            // Try GP3D fork's setPermission(String, ClaimPermission) method with Inventory permission
            try {
                // Find ClaimPermission enum using the claim's classloader
                Class<?> claimPermClass = null;
                ClassLoader gpClassLoader = claimClass.getClassLoader();
                String[] permCandidates = new String[]{
                    "me.ryanhamshire.GriefPrevention.ClaimPermission",
                    "me.ryanhamshire.griefprevention.ClaimPermission"
                };
                for (String cname : permCandidates) {
                    try {
                        claimPermClass = gpClassLoader.loadClass(cname);
                        Bukkit.getLogger().info("[GPBridge] Found ClaimPermission class: " + cname);
                        break;
                    } catch (ClassNotFoundException ignored) {}
                }
                
                if (claimPermClass != null) {
                    // Get ClaimPermission.Inventory enum constant
                    Object inventoryPerm = null;
                    for (Object enumConst : claimPermClass.getEnumConstants()) {
                        if (enumConst.toString().equalsIgnoreCase("Inventory")) {
                            inventoryPerm = enumConst;
                            break;
                        }
                    }
                    
                    if (inventoryPerm != null) {
                        // Call setPermission(String playerID, ClaimPermission permissionLevel)
                        Bukkit.getLogger().info("[GPBridge] Attempting setPermission(" + targetUUID.toString() + ", " + inventoryPerm + ") on claim class: " + claimClass.getName());
                        Method setPermission = claimClass.getMethod("setPermission", String.class, claimPermClass);
                        setPermission.invoke(claim, targetUUID.toString(), inventoryPerm);
                        Bukkit.getLogger().info("[GPBridge] Successfully granted Inventory permission via setPermission for " + target);
                        return true;
                    } else {
                        Bukkit.getLogger().warning("[GPBridge] Could not find Inventory enum constant in ClaimPermission");
                    }
                } else {
                    Bukkit.getLogger().warning("[GPBridge] Could not find ClaimPermission class");
                }
            } catch (ReflectiveOperationException e) {
                Bukkit.getLogger().warning("[GPBridge] setPermission failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (DEBUG) e.printStackTrace();
            }

        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }

        return false;
    }

    /**
     * Grant container/inventory trust to "public" on the claim (everyone can use containers).
     * Used for real mailbox protocol so non-owners can deposit via the real chest.
     */
    public boolean containerTrustPublic(Object claim) {
        if (!isAvailable() || claim == null) return false;
        try {
            Class<?> claimClass = claim.getClass();
            // Try setPermission("public", ClaimPermission.Inventory) - GP commands use "public" as string
            ClassLoader gpClassLoader = claimClass.getClassLoader();
            String[] permCandidates = new String[]{
                "me.ryanhamshire.GriefPrevention.ClaimPermission",
                "me.ryanhamshire.griefprevention.ClaimPermission"
            };
            for (String cname : permCandidates) {
                try {
                    Class<?> claimPermClass = gpClassLoader.loadClass(cname);
                    Object inventoryPerm = null;
                    for (Object enumConst : claimPermClass.getEnumConstants()) {
                        if (enumConst.toString().equalsIgnoreCase("Inventory")) {
                            inventoryPerm = enumConst;
                            break;
                        }
                    }
                    if (inventoryPerm != null) {
                        Method setPermission = claimClass.getMethod("setPermission", String.class, claimPermClass);
                        setPermission.invoke(claim, "public", inventoryPerm);
                        if (DEBUG) Bukkit.getLogger().info("[GPBridge] containerTrustPublic: setPermission(public, Inventory) succeeded");
                        return true;
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            // Fallback: try grantInventoryTrust with "public" (may resolve to special UUID in some forks)
            if (grantInventoryTrust(null, "public", claim)) {
                if (DEBUG) Bukkit.getLogger().info("[GPBridge] containerTrustPublic: grantInventoryTrust(public) succeeded");
                return true;
            }
        } catch (ReflectiveOperationException e) {
            if (DEBUG) Bukkit.getLogger().info("[GPBridge] containerTrustPublic: " + e.getMessage());
        }
        return false;
    }

    public boolean ban(Player executor, String target, Object claim) {
        // TODO: Implement ban via enter-deny or claim permissions if available
        return false;
    }

    public boolean unban(Player executor, String target, Object claim) {
        // TODO: Implement unban
        return false;
    }

    /** Return a list of all top-level claims from the datastore. */
    public java.util.List<Object> getAllClaims() {
        java.util.List<Object> claims = new java.util.ArrayList<>();
        if (!isAvailable()) return claims;
        try {
            Method getClaims = dataStore.getClass().getMethod("getClaims");
            Object raw = getClaims.invoke(dataStore);
            if (raw == null) return claims;
            if (raw instanceof java.util.Map<?, ?>) claims.addAll(((java.util.Map<?, ?>) raw).values());
            else if (raw instanceof java.util.Collection<?>) claims.addAll((java.util.Collection<?>) raw);
            else if (raw instanceof Iterable<?>) for (Object c : (Iterable<?>) raw) claims.add(c);
            else if (raw.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(raw);
                for (int i = 0; i < len; i++) claims.add(java.lang.reflect.Array.get(raw, i));
            }
        } catch (ReflectiveOperationException ignored) {}
        // Fallback: GP3D uses dataStore.claims field; getClaims() may return wrapper that fails instanceof
        if (claims.isEmpty()) {
            try {
                java.lang.reflect.Field f = dataStore.getClass().getDeclaredField("claims");
                f.setAccessible(true);
                Object claimsField = f.get(dataStore);
                if (claimsField instanceof java.util.Collection<?>) claims.addAll((java.util.Collection<?>) claimsField);
            } catch (ReflectiveOperationException ignored) {}
        }
        return claims;
    }

    /** Admin claims in GP/GP3D have null ownerID; prefer Claim.isAdminClaim() when available. */
    public boolean isAdminClaim(Object claim) {
        if (claim == null) return false;
        try {
            java.lang.reflect.Method m = claim.getClass().getMethod("isAdminClaim");
            Object r = m.invoke(claim);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object owner = claim.getClass().getMethod("getOwnerID").invoke(claim);
            return owner == null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
    
    public boolean isOwner(Object claim, UUID playerId) {
        if (claim == null || playerId == null) return false;
        try {
            Method getOwnerID = claim.getClass().getMethod("getOwnerID");
            UUID ownerId = (UUID) getOwnerID.invoke(claim);
            return playerId.equals(ownerId);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
    
    public Optional<String> getClaimWorld(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            Method getLesser = claim.getClass().getMethod("getLesserBoundaryCorner");
            Object loc = getLesser.invoke(claim);
            Method getWorld = loc.getClass().getMethod("getWorld");
            Object world = getWorld.invoke(loc);
            Method getName = world.getClass().getMethod("getName");
            return Optional.of((String) getName.invoke(world));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean transferClaimOwner(Object claim, UUID newOwnerId) {
        if (!isAvailable() || claim == null || newOwnerId == null) return false;
        try {
            Class<?> claimCls = claim.getClass();

            Method changeOwner = findChangeOwnerMethod(claimCls);
            if (changeOwner != null) {
                changeOwner.invoke(dataStore, claim, newOwnerId);
                return true;
            }

            if (!setClaimOwnerReflectively(claim, claimCls, newOwnerId)) {
                return false;
            }

            persistClaim(claim, claimCls);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private Method findChangeOwnerMethod(Class<?> claimCls) {
        if (dataStore == null) return null;
        Class<?> dsClass = dataStore.getClass();

        try {
            return dsClass.getMethod("changeClaimOwner", claimCls, UUID.class);
        } catch (NoSuchMethodException ignored) { }

        if (claimClass != null && claimClass.isAssignableFrom(claimCls)) {
            try {
                return dsClass.getMethod("changeClaimOwner", claimClass, UUID.class);
            } catch (NoSuchMethodException ignored) { }
        }

        for (Method method : dsClass.getMethods()) {
            if (!method.getName().equals("changeClaimOwner")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0].isAssignableFrom(claimCls) && UUID.class.isAssignableFrom(params[1])) {
                return method;
            }
        }
        return null;
    }

    private boolean setClaimOwnerReflectively(Object claim, Class<?> claimCls, UUID newOwnerId) {
        try {
            Method setOwner = claimCls.getMethod("setOwnerID", UUID.class);
            setOwner.invoke(claim, newOwnerId);
            return true;
        } catch (NoSuchMethodException ignored) {
            // fall back to field access
        } catch (ReflectiveOperationException e) {
            return false;
        }

        try {
            Field ownerField = claimCls.getDeclaredField("ownerID");
            ownerField.setAccessible(true);
            ownerField.set(claim, newOwnerId);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private void persistClaim(Object claim, Class<?> claimCls) {
        if (dataStore == null) return;
        for (Method method : dataStore.getClass().getMethods()) {
            if (!method.getName().equals("saveClaim")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(claimCls)) {
                try {
                    method.invoke(dataStore, claim);
                } catch (ReflectiveOperationException ignored) { }
                return;
            }
        }
    }

    /**
     * Persist claim changes to disk (e.g. after modifying trust).
     * Call this after trust() to ensure the renter's trust is saved.
     */
    public void saveClaim(Object claim) {
        if (claim == null || dataStore == null) return;
        persistClaim(claim, claim.getClass());
    }

    /**
     * Delete a claim or subdivision from GriefPrevention.
     * Used when removing self-mailbox 1x1x1 subdivisions.
     */
    public boolean deleteClaim(Object claim) {
        if (claim == null || dataStore == null) return false;
        try {
            Class<?> claimCls = claim.getClass();
            Method deleteClaim = dataStore.getClass().getMethod("deleteClaim", claimCls);
            deleteClaim.invoke(dataStore, claim);
            return true;
        } catch (NoSuchMethodException e) {
            for (Method m : dataStore.getClass().getMethods()) {
                if (!"deleteClaim".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(claim.getClass())) {
                    try {
                        m.invoke(dataStore, claim);
                        return true;
                    } catch (ReflectiveOperationException ignored) { }
                    break;
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }
    
    public int getClaimArea(Object claim) {
        return getClaimCorners(claim)
            .map(corners -> Math.abs((corners.x2 - corners.x1 + 1) * (corners.z2 - corners.z1 + 1)))
            .orElse(0);
    }

    public enum ResizeDirection {
        NORTH,
        SOUTH,
        EAST,
        WEST,
        UP,
        DOWN
    }

    public enum ResizeFailureReason {
        NONE,
        NOT_AVAILABLE,
        INVALID_INPUT,
        UNSUPPORTED,
        INVALID_OFFSET_RANGE,
        TOO_SMALL,
        NOT_ENOUGH_BLOCKS,
        OUTSIDE_PARENT,
        INNER_SUBDIVISION_TOO_CLOSE,
        SIBLING_OVERLAP,
        WOULD_CLIP_CHILD,
        APPLY_FAILED
    }

    public static final class ResizePreview {
        public final boolean supported;
        public final boolean valid;
        public final ResizeFailureReason failureReason;
        public final int requestedOffset;
        public final int clampedOffset;
        public final int maxExpand;
        public final int maxShrink;
        public final int minWidth;
        public final int minArea;
        public final int remainingClaimBlocks;
        public final int currentWidth;
        public final int currentHeight;
        public final int currentDepth;
        public final int currentArea;
        public final int newWidth;
        public final int newHeight;
        public final int newDepth;
        public final int newArea;
        public final int blockDelta;
        public final ClaimCorners currentCorners;
        public final ClaimCorners newCorners;

        private ResizePreview(
            boolean supported,
            boolean valid,
            ResizeFailureReason failureReason,
            int requestedOffset,
            int clampedOffset,
            int maxExpand,
            int maxShrink,
            int minWidth,
            int minArea,
            int remainingClaimBlocks,
            int currentWidth,
            int currentHeight,
            int currentDepth,
            int currentArea,
            int newWidth,
            int newHeight,
            int newDepth,
            int newArea,
            int blockDelta,
            ClaimCorners currentCorners,
            ClaimCorners newCorners
        ) {
            this.supported = supported;
            this.valid = valid;
            this.failureReason = failureReason;
            this.requestedOffset = requestedOffset;
            this.clampedOffset = clampedOffset;
            this.maxExpand = maxExpand;
            this.maxShrink = maxShrink;
            this.minWidth = minWidth;
            this.minArea = minArea;
            this.remainingClaimBlocks = remainingClaimBlocks;
            this.currentWidth = currentWidth;
            this.currentHeight = currentHeight;
            this.currentDepth = currentDepth;
            this.currentArea = currentArea;
            this.newWidth = newWidth;
            this.newHeight = newHeight;
            this.newDepth = newDepth;
            this.newArea = newArea;
            this.blockDelta = blockDelta;
            this.currentCorners = currentCorners;
            this.newCorners = newCorners;
        }
    }

    public static final class ResizeResult {
        public final boolean success;
        public final ResizeFailureReason failureReason;
        public final ResizePreview preview;
        public final Object claim;

        private ResizeResult(boolean success, ResizeFailureReason failureReason, ResizePreview preview, Object claim) {
            this.success = success;
            this.failureReason = failureReason;
            this.preview = preview;
            this.claim = claim;
        }
    }

    public static final class SegmentEdgeInfo {
        public final int edgeIndex;
        public final ResizeDirection direction;
        public final int axisCoordinate;
        public final int minAlongAxis;
        public final int maxAlongAxis;
        public final boolean horizontal;

        private SegmentEdgeInfo(
                int edgeIndex,
                ResizeDirection direction,
                int axisCoordinate,
                int minAlongAxis,
                int maxAlongAxis,
                boolean horizontal
        ) {
            this.edgeIndex = edgeIndex;
            this.direction = direction;
            this.axisCoordinate = axisCoordinate;
            this.minAlongAxis = minAlongAxis;
            this.maxAlongAxis = maxAlongAxis;
            this.horizontal = horizontal;
        }
    }

    public static final class CreateClaimResult {
        public final boolean supported;
        public final boolean success;
        public final boolean dryRun;
        public final String message;
        public final Object claim;

        private CreateClaimResult(boolean supported, boolean success, boolean dryRun, String message, Object claim) {
            this.supported = supported;
            this.success = success;
            this.dryRun = dryRun;
            this.message = message;
            this.claim = claim;
        }
    }

    private record GridPoint(int x, int z) { }

    public boolean canResizeClaim(Object claim) {
        if (!isAvailable() || claim == null || dataStore == null) return false;
        return findResizeClaimMethod(claim.getClass()) != null;
    }

    /**
     * Shaped top-level 2D claims use a segment-aware path instead of rectangular corner rewrites.
     */
    public boolean usesSegmentAwareResize(Object claim, ResizeDirection direction) {
        if (claim == null || direction == null) return false;
        return isShapedClaim(claim)
                && !is3DClaim(claim)
                && !isSubdivision(claim)
                && isHorizontalDirection(direction);
    }

    /**
     * Resolve the shaped boundary segment that would be edited for a directional push,
     * using a reference location to pick the nearest matching edge run.
     */
    public Optional<SegmentEdgeInfo> resolveSegmentEdge(Object claim, ResizeDirection direction, Location reference) {
        if (!usesSegmentAwareResize(claim, direction)) {
            return Optional.empty();
        }

        SegmentAwareResizeSelection selection = resolveSegmentAwareSelection(claim, direction, reference);
        if (selection == null) {
            return Optional.empty();
        }
        SegmentEdgeInfo info = extractSegmentEdgeInfo(selection.polygon, selection.edgeIndex, direction);
        return Optional.ofNullable(info);
    }

    /**
     * Resolve all oriented boundary edges for a shaped top-level claim. The caller can
     * then select the edge that best matches its own interaction model.
     */
    public List<SegmentEdgeInfo> resolveSegmentEdges(Object claim, ResizeDirection direction) {
        if (!supportsMapSegmentizedShapedEdit(claim, direction)) {
            return List.of();
        }

        Object polygon = resolveClaimBoundaryPolygon(claim);
        if (polygon == null) {
            return List.of();
        }

        boolean wantsHorizontal = direction == ResizeDirection.NORTH || direction == ResizeDirection.SOUTH;
        boolean wantsVertical = direction == ResizeDirection.EAST || direction == ResizeDirection.WEST;
        if (!wantsHorizontal && !wantsVertical) {
            return List.of();
        }

        List<?> edges;
        try {
            Method edgesMethod = polygon.getClass().getMethod("edges");
            Object raw = edgesMethod.invoke(polygon);
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            edges = list;
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return List.of();
        }

        List<SegmentEdgeInfo> infos = new ArrayList<>(edges.size());
        for (int index = 0; index < edges.size(); index++) {
            Object edge = edges.get(index);
            try {
                boolean horizontal = invokeBoolean(edge, "isHorizontal");
                boolean vertical = invokeBoolean(edge, "isVertical");
                if (wantsHorizontal && !horizontal) {
                    continue;
                }
                if (wantsVertical && !vertical) {
                    continue;
                }
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
                continue;
            }

            SegmentEdgeInfo info = extractSegmentEdgeInfo(polygon, index, direction);
            if (info != null) {
                infos.add(info);
            }
        }

        return infos;
    }

    /**
     * Split the targeted shaped boundary edge at the requested along-axis range so a
     * follow-up directional resize moves only that span (nib-style), not the whole side.
     */
    public CreateClaimResult ensureSegmentBoundariesForMapNib(
            Player player,
            Object claim,
            SegmentEdgeInfo edgeInfo,
            int segmentMinAlong,
            int segmentMaxAlong
    ) {
        if (!isAvailable() || dataStore == null || player == null || claim == null || edgeInfo == null) {
            return new CreateClaimResult(false, false, false, "GriefPrevention is not available.", claim);
        }
        if (!supportsMapSegmentizedShapedEdit(claim, edgeInfo.direction)) {
            return new CreateClaimResult(false, false, false, "Claim does not support shaped segment-aware resizing.", claim);
        }

        int minAlong = Math.min(segmentMinAlong, segmentMaxAlong);
        int maxAlong = Math.max(segmentMinAlong, segmentMaxAlong);
        Object polygon = resolveClaimBoundaryPolygon(claim);
        if (polygon == null) {
            return new CreateClaimResult(true, false, false, "Could not resolve claim boundary polygon.", claim);
        }

        try {
            ClassLoader loader = claim.getClass().getClassLoader();
            Class<?> pointClass = loader.loadClass("com.griefprevention.geometry.OrthogonalPoint2i");
            java.lang.reflect.Constructor<?> ctor = pointClass.getDeclaredConstructor(int.class, int.class);
            ctor.setAccessible(true);

            Object pointA;
            Object pointB;
            if (edgeInfo.horizontal) {
                pointA = ctor.newInstance(minAlong, edgeInfo.axisCoordinate);
                pointB = ctor.newInstance(maxAlong, edgeInfo.axisCoordinate);
            } else {
                pointA = ctor.newInstance(edgeInfo.axisCoordinate, minAlong);
                pointB = ctor.newInstance(edgeInfo.axisCoordinate, maxAlong);
            }

            Object updatedPolygon = polygon;
            boolean changed = false;

            Object afterA = insertNodeIfInterior(updatedPolygon, pointA);
            if (afterA != updatedPolygon) {
                updatedPolygon = afterA;
                changed = true;
            }

            Object afterB = insertNodeIfInterior(updatedPolygon, pointB);
            if (afterB != updatedPolygon) {
                updatedPolygon = afterB;
                changed = true;
            }

            if (!changed) {
                return new CreateClaimResult(true, true, false, "Segment boundaries already aligned.", claim);
            }

            Object playerData = resolvePlayerData(player.getUniqueId());
            if (playerData == null) {
                return new CreateClaimResult(false, false, false, "Could not resolve player data for shaped update.", claim);
            }

            Method updateShapedClaim = findUpdateShapedClaimMethod(claim.getClass(), updatedPolygon.getClass(), playerData.getClass());
            if (updateShapedClaim == null) {
                return new CreateClaimResult(false, false, false, "This GP build doesn't expose updateShapedClaim.", claim);
            }

            Object updateResult = updateShapedClaim.invoke(dataStore, player, playerData, claim, updatedPolygon);
            if (!extractResizeSucceeded(updateResult)) {
                String denial = extractDenialMessage(updateResult);
                return new CreateClaimResult(
                        true,
                        false,
                        false,
                        denial == null || denial.isBlank()
                                ? "Could not prepare a boundary segment for this map nib edit."
                                : denial,
                        claim
                );
            }

            Object updatedClaim = extractResultClaim(updateResult);
            if (updatedClaim == null) {
                updatedClaim = claim;
            }
            return new CreateClaimResult(true, true, false, "Segment boundaries prepared.", updatedClaim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, false, "Failed to segmentize shaped boundary for map nib edit.", claim);
        }
    }

    /**
     * Merge a rectangular map cell patch into a top-level 2D claim by unioning occupied
     * cells, then committing the resulting polygon through updateShapedClaim.
     */
    public CreateClaimResult mergeMapCellIntoClaim(
            Player player,
            Object claim,
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            boolean dryRun
    ) {
        if (!isAvailable() || dataStore == null || player == null || claim == null || world == null) {
            return new CreateClaimResult(false, false, dryRun, "GriefPrevention is not available.", claim);
        }
        if (isSubdivision(claim) || is3DClaim(claim)) {
            return new CreateClaimResult(false, false, dryRun, "This GP build only supports map patch merge on top-level 2D claims.", claim);
        }

        Object originalPolygon = resolveClaimBoundaryPolygon(claim);
        if (originalPolygon == null) {
            return new CreateClaimResult(true, false, dryRun, "Could not resolve the selected claim boundary polygon.", claim);
        }

        Set<GridPoint> occupied;
        try {
            occupied = extractOccupiedPolygonCells(originalPolygon);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            ClaimCorners fallbackCorners = cornersFromPolygonBounds(getClaimCorners(claim).orElse(null), originalPolygon);
            if (fallbackCorners == null) {
                return new CreateClaimResult(true, false, dryRun, "Could not resolve the selected claim geometry.", claim);
            }
            occupied = extractOccupiedClaimCells(claim, world, fallbackCorners);
        }
        if (occupied.isEmpty()) {
            return new CreateClaimResult(true, false, dryRun, "Could not resolve occupied claim cells for that merge.", claim);
        }
        if (!patchTouchesClaim(occupied, minX, maxX, minZ, maxZ)) {
            return new CreateClaimResult(true, false, dryRun, "No adjacent boundary matched for that map cell.", claim);
        }

        Set<GridPoint> merged = new HashSet<>(occupied);
        for (int x = Math.min(minX, maxX); x <= Math.max(minX, maxX); x++) {
            for (int z = Math.min(minZ, maxZ); z <= Math.max(minZ, maxZ); z++) {
                merged.add(new GridPoint(x, z));
            }
        }

        List<Object> absorbedClaims = collectAbsorbableConnectedClaims(claim, world, merged);
        Set<String> ignoredClaimIds = new HashSet<>();
        String selectedClaimId = getClaimId(claim).orElse(null);
        if (selectedClaimId != null) {
            ignoredClaimIds.add(selectedClaimId);
        }
        List<String> absorbedClaimIds = new ArrayList<>();
        for (Object absorbedClaim : absorbedClaims) {
            String absorbedId = getClaimId(absorbedClaim).orElse(null);
            if (absorbedId != null) {
                absorbedClaimIds.add(absorbedId);
                ignoredClaimIds.add(absorbedId);
            }
        }
        if (hasExternalClaimConflict(world, merged, ignoredClaimIds)) {
            return new CreateClaimResult(true, false, dryRun, "That map cell would collide with another claim.", claim);
        }

        Object candidatePolygon;
        try {
            candidatePolygon = buildPolygonFromOccupiedPoints(claim.getClass().getClassLoader(), merged);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, dryRun, "That map cell could not be merged cleanly into the selected claim.", claim);
        } catch (IllegalArgumentException e) {
            return new CreateClaimResult(true, false, dryRun, e.getMessage(), claim);
        }

        if (dryRun) {
            return new CreateClaimResult(true, true, true, "Ready to merge that map cell into the selected claim.", claim);
        }

        Object playerData = resolvePlayerData(player.getUniqueId());
        if (playerData == null) {
            return new CreateClaimResult(false, false, false, "Could not resolve player data for shaped update.", claim);
        }

        Method updateShapedClaim = findUpdateShapedClaimMethod(claim.getClass(), candidatePolygon.getClass(), playerData.getClass());
        if (updateShapedClaim == null) {
            return new CreateClaimResult(false, false, false, "This GP build doesn't expose updateShapedClaim.", claim);
        }

        try {
            for (Object absorbedClaim : absorbedClaims) {
                if (!deleteClaim(absorbedClaim)) {
                    return new CreateClaimResult(true, false, false, "Failed to merge a connected detached claim into the selected claim.", claim);
                }
            }

            Object updateResult = updateShapedClaim.invoke(dataStore, player, playerData, claim, candidatePolygon);
            if (!extractResizeSucceeded(updateResult)) {
                String denial = extractDenialMessage(updateResult);
                return new CreateClaimResult(
                        true,
                        false,
                        false,
                        denial == null || denial.isBlank()
                                ? "That map cell could not be merged into the selected claim."
                                : denial,
                        claim
                );
            }

            Object updatedClaim = extractResultClaim(updateResult);
            if (updatedClaim == null) {
                updatedClaim = claim;
            }

            mergeAbsorbedClaimIcons(player, updatedClaim, claim, absorbedClaimIds);
            return new CreateClaimResult(true, true, false, "Claimed that map cell into the selected claim.", updatedClaim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, false, "Failed to merge that map cell into the selected claim.", claim);
        }
    }

    /**
     * Remove a rectangular map cell patch from a top-level 2D claim by subtracting occupied
     * cells, then committing the resulting polygon through updateShapedClaim.
     */
    public CreateClaimResult subtractMapCellFromClaim(
            Player player,
            Object claim,
            World world,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            boolean dryRun
    ) {
        if (!isAvailable() || dataStore == null || player == null || claim == null || world == null) {
            return new CreateClaimResult(false, false, dryRun, "GriefPrevention is not available.", claim);
        }
        if (isSubdivision(claim) || is3DClaim(claim)) {
            return new CreateClaimResult(false, false, dryRun, "This GP build only supports map cell unclaim on top-level 2D claims.", claim);
        }

        Object originalPolygon = resolveClaimBoundaryPolygon(claim);
        if (originalPolygon == null) {
            return new CreateClaimResult(true, false, dryRun, "Could not resolve the selected claim boundary polygon.", claim);
        }

        Set<GridPoint> occupied = resolveOccupiedClaimCells(claim, world);
        if (occupied.isEmpty()) {
            return new CreateClaimResult(true, false, dryRun, "Could not resolve occupied claim cells for that unclaim.", claim);
        }

        int patchMinX = Math.min(minX, maxX);
        int patchMaxX = Math.max(minX, maxX);
        int patchMinZ = Math.min(minZ, maxZ);
        int patchMaxZ = Math.max(minZ, maxZ);

        Set<GridPoint> remaining = new HashSet<>(occupied);
        boolean removedAny = remaining.removeIf(point -> point.x() >= patchMinX
                && point.x() <= patchMaxX
                && point.z() >= patchMinZ
                && point.z() <= patchMaxZ);
        if (!removedAny) {
            return new CreateClaimResult(true, false, dryRun, "That map cell is not part of the selected claim.", claim);
        }

        if (remaining.isEmpty()) {
            if (dryRun) {
                return new CreateClaimResult(true, true, true, "Ready to abandon the selected claim from the map editor.", claim);
            }
            if (!deleteClaim(claim)) {
                return new CreateClaimResult(true, false, false, "Failed to abandon the selected claim.", claim);
            }

            removeDeletedClaimMetadata(getClaimId(claim).orElse(null));
            return new CreateClaimResult(true, true, false, "Abandoned the selected claim from the map editor.", null);
        }

        Object patchPolygon;
        try {
            patchPolygon = buildRectanglePolygon(claim.getClass().getClassLoader(), patchMinX, patchMaxX, patchMinZ, patchMaxZ);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, dryRun, "Could not prepare the selected map cell for unclaim.", claim);
        }

        Object candidatePolygon;
        try {
            candidatePolygon = subtractClaimPolygon(claim.getClass().getClassLoader(), originalPolygon, patchPolygon, remaining);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, dryRun, "That map cell could not be removed cleanly from the selected claim.", claim);
        } catch (IllegalArgumentException e) {
            return new CreateClaimResult(true, false, dryRun, friendlyMapSubtractionFailure(e.getMessage()), claim);
        }

        if (dryRun) {
            return new CreateClaimResult(true, true, true, "Ready to unclaim that map cell from the selected claim.", claim);
        }

        Object playerData = resolvePlayerData(player.getUniqueId());
        if (playerData == null) {
            return new CreateClaimResult(false, false, false, "Could not resolve player data for shaped update.", claim);
        }

        Method updateShapedClaim = findUpdateShapedClaimMethod(claim.getClass(), candidatePolygon.getClass(), playerData.getClass());
        if (updateShapedClaim == null) {
            return new CreateClaimResult(false, false, false, "This GP build doesn't expose updateShapedClaim.", claim);
        }

        try {
            Object updateResult = updateShapedClaim.invoke(dataStore, player, playerData, claim, candidatePolygon);
            if (!extractResizeSucceeded(updateResult)) {
                String denial = extractDenialMessage(updateResult);
                return new CreateClaimResult(
                        true,
                        false,
                        false,
                        denial == null || denial.isBlank()
                                ? "That map cell could not be removed from the selected claim."
                                : denial,
                        claim
                );
            }

            Object updatedClaim = extractResultClaim(updateResult);
            if (updatedClaim == null) {
                updatedClaim = claim;
            }
            return new CreateClaimResult(true, true, false, "Unclaimed that map cell from the selected claim.", updatedClaim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new CreateClaimResult(true, false, false, "Failed to remove that map cell from the selected claim.", claim);
        }
    }

    private List<Object> collectAbsorbableConnectedClaims(Object selectedClaim, World world, Set<GridPoint> mergedOccupied) {
        List<Object> absorbedClaims = new ArrayList<>();
        UUID ownerId = getClaimOwner(selectedClaim);
        if (ownerId == null || world == null) {
            return absorbedClaims;
        }

        String worldName = world.getName();
        Set<Object> absorbedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean changed;
        do {
            changed = false;
            for (Object otherClaim : getAllClaims()) {
                if (otherClaim == null
                        || otherClaim == selectedClaim
                        || absorbedSet.contains(otherClaim)
                        || isSubdivision(otherClaim)
                        || isAdminClaim(otherClaim)
                        || is3DClaim(otherClaim)) {
                    continue;
                }

                if (!ownerId.equals(getClaimOwner(otherClaim))) {
                    continue;
                }
                if (!worldName.equals(getClaimWorld(otherClaim).orElse(null))) {
                    continue;
                }

                Set<GridPoint> otherOccupied = resolveOccupiedClaimCells(otherClaim, world);
                if (otherOccupied.isEmpty() || !occupiedSetsTouch(mergedOccupied, otherOccupied)) {
                    continue;
                }

                mergedOccupied.addAll(otherOccupied);
                absorbedClaims.add(otherClaim);
                absorbedSet.add(otherClaim);
                changed = true;
            }
        } while (changed);

        return absorbedClaims;
    }

    private Set<GridPoint> resolveOccupiedClaimCells(Object claim, World world) {
        Object polygon = resolveClaimBoundaryPolygon(claim);
        if (polygon != null) {
            try {
                return extractOccupiedPolygonCells(polygon);
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        ClaimCorners corners = getClaimCorners(claim).orElse(null);
        if (corners == null || world == null) {
            return Set.of();
        }
        return extractOccupiedClaimCells(claim, world, corners);
    }

    private boolean occupiedSetsTouch(Set<GridPoint> left, Set<GridPoint> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }

        for (GridPoint point : left) {
            if (right.contains(point)
                    || right.contains(new GridPoint(point.x() + 1, point.z()))
                    || right.contains(new GridPoint(point.x() - 1, point.z()))
                    || right.contains(new GridPoint(point.x(), point.z() + 1))
                    || right.contains(new GridPoint(point.x(), point.z() - 1))) {
                return true;
            }
        }

        return false;
    }

    private boolean hasExternalClaimConflict(World world, Set<GridPoint> mergedOccupied, Set<String> ignoredClaimIds) {
        if (world == null || mergedOccupied.isEmpty()) {
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (GridPoint point : mergedOccupied) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }

        for (Object otherClaim : getAllClaims()) {
            if (otherClaim == null || isSubdivision(otherClaim)) {
                continue;
            }

            String claimId = getClaimId(otherClaim).orElse(null);
            if (claimId != null && ignoredClaimIds.contains(claimId)) {
                continue;
            }
            if (!world.getName().equals(getClaimWorld(otherClaim).orElse(null))) {
                continue;
            }

            ClaimCorners corners = getClaimCorners(otherClaim).orElse(null);
            if (corners == null
                    || corners.x2 < minX
                    || corners.x1 > maxX
                    || corners.z2 < minZ
                    || corners.z1 > maxZ) {
                continue;
            }

            Set<GridPoint> otherOccupied = resolveOccupiedClaimCells(otherClaim, world);
            for (GridPoint point : otherOccupied) {
                if (mergedOccupied.contains(point)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void mergeAbsorbedClaimIcons(Player player, Object updatedClaim, Object selectedClaim, List<String> absorbedClaimIds) {
        if (absorbedClaimIds.isEmpty()) {
            return;
        }

        GPExpansionPlugin plugin;
        try {
            plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(GPExpansionPlugin.class);
        } catch (IllegalStateException e) {
            return;
        }

        String targetClaimId = getClaimId(updatedClaim).orElseGet(() -> getClaimId(selectedClaim).orElse(null));
        if (targetClaimId == null) {
            return;
        }

        String preferredClaimId = targetClaimId;
        if (player != null) {
            Object standingClaim = getClaimAt(player.getLocation(), player).orElse(null);
            String standingClaimId = getClaimId(standingClaim).orElse(null);
            if (standingClaimId != null
                    && (standingClaimId.equals(targetClaimId) || absorbedClaimIds.contains(standingClaimId))) {
                preferredClaimId = standingClaimId;
            }
        }

        List<String> sourceClaimIds = new ArrayList<>(absorbedClaimIds.size() + 1);
        sourceClaimIds.add(targetClaimId);
        sourceClaimIds.addAll(absorbedClaimIds);
        plugin.getClaimDataStore().mergeIconHistories(targetClaimId, preferredClaimId, sourceClaimIds);
        plugin.getClaimDataStore().save();
    }

    private Object unionClaimPolygon(
            ClassLoader loader,
            Object originalPolygon,
            Object patchPolygon,
            Set<GridPoint> mergedOccupied
    ) throws ReflectiveOperationException {
        Object candidate = tryUnionViaClaimEditorSkeleton(loader, originalPolygon, patchPolygon);
        if (candidate != null) {
            return candidate;
        }
        return buildPolygonFromOccupiedPoints(loader, mergedOccupied);
    }

    private Object subtractClaimPolygon(
            ClassLoader loader,
            Object originalPolygon,
            Object patchPolygon,
            Set<GridPoint> remainingOccupied
    ) throws ReflectiveOperationException {
        Object candidate = trySubtractViaClaimEditorSkeleton(loader, originalPolygon, patchPolygon);
        if (candidate != null) {
            return candidate;
        }
        return buildPolygonFromOccupiedPoints(loader, remainingOccupied);
    }

    private Object buildRectanglePolygon(ClassLoader loader, int minX, int maxX, int minZ, int maxZ)
            throws ReflectiveOperationException {
        if (loader == null) {
            throw new IllegalArgumentException("Could not resolve claim geometry classes.");
        }
        Class<?> polygonClass = loader.loadClass("com.griefprevention.geometry.OrthogonalPolygon");
        Method fromRectangle = polygonClass.getMethod(
                "fromRectangle",
                int.class,
                int.class,
                int.class,
                int.class
        );
        return fromRectangle.invoke(
                null,
                Math.min(minX, maxX),
                Math.min(minZ, maxZ),
                Math.max(minX, maxX),
                Math.max(minZ, maxZ)
        );
    }

    private Object tryUnionViaClaimEditorSkeleton(ClassLoader loader, Object originalPolygon, Object patchPolygon)
            throws ReflectiveOperationException {
        if (loader == null || originalPolygon == null || patchPolygon == null) {
            return null;
        }

        try {
            Class<?> skeletonClass = loader.loadClass("com.griefprevention.claims.editor.ClaimEditorSkeleton");
            Object skeleton = skeletonClass.getDeclaredConstructor().newInstance();
            Method unionMethod = skeletonClass.getDeclaredMethod(
                    "unionPolygons",
                    originalPolygon.getClass(),
                    patchPolygon.getClass()
            );
            unionMethod.setAccessible(true);
            return unionMethod.invoke(skeleton, originalPolygon, patchPolygon);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private Object trySubtractViaClaimEditorSkeleton(ClassLoader loader, Object originalPolygon, Object patchPolygon)
            throws ReflectiveOperationException {
        if (loader == null || originalPolygon == null || patchPolygon == null) {
            return null;
        }

        try {
            Class<?> skeletonClass = loader.loadClass("com.griefprevention.claims.editor.ClaimEditorSkeleton");
            Object skeleton = skeletonClass.getDeclaredConstructor().newInstance();
            Method subtractMethod = skeletonClass.getDeclaredMethod(
                    "subtractPolygons",
                    originalPolygon.getClass(),
                    patchPolygon.getClass()
            );
            subtractMethod.setAccessible(true);
            return subtractMethod.invoke(skeleton, originalPolygon, patchPolygon);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private Set<GridPoint> extractOccupiedClaimCells(Object claim, World world, ClaimCorners corners) {
        Set<GridPoint> occupied = new HashSet<>();
        int sampleY = world.getMinHeight() + 1;
        for (int x = corners.x1; x <= corners.x2; x++) {
            for (int z = corners.z1; z <= corners.z2; z++) {
                if (claimContains(claim, world, x, sampleY, z)) {
                    occupied.add(new GridPoint(x, z));
                }
            }
        }
        return occupied;
    }

    private boolean patchTouchesClaim(Set<GridPoint> occupied, int minX, int maxX, int minZ, int maxZ) {
        int patchMinX = Math.min(minX, maxX);
        int patchMaxX = Math.max(minX, maxX);
        int patchMinZ = Math.min(minZ, maxZ);
        int patchMaxZ = Math.max(minZ, maxZ);

        for (int x = patchMinX; x <= patchMaxX; x++) {
            for (int z = patchMinZ; z <= patchMaxZ; z++) {
                GridPoint point = new GridPoint(x, z);
                if (occupied.contains(point)
                        || occupied.contains(new GridPoint(x + 1, z))
                        || occupied.contains(new GridPoint(x - 1, z))
                        || occupied.contains(new GridPoint(x, z + 1))
                        || occupied.contains(new GridPoint(x, z - 1))) {
                    return true;
                }
            }
        }

        return false;
    }

    private String friendlyMapSubtractionFailure(String message) {
        if (message == null || message.isBlank()) {
            return "That map cell could not be removed cleanly from the selected claim.";
        }
        if (message.contains("split the claim") || message.contains("disconnected")) {
            return "That map cell would split the selected claim into multiple pieces.";
        }
        if (message.contains("remove the claim")) {
            return "That map cell would remove the selected claim.";
        }
        if (message.contains("follow") || message.contains("did not close")) {
            return "That map cell would create a claim shape this editor can't represent cleanly.";
        }
        return message;
    }

    private void removeDeletedClaimMetadata(String claimId) {
        if (claimId == null || claimId.isBlank()) {
            return;
        }

        GPExpansionPlugin plugin;
        try {
            plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(GPExpansionPlugin.class);
        } catch (IllegalStateException e) {
            return;
        }

        plugin.getClaimDataStore().remove(claimId);
        plugin.getClaimDataStore().save();
    }

    /**
     * A pure-Java, allocation-free view of an orthogonal polygon's corners. We extract
     * corner x/z values via reflection exactly once, then all subsequent point-in-polygon
     * tests run without any reflection. This is critical because Paper's reflection
     * remapper makes each getMethod("x")/invoke very slow, and the claim-map editor calls
     * containsCell in tight W*H loops.
     */
    private static final class PolygonView {
        final int[] xs;
        final int[] zs;
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;
        final boolean isRectangle;

        PolygonView(int[] xs, int[] zs, int minX, int maxX, int minZ, int maxZ) {
            this.xs = xs;
            this.zs = zs;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            // Orthogonal polygons with exactly 4 corners are axis-aligned rectangles.
            this.isRectangle = xs.length == 4;
        }

        boolean containsCell(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return false;
            }
            if (isRectangle) {
                return true;
            }
            // Treat corner lattice points and boundary edge points as inside.
            // For an orthogonal polygon, a lattice point is on the boundary iff it lies
            // on an axis-aligned segment between two consecutive corners.
            int n = xs.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                int ax = xs[i], az = zs[i];
                int bx = xs[j], bz = zs[j];
                if (ax == bx && x == ax && z >= Math.min(az, bz) && z <= Math.max(az, bz)) {
                    return true;
                }
                if (az == bz && z == az && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)) {
                    return true;
                }
            }
            // Ray cast using cell-center sample to classify interior.
            double sampleX = x + 0.5D;
            double sampleZ = z + 0.5D;
            boolean inside = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                int ax = xs[i], az = zs[i];
                int bx = xs[j], bz = zs[j];
                boolean crosses = (az > sampleZ) != (bz > sampleZ);
                if (!crosses) continue;
                double intersectionX = (double) (bx - ax) * (sampleZ - az) / (double) (bz - az) + ax;
                if (sampleX < intersectionX) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    /**
     * Reflectively read the polygon's corners and bounds exactly once, returning a
     * PolygonView that supports fast, reflection-free containment tests.
     */
    private PolygonView buildPolygonView(Object polygon) throws ReflectiveOperationException {
        Method cornersMethod = cachedPolygonCornersMethod;
        if (cornersMethod == null || cachedPolygonClass != polygon.getClass()) {
            cornersMethod = polygon.getClass().getMethod("corners");
            cachedPolygonCornersMethod = cornersMethod;
            cachedPolygonClass = polygon.getClass();
        }
        Object raw = cornersMethod.invoke(polygon);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        int n = list.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        // Resolve x()/z() Methods from the concrete corner class just once, caching
        // them on the bridge so subsequent polygon builds skip the remapper lookup.
        Object first = list.get(0);
        Method xMethod = cachedPointXMethod;
        Method zMethod = cachedPointZMethod;
        if (xMethod == null || zMethod == null || cachedPointClass != first.getClass()) {
            xMethod = first.getClass().getMethod("x");
            zMethod = first.getClass().getMethod("z");
            cachedPointXMethod = xMethod;
            cachedPointZMethod = zMethod;
            cachedPointClass = first.getClass();
        }

        for (int i = 0; i < n; i++) {
            Object corner = list.get(i);
            int cx = ((Number) xMethod.invoke(corner)).intValue();
            int cz = ((Number) zMethod.invoke(corner)).intValue();
            xs[i] = cx;
            zs[i] = cz;
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            if (cz < minZ) minZ = cz;
            if (cz > maxZ) maxZ = cz;
        }
        return new PolygonView(xs, zs, minX, maxX, minZ, maxZ);
    }

    private Set<GridPoint> extractOccupiedPolygonCells(Object polygon) throws ReflectiveOperationException {
        Set<GridPoint> occupied = new HashSet<>();
        PolygonView view = buildPolygonView(polygon);
        if (view == null) {
            return occupied;
        }
        for (int x = view.minX; x <= view.maxX; x++) {
            for (int z = view.minZ; z <= view.maxZ; z++) {
                if (view.containsCell(x, z)) {
                    occupied.add(new GridPoint(x, z));
                }
            }
        }
        return occupied;
    }

    private int countPolygonCoverageInCell(
            Object polygon,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) throws ReflectiveOperationException {
        PolygonView view = buildPolygonView(polygon);
        if (view == null) return 0;
        return countCoverage(view, minX, maxX, minZ, maxZ);
    }

    private static int countCoverage(PolygonView view, int minX, int maxX, int minZ, int maxZ) {
        int lowX = Math.max(minX, view.minX);
        int highX = Math.min(maxX, view.maxX);
        int lowZ = Math.max(minZ, view.minZ);
        int highZ = Math.min(maxZ, view.maxZ);
        if (lowX > highX || lowZ > highZ) {
            return 0;
        }
        // Rectangle polygons: overlap area is exact.
        if (view.isRectangle) {
            return (highX - lowX + 1) * (highZ - lowZ + 1);
        }
        int covered = 0;
        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                if (view.containsCell(x, z)) {
                    covered++;
                }
            }
        }
        return covered;
    }

    private Object buildPolygonFromOccupiedPoints(ClassLoader loader, Set<GridPoint> occupied)
            throws ReflectiveOperationException {
        if (loader == null) {
            throw new IllegalArgumentException("Could not resolve claim geometry classes.");
        }
        if (occupied.isEmpty()) {
            throw new IllegalArgumentException("That map merge would remove the claim body.");
        }

        List<ContourVertex> tracedContour = traceOccupiedContour(occupied);
        List<ContourVertex> compressedContour = compressContourPath(tracedContour);
        List<GridPoint> mappedCorners = mapContourCornersToOccupiedPoints(compressedContour, occupied);
        List<GridPoint> compressed = compressBoundaryPath(mappedCorners);

        Class<?> pointClass = loader.loadClass("com.griefprevention.geometry.OrthogonalPoint2i");
        Class<?> polygonClass = loader.loadClass("com.griefprevention.geometry.OrthogonalPolygon");
        java.lang.reflect.Constructor<?> pointCtor = pointClass.getDeclaredConstructor(int.class, int.class);
        pointCtor.setAccessible(true);

        List<Object> reflectedPath = new ArrayList<>(compressed.size());
        for (GridPoint point : compressed) {
            reflectedPath.add(pointCtor.newInstance(point.x(), point.z()));
        }

        Method fromClosedPath = polygonClass.getMethod("fromClosedPath", List.class);
        return fromClosedPath.invoke(null, reflectedPath);
    }

    private List<ContourVertex> traceOccupiedContour(Set<GridPoint> occupied) {
        List<ContourEdge> edges = new ArrayList<>();
        for (GridPoint point : occupied) {
            addContourEdgesForPoint(occupied, point, edges);
        }

        if (edges.isEmpty()) {
            throw new IllegalArgumentException("Unable to trace merged claim boundary.");
        }

        Map<ContourVertex, List<ContourEdge>> outgoing = new HashMap<>();
        for (ContourEdge edge : edges) {
            outgoing.computeIfAbsent(edge.start(), ignored -> new ArrayList<>()).add(edge);
        }

        ContourEdge startEdge = edges.stream()
                .min(Comparator.comparingInt((ContourEdge edge) -> edge.start().z())
                        .thenComparingInt(edge -> edge.start().x())
                        .thenComparingInt(edge -> edge.end().x())
                        .thenComparingInt(edge -> edge.end().z()))
                .orElseThrow();

        List<ContourVertex> traced = new ArrayList<>();
        traced.add(startEdge.start());

        Set<ContourEdge> visited = new HashSet<>();
        ContourEdge current = startEdge;
        int guard = edges.size() + 1;
        while (guard-- > 0) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException("Merged claim boundary could not be followed.");
            }

            traced.add(current.end());
            if (current.end().equals(startEdge.start())) {
                break;
            }

            List<ContourEdge> nextEdges = outgoing.get(current.end());
            if (nextEdges == null || nextEdges.size() != 1) {
                throw new IllegalArgumentException("Merged claim boundary could not be followed.");
            }

            current = nextEdges.get(0);
        }

        if (!traced.get(traced.size() - 1).equals(startEdge.start())) {
            throw new IllegalArgumentException("Merged claim boundary did not close.");
        }

        if (visited.size() != edges.size()) {
            throw new IllegalArgumentException("Merged claim boundary is disconnected.");
        }

        return traced;
    }

    private void addContourEdgesForPoint(Set<GridPoint> occupied, GridPoint point, List<ContourEdge> edges) {
        int x = point.x();
        int z = point.z();

        if (!occupied.contains(new GridPoint(x, z - 1))) {
            edges.add(new ContourEdge(
                    new ContourVertex(2 * x - 1, 2 * z - 1),
                    new ContourVertex(2 * x + 1, 2 * z - 1)
            ));
        }

        if (!occupied.contains(new GridPoint(x + 1, z))) {
            edges.add(new ContourEdge(
                    new ContourVertex(2 * x + 1, 2 * z - 1),
                    new ContourVertex(2 * x + 1, 2 * z + 1)
            ));
        }

        if (!occupied.contains(new GridPoint(x, z + 1))) {
            edges.add(new ContourEdge(
                    new ContourVertex(2 * x + 1, 2 * z + 1),
                    new ContourVertex(2 * x - 1, 2 * z + 1)
            ));
        }

        if (!occupied.contains(new GridPoint(x - 1, z))) {
            edges.add(new ContourEdge(
                    new ContourVertex(2 * x - 1, 2 * z + 1),
                    new ContourVertex(2 * x - 1, 2 * z - 1)
            ));
        }
    }

    private List<ContourVertex> compressContourPath(List<ContourVertex> traced) {
        if (traced.size() < 4) {
            throw new IllegalArgumentException("Merged claim boundary is too small.");
        }

        List<ContourVertex> compressed = new ArrayList<>();
        int cycleLength = traced.size() - 1;
        for (int i = 0; i < cycleLength; i++) {
            ContourVertex previous = traced.get((i - 1 + cycleLength) % cycleLength);
            ContourVertex current = traced.get(i);
            ContourVertex next = traced.get((i + 1) % cycleLength);

            int dx1 = Integer.compare(current.x(), previous.x());
            int dz1 = Integer.compare(current.z(), previous.z());
            int dx2 = Integer.compare(next.x(), current.x());
            int dz2 = Integer.compare(next.z(), current.z());

            if (i == 0 || dx1 != dx2 || dz1 != dz2) {
                compressed.add(current);
            }
        }

        compressed.add(compressed.get(0));
        return compressed;
    }

    private List<GridPoint> mapContourCornersToOccupiedPoints(List<ContourVertex> contour, Set<GridPoint> occupied) {
        List<GridPoint> mapped = new ArrayList<>();
        int cycleLength = contour.size() - 1;
        for (int i = 0; i < cycleLength; i++) {
            ContourVertex previous = contour.get((i - 1 + cycleLength) % cycleLength);
            ContourVertex current = contour.get(i);
            ContourVertex next = contour.get((i + 1) % cycleLength);
            GridPoint point = resolveContourCornerPoint(previous, current, next, occupied);
            if (mapped.isEmpty() || !mapped.get(mapped.size() - 1).equals(point)) {
                mapped.add(point);
            }
        }

        if (mapped.size() < 4) {
            throw new IllegalArgumentException("Merged claim boundary is too small.");
        }

        mapped.add(mapped.get(0));
        return mapped;
    }

    private GridPoint resolveContourCornerPoint(
            ContourVertex previous,
            ContourVertex vertex,
            ContourVertex next,
            Set<GridPoint> occupied
    ) {
        int lowX = Math.floorDiv(vertex.x(), 2);
        int highX = Math.floorDiv(vertex.x() + 1, 2);
        int lowZ = Math.floorDiv(vertex.z(), 2);
        int highZ = Math.floorDiv(vertex.z() + 1, 2);

        int incomingDirection = contourDirection(previous, vertex);
        int outgoingDirection = contourDirection(vertex, next);
        int interiorFromIncoming = rotateRight(incomingDirection);
        int interiorFromOutgoing = rotateRight(outgoingDirection);

        int resolvedX;
        if (interiorFromIncoming == 0 || interiorFromOutgoing == 0) {
            resolvedX = highX;
        } else if (interiorFromIncoming == 2 || interiorFromOutgoing == 2) {
            resolvedX = lowX;
        } else {
            throw new IllegalArgumentException("Merged claim boundary could not be followed.");
        }

        int resolvedZ;
        if (interiorFromIncoming == 1 || interiorFromOutgoing == 1) {
            resolvedZ = highZ;
        } else if (interiorFromIncoming == 3 || interiorFromOutgoing == 3) {
            resolvedZ = lowZ;
        } else {
            throw new IllegalArgumentException("Merged claim boundary could not be followed.");
        }

        GridPoint point = new GridPoint(resolvedX, resolvedZ);
        if (!occupied.contains(point)) {
            throw new IllegalArgumentException("Merged claim boundary could not be followed.");
        }
        return point;
    }

    private int contourDirection(ContourVertex start, ContourVertex end) {
        if (end.x() > start.x()) {
            return 0;
        }
        if (end.z() > start.z()) {
            return 1;
        }
        if (end.x() < start.x()) {
            return 2;
        }
        if (end.z() < start.z()) {
            return 3;
        }
        throw new IllegalArgumentException("Contour path cannot contain duplicate vertices.");
    }

    private int rotateRight(int direction) {
        return switch (direction) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            case 3 -> 0;
            default -> throw new IllegalArgumentException("Unknown contour direction.");
        };
    }

    private List<GridPoint> compressBoundaryPath(List<GridPoint> traced) {
        List<GridPoint> path = new ArrayList<>(traced);
        if (path.size() < 4) {
            throw new IllegalArgumentException("Merged claim boundary is too small.");
        }

        List<GridPoint> compressed = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            GridPoint previous = path.get((i - 1 + path.size() - 1) % (path.size() - 1));
            GridPoint current = path.get(i);
            GridPoint next = path.get((i + 1) % (path.size() - 1));

            int dx1 = Integer.compare(current.x(), previous.x());
            int dz1 = Integer.compare(current.z(), previous.z());
            int dx2 = Integer.compare(next.x(), current.x());
            int dz2 = Integer.compare(next.z(), current.z());

            if (i == 0 || dx1 != dx2 || dz1 != dz2) {
                compressed.add(current);
            }
        }

        compressed.add(compressed.get(0));
        return compressed;
    }

    private record ContourVertex(int x, int z) { }

    private record ContourEdge(ContourVertex start, ContourVertex end) { }

    /**
     * Returns true when GP config has AllowShapedClaims enabled.
     * Defaults to true when unavailable so we don't unexpectedly block UI in unknown builds.
     */
    public boolean isShapedClaimsAllowed() {
        if (!isAvailable() || gpInstance == null) return true;
        try {
            // GP3D field
            try {
                Field field = gpInstance.getClass().getDeclaredField("config_claims_allowShapedClaims");
                field.setAccessible(true);
                Object value = field.get(gpInstance);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (NoSuchFieldException ignored) { }

            // Getter fallback
            try {
                Method getter = gpInstance.getClass().getMethod("isShapedClaimsAllowed");
                Object value = getter.invoke(gpInstance);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (NoSuchMethodException ignored) { }
        } catch (ReflectiveOperationException ignored) {
        }
        return true;
    }

    /**
     * Create a top-level square claim (or dry-run the same creation) using GP's own createClaim pipeline.
     * This preserves GP minimum width/area and claim-block checks.
     */
    public CreateClaimResult createSquareClaim(Player player, World world, int minX, int maxX, int minZ, int maxZ, boolean dryRun) {
        if (!isAvailable() || dataStore == null || player == null || world == null) {
            return new CreateClaimResult(false, false, dryRun, "GriefPrevention is not available.", null);
        }

        int y1 = world.getMinHeight();
        int y2 = world.getMaxHeight() - 1;
        UUID owner = player.getUniqueId();

        for (Method method : dataStore.getClass().getMethods()) {
            if (!"createClaim".equals(method.getName())) continue;
            Class<?>[] p = method.getParameterTypes();
            try {
                Object rawResult = null;

                // GP/GP3D modern signature:
                // createClaim(World, x1, x2, y1, y2, z1, z2, UUID, Claim parent, Long id, Player creatingPlayer, boolean dryRun)
                if (p.length == 12
                        && p[0] == World.class
                        && p[1] == int.class
                        && p[2] == int.class
                        && p[3] == int.class
                        && p[4] == int.class
                        && p[5] == int.class
                        && p[6] == int.class
                        && p[7] == UUID.class
                        && p[9] == Long.class
                        && p[10] == Player.class
                        && p[11] == boolean.class) {
                    rawResult = method.invoke(dataStore, world, minX, maxX, y1, y2, minZ, maxZ, owner, null, null, player, dryRun);
                }
                // Legacy variant without dryRun:
                else if (p.length == 11
                        && p[0] == World.class
                        && p[1] == int.class
                        && p[2] == int.class
                        && p[3] == int.class
                        && p[4] == int.class
                        && p[5] == int.class
                        && p[6] == int.class
                        && p[7] == UUID.class
                        && p[9] == Long.class
                        && p[10] == Player.class) {
                    rawResult = method.invoke(dataStore, world, minX, maxX, y1, y2, minZ, maxZ, owner, null, null, player);
                }
                // Older 2D variant fallback:
                else if (p.length >= 7
                        && p[0] == World.class
                        && p[1] == int.class
                        && p[2] == int.class
                        && p[3] == int.class
                        && p[4] == int.class
                        && p[5] == UUID.class) {
                    rawResult = method.invoke(dataStore, world, minX, minZ, maxX, maxZ, owner, null);
                }

                if (rawResult == null) continue;
                return parseCreateClaimResult(rawResult, dryRun);
            } catch (Exception e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        return new CreateClaimResult(false, false, dryRun, "This GP build doesn't expose createClaim.", null);
    }

    private CreateClaimResult parseCreateClaimResult(Object rawResult, boolean dryRun) {
        boolean success = false;
        Object claim = null;
        String message = null;

        try {
            try {
                Field f = rawResult.getClass().getField("succeeded");
                Object v = f.get(rawResult);
                if (v instanceof Boolean b) success = b;
            } catch (NoSuchFieldException ignored) {
                try {
                    Method m = rawResult.getClass().getMethod("succeeded");
                    Object v = m.invoke(rawResult);
                    if (v instanceof Boolean b) success = b;
                } catch (NoSuchMethodException ignored2) {
                    try {
                        Method m = rawResult.getClass().getMethod("getSucceeded");
                        Object v = m.invoke(rawResult);
                        if (v instanceof Boolean b) success = b;
                    } catch (NoSuchMethodException ignored3) { }
                }
            }

            try {
                Field f = rawResult.getClass().getField("claim");
                claim = f.get(rawResult);
            } catch (NoSuchFieldException ignored) {
                try {
                    Method m = rawResult.getClass().getMethod("getClaim");
                    claim = m.invoke(rawResult);
                } catch (NoSuchMethodException ignored2) { }
            }

            if (!success) {
                try {
                    Field f = rawResult.getClass().getField("denialMessage");
                    Object denial = f.get(rawResult);
                    if (denial instanceof java.util.function.Supplier<?> supplier) {
                        Object supplied = supplier.get();
                        if (supplied != null) message = supplied.toString();
                    } else if (denial != null) {
                        message = denial.toString();
                    }
                } catch (NoSuchFieldException ignored) {
                    // Optional field
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }

        if (message == null || message.isBlank()) {
            message = success ? "Claim created." : "Claim creation failed.";
        }
        return new CreateClaimResult(true, success, dryRun, message, claim);
    }

    public ResizePreview previewResizeClaim(Player player, Object claim, ResizeDirection direction, int requestedOffset) {
        return previewResizeClaim(player, claim, direction, requestedOffset, null);
    }

    public ResizePreview previewResizeClaim(
            Player player,
            Object claim,
            ResizeDirection direction,
            int requestedOffset,
            Location referenceLocation
    ) {
        if (!isAvailable() || dataStore == null) {
            return new ResizePreview(false, false, ResizeFailureReason.NOT_AVAILABLE, requestedOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        if (player == null || claim == null || direction == null) {
            return new ResizePreview(false, false, ResizeFailureReason.INVALID_INPUT, requestedOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        if (usesSegmentAwareResize(claim, direction)) {
            Location reference = referenceLocation != null ? referenceLocation : player.getLocation();
            return previewSegmentAwareResize(player, claim, direction, requestedOffset, reference);
        }
        if (findResizeClaimMethod(claim.getClass()) == null) {
            return new ResizePreview(false, false, ResizeFailureReason.UNSUPPORTED, requestedOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }

        ClaimCorners current = getClaimCorners(claim).orElse(null);
        if (current == null) {
            return new ResizePreview(false, false, ResizeFailureReason.INVALID_INPUT, requestedOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }

        int currentWidth = current.x2 - current.x1 + 1;
        int currentHeight = current.y2 - current.y1 + 1;
        int currentDepth = current.z2 - current.z1 + 1;
        int currentArea = currentWidth * currentDepth;
        int minWidth = getConfiguredMinWidth();
        int minArea = getConfiguredMinArea(minWidth);
        int remaining = getPlayerClaimStats(player).map(stats -> stats.remaining).orElse(0);
        boolean enforceMinSize = usesMinimumSizeRules(claim);

        int maxShrink = computeMaxShrink(claim, current, direction, currentWidth, currentHeight, currentDepth, minWidth, minArea, enforceMinSize);
        int maxExpand = computeMaxExpand(claim, current, direction, remaining, currentWidth, currentDepth);
        int clampedOffset = Math.max(-maxShrink, Math.min(maxExpand, requestedOffset));
        ClaimCorners updated = newCornersForOffset(current, direction, clampedOffset);
        int newWidth = updated.x2 - updated.x1 + 1;
        int newHeight = updated.y2 - updated.y1 + 1;
        int newDepth = updated.z2 - updated.z1 + 1;
        int newArea = newWidth * newDepth;
        int blockDelta = newArea - currentArea;

        ResizeFailureReason failure = ResizeFailureReason.NONE;
        boolean valid = true;

        if (requestedOffset != clampedOffset) {
            valid = false;
            failure = ResizeFailureReason.INVALID_OFFSET_RANGE;
        } else if (enforceMinSize && (newWidth < minWidth || newDepth < minWidth || newArea < minArea)) {
            valid = false;
            failure = ResizeFailureReason.TOO_SMALL;
        } else if (blockDelta > remaining) {
            valid = false;
            failure = ResizeFailureReason.NOT_ENOUGH_BLOCKS;
        } else if ((failure = validateParentBounds(claim, updated)) != ResizeFailureReason.NONE) {
            valid = false;
        } else if ((failure = validateSiblingSpacing(claim, updated)) != ResizeFailureReason.NONE) {
            valid = false;
        } else if ((failure = validateContainedChildren(claim, updated)) != ResizeFailureReason.NONE) {
            valid = false;
        }

        return new ResizePreview(true, valid, failure, requestedOffset, clampedOffset, maxExpand, maxShrink, minWidth, minArea, remaining, currentWidth, currentHeight, currentDepth, currentArea, newWidth, newHeight, newDepth, newArea, blockDelta, current, updated);
    }

    public ResizeResult resizeClaim(Player player, Object claim, ResizeDirection direction, int requestedOffset) {
        return resizeClaim(player, claim, direction, requestedOffset, null);
    }

    public ResizeResult resizeClaim(
            Player player,
            Object claim,
            ResizeDirection direction,
            int requestedOffset,
            Location referenceLocation
    ) {
        if (usesSegmentAwareResize(claim, direction)) {
            Location reference = referenceLocation != null ? referenceLocation : (player != null ? player.getLocation() : null);
            return resizeSegmentAwareShapedClaim(player, claim, direction, requestedOffset, reference);
        }

        ResizePreview preview = previewResizeClaim(player, claim, direction, requestedOffset, referenceLocation);
        if (!preview.supported || !preview.valid) {
            return new ResizeResult(false, preview.failureReason, preview, claim);
        }

        Method resizeClaim = findResizeClaimMethod(claim.getClass());
        if (resizeClaim == null) {
            return new ResizeResult(false, ResizeFailureReason.UNSUPPORTED, preview, claim);
        }

        try {
            Object result = resizeClaim.invoke(
                dataStore,
                claim,
                preview.newCorners.x1,
                preview.newCorners.x2,
                preview.newCorners.y1,
                preview.newCorners.y2,
                preview.newCorners.z1,
                preview.newCorners.z2,
                player
            );

            if (!extractResizeSucceeded(result)) {
                return new ResizeResult(false, ResizeFailureReason.APPLY_FAILED, preview, claim);
            }

            Object resizedClaim = extractResultClaim(result);
            if (resizedClaim == null) resizedClaim = claim;
            return new ResizeResult(true, ResizeFailureReason.NONE, preview, resizedClaim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new ResizeResult(false, ResizeFailureReason.APPLY_FAILED, preview, claim);
        }
    }

    private static final class SegmentAwareResizeSelection {
        private final Object polygon;
        private final int edgeIndex;

        private SegmentAwareResizeSelection(Object polygon, int edgeIndex) {
            this.polygon = polygon;
            this.edgeIndex = edgeIndex;
        }
    }

    private ResizePreview previewSegmentAwareResize(
            Player player,
            Object claim,
            ResizeDirection direction,
            int requestedOffset,
            Location reference
    ) {
        ClaimCorners current = getClaimCorners(claim).orElse(null);
        if (current == null) {
            return new ResizePreview(false, false, ResizeFailureReason.INVALID_INPUT, requestedOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }

        SegmentAwareResizeSelection selection = resolveSegmentAwareSelection(claim, direction, reference);
        if (selection == null) {
            return new ResizePreview(true, false, ResizeFailureReason.INVALID_INPUT, requestedOffset, 0, 0, 0, getConfiguredShapedMinWidth(), getConfiguredShapedMinArea(), getPlayerClaimStats(player).map(stats -> stats.remaining).orElse(0), current.x2 - current.x1 + 1, current.y2 - current.y1 + 1, current.z2 - current.z1 + 1, getClaimAreaSafe(claim), current.x2 - current.x1 + 1, current.y2 - current.y1 + 1, current.z2 - current.z1 + 1, getClaimAreaSafe(claim), 0, current, current);
        }

        int polygonOffset = translateSegmentOffsetForDirection(direction, requestedOffset);
        Object polygonAfter = selection.polygon;
        boolean valid = true;
        ResizeFailureReason failure = ResizeFailureReason.NONE;
        if (requestedOffset != 0) {
            Object validation = applyPolygonEdgeOffset(selection.polygon, selection.edgeIndex, polygonOffset);
            if (!extractValidationValid(validation)) {
                valid = false;
                failure = ResizeFailureReason.INVALID_OFFSET_RANGE;
            } else {
                Object candidate = extractValidationPolygon(validation);
                if (candidate == null) {
                    valid = false;
                    failure = ResizeFailureReason.INVALID_OFFSET_RANGE;
                } else {
                    polygonAfter = candidate;
                }
            }
        }

        ClaimCorners updated = cornersFromPolygonBounds(current, polygonAfter);
        int remaining = getPlayerClaimStats(player).map(stats -> stats.remaining).orElse(0);
        int currentWidth = current.x2 - current.x1 + 1;
        int currentHeight = current.y2 - current.y1 + 1;
        int currentDepth = current.z2 - current.z1 + 1;
        int currentArea = getClaimAreaSafe(claim);
        int newWidth = updated.x2 - updated.x1 + 1;
        int newHeight = updated.y2 - updated.y1 + 1;
        int newDepth = updated.z2 - updated.z1 + 1;
        int newArea = newWidth * newDepth;
        int blockDelta = newArea - currentArea;
        if (valid && blockDelta > remaining) {
            valid = false;
            failure = ResizeFailureReason.NOT_ENOUGH_BLOCKS;
        }

        return new ResizePreview(
                true,
                valid,
                failure,
                requestedOffset,
                valid ? requestedOffset : 0,
                0,
                0,
                getConfiguredShapedMinWidth(),
                getConfiguredShapedMinArea(),
                remaining,
                currentWidth,
                currentHeight,
                currentDepth,
                currentArea,
                newWidth,
                newHeight,
                newDepth,
                newArea,
                blockDelta,
                current,
                updated
        );
    }

    private ResizeResult resizeSegmentAwareShapedClaim(
            Player player,
            Object claim,
            ResizeDirection direction,
            int requestedOffset,
            Location reference
    ) {
        ResizePreview preview = previewSegmentAwareResize(player, claim, direction, requestedOffset, reference);
        if (!preview.supported || !preview.valid) {
            return new ResizeResult(false, preview.failureReason, preview, claim);
        }

        SegmentAwareResizeSelection selection = resolveSegmentAwareSelection(claim, direction, reference);
        if (selection == null) {
            return new ResizeResult(false, ResizeFailureReason.INVALID_INPUT, preview, claim);
        }

        int polygonOffset = translateSegmentOffsetForDirection(direction, requestedOffset);
        Object validation = applyPolygonEdgeOffset(selection.polygon, selection.edgeIndex, polygonOffset);
        if (!extractValidationValid(validation)) {
            return new ResizeResult(false, ResizeFailureReason.INVALID_OFFSET_RANGE, preview, claim);
        }
        Object candidatePolygon = extractValidationPolygon(validation);
        if (candidatePolygon == null) {
            return new ResizeResult(false, ResizeFailureReason.INVALID_OFFSET_RANGE, preview, claim);
        }

        Object playerData = resolvePlayerData(player.getUniqueId());
        if (playerData == null) {
            return new ResizeResult(false, ResizeFailureReason.NOT_AVAILABLE, preview, claim);
        }

        Method updateShapedClaim = findUpdateShapedClaimMethod(claim.getClass(), candidatePolygon.getClass(), playerData.getClass());
        if (updateShapedClaim == null) {
            return new ResizeResult(false, ResizeFailureReason.UNSUPPORTED, preview, claim);
        }

        try {
            Object updateResult = updateShapedClaim.invoke(dataStore, player, playerData, claim, candidatePolygon);
            if (!extractResizeSucceeded(updateResult)) {
                String denial = extractDenialMessage(updateResult);
                return new ResizeResult(false, mapShapedResizeFailure(denial), preview, claim);
            }

            Object resizedClaim = extractResultClaim(updateResult);
            if (resizedClaim == null) resizedClaim = claim;

            ClaimCorners current = getClaimCorners(claim).orElse(preview.currentCorners);
            ClaimCorners updated = cornersFromPolygonBounds(current, candidatePolygon);
            int remaining = getPlayerClaimStats(player).map(stats -> stats.remaining).orElse(0);
            int currentWidth = current.x2 - current.x1 + 1;
            int currentHeight = current.y2 - current.y1 + 1;
            int currentDepth = current.z2 - current.z1 + 1;
            int currentArea = getClaimAreaSafe(claim);
            int newWidth = updated.x2 - updated.x1 + 1;
            int newHeight = updated.y2 - updated.y1 + 1;
            int newDepth = updated.z2 - updated.z1 + 1;
            int newArea = newWidth * newDepth;
            int blockDelta = newArea - currentArea;

            ResizePreview appliedPreview = new ResizePreview(
                    true,
                    true,
                    ResizeFailureReason.NONE,
                    requestedOffset,
                    requestedOffset,
                    0,
                    0,
                    getConfiguredShapedMinWidth(),
                    getConfiguredShapedMinArea(),
                    remaining,
                    currentWidth,
                    currentHeight,
                    currentDepth,
                    currentArea,
                    newWidth,
                    newHeight,
                    newDepth,
                    newArea,
                    blockDelta,
                    current,
                    updated
            );
            return new ResizeResult(true, ResizeFailureReason.NONE, appliedPreview, resizedClaim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return new ResizeResult(false, ResizeFailureReason.APPLY_FAILED, preview, claim);
        }
    }

    private SegmentAwareResizeSelection resolveSegmentAwareSelection(Object claim, ResizeDirection direction, Location reference) {
        Object polygon = resolveClaimBoundaryPolygon(claim);
        if (polygon == null) {
            return null;
        }
        Integer edgeIndex = selectDirectionalEdgeIndex(polygon, direction, reference);
        if (edgeIndex == null) {
            return null;
        }
        return new SegmentAwareResizeSelection(polygon, edgeIndex);
    }

    private SegmentEdgeInfo extractSegmentEdgeInfo(Object polygon, int edgeIndex, ResizeDirection direction) {
        if (polygon == null || edgeIndex < 0) {
            return null;
        }
        try {
            Method edgesMethod = polygon.getClass().getMethod("edges");
            Object rawEdges = edgesMethod.invoke(polygon);
            if (!(rawEdges instanceof List<?> edges) || edgeIndex >= edges.size()) {
                return null;
            }

            Object edge = edges.get(edgeIndex);
            boolean horizontal = invokeBoolean(edge, "isHorizontal");
            boolean vertical = invokeBoolean(edge, "isVertical");
            if (!horizontal && !vertical) {
                return null;
            }

            int axisCoordinate;
            int minAlong;
            int maxAlong;
            if (horizontal) {
                axisCoordinate = invokePointCoordinate(edge, "start", "z");
                minAlong = invokeIntOr(edge, "minX", invokePointCoordinate(edge, "start", "x"));
                maxAlong = invokeIntOr(edge, "maxX", invokePointCoordinate(edge, "end", "x"));
            } else {
                axisCoordinate = invokePointCoordinate(edge, "start", "x");
                minAlong = invokeIntOr(edge, "minZ", invokePointCoordinate(edge, "start", "z"));
                maxAlong = invokeIntOr(edge, "maxZ", invokePointCoordinate(edge, "end", "z"));
            }

            return new SegmentEdgeInfo(edgeIndex, direction, axisCoordinate, minAlong, maxAlong, horizontal);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private Object resolveClaimBoundaryPolygon(Object claim) {
        try {
            Method getBoundaryPolygon = claim.getClass().getMethod("getBoundaryPolygon");
            return getBoundaryPolygon.invoke(claim);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private Integer selectDirectionalEdgeIndex(Object polygon, ResizeDirection direction, Location reference) {
        boolean wantsHorizontal = direction == ResizeDirection.NORTH || direction == ResizeDirection.SOUTH;
        boolean wantsVertical = direction == ResizeDirection.EAST || direction == ResizeDirection.WEST;
        if (!wantsHorizontal && !wantsVertical) {
            return null;
        }

        List<?> edges;
        try {
            Method edgesMethod = polygon.getClass().getMethod("edges");
            Object raw = edgesMethod.invoke(polygon);
            if (!(raw instanceof List<?> list)) {
                return null;
            }
            edges = list;
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return null;
        }

        double refX = reference != null ? reference.getX() : Double.NaN;
        double refZ = reference != null ? reference.getZ() : Double.NaN;
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;

        // First pass: nearest edge with the expected orientation on the requested side
        // of the reference point.
        for (int index = 0; index < edges.size(); index++) {
            Object edge = edges.get(index);
            try {
                boolean horizontal = invokeBoolean(edge, "isHorizontal");
                boolean vertical = invokeBoolean(edge, "isVertical");
                if (wantsHorizontal && !horizontal) {
                    continue;
                }
                if (wantsVertical && !vertical) {
                    continue;
                }

                if (!Double.isNaN(refX)
                        && !Double.isNaN(refZ)
                        && !isEdgeOnRequestedSide(direction, edge, refX, refZ, horizontal)) {
                    continue;
                }

                double distance = distanceToEdge(edge, refX, refZ, horizontal);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        if (bestIndex >= 0) {
            return bestIndex;
        }

        // Fallback: nearest oriented edge if side classification doesn't produce a match.
        for (int index = 0; index < edges.size(); index++) {
            Object edge = edges.get(index);
            try {
                boolean horizontal = invokeBoolean(edge, "isHorizontal");
                boolean vertical = invokeBoolean(edge, "isVertical");
                if (wantsHorizontal && !horizontal) {
                    continue;
                }
                if (wantsVertical && !vertical) {
                    continue;
                }
                double distance = distanceToEdge(edge, refX, refZ, horizontal);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        return bestIndex >= 0 ? bestIndex : null;
    }

    private boolean isEdgeOnRequestedSide(
            ResizeDirection direction,
            Object edge,
            double refX,
            double refZ,
            boolean horizontal
    ) throws ReflectiveOperationException {
        if (horizontal) {
            int edgeZ = invokePointCoordinate(edge, "start", "z");
            return switch (direction) {
                case NORTH -> edgeZ < refZ;
                case SOUTH -> edgeZ > refZ;
                default -> true;
            };
        }

        int edgeX = invokePointCoordinate(edge, "start", "x");
        return switch (direction) {
            case WEST -> edgeX < refX;
            case EAST -> edgeX > refX;
            default -> true;
        };
    }

    private int translateSegmentOffsetForDirection(ResizeDirection direction, int requestedOffset) {
        return switch (direction) {
            case NORTH, WEST -> -requestedOffset;
            default -> requestedOffset;
        };
    }

    private Object insertNodeIfInterior(Object polygon, Object point) throws ReflectiveOperationException {
        if (polygon == null || point == null) {
            return polygon;
        }

        Method matchesMethod = polygon.getClass().getMethod("edgeIndexesContainingInteriorPoint", point.getClass());
        Object rawMatches = matchesMethod.invoke(polygon, point);
        if (!(rawMatches instanceof List<?> matches) || matches.isEmpty()) {
            return polygon;
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException("That node point does not resolve to a single editable boundary segment.");
        }

        Object edgeIndexRaw = matches.get(0);
        if (!(edgeIndexRaw instanceof Number number)) {
            throw new IllegalArgumentException("Could not resolve boundary segment index for node insertion.");
        }
        int edgeIndex = number.intValue();

        Method insertMethod = polygon.getClass().getMethod("insertNode", int.class, point.getClass());
        return insertMethod.invoke(polygon, edgeIndex, point);
    }

    private boolean invokeBoolean(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        Object value = method.invoke(target);
        return value instanceof Boolean && (Boolean) value;
    }

    private double distanceToEdge(Object edge, double refX, double refZ, boolean horizontal) throws ReflectiveOperationException {
        int minX = invokeIntOr(edge, "minX", 0);
        int maxX = invokeIntOr(edge, "maxX", minX);
        int minZ = invokeIntOr(edge, "minZ", 0);
        int maxZ = invokeIntOr(edge, "maxZ", minZ);
        if (horizontal) {
            int edgeZ = invokePointCoordinate(edge, "start", "z");
            double clampedX = Math.max(minX, Math.min(maxX, refX));
            return Math.abs(refX - clampedX) + Math.abs(refZ - edgeZ);
        }

        int edgeX = invokePointCoordinate(edge, "start", "x");
        double clampedZ = Math.max(minZ, Math.min(maxZ, refZ));
        return Math.abs(refZ - clampedZ) + Math.abs(refX - edgeX);
    }

    private int invokePointCoordinate(Object edge, String endpointMethod, String coordinateMethod) throws ReflectiveOperationException {
        Method endpoint = edge.getClass().getMethod(endpointMethod);
        Object point = endpoint.invoke(edge);
        if (point == null) {
            return 0;
        }
        Method coordinate = point.getClass().getMethod(coordinateMethod);
        Object value = coordinate.invoke(point);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private int invokeIntOr(Object target, String methodName, int fallback) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        Object value = method.invoke(target);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private Object applyPolygonEdgeOffset(Object polygon, int edgeIndex, int amount) {
        try {
            Method expandEdge = polygon.getClass().getMethod("expandEdge", int.class, int.class);
            return expandEdge.invoke(polygon, edgeIndex, amount);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private boolean extractValidationValid(Object validationResult) {
        if (validationResult == null) {
            return false;
        }
        try {
            Method isValid = validationResult.getClass().getMethod("isValid");
            Object value = isValid.invoke(validationResult);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (ReflectiveOperationException ignored) { }
        try {
            Field field = validationResult.getClass().getField("valid");
            Object value = field.get(validationResult);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private Object extractValidationPolygon(Object validationResult) {
        if (validationResult == null) {
            return null;
        }
        try {
            Method polygon = validationResult.getClass().getMethod("polygon");
            return polygon.invoke(validationResult);
        } catch (ReflectiveOperationException ignored) { }
        try {
            Method polygon = validationResult.getClass().getMethod("getPolygon");
            return polygon.invoke(validationResult);
        } catch (ReflectiveOperationException ignored) { }
        try {
            Field field = validationResult.getClass().getField("polygon");
            return field.get(validationResult);
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    private Method findUpdateShapedClaimMethod(Class<?> claimCls, Class<?> polygonCls, Class<?> playerDataCls) {
        if (dataStore == null || claimCls == null || polygonCls == null || playerDataCls == null) {
            return null;
        }

        for (Method method : dataStore.getClass().getMethods()) {
            if (!"updateShapedClaim".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 4) continue;
            if (!Player.class.isAssignableFrom(params[0])) continue;
            if (!params[1].isAssignableFrom(playerDataCls)) continue;
            if (!params[2].isAssignableFrom(claimCls)) continue;
            if (!params[3].isAssignableFrom(polygonCls)) continue;
            return method;
        }

        for (Method method : dataStore.getClass().getDeclaredMethods()) {
            if (!"updateShapedClaim".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 4) continue;
            if (!Player.class.isAssignableFrom(params[0])) continue;
            if (!params[1].isAssignableFrom(playerDataCls)) continue;
            if (!params[2].isAssignableFrom(claimCls)) continue;
            if (!params[3].isAssignableFrom(polygonCls)) continue;
            method.setAccessible(true);
            return method;
        }

        return null;
    }

    private Object resolvePlayerData(UUID playerId) {
        if (dataStore == null || playerId == null) {
            return null;
        }
        try {
            Method method = cachedGetPlayerDataMethod;
            if (method == null) {
                method = dataStore.getClass().getMethod("getPlayerData", UUID.class);
                cachedGetPlayerDataMethod = method;
            }
            return method.invoke(dataStore, playerId);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return null;
        }
    }

    private ClaimCorners cornersFromPolygonBounds(ClaimCorners fallback, Object polygon) {
        if (fallback == null || polygon == null) {
            return fallback;
        }
        try {
            int minX = invokeIntOr(polygon, "minX", fallback.x1);
            int maxX = invokeIntOr(polygon, "maxX", fallback.x2);
            int minZ = invokeIntOr(polygon, "minZ", fallback.z1);
            int maxZ = invokeIntOr(polygon, "maxZ", fallback.z2);
            return new ClaimCorners(minX, fallback.y1, minZ, maxX, fallback.y2, maxZ);
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return fallback;
        }
    }

    private String extractDenialMessage(Object result) {
        if (result == null) {
            return null;
        }
        for (String fieldName : new String[]{"denialMessage", "message"}) {
            try {
                Field field = result.getClass().getField(fieldName);
                Object value = field.get(result);
                String extracted = supplierToMessage(value);
                if (extracted != null && !extracted.isBlank()) {
                    return extracted;
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        for (String methodName : new String[]{"getDenialMessage", "denialMessage", "getMessage"}) {
            try {
                Method method = result.getClass().getMethod(methodName);
                Object value = method.invoke(result);
                String extracted = supplierToMessage(value);
                if (extracted != null && !extracted.isBlank()) {
                    return extracted;
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private String supplierToMessage(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.util.function.Supplier<?> supplier) {
            Object supplied = supplier.get();
            return supplied == null ? null : supplied.toString();
        }
        return value.toString();
    }

    private ResizeFailureReason mapShapedResizeFailure(String message) {
        if (message == null || message.isBlank()) {
            return ResizeFailureReason.APPLY_FAILED;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("need") && lower.contains("claim block")) {
            return ResizeFailureReason.NOT_ENOUGH_BLOCKS;
        }
        if (lower.contains("too small") || lower.contains("too narrow") || lower.contains("at least")) {
            return ResizeFailureReason.TOO_SMALL;
        }
        if (lower.contains("outside") && lower.contains("parent")) {
            return ResizeFailureReason.OUTSIDE_PARENT;
        }
        if (lower.contains("overlap")) {
            return ResizeFailureReason.SIBLING_OVERLAP;
        }
        if (lower.contains("subdivision")) {
            return ResizeFailureReason.WOULD_CLIP_CHILD;
        }
        return ResizeFailureReason.APPLY_FAILED;
    }

    private boolean isHorizontalDirection(ResizeDirection direction) {
        return direction == ResizeDirection.NORTH
                || direction == ResizeDirection.SOUTH
                || direction == ResizeDirection.EAST
                || direction == ResizeDirection.WEST;
    }

    private boolean supportsMapSegmentizedShapedEdit(Object claim, ResizeDirection direction) {
        return claim != null
                && isHorizontalDirection(direction)
                && !is3DClaim(claim)
                && !isSubdivision(claim);
    }

    // =========================
    // Claim block mutation API
    // =========================

    /**
     * Get the corners of a claim as (x1,y1,z1) to (x2,y2,z2)
     */
    public Optional<ClaimCorners> getClaimCorners(Object claim) {
        if (claim == null) return Optional.empty();
        try {
            Method getLesser = claim.getClass().getMethod("getLesserBoundaryCorner");
            Method getGreater = claim.getClass().getMethod("getGreaterBoundaryCorner");
            
            Object lesser = getLesser.invoke(claim);
            Object greater = getGreater.invoke(claim);
            
            Method getX = lesser.getClass().getMethod("getX");
            Method getY = lesser.getClass().getMethod("getY");
            Method getZ = lesser.getClass().getMethod("getZ");
            
            int x1 = ((Number) getX.invoke(lesser)).intValue();
            int y1 = ((Number) getY.invoke(lesser)).intValue();
            int z1 = ((Number) getZ.invoke(lesser)).intValue();
            
            int x2 = ((Number) getX.invoke(greater)).intValue();
            int y2 = ((Number) getY.invoke(greater)).intValue();
            int z2 = ((Number) getZ.invoke(greater)).intValue();
            
            return Optional.of(new ClaimCorners(x1, y1, z1, x2, y2, z2));
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
            return Optional.empty();
        }
    }
    
    /**
     * Get the parent claim of a subclaim, or the claim itself if it's a top-level claim
     */
    public Optional<Object> getParentClaim(Object claim) {
        if (claim == null) return Optional.empty();

        String[] fieldNames = {"parent", "parentClaim"};
        for (String fieldName : fieldNames) {
            Class<?> type = claim.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object parent = field.get(claim);
                    if (parent != null && parent != claim) {
                        return Optional.of(parent);
                    }
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                } catch (ReflectiveOperationException e) {
                    if (DEBUG) e.printStackTrace();
                    break;
                }
            }
        }

        String[] methodNames = {"getParent", "getParentClaim"};
        for (String methodName : methodNames) {
            try {
                Method method = claim.getClass().getMethod(methodName);
                Object parent = method.invoke(claim);
                if (parent != null && parent != claim) {
                    return Optional.of(parent);
                }
            } catch (NoSuchMethodException e) {
                continue;
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }
        
        // No parent found, return the claim itself
        return Optional.of(claim);
    }
    
    /**
     * Get all subclaims of a claim
     * Supports both old GP (separate subclaim files) and new GP3D (Children in parent YAML)
     */
    @SuppressWarnings("unchecked")
    public List<Object> getSubclaims(Object claim) {
        if (claim == null) return Collections.emptyList();
        
        // First try the new GP3D approach with Children
        String[] newMethodNames = {"getChildren", "getChildClaims"};
        for (String methodName : newMethodNames) {
            try {
                Method method = claim.getClass().getMethod(methodName);
                Object children = method.invoke(claim);
                if (children instanceof Collection) {
                    List<Object> result = new ArrayList<>((Collection<Object>) children);
                    if (DEBUG && !result.isEmpty()) {
                        Bukkit.getLogger().info("[GPExpansion][debug] Found " + result.size() + " children using " + methodName);
                    }
                    return result;
                }
            } catch (ReflectiveOperationException e) {
                // Continue to next method name
            }
        }
        
        // Try field access for Children
        String[] newFieldNames = {"children", "childClaims"};
        for (String fieldName : newFieldNames) {
            try {
                Field field = claim.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object children = field.get(claim);
                if (children instanceof Collection) {
                    List<Object> result = new ArrayList<>((Collection<Object>) children);
                    if (DEBUG && !result.isEmpty()) {
                        Bukkit.getLogger().info("[GPExpansion][debug] Found " + result.size() + " children in field " + fieldName);
                    }
                    return result;
                }
            } catch (ReflectiveOperationException e) {
                // Continue to next field name
            }
        }
        
        // Fallback to old GP approach with getSubclaims
        try {
            Method getSubclaims = claim.getClass().getMethod("getSubclaims");
            Object subclaims = getSubclaims.invoke(claim);
            if (subclaims instanceof Collection) {
                List<Object> result = new ArrayList<>((Collection<Object>) subclaims);
                if (DEBUG && !result.isEmpty()) {
                    Bukkit.getLogger().info("[GPExpansion][debug] Found " + result.size() + " subclaims using getSubclaims (old GP)");
                }
                return result;
            }
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
        }
        
        // Try old field names as final fallback
        String[] oldFieldNames = {"subclaims", "subClaims"};
        for (String fieldName : oldFieldNames) {
            try {
                Field field = claim.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object subclaims = field.get(claim);
                if (subclaims instanceof Collection) {
                    List<Object> result = new ArrayList<>((Collection<Object>) subclaims);
                    if (DEBUG && !result.isEmpty()) {
                        Bukkit.getLogger().info("[GPExpansion][debug] Found " + result.size() + " subclaims in field " + fieldName + " (old GP)");
                    }
                    return result;
                }
            } catch (ReflectiveOperationException e) {
                // Continue to next field name
            }
        }
        
        if (DEBUG) {
            Bukkit.getLogger().info("[GPExpansion][debug] No subclaims/children found for claim");
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Get all claims where the player has any trust level
     */
    public List<Object> getClaimsWhereTrusted(UUID playerId) {
        List<Object> result = new ArrayList<>();
        if (!isAvailable() || playerId == null) return result;
        
        try {
            // Get all claims
            List<Object> allClaims = getAllClaims();
            
            // Check each claim if player has any trust
            for (Object claim : allClaims) {
                if (hasAnyTrust(claim, playerId)) {
                    result.add(claim);
                }
            }
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }
        
        return result;
    }
    
    /**
     * Check if a player has build or inventory trust in a claim.
     */
    public boolean hasBuildOrInventoryTrust(Object claim, UUID playerId) {
        if (claim == null || playerId == null) return false;
        if (isTrusted(claim, playerId, "build")) return true;
        if (isTrusted(claim, playerId, "inventory")) return true;
        if (isTrusted(claim, playerId, "access")) return true;
        if (isTrusted(claim, playerId, "manager")) return true;
        return false;
    }

    public enum TrustLevel {
        MANAGE,
        BUILD,
        CONTAINERS,
        ACCESS
    }

    public EnumSet<TrustLevel> getTrustLevels(Object claim, UUID playerId) {
        EnumSet<TrustLevel> levels = EnumSet.noneOf(TrustLevel.class);
        if (claim == null || playerId == null) return levels;

        if (isTrusted(claim, playerId, "manager")) levels.add(TrustLevel.MANAGE);
        if (isTrusted(claim, playerId, "build")) levels.add(TrustLevel.BUILD);
        if (isTrusted(claim, playerId, "inventory")) levels.add(TrustLevel.CONTAINERS);
        if (isTrusted(claim, playerId, "access")) levels.add(TrustLevel.ACCESS);
        return levels;
    }

    public Map<UUID, EnumSet<TrustLevel>> getTrustedPlayers(Object claim) {
        Map<UUID, EnumSet<TrustLevel>> trusted = new LinkedHashMap<>();
        if (claim == null) return trusted;

        mergeTrustCollection(trusted, claim, TrustLevel.MANAGE, "manager", "permission");
        mergeTrustCollection(trusted, claim, TrustLevel.BUILD, "build");
        mergeTrustCollection(trusted, claim, TrustLevel.CONTAINERS, "inventory", "container");
        mergeTrustCollection(trusted, claim, TrustLevel.ACCESS, "access");
        return trusted;
    }

    /**
     * Create a 1x1x1 subdivision at the given location (GP3D).
     * Returns the new claim ID if successful, empty otherwise.
     */
    public Optional<String> create1x1SubdivisionAt(Location loc, UUID ownerId, Object parentClaim) {
        if (!isAvailable() || !isGP3D() || parentClaim == null || loc == null) return Optional.empty();
        try {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            org.bukkit.World world = loc.getWorld();
            if (world == null) return Optional.empty();

            Class<?> dsClass = dataStore.getClass();
            Object[] args = new Object[]{world, x, x, y, y, z, z, ownerId, parentClaim, null, null, false};

            // Find and invoke createClaim(World, int, int, int, int, int, int, UUID, Claim, Long, Player, boolean)
            for (Method m : dsClass.getMethods()) {
                if (!"createClaim".equals(m.getName()) || m.getParameterCount() != 12) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0] != org.bukkit.World.class || params[7] != UUID.class || params[9] != Long.class
                    || params[10] != org.bukkit.entity.Player.class || params[11] != boolean.class) continue;
                if (params[1] != int.class || params[2] != int.class || params[3] != int.class
                    || params[4] != int.class || params[5] != int.class || params[6] != int.class) continue;
                if (!params[8].isAssignableFrom(parentClaim.getClass())) continue;
                try {
                    Object result = m.invoke(dataStore, args);
                    if (result != null) {
                        boolean ok = false;
                        try { ok = Boolean.TRUE.equals(result.getClass().getField("succeeded").get(result)); } catch (Exception e1) {
                            try { ok = Boolean.TRUE.equals(result.getClass().getMethod("succeeded").invoke(result)); } catch (Exception e2) {
                                try { ok = Boolean.TRUE.equals(result.getClass().getMethod("getSucceeded").invoke(result)); } catch (Exception ignored) {}
                            }
                        }
                        if (ok) {
                            Object newClaim = null;
                            try { newClaim = result.getClass().getField("claim").get(result); } catch (Exception e1) {
                                try { newClaim = result.getClass().getMethod("getClaim").invoke(result); } catch (Exception ignored) {}
                            }
                            if (newClaim != null) {
                                Optional<String> id = getClaimId(newClaim);
                                if (id.isPresent()) return id;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG) Bukkit.getLogger().info("[GPBridge] createClaim invoke: " + e.getMessage());
                }
                break; // Only try the first matching method
            }

            // Fallback: try various reflective patterns
            for (Method m : dsClass.getMethods()) {
                String name = m.getName().toLowerCase();
                if (!name.contains("create") && !name.contains("sub")) continue;
                if (m.getParameterCount() < 6) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    Object newClaim = null;
                    if (params.length >= 9 && params[0] == org.bukkit.World.class && params[8] == UUID.class) {
                        newClaim = m.invoke(dataStore, world, x, y, z, x, y, z, ownerId, parentClaim);
                    } else if (params.length >= 8 && params[0] == org.bukkit.World.class) {
                        if (params[7] == UUID.class) {
                            newClaim = m.invoke(dataStore, world, x, y, z, x, y, z, ownerId);
                        } else {
                            newClaim = m.invoke(dataStore, world, x, y, z, x, y, z, ownerId.toString(), parentClaim);
                        }
                    } else if (params.length >= 7 && params[0] == org.bukkit.World.class) {
                        if (params[6] == UUID.class) {
                            newClaim = m.invoke(dataStore, world, x, y, z, x, y, z, ownerId);
                        } else {
                            newClaim = m.invoke(dataStore, world, x, y, z, x, y, z, ownerId.toString());
                        }
                    }
                    if (newClaim != null) {
                        Optional<String> id = getClaimId(newClaim);
                        if (id.isPresent()) return id;
                    }
                } catch (Exception ignored) {}
            }

            // Try claim.createSubclaim / addSubClaim / createChild etc. on parent (6 ints: x1,y1,z1,x2,y2,z2)
            for (Method m : parentClaim.getClass().getMethods()) {
                String name = m.getName().toLowerCase();
                if (!name.contains("create") && !name.contains("add") && !name.contains("sub")) continue;
                if (m.getParameterCount() != 6) continue;
                Class<?>[] params = m.getParameterTypes();
                boolean allInt = true;
                for (Class<?> p : params) { if (p != int.class && p != Integer.class) { allInt = false; break; } }
                if (!allInt) continue;
                try {
                    Object newClaim = m.invoke(parentClaim, x, y, z, x, y, z);
                    if (newClaim != null) {
                        Optional<String> id = getClaimId(newClaim);
                        if (id.isPresent()) return id;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }
        return Optional.empty();
    }

    /**
     * Create a 1x1 (one block XZ) 2D subdivision at the given location for regular (non-GP3D) GP.
     * Returns the new claim ID or empty if not available or not GP3D (use create1x1SubdivisionAt for 3D).
     */
    public Optional<String> create1x1Subdivision2DAt(Location loc, UUID ownerId, Object parentClaim) {
        if (!isAvailable() || isGP3D() || parentClaim == null || loc == null) return Optional.empty();
        org.bukkit.World world = loc.getWorld();
        if (world == null) return Optional.empty();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        try {
            Class<?> dsClass = dataStore.getClass();
            // Try 2D-style createClaim(World, x1, z1, x2, z2, UUID, Claim, ...)
            for (Method m : dsClass.getMethods()) {
                if (!"createClaim".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 6 || p[0] != org.bukkit.World.class) continue;
                try {
                    Object result = null;
                    if (p.length == 8 && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class
                        && p[5] == UUID.class && p[6].isAssignableFrom(parentClaim.getClass())) {
                        result = m.invoke(dataStore, world, x, z, x, z, ownerId, parentClaim);
                    } else if (p.length == 7 && p[5] == UUID.class && p[6].isAssignableFrom(parentClaim.getClass())) {
                        result = m.invoke(dataStore, world, x, z, x, z, ownerId, parentClaim);
                    } else if (p.length == 11 && p[5] == int.class && p[6] == int.class) {
                        // (World, x1, x2, y1, y2, z1, z2, UUID, Claim, Long, Player) - use full height for 2D
                        int y1 = world.getMinHeight();
                        int y2 = world.getMaxHeight() - 1;
                        result = m.invoke(dataStore, world, x, x, y1, y2, z, z, ownerId, parentClaim, null, null);
                    } else if (p.length == 12 && p[11] == boolean.class) {
                        int y1 = world.getMinHeight();
                        int y2 = world.getMaxHeight() - 1;
                        result = m.invoke(dataStore, world, x, x, y1, y2, z, z, ownerId, parentClaim, null, null, false);
                    }
                    if (result != null) {
                        boolean ok = false;
                        try { ok = Boolean.TRUE.equals(result.getClass().getField("succeeded").get(result)); } catch (Exception e1) {
                            try { ok = Boolean.TRUE.equals(result.getClass().getMethod("succeeded").invoke(result)); } catch (Exception e2) {
                                try { ok = Boolean.TRUE.equals(result.getClass().getMethod("getSucceeded").invoke(result)); } catch (Exception ignored) {}
                            }
                        }
                        if (ok) {
                            Object newClaim = null;
                            try { newClaim = result.getClass().getField("claim").get(result); } catch (Exception e1) {
                                try { newClaim = result.getClass().getMethod("getClaim").invoke(result); } catch (Exception ignored) {}
                            }
                            if (newClaim != null) {
                                Optional<String> id = getClaimId(newClaim);
                                if (id.isPresent()) return id;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (DEBUG) Bukkit.getLogger().info("[GPBridge] create1x1Subdivision2DAt: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Check if a player has any trust level in a claim
     */
    private boolean hasAnyTrust(Object claim, UUID playerId) {
        if (claim == null || playerId == null) return false;
        try {
            Method getOwnerID = claim.getClass().getMethod("getOwnerID");
            UUID ownerId = (UUID) getOwnerID.invoke(claim);
            if (playerId.equals(ownerId)) return true;
            
            // Check explicit trust
            if (isTrusted(claim, playerId, "manager")) return true;
            if (isTrusted(claim, playerId, "build")) return true;
            if (isTrusted(claim, playerId, "access")) return true;
            if (isTrusted(claim, playerId, "inventory")) return true;
            
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    private void mergeTrustCollection(Map<UUID, EnumSet<TrustLevel>> trusted, Object claim, TrustLevel level, String... trustTypes) {
        for (String trustType : trustTypes) {
            for (UUID playerId : getTrustedPlayersForType(claim, trustType)) {
                trusted.computeIfAbsent(playerId, ignored -> EnumSet.noneOf(TrustLevel.class)).add(level);
            }
        }
    }

    private Set<UUID> getTrustedPlayersForType(Object claim, String trustType) {
        Set<UUID> players = new LinkedHashSet<>();
        if (claim == null || trustType == null || trustType.isEmpty()) return players;

        String base = trustType.substring(0, 1).toUpperCase() + trustType.substring(1).toLowerCase(Locale.ROOT);
        String upper = trustType.toUpperCase(Locale.ROOT);
        String lower = trustType.toLowerCase(Locale.ROOT);
        String[] methodNames = {
            "get" + base + "Trusted",
            "get" + base + "Trust",
            "get" + upper + "Trust",
            lower + "Trust"
        };

        for (String methodName : methodNames) {
            try {
                Method getTrust = claim.getClass().getMethod(methodName);
                Object trusted = getTrust.invoke(claim);
                players.addAll(normalizeTrustedEntries(trusted));
                if (!players.isEmpty()) {
                    return players;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
                return players;
            }
        }

        return players;
    }

    private Set<UUID> normalizeTrustedEntries(Object trusted) {
        Set<UUID> players = new LinkedHashSet<>();
        if (!(trusted instanceof Collection<?> collection)) {
            return players;
        }

        for (Object entry : collection) {
            if (entry instanceof UUID uuid) {
                players.add(uuid);
                continue;
            }
            if (!(entry instanceof String stringValue) || stringValue.isBlank()) {
                continue;
            }

            try {
                players.add(UUID.fromString(stringValue));
                continue;
            } catch (IllegalArgumentException ignored) {
            }

            try {
                UUID offlineUuid = Bukkit.getOfflinePlayer(stringValue).getUniqueId();
                if (offlineUuid != null) {
                    players.add(offlineUuid);
                }
            } catch (Exception ignored) {
            }
        }

        return players;
    }
    
    /**
     * Check if GP3D allows nested subclaims (subdivisions inside subdivisions).
     * Returns false if not GP3D or if the setting cannot be determined.
     */
    public boolean getAllowNestedSubclaims() {
        if (!isGP3D() || gpInstance == null) return false;
        try {
            // Try to get the config value for AllowNestedSubclaims
            // Common paths: GriefPrevention.instance.config.claims.allowNestedSubclaims
            Object config = gpInstance.getClass().getMethod("getConfig").invoke(gpInstance);
            if (config != null) {
                // Try direct field access
                try {
                    java.lang.reflect.Field field = config.getClass().getDeclaredField("allowNestedSubclaims");
                    field.setAccessible(true);
                    Object value = field.get(config);
                    if (value instanceof Boolean) return (Boolean) value;
                } catch (NoSuchFieldException ignored) {}
                
                // Try getter method
                try {
                    Method getter = config.getClass().getMethod("getAllowNestedSubclaims");
                    Object value = getter.invoke(config);
                    if (value instanceof Boolean) return (Boolean) value;
                } catch (NoSuchMethodException ignored) {}
                
                // Try isAllowNestedSubclaims
                try {
                    Method getter = config.getClass().getMethod("isAllowNestedSubclaims");
                    Object value = getter.invoke(config);
                    if (value instanceof Boolean) return (Boolean) value;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }
        return false; // Default to false if we can't determine
    }

    /**
     * Check if a claim is a subdivision
     */
    public boolean isSubdivision(Object claim) {
        if (!isAvailable() || claim == null) return false;
        
        // Debug: List available methods
        if (DEBUG) {
            System.out.println("[GPBridge] Available methods on Claim class:");
            for (Method m : claim.getClass().getMethods()) {
                if (m.getName().toLowerCase().contains("parent") || 
                    m.getName().toLowerCase().contains("sub") ||
                    m.getName().toLowerCase().contains("top") ||
                    m.getName().toLowerCase().contains("owner") ||
                    m.getName().toLowerCase().contains("child") ||
                    m.getName().toLowerCase().contains("admin") ||
                    m.getName().toLowerCase().contains("id")) {
                    System.out.println("  " + m.getName() + " - " + m.getParameterCount() + " params");
                }
            }
        }
        
        // First try direct field access for parent
        try {
            Field parentField = claim.getClass().getDeclaredField("parent");
            parentField.setAccessible(true);
            Object parent = parentField.get(claim);
            if (parent != null && parent != claim) {
                if (DEBUG) System.out.println("[GPBridge] Found parent via field access: " + parent);
                return true;
            }
        } catch (Exception e) {
            if (DEBUG) System.out.println("[GPBridge] No parent field accessible");
        }
        
        // Try multiple possible method names for checking subdivisions
        String[] methodNames = {"getParent", "getParentClaim", "getTopLevelClaim", "isSubdivision"};
        
        for (String methodName : methodNames) {
            try {
                if (methodName.equals("isSubdivision")) {
                    // If there's an isSubdivision boolean method
                    Method isSub = claim.getClass().getMethod(methodName);
                    Object result = isSub.invoke(claim);
                    if (result instanceof Boolean) {
                        if (DEBUG) System.out.println("[GPBridge] Found isSubdivision method: " + result);
                        return (Boolean) result;
                    }
                } else {
                    // Try getParent-like methods
                    Method method = claim.getClass().getMethod(methodName);
                    Object parent = method.invoke(claim);
                    if (parent != null && parent != claim) {
                        if (DEBUG) System.out.println("[GPBridge] Found parent via " + methodName + ": " + parent);
                        return true;
                    }
                }
            } catch (NoSuchMethodException e) {
                // Try next method
                continue;
            } catch (ReflectiveOperationException e) {
                if (DEBUG) e.printStackTrace();
            }
        }
        
        // Check if it's in an administrative claim (subdivisions in admin claims)
        try {
            Method isAdmin = claim.getClass().getMethod("isAdminClaim");
            Object adminResult = isAdmin.invoke(claim);
            if (adminResult instanceof Boolean && (Boolean) adminResult) {
                // For admin claims, we need to check if it's a subdivision
                // Try to get the owner - admin claims have no owner, subdivisions do
                Method getOwner = claim.getClass().getMethod("getOwnerID");
                Object owner = getOwner.invoke(claim);
                if (owner != null) {
                    if (DEBUG) System.out.println("[GPBridge] Found subdivision in admin claim (has owner)");
                    return true;
                }
            }
        } catch (ReflectiveOperationException e) {
            // Ignore if methods don't exist
        }
        
        // Additional check: try to get subclaims of the parent to see if this claim is listed
        try {
            // Check if this claim has an ID that suggests it's a subclaim
            // Some versions use naming conventions
            Method getClaimId = claim.getClass().getMethod("getID");
            Object id = getClaimId.invoke(claim);
            if (DEBUG) System.out.println("[GPBridge] Claim ID: " + id);
            if (id instanceof String) {
                String idStr = (String) id;
                // Some versions use dot notation for subclaims (e.g., "123.1")
                if (idStr.contains(".")) {
                    if (DEBUG) System.out.println("[GPBridge] Detected subclaim by ID format: " + idStr);
                    return true;
                }
            }
        } catch (ReflectiveOperationException e) {
            // Ignore
        }
        
        // Try checking if claim has children (reverse check)
        try {
            Method getChildren = claim.getClass().getMethod("getChildren");
            Object children = getChildren.invoke(claim);
            if (children instanceof Collection) {
                // If it has children, it might be a parent claim, not a subdivision
                if (DEBUG) System.out.println("[GPBridge] Claim has children, not a subdivision");
            }
        } catch (ReflectiveOperationException e) {
            // Ignore
        }
        
        if (DEBUG) System.out.println("[GPBridge] Claim is not a subdivision");
        return false;
    }
    
    /**
     * Check if a claim is a 3D claim
     */
    public boolean is3DClaim(Object claim) {
        if (!isAvailable() || claim == null) return false;
        try {
            Method getMinY = claim.getClass().getMethod("getMinY");
            Method getMaxY = claim.getClass().getMethod("getMaxY");
            Object minY = getMinY.invoke(claim);
            Object maxY = getMaxY.invoke(claim);
            if (minY instanceof Integer && maxY instanceof Integer) {
                // For 3D claims, minY and maxY should be set (they can be equal for 1-high claims)
                // 2D claims have minY = default min height and maxY = default max height
                int minVal = ((Integer) minY).intValue();
                int maxVal = ((Integer) maxY).intValue();
                // Check if it's a reasonable 3D claim (not full world height)
                boolean is3D = (maxVal - minVal) < 200; // 2D claims typically span full world height
                if (DEBUG) {
                    System.out.println("[GPBridge] is3DClaim check: minY=" + minVal + ", maxY=" + maxVal + 
                        ", diff=" + (maxVal - minVal) + ", is3D=" + is3D);
                }
                return is3D;
            }
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
    }

    /**
     * Check if a claim uses shaped (non-rectangular) geometry.
     */
    public boolean isShapedClaim(Object claim) {
        if (!isAvailable() || claim == null) return false;
        try {
            Method isShaped = claim.getClass().getMethod("isShaped");
            Object result = isShaped.invoke(claim);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (ReflectiveOperationException ignored) {
            // Older GP builds may not expose shaped geometry.
        }
        return false;
    }
    
    /**
     * Get the dimensions of a claim (width, height, depth)
     */
    public int[] getClaimDimensions(Object claim) {
        if (!isAvailable() || claim == null) return new int[]{0, 0, 0};
        try {
            ClaimCorners corners = getClaimCorners(claim).orElse(null);
            if (corners == null) return new int[]{0, 0, 0};
            int width = corners.x2 - corners.x1 + 1;
            int height = corners.y2 - corners.y1 + 1;
            int depth = corners.z2 - corners.z1 + 1;
            return new int[]{width, height, depth};
        } catch (Exception e) {
            if (DEBUG) e.printStackTrace();
        }
        return new int[]{0, 0, 0};
    }
    
    private boolean isTrusted(Object claim, UUID playerId, String trustType) {
        try {
            // Try different method name patterns
            String[] methodNames = {
                "get" + trustType.substring(0, 1).toUpperCase() + trustType.substring(1) + "Trusted",
                "get" + trustType.substring(0, 1).toUpperCase() + trustType.substring(1) + "Trust",
                "get" + trustType.toUpperCase() + "Trust",
                trustType.toLowerCase() + "Trust"
            };
            
            ReflectiveOperationException lastException = null;
            
            for (String methodName : methodNames) {
                try {
                    Method getTrust = claim.getClass().getMethod(methodName);
                    Object trusted = getTrust.invoke(claim);
                    if (trusted instanceof Collection) {
                        return ((Collection<?>) trusted).contains(playerId);
                    }
                    return false; // Method exists but doesn't return a collection
                } catch (NoSuchMethodException e) {
                    lastException = e;
                    continue; // Try next method name
                } catch (ReflectiveOperationException e) {
                    if (DEBUG) e.printStackTrace();
                    return false;
                }
            }
            
            // If we get here, no method was found
            if (DEBUG && lastException != null) {
                try {
                    Bukkit.getLogger().warning("Trust method not found for type: " + trustType);
                } catch (Throwable ignored) {}
            }
        } catch (Exception e) {
            if (DEBUG) {
                try {
                    Bukkit.getLogger().warning("Error checking trust for type: " + trustType);
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }
    
    /**
     * Simple data class to hold claim corner coordinates
     */
    public static class ClaimCorners {
        public final int x1, y1, z1, x2, y2, z2;
        
        public ClaimCorners(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
        }
    }

    private int computeMaxExpand(Object claim, ClaimCorners current, ResizeDirection direction, int remainingBlocks, int width, int depth) {
        if (direction == ResizeDirection.UP || direction == ResizeDirection.DOWN) {
            Object parent = getParentClaim(claim).orElse(null);
            if (parent == null || parent == claim) return 0;

            ClaimCorners parentCorners = getClaimCorners(parent).orElse(null);
            if (parentCorners == null) return 0;

            return switch (direction) {
                case UP -> Math.max(0, parentCorners.y2 - current.y2);
                case DOWN -> Math.max(0, current.y1 - parentCorners.y1);
                default -> 0;
            };
        }

        int oppositeAxis = (direction == ResizeDirection.NORTH || direction == ResizeDirection.SOUTH) ? width : depth;
        if (oppositeAxis <= 0) return 0;
        return Math.max(0, remainingBlocks / oppositeAxis);
    }

    private int computeMaxShrink(Object claim, ClaimCorners current, ResizeDirection direction, int width, int height, int depth, int minWidth, int minArea, boolean enforceMinSize) {
        int maxShrink;
        if (direction == ResizeDirection.UP || direction == ResizeDirection.DOWN) {
            int minHeight = 1;
            maxShrink = Math.max(0, height - minHeight);
        } else {
            boolean northSouth = direction == ResizeDirection.NORTH || direction == ResizeDirection.SOUTH;
            int axisLength = northSouth ? depth : width;
            int otherAxisLength = northSouth ? width : depth;
            if (!enforceMinSize) {
                maxShrink = Math.max(0, axisLength - 1);
            } else {
                int maxShrinkFromWidth = Math.max(0, axisLength - minWidth);
                int minAxisForArea = otherAxisLength > 0 ? (int) Math.ceil((double) minArea / otherAxisLength) : axisLength;
                maxShrink = Math.min(maxShrinkFromWidth, Math.max(0, axisLength - minAxisForArea));
            }
        }

        for (Object child : getSubclaims(claim)) {
            ClaimCorners childCorners = getClaimCorners(child).orElse(null);
            if (childCorners == null) continue;
            maxShrink = Math.min(maxShrink, computeChildShrinkLimit(claim, current, child, childCorners, direction));
        }

        return Math.max(0, maxShrink);
    }

    private int computeChildShrinkLimit(Object claim, ClaimCorners parent, Object childClaim, ClaimCorners child, ResizeDirection direction) {
        boolean insetRequired = isSubdivision(claim);
        int horizontalInset = insetRequired ? 1 : 0;
        int verticalInset = insetRequired && is3DClaim(claim) && is3DClaim(childClaim) ? 1 : 0;
        return switch (direction) {
            case NORTH -> Math.max(0, child.z1 - parent.z1 - horizontalInset);
            case SOUTH -> Math.max(0, parent.z2 - child.z2 - horizontalInset);
            case WEST -> Math.max(0, child.x1 - parent.x1 - horizontalInset);
            case EAST -> Math.max(0, parent.x2 - child.x2 - horizontalInset);
            case UP -> Math.max(0, parent.y2 - child.y2 - verticalInset);
            case DOWN -> Math.max(0, child.y1 - parent.y1 - verticalInset);
        };
    }

    private ClaimCorners newCornersForOffset(ClaimCorners current, ResizeDirection direction, int offset) {
        int newX1 = current.x1;
        int newX2 = current.x2;
        int newY1 = current.y1;
        int newY2 = current.y2;
        int newZ1 = current.z1;
        int newZ2 = current.z2;

        switch (direction) {
            case NORTH -> newZ1 -= offset;
            case SOUTH -> newZ2 += offset;
            case WEST -> newX1 -= offset;
            case EAST -> newX2 += offset;
            case UP -> newY2 += offset;
            case DOWN -> newY1 -= offset;
        }

        return new ClaimCorners(newX1, newY1, newZ1, newX2, newY2, newZ2);
    }

    private ResizeFailureReason validateParentBounds(Object claim, ClaimCorners proposed) {
        Object parent = getParentClaim(claim).orElse(null);
        if (parent == null || parent == claim) return ResizeFailureReason.NONE;

        ClaimCorners parentCorners = getClaimCorners(parent).orElse(null);
        if (parentCorners == null) return ResizeFailureReason.NONE;

        if (isSubdivision(parent)) {
            boolean violatesXZ = proposed.x1 < parentCorners.x1 + 1
                || proposed.x2 > parentCorners.x2 - 1
                || proposed.z1 < parentCorners.z1 + 1
                || proposed.z2 > parentCorners.z2 - 1;
            boolean violatesY = is3DClaim(parent) && (
                proposed.y1 < parentCorners.y1 + 1
                    || proposed.y2 > parentCorners.y2 - 1
            );
            return (violatesXZ || violatesY) ? ResizeFailureReason.INNER_SUBDIVISION_TOO_CLOSE : ResizeFailureReason.NONE;
        }

        return proposed.x1 >= parentCorners.x1
            && proposed.x2 <= parentCorners.x2
            && proposed.y1 >= parentCorners.y1
            && proposed.y2 <= parentCorners.y2
            && proposed.z1 >= parentCorners.z1
            && proposed.z2 <= parentCorners.z2
            ? ResizeFailureReason.NONE
            : ResizeFailureReason.OUTSIDE_PARENT;
    }

    private ResizeFailureReason validateContainedChildren(Object claim, ClaimCorners proposed) {
        boolean subdivision = isSubdivision(claim);
        int inset = subdivision ? 1 : 0;
        for (Object child : getSubclaims(claim)) {
            ClaimCorners childCorners = getClaimCorners(child).orElse(null);
            if (childCorners == null) continue;
            if (subdivision) {
                boolean violatesX = childCorners.x1 < proposed.x1 + inset || childCorners.x2 > proposed.x2 - inset;
                boolean violatesZ = childCorners.z1 < proposed.z1 + inset || childCorners.z2 > proposed.z2 - inset;
                boolean violatesY = is3DClaim(claim) && is3DClaim(child)
                    && (childCorners.y1 < proposed.y1 + inset || childCorners.y2 > proposed.y2 - inset);
                if (violatesX || violatesZ || violatesY) {
                    return ResizeFailureReason.INNER_SUBDIVISION_TOO_CLOSE;
                }
                continue;
            }

            boolean containedX = proposed.x1 <= childCorners.x1 && proposed.x2 >= childCorners.x2;
            boolean containedZ = proposed.z1 <= childCorners.z1 && proposed.z2 >= childCorners.z2;
            if (!(containedX && containedZ)) {
                return ResizeFailureReason.WOULD_CLIP_CHILD;
            }
        }
        return ResizeFailureReason.NONE;
    }

    private ResizeFailureReason validateSiblingSpacing(Object claim, ClaimCorners proposed) {
        Object parent = getParentClaim(claim).orElse(null);
        if (parent == null || parent == claim) return ResizeFailureReason.NONE;

        boolean parentIs3D = is3DClaim(parent);
        boolean claimIs3D = is3DClaim(claim);
        boolean nestedParent = isSubdivision(parent);
        boolean allowNested3DStacking = isNestedSubclaimsEnabled() && claimIs3D;
        String currentClaimId = getClaimId(claim).orElse(null);

        for (Object sibling : getSubclaims(parent)) {
            if (sibling == claim) continue;
            if (currentClaimId != null && currentClaimId.equals(getClaimId(sibling).orElse(null))) continue;

            ClaimCorners siblingCorners = getClaimCorners(sibling).orElse(null);
            if (siblingCorners == null) continue;

            boolean xOverlap = proposed.x1 <= siblingCorners.x2 && proposed.x2 >= siblingCorners.x1;
            boolean zOverlap = proposed.z1 <= siblingCorners.z2 && proposed.z2 >= siblingCorners.z1;
            if (!xOverlap || !zOverlap) {
                continue;
            }

            boolean siblingIs3D = is3DClaim(sibling);
            boolean verticalSeparated = proposed.y2 < siblingCorners.y1 || proposed.y1 > siblingCorners.y2;
            if (parentIs3D) {
                if (allowNested3DStacking && siblingIs3D && verticalSeparated && !nestedParent) {
                    continue;
                }
            } else if (verticalSeparated) {
                continue;
            }

            if (!claimIs3D && !siblingIs3D) {
                int overlapWidth = Math.min(proposed.x2, siblingCorners.x2) - Math.max(proposed.x1, siblingCorners.x1) + 1;
                int overlapDepth = Math.min(proposed.z2, siblingCorners.z2) - Math.max(proposed.z1, siblingCorners.z1) + 1;
                if (overlapWidth <= 0 || overlapDepth <= 0) {
                    continue;
                }
            }

            return ResizeFailureReason.SIBLING_OVERLAP;
        }

        return ResizeFailureReason.NONE;
    }

    private boolean usesMinimumSizeRules(Object claim) {
        return !isSubdivision(claim);
    }

    private long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private int unpackZ(long packed) {
        return (int) packed;
    }

    private boolean isNestedSubclaimsEnabled() {
        Boolean value = getGpConfigBoolean("config_claims_allowNestedSubClaims");
        return Boolean.TRUE.equals(value);
    }

    private int getConfiguredMinWidth() {
        Integer value = getGpConfigInt("config_claims_minWidth");
        return value != null && value > 0 ? value : 5;
    }

    private int getConfiguredShapedMinWidth() {
        Integer value = getGpConfigInt("config_claims_shapedMinWidth");
        return value != null && value > 0 ? value : 1;
    }

    private int getConfiguredMinArea(int minWidth) {
        Integer value = getGpConfigInt("config_claims_minArea");
        int fallback = minWidth * minWidth;
        return value != null && value > 0 ? value : fallback;
    }

    private int getConfiguredShapedMinArea() {
        Integer value = getGpConfigInt("config_claims_shapedMinArea");
        return value != null && value > 0 ? value : 25;
    }

    /**
     * Reads the double value of a GP config field (e.g. {@code config_economy_claimBlocksPurchaseCost}).
     * Returns null if the field is missing or not a number.
     */
    public Double getGpConfigDouble(String fieldName) {
        if (gpInstance == null) return null;
        try {
            Field field = gpInstance.getClass().getField(fieldName);
            Object value = field.get(gpInstance);
            if (value instanceof Number) return ((Number) value).doubleValue();
        } catch (ReflectiveOperationException ignored) {}
        try {
            Field field = gpInstance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(gpInstance);
            if (value instanceof Number) return ((Number) value).doubleValue();
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    /** Is GP's "buy/sell claim blocks" economy feature enabled (config_economy_claimBlocksEnabled)? */
    public boolean isClaimBlocksEconomyEnabled() {
        Boolean v = getGpConfigBoolean("config_economy_claimBlocksEnabled");
        return v != null && v;
    }

    /** Cost per claim block for /buyclaimblocks (config_economy_claimBlocksPurchaseCost). */
    public double getClaimBlocksPurchaseCost() {
        Double v = getGpConfigDouble("config_economy_claimBlocksPurchaseCost");
        return v != null ? v : 0.0D;
    }

    /** Credit {@code amount} bonus claim blocks to the player (and persist). */
    public boolean creditBonusClaimBlocks(Player player, int amount) {
        if (amount <= 0) return false;
        return changeClaimBlocks(player, amount);
    }

    /** Returns the player's current remaining claim blocks, or 0 if unavailable. */
    public int getRemainingClaimBlocks(Player player) {
        return getPlayerClaimStats(player).map(s -> s.remaining).orElse(0);
    }

    private Integer getGpConfigInt(String fieldName) {
        if (gpInstance == null) return null;
        try {
            Field field = gpInstance.getClass().getField(fieldName);
            Object value = field.get(gpInstance);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (ReflectiveOperationException ignored) {}
        try {
            Field field = gpInstance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(gpInstance);
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    private Boolean getGpConfigBoolean(String fieldName) {
        if (gpInstance == null) return null;
        try {
            Field field = gpInstance.getClass().getField(fieldName);
            Object value = field.get(gpInstance);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Method getter = gpInstance.getClass().getMethod(fieldName);
            Object value = getter.invoke(gpInstance);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    private Method findResizeClaimMethod(Class<?> claimCls) {
        if (dataStore == null || claimCls == null) return null;
        for (Method method : dataStore.getClass().getMethods()) {
            if (!"resizeClaim".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 8) continue;
            if (!params[0].isAssignableFrom(claimCls)) continue;
            boolean ints = true;
            for (int i = 1; i <= 6; i++) {
                if (params[i] != int.class && params[i] != Integer.TYPE) {
                    ints = false;
                    break;
                }
            }
            if (!ints) continue;
            if (!Player.class.isAssignableFrom(params[7])) continue;
            return method;
        }
        return null;
    }

    private boolean extractResizeSucceeded(Object result) {
        if (result == null) return false;
        if (result instanceof Boolean) return (Boolean) result;
        for (String fieldName : new String[]{"succeeded", "success"}) {
            try {
                Field field = result.getClass().getField(fieldName);
                Object value = field.get(result);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (ReflectiveOperationException ignored) {}
        }
        for (String methodName : new String[]{"succeeded", "getSucceeded", "isSucceeded", "isSuccess", "getSuccess"}) {
            try {
                Method method = result.getClass().getMethod(methodName);
                Object value = method.invoke(result);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (ReflectiveOperationException ignored) {}
        }
        return false;
    }

    private Object extractResultClaim(Object result) {
        if (result == null) return null;
        for (String fieldName : new String[]{"claim", "resultClaim"}) {
            try {
                Field field = result.getClass().getField(fieldName);
                return field.get(result);
            } catch (ReflectiveOperationException ignored) {}
        }
        for (String methodName : new String[]{"getClaim", "claim"}) {
            try {
                Method method = result.getClass().getMethod(methodName);
                return method.invoke(result);
            } catch (ReflectiveOperationException ignored) {}
        }
        return null;
    }
    
    /**
     * Adjust a player's claim blocks. Negative delta charges claim blocks.
     * Returns true if applied. Ensures no negative balances; will prefer deducting bonus, then accrued.
     */
    public boolean changeClaimBlocks(Player player, int delta) {
        if (!isAvailable()) return false;
        try {
            Object pd = dataStore.getClass().getMethod("getPlayerData", UUID.class).invoke(dataStore, player.getUniqueId());
            if (pd == null) return false;

            int accrued = getPdInt(pd, "getAccruedClaimBlocks", "accruedClaimBlocks");
            int bonus = getPdInt(pd, "getBonusClaimBlocks", "bonusClaimBlocks");
            if (delta == 0) return true;

            if (delta < 0) {
                int need = -delta;
                int total = accrued + bonus;
                if (total < need) return false; // insufficient
                // deduct from bonus first
                int fromBonus = Math.min(bonus, need);
                bonus -= fromBonus;
                need -= fromBonus;
                if (need > 0) {
                    accrued -= need;
                }
            } else {
                // Adding blocks: put into bonus
                bonus += delta;
            }

            // write back
            setPdInt(pd, "setAccruedClaimBlocks", "accruedClaimBlocks", accrued);
            setPdInt(pd, "setBonusClaimBlocks", "bonusClaimBlocks", bonus);
            // Some forks require a save; attempt savePlayerData if present
            try {
                Method save = dataStore.getClass().getMethod("savePlayerData", UUID.class, pd.getClass());
                save.invoke(dataStore, player.getUniqueId(), pd);
            } catch (NoSuchMethodException ignored) {
                // best effort
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private int getPdInt(Object pd, String getterName, String fieldName) {
        Integer val = null;
        try {
            Method m = pd.getClass().getMethod(getterName);
            Object r = m.invoke(pd);
            if (r instanceof Number) val = ((Number) r).intValue();
        } catch (ReflectiveOperationException ignored) {}
        if (val != null) return val;
        try {
            Field f = pd.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object r = f.get(pd);
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (ReflectiveOperationException ignored) {}
        return 0;
    }

    private void setPdInt(Object pd, String setterName, String fieldName, int value) {
        try {
            Method m = pd.getClass().getMethod(setterName, int.class);
            m.invoke(pd, value);
            return;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Field f = pd.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(pd, value);
        } catch (ReflectiveOperationException ignored) {}
    }

    /**
     * Get all admin claims.
     */
    public List<Object> getAdminClaims() {
        List<Object> results = new ArrayList<>();
        if (!isAvailable()) return results;
        for (Object claim : getAllClaims()) {
            if (claim != null && isAdminClaim(claim) && !isSubdivision(claim)) {
                results.add(claim);
            }
        }
        return results;
    }

    /**
     * Get the owner UUID of a claim.
     */
    public UUID getClaimOwner(Object claim) {
        if (claim == null) return null;
        try {
            Method getOwnerID = claim.getClass().getMethod("getOwnerID");
            Object owner = getOwnerID.invoke(claim);
            if (owner instanceof UUID) return (UUID) owner;
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }
}
