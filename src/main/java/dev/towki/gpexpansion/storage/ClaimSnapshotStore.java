package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.bukkit.entity.Player;

/**
 * Stores and restores block snapshots for claims.
 * Used for eviction restoration when owner has griefprevention.restoresnapshot.
 */
public class ClaimSnapshotStore {
    private static final String PERMISSION = "griefprevention.restoresnapshot";
    private static final int DEBUG_X = 29, DEBUG_Y = 67, DEBUG_Z = 1298;

    private final GPExpansionPlugin plugin;
    private final File dataDir;

    public ClaimSnapshotStore(GPExpansionPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "snapshots");
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    public static class SnapshotEntry {
        public final String id;
        public final long created;

        public SnapshotEntry(String id, long created) {
            this.id = id;
            this.created = created;
        }
    }

    /** A single block change (location + data) for sending to clients after restore. */
    public static class BlockChange {
        public final Location location;
        public final BlockData data;

        public BlockChange(Location location, BlockData data) {
            this.location = location;
            this.data = data;
        }
    }

    /**
     * Create a snapshot of the claim's blocks using structure NBT only (Paper/Spigot 1.20+, Purpur, Folia/Canvas).
     * Claims larger than 128 blocks on any axis cannot be snapshotted.
     */
    public SnapshotEntry createSnapshot(String claimId, Object claim, World world) {
        GPBridge gp = new GPBridge();
        Optional<GPBridge.ClaimCorners> cornersOpt = gp.getClaimCorners(claim);
        if (!cornersOpt.isPresent()) return null;
        GPBridge.ClaimCorners c = cornersOpt.get();
        int minX = Math.min(c.x1, c.x2);
        int maxX = Math.max(c.x1, c.x2);
        int minY = Math.min(c.y1, c.y2);
        int maxY = Math.max(c.y1, c.y2);
        int minZ = Math.min(c.z1, c.z2);
        int maxZ = Math.max(c.z1, c.z2);
        // 2D (cuboid) claims: use full world height only if it fits in 128 blocks (structure limit); otherwise use claim's Y bounds
        if (!gp.is3DClaim(claim)) {
            int worldSpanY = world.getMaxHeight() - world.getMinHeight();
            if (worldSpanY <= 128) {
                minY = world.getMinHeight();
                maxY = world.getMaxHeight() - 1;
            }
            // else keep claim's actual minY/maxY so we stay within 128-block limit when possible
        }
        if (plugin.getConfigManager().isDebugEnabled() && "world".equals(world.getName())) {
            boolean includesTest = (29 >= minX && 29 <= maxX && 67 >= minY && 67 <= maxY && 1298 >= minZ && 1298 <= maxZ);
            plugin.getLogger().info("[Snapshot debug] Create bounds: " + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ + (includesTest ? " (includes 29,67,1298)" : " (29,67,1298 OUTSIDE bounds)"));
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long created = System.currentTimeMillis();
        File claimDir = new File(dataDir, sanitize(claimId));
        claimDir.mkdirs();
        File indexFile = new File(claimDir, "index.yml");
        YamlConfiguration index = indexFile.exists() ? YamlConfiguration.loadConfiguration(indexFile) : new YamlConfiguration();
        List<Map<?, ?>> list = index.getMapList("snapshots");
        if (list == null) list = new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("created", created);

        if (!createSnapshotStructureNbt(world, minX, minY, minZ, maxX, maxY, maxZ, claimDir, id, entry, index, list, indexFile)) {
            return null;
        }
        list.add(entry);
        index.set("snapshots", list);
        try {
            index.save(indexFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save snapshot index: " + e.getMessage());
            return null;
        }
        return new SnapshotEntry(id, created);
    }

    /**
     * Uses Paper/Bukkit Structure API (structure NBT). Works on Paper/Spigot 1.20+, Purpur, Folia.
     * Returns true on success; false if API unavailable, claim &gt;128 per axis, or save failed.
     */
    private boolean createSnapshotStructureNbt(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            File claimDir, String id, Map<String, Object> entry, YamlConfiguration index, List<Map<?, ?>> list, File indexFile) {
        try {
            StructureManager sm = Bukkit.getServer().getStructureManager();
            if (sm == null) {
                plugin.getLogger().warning("StructureManager is null; structure API not available (use Paper/Spigot 1.20+).");
                return false;
            }
            Structure structure = sm.createStructure();
            if (structure == null) {
                plugin.getLogger().warning("createStructure() returned null.");
                return false;
            }
            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            if (sizeX > 128 || sizeY > 128 || sizeZ > 128) {
                plugin.getLogger().warning("Claim too large for structure snapshot (max 128 per axis).");
                return false;
            }
            // Paper API: fill(Location corner1, Location corner2, boolean includeEntities)
            Location corner1 = new Location(world, minX, minY, minZ);
            Location corner2 = new Location(world, maxX, maxY, maxZ);
            try {
                structure.fill(corner1, corner2, false);
            } catch (NoSuchMethodError e) {
                // Older Paper: try fill(Location origin, BlockVector size, boolean)
                try {
                    org.bukkit.util.BlockVector size = new org.bukkit.util.BlockVector(sizeX, sizeY, sizeZ);
                    structure.getClass().getMethod("fill", Location.class, org.bukkit.util.BlockVector.class, boolean.class)
                        .invoke(structure, corner1, size, false);
                } catch (ReflectiveOperationException e2) {
                    plugin.getLogger().warning("Structure.fill not available: " + e2.getMessage());
                    return false;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Structure fill failed: " + t.getMessage());
                if (t.getCause() != null) t.getCause().printStackTrace();
                return false;
            }
            File nbtFile = new File(claimDir, id + ".nbt");
            sm.saveStructure(nbtFile, structure);
            if (!nbtFile.exists() || nbtFile.length() < 32) {
                plugin.getLogger().warning("Structure NBT appears empty.");
                return false;
            }
            entry.put("originX", minX);
            entry.put("originY", minY);
            entry.put("originZ", minZ);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Structure snapshot create failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Restore from structure NBT (load + place). Works on Paper/Spigot and on Folia region thread.
     */
    private boolean restoreSnapshotStructureNbt(String claimId, String snapshotId, World world, File claimDir, File nbtFile) {
        try {
            File indexF = new File(claimDir, "index.yml");
            if (!indexF.exists()) return false;
            YamlConfiguration index = YamlConfiguration.loadConfiguration(indexF);
            List<Map<?, ?>> list = index.getMapList("snapshots");
            if (list == null) return false;
            Map<?, ?> entry = null;
            for (Map<?, ?> m : list) {
                if (Objects.equals(String.valueOf(m.get("id")), snapshotId)) { entry = m; break; }
            }
            if (entry == null || entry.get("originX") == null) return false;
            int ox = ((Number) entry.get("originX")).intValue();
            int oy = ((Number) entry.get("originY")).intValue();
            int oz = ((Number) entry.get("originZ")).intValue();
            StructureManager sm = Bukkit.getServer().getStructureManager();
            if (sm == null) {
                plugin.getLogger().warning("StructureManager is null on restore.");
                return false;
            }
            Structure structure = sm.loadStructure(nbtFile);
            if (structure == null) {
                plugin.getLogger().warning("loadStructure returned null.");
                return false;
            }
            BlockVector size = structure.getSize();
            if (size.getBlockX() <= 0 || size.getBlockY() <= 0 || size.getBlockZ() <= 0) {
                plugin.getLogger().warning("Structure NBT is empty (size " + size.getBlockX() + "," + size.getBlockY() + "," + size.getBlockZ() + ").");
                return false;
            }
            Location origin = new Location(world, ox, oy, oz);
            structure.place(origin, false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Structure snapshot restore failed: " + t.getMessage());
            return false;
        }
    }

    public List<SnapshotEntry> listSnapshots(String claimId) {
        File claimDir = new File(dataDir, sanitize(claimId));
        File indexFile = new File(claimDir, "index.yml");
        if (!indexFile.exists()) return Collections.emptyList();
        YamlConfiguration index = YamlConfiguration.loadConfiguration(indexFile);
        List<Map<?, ?>> list = index.getMapList("snapshots");
        if (list == null) return Collections.emptyList();
        List<SnapshotEntry> out = new ArrayList<>();
        for (Map<?, ?> m : list) {
            String id = String.valueOf(m.get("id"));
            Object c = m.get("created");
            long created = c instanceof Number ? ((Number) c).longValue() : 0L;
            out.add(new SnapshotEntry(id, created));
        }
        out.sort(Comparator.comparingLong(e -> e.created));
        return out;
    }

    /**
     * List all claim IDs that have at least one snapshot (for "list all").
     */
    public List<String> listClaimIdsWithSnapshots() {
        File[] dirs = dataDir.listFiles(File::isDirectory);
        if (dirs == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (File d : dirs) {
            File indexFile = new File(d, "index.yml");
            if (indexFile.exists()) ids.add(d.getName());
        }
        Collections.sort(ids);
        return ids;
    }

    public boolean removeSnapshot(String claimId, String snapshotId) {
        File claimDir = new File(dataDir, sanitize(claimId));
        File dataFile = new File(claimDir, snapshotId + ".snap");
        if (dataFile.exists()) dataFile.delete();
        File nbtFile = new File(claimDir, snapshotId + ".nbt");
        if (nbtFile.exists()) nbtFile.delete();
        File indexFile = new File(claimDir, "index.yml");
        if (!indexFile.exists()) return true;
        YamlConfiguration index = YamlConfiguration.loadConfiguration(indexFile);
        List<Map<?, ?>> list = index.getMapList("snapshots");
        if (list == null) return true;
        list.removeIf(m -> Objects.equals(String.valueOf(m.get("id")), snapshotId));
        index.set("snapshots", list);
        try {
            index.save(indexFile);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public Optional<SnapshotEntry> getLatestSnapshot(String claimId) {
        List<SnapshotEntry> all = listSnapshots(claimId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /**
     * Restore the claim to the given snapshot using structure NBT (.nbt). Works on Paper/Spigot and on Folia (run on region thread).
     */
    public boolean restoreSnapshot(String claimId, String snapshotId, World world) {
        return restoreSnapshot(claimId, snapshotId, world, null);
    }

    /**
     * Restore and optionally collect block changes for sending to clients. NBT restore does not fill outChanges.
     * @param outChanges unused (kept for API compatibility); may be null
     */
    public boolean restoreSnapshot(String claimId, String snapshotId, World world, List<BlockChange> outChanges) {
        File claimDir = new File(dataDir, sanitize(claimId));
        File nbtFile = new File(claimDir, snapshotId + ".nbt");
        if (!nbtFile.exists()) return false;
        return restoreSnapshotStructureNbt(claimId, snapshotId, world, claimDir, nbtFile);
    }

    /**
     * Send every block change to every player in the world so clients see the restore (Folia often doesn't broadcast).
     */
    public void sendBlockChangesToPlayers(World world, List<BlockChange> changes) {
        if (changes == null || changes.isEmpty()) return;
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) return;
        int totalSent = 0;
        for (Player p : players) {
            if (!p.isOnline() || p.getWorld() != world) continue;
            for (BlockChange c : changes) {
                if (c.location.getWorld() != world) continue;
                if (plugin.getConfigManager().isDebugEnabled() && c.location.getBlockX() == DEBUG_X && c.location.getBlockY() == DEBUG_Y && c.location.getBlockZ() == DEBUG_Z && "world".equals(world.getName())) {
                    plugin.getLogger().info("[Snapshot debug] sendBlockChange to " + p.getName() + ": " + DEBUG_X + "," + DEBUG_Y + "," + DEBUG_Z + " -> " + c.data.getAsString());
                }
                p.sendBlockChange(c.location, c.data);
                totalSent++;
            }
            try {
                p.teleport(p.getLocation());
            } catch (Throwable ignored) {}
        }
        plugin.getLogger().info("Snapshot restore: sent " + totalSent + " block updates to " + players.size() + " player(s), refreshed chunks.");
    }

    private static String sanitize(String claimId) {
        if (claimId == null) return "unknown";
        return claimId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public static String getPermission() {
        return PERMISSION;
    }
}
