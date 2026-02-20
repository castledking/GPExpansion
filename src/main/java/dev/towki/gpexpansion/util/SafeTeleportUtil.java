package dev.towki.gpexpansion.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class SafeTeleportUtil {

    private static final int RADIUS = 16;
    private static final int MAX_Y_SEARCH = 10;
    private static volatile Vector3D[] volume;

    private static final Set<Material> DAMAGING_TYPES = EnumSet.of(
        Material.CACTUS,
        Material.CAMPFIRE,
        Material.FIRE,
        Material.MAGMA_BLOCK,
        Material.SOUL_CAMPFIRE,
        Material.SOUL_FIRE,
        Material.SWEET_BERRY_BUSH,
        Material.WITHER_ROSE
    );

    private static final Set<Material> LAVA_TYPES;

    static {
        LAVA_TYPES = EnumSet.of(Material.LAVA);
        try {
            LAVA_TYPES.add(Material.valueOf("FLOWING_LAVA"));
        } catch (IllegalArgumentException ignored) {}
    }

    private static final Set<Material> HOLLOW_BY_NAME;

    static {
        HOLLOW_BY_NAME = EnumSet.noneOf(Material.class);
        String[] names = {
            "AIR", "CAVE_AIR", "VOID_AIR", "FLOWING_WATER", "WATER", "ICE", "GLASS",
            "GLASS_PANE", "LEAVES", "LEAVES_2", "STRIPPED_LEAVES", "SNOW", "SNOW_BLOCK",
            "TALL_GRASS", "GRASS", "FERN", "LARGE_FERN", "VINE", "GLOW_LICHEN",
            "LIGHT", "REDSTONE_WIRE", "TRIPWIRE", "IRON_BARS", "NETHER_PORTAL",
            "END_PORTAL", "POWERED_RAIL", "RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
            "SUGAR_CANE", "SWEET_BERRY_BUSH", "COCOA", "LADDER", "SCAFFOLDING",
            "BAMBOO", "BAMBOO_SAPLING", "BELL", "BLAST_FURNACE", "BREWING_STAND",
            "CAMPFIRE", "SOUL_CAMPFIRE", "CARTOGRAPHY_TABLE", "COMPOSTER", "CREEPER_HEAD",
            "DRAGON_HEAD", "DRAGON_WALL_HEAD", "PIGLIN_HEAD", "PLAYER_HEAD", "PLAYER_WALL_HEAD",
            "SKELETON_SKULL", "SKELETON_WALL_SKULL", "WITHER_SKELETON_SKULL", "WITHER_SKELETON_WALL_SKULL",
            "ZOMBIE_HEAD", "ZOMBIE_WALL_HEAD", "CHAIN", "CHEST", "ENDER_CHEST",
            "SHULKER_BOX", "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX", "MAGENTA_SHULKER_BOX",
            "LIGHT_BLUE_SHULKER_BOX", "YELLOW_SHULKER_BOX", "LIME_SHULKER_BOX", "PINK_SHULKER_BOX",
            "GRAY_SHULKER_BOX", "LIGHT_GRAY_SHULKER_BOX", "CYAN_SHULKER_BOX", "PURPLE_SHULKER_BOX",
            "BLUE_SHULKER_BOX", "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX", "RED_SHULKER_BOX",
            "BLACK_SHULKER_BOX", "FENCE", "FENCE_GATE", "IRON_DOOR", "OAK_DOOR", "BIRCH_DOOR",
            "SPRUCE_DOOR", "JUNGLE_DOOR", "ACACIA_DOOR", "DARK_OAK_DOOR", "CRIMSON_DOOR",
            "WARPED_DOOR", "IRON_TRAPDOOR", "OAK_TRAPDOOR", "BIRCH_TRAPDOOR", "SPRUCE_TRAPDOOR",
            "JUNGLE_TRAPDOOR", "ACACIA_TRAPDOOR", "DARK_OAK_TRAPDOOR", "CRIMSON_TRAPDOOR",
            "WARPED_TRAPDOOR", "COBBLESTONE_WALL", "MOSSY_COBBLESTONE_WALL", "OAK_WALL_SIGN",
            "SPRUCE_WALL_SIGN", "BIRCH_WALL_SIGN", "JUNGLE_WALL_SIGN", "ACACIA_WALL_SIGN",
            "DARK_OAK_WALL_SIGN", "CRIMSON_WALL_SIGN", "WARPED_WALL_SIGN", "TORCH", "WALL_TORCH",
            "REDSTONE_TORCH", "WALL_REDSTONE_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH", "LANTERN",
            "SOUL_LANTERN", "BEACON", "CONduit", "END_ROD", "POINTED_DRIPSTONE", "DRIPSTONE_BLOCK",
            "SPORE_BLOSSOM", "MOSS_BLOCK", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "HANGING_ROOTS",
            "ROOTED_DIRT", "MUD", "MUDDY_MANGROVE_ROOTS", "MANGROVE_ROOTS", "CANDLE", "WHITE_CANDLE",
            "ORANGE_CANDLE", "MAGENTA_CANDLE", "LIGHT_BLUE_CANDLE", "YELLOW_CANDLE", "LIME_CANDLE",
            "PINK_CANDLE", "GRAY_CANDLE", "LIGHT_GRAY_CANDLE", "CYAN_CANDLE", "PURPLE_CANDLE",
            "BLUE_CANDLE", "BROWN_CANDLE", "GREEN_CANDLE", "RED_CANDLE", "BLACK_CANDLE"
        };
        for (String name : names) {
            try {
                HOLLOW_BY_NAME.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static Vector3D[] getVolume() {
        if (volume == null) {
            synchronized (SafeTeleportUtil.class) {
                if (volume == null) {
                    List<Vector3D> pos = new ArrayList<>();
                    for (int x = -RADIUS; x <= RADIUS; x++) {
                        for (int y = -RADIUS; y <= RADIUS; y++) {
                            for (int z = -RADIUS; z <= RADIUS; z++) {
                                pos.add(new Vector3D(x, y, z));
                            }
                        }
                    }
                    pos.sort(Comparator.comparingInt(a -> a.x * a.x + a.y * a.y + a.z * a.z));
                    volume = pos.toArray(new Vector3D[0]);
                }
            }
        }
        return volume;
    }

    private SafeTeleportUtil() {
    }

    private static boolean isHollow(Material mat) {
        if (HOLLOW_BY_NAME.contains(mat)) return true;
        String name = mat.name();
        return name.contains("SIGN") || name.contains("BUTTON") || name.contains("PRESSURE_PLATE") ||
               name.contains("SLAB") || name.contains("STAIRS") || name.contains("FENCE") ||
               (name.contains("DOOR") && !name.contains("BLOCK")) || name.contains("TRAPDOOR") ||
               name.contains("GATE");
    }

    public static boolean isBlockUnsafe(World world, int x, int y, int z) {
        if (y < world.getMinHeight()) {
            return true;
        }

        Material block = world.getBlockAt(x, y, z).getType();
        Material below = world.getBlockAt(x, y - 1, z).getType();
        Material above = world.getBlockAt(x, y + 1, z).getType();

        // Block below must be solid (not hollow, not lava, not water-like)
        if (LAVA_TYPES.contains(block) || LAVA_TYPES.contains(below) || DAMAGING_TYPES.contains(below) || 
            DAMAGING_TYPES.contains(block) || isBedBlock(below)) {
            return true;
        }

        // Water is also unsafe - must have solid ground
        if (below.name().contains("WATER") || below.name().contains("ICE")) {
            return true;
        }

        if (block == Material.NETHER_PORTAL || block == Material.END_PORTAL) {
            return true;
        }

        return !isHollow(block) || !isHollow(above);
    }

    public static boolean isBlockAboveAir(World world, int x, int y, int z) {
        return y <= world.getMinHeight() || isHollow(world.getBlockAt(x, y - 1, z).getType());
    }

    public static Location getSafeDestination(Location loc) {
        return getSafeDestination(loc, null);
    }

    public static Location getSafeDestination(Location loc, int[] bounds) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }

        World world = loc.getWorld();
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();

        int x = loc.getBlockX();
        int y = (int) Math.round(loc.getY());
        int z = loc.getBlockZ();

        final int origX = x;
        final int origY = y;
        final int origZ = z;
        
        int maxY = Math.min(origY + MAX_Y_SEARCH, worldMaxY);
        int minX = bounds != null ? bounds[0] : origX - 16;
        int maxX = bounds != null ? bounds[1] : origX + 16;
        int minZ = bounds != null ? bounds[2] : origZ - 16;
        int maxZ = bounds != null ? bounds[3] : origZ + 16;

        // Find the nearest SAFE solid block below the spawn point
        // Must skip: lava, air, water, damaging blocks
        int safeGroundY = -1;
        for (int checkY = y - 1; checkY >= worldMinY; checkY--) {
            Material checkBlock = world.getBlockAt(x, checkY, z).getType();
            // Must be solid (not hollow) AND not dangerous
            if (!isHollow(checkBlock) && !LAVA_TYPES.contains(checkBlock) && 
                !DAMAGING_TYPES.contains(checkBlock) && !checkBlock.name().contains("WATER") &&
                !checkBlock.name().contains("ICE") && !isBedBlock(checkBlock)) {
                safeGroundY = checkY;
                break;
            }
        }
        
        // If no solid ground at all, fail
        if (safeGroundY == -1) {
            return null;
        }

        // The safe Y is air above the safe ground
        int safeStandY = safeGroundY + 1;
        
        Vector3D[] vol = getVolume();
        
        // Priority 1: Check if spawn's calculated safe Y is already safe
        if (!isBlockUnsafe(world, x, safeStandY, z)) {
            return new Location(world, x + 0.5, safeStandY, z + 0.5);
        }
        
        // Priority 2: Search at safeStandY level (same level as where player would stand)
        // But start from the rim position, not the center
        Location safeAtStandLevel = findSafeAtLevelFromSolid(world, x, z, safeStandY, vol, bounds, minX, maxX, minZ, maxZ);
        if (safeAtStandLevel != null) {
            return safeAtStandLevel;
        }
        
        // Priority 3: Search upward from safeStandY (within maxY limit)
        for (int searchY = safeStandY; searchY < maxY; searchY++) {
            Location found = findSafeAtLevelFromSolid(world, x, z, searchY, vol, bounds, minX, maxX, minZ, maxZ);
            if (found != null) {
                return found;
            }
        }
        
        return null;
    }
    
    // Search outward from the spawn X,Z position (the center of the pit)
    private static Location findSafeAtLevelFromSolid(World world, int centerX, int centerZ, int y, 
            Vector3D[] vol, int[] bounds, int minX, int maxX, int minZ, int maxZ) {
        
        // Check origin first
        if (!isBlockUnsafe(world, centerX, y, centerZ)) {
            return new Location(world, centerX + 0.5, y, centerZ + 0.5);
        }
        
        // Search outward from center at this Y level
        for (Vector3D offset : vol) {
            int newX = centerX + offset.x;
            int newZ = centerZ + offset.z;
            
            if (bounds != null && (newX < minX || newX > maxX || newZ < minZ || newZ > maxZ)) {
                continue;
            }
            
            if (!isBlockUnsafe(world, newX, y, newZ)) {
                return new Location(world, newX + 0.5, y, newZ + 0.5);
            }
        }
        
        return null;
    }

    public static boolean isLocationSafe(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return !isBlockUnsafe(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()) &&
               !isBlockAboveAir(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static boolean isLocationSafeForSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        
        // Check the block at player position - should be air/hollow
        Material block = world.getBlockAt(x, y, z).getType();
        if (!isHollow(block)) {
            return false;
        }
        
        // Check the block below - must be solid (not hollow, not lava, not damaging)
        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (isHollow(below) || LAVA_TYPES.contains(below) || DAMAGING_TYPES.contains(below) || isBedBlock(below)) {
            return false;
        }
        
        // Check block above - should be air/hollow
        Material above = world.getBlockAt(x, y + 1, z).getType();
        if (!isHollow(above) && above != Material.CAVE_AIR && above != Material.AIR) {
            return false;
        }
        
        return true;
    }

    private static boolean isBedBlock(Material mat) {
        return mat.name().contains("BED") && !mat.name().equals("BEDROCK");
    }

    private static class Vector3D {
        final int x;
        final int y;
        final int z;

        Vector3D(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
