package dev.towki.gpexpansion.gp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Bridge to GriefPrevention main fork (compile-time dependency, provided scope).
 */
public class GPBridge {

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

    public Optional<Object> getClaimAt(Location location) {
        return getClaimAt(location, null);
    }

    public Optional<Object> getClaimAt(Location location, Player player) {
        if (!isAvailable()) {
            if (DEBUG) {
                try { Bukkit.getLogger().warning("[GPExpansion][debug] getClaimAt aborted: GP not available: " + availabilityDiag()); } catch (Throwable ignored) {}
            }
            return Optional.empty();
        }
        try {
            // Find a compatible getClaimAt method
            Method target = null;
            int arity = 0; // 2, 3, or 4
            for (Method m : dataStore.getClass().getMethods()) {
                if (!m.getName().equals("getClaimAt")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (DEBUG) {
                    try {
                        // Debug logging removed
                    } catch (Throwable ignored) {}
                }
                if (p.length == 4 && Location.class.isAssignableFrom(p[0]) && p[1] == boolean.class && p[2] == boolean.class) {
                    target = m;
                    arity = 4;
                    break; // prefer the most specific variant
                } else if (p.length == 3 && Location.class.isAssignableFrom(p[0]) && p[1] == boolean.class) {
                    target = m;
                    arity = 3;
                    // don't break yet; continue to see if a 4-arg exists, but keep this as fallback
                } else if (p.length == 2 && Location.class.isAssignableFrom(p[0]) && p[1] == boolean.class) {
                    if (target == null) { // only keep if nothing else found
                        target = m;
                        arity = 2;
                    }
                }
            }
            if (target == null && claimClass != null) {
                // Fallback to exact signature with claim class if present
                try {
                    target = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
                    arity = 3;
                } catch (NoSuchMethodException ignored) {}
            }
            if (target == null) return Optional.empty();

            // Prepare third argument if required
            Object thirdArg = null;
            if (arity == 3) {
                Class<?>[] p = target.getParameterTypes();
                Class<?> third = p[2];
                if (Player.class.isAssignableFrom(third)) {
                    thirdArg = player; // may be null, but better than always null when available
                } else if (third.getSimpleName().equals("PlayerData") || third.getName().endsWith(".PlayerData")) {
                    if (player != null) {
                        try {
                            Method getPlayerData = dataStore.getClass().getMethod("getPlayerData", java.util.UUID.class);
                            thirdArg = getPlayerData.invoke(dataStore, player.getUniqueId());
                        } catch (ReflectiveOperationException ignored) {
                            thirdArg = null;
                        }
                    }
                }
            }

            // Try ignoreHeight = true first, then false
            if (DEBUG) {
                try {
                    // Debug logging removed
                } catch (Throwable ignored) {}
            }
            Object claim;
            if (arity == 4) {
                // Signature: (Location, boolean ignoreHeight, boolean ignoreSubclaims, Claim cached)
                claim = target.invoke(dataStore, location, true, false, null);
            } else if (arity == 3) {
                claim = target.invoke(dataStore, location, true, thirdArg);
            } else {
                claim = target.invoke(dataStore, location, true);
            }
            if (claim == null) {
                if (arity == 4) {
                    claim = target.invoke(dataStore, location, false, false, null);
                } else if (arity == 3) {
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
        
        // Try multiple possible method names
        String[] methodNames = {"getParent", "getParentClaim", "getTopLevelClaim"};
        
        for (String methodName : methodNames) {
            try {
                Method method = claim.getClass().getMethod(methodName);
                Object parent = method.invoke(claim);
                if (parent != null && parent != claim) {
                    return Optional.of(parent);
                }
            } catch (NoSuchMethodException e) {
                // Try next method
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
        return false;
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
            if (isTrusted(claim, playerId, "build")) return true;
            if (isTrusted(claim, playerId, "access")) return true;
            if (isTrusted(claim, playerId, "inventory")) return true;
            
        } catch (ReflectiveOperationException e) {
            if (DEBUG) e.printStackTrace();
        }
        return false;
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
