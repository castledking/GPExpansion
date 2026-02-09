package dev.towki.gpexpansion.storage;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

import org.bukkit.Chunk;
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
    private final Set<String> restoringClaims = Collections.synchronizedSet(new HashSet<>());

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
     * Create a snapshot of the claim's blocks using .snap (block-by-block YAML). Any claim size is supported.
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
        // 2D (cuboid) claims: use full world height (no 128 limit; .snap format supports any size)
        if (!gp.is3DClaim(claim)) {
            minY = world.getMinHeight();
            maxY = world.getMaxHeight() - 1;
        }
        if (plugin.getConfigManager().isDebugEnabled() && "world".equals(world.getName())) {
            boolean in3D = (DEBUG_X >= minX && DEBUG_X <= maxX && DEBUG_Y >= minY && DEBUG_Y <= maxY && DEBUG_Z >= minZ && DEBUG_Z <= maxZ);
            boolean in2D = (DEBUG_X >= minX && DEBUG_X <= maxX && DEBUG_Z >= minZ && DEBUG_Z <= maxZ);
            boolean is3DClaim = gp.is3DClaim(claim);
            plugin.getLogger().info("[Snapshot debug] Create bounds: " + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ
                + " is3D=" + is3DClaim + " | (29,67,1298) in2D=" + in2D + " in3D=" + in3D);
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

        // Write .snap (block-by-block YAML) — reliable on Purpur/Folia; NBT place() and Palette API are unreliable
        if (!createSnapshotSnap(world, minX, minY, minZ, maxX, maxY, maxZ, claimDir, id, entry)) {
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
     * Create snapshot as .snap (block-by-block YAML). Reliable on Purpur/Folia.
     * Format: YAML list of {x,y,z,data} where x,y,z are relative to origin, data is blockData.getAsString().
     */
    private boolean createSnapshotSnap(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            File claimDir, String id, Map<String, Object> entry) {
        try {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockData data = world.getBlockAt(x, y, z).getBlockData();
                        if (plugin.getConfigManager().isDebugEnabled() && x == DEBUG_X && y == DEBUG_Y && z == DEBUG_Z && "world".equals(world.getName())) {
                            plugin.getLogger().info("[Snapshot debug] SAVE (29,67,1298): " + data.getAsString());
                        }
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("x", x - minX);
                        b.put("y", y - minY);
                        b.put("z", z - minZ);
                        b.put("d", data.getAsString());
                        blocks.add(b);
                    }
                }
            }
            entry.put("originX", minX);
            entry.put("originY", minY);
            entry.put("originZ", minZ);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("blocks", blocks);
            File snapFile = new File(claimDir, id + ".snap");
            yaml.save(snapFile);
            return snapFile.exists() && snapFile.length() > 0;
        } catch (Throwable t) {
            plugin.getLogger().warning("Snapshot .snap create failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Uses Paper/Bukkit Structure API (structure NBT). Works on Paper/Spigot 1.20+, Purpur, Folia.
     * Returns true on success; false if API unavailable, claim &gt;128 per axis, or save failed.
     * Kept only for migration; new snapshots use .snap.
     */
    @SuppressWarnings("unused")
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
     * On Folia: load chunk via getChunkAtAsync, then schedule block-setting on region thread via runAtLocation.
     * The chunk load ensures the region exists; runAtLocation runs the actual block edits on the correct thread.
     */
    private void loadChunkAndRestoreBlocks(World world, int chunkX, int chunkZ, List<BlockToPlace> blocks) {
        Location chunkLoc = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        Runnable setBlocks = () -> {
            world.getChunkAt(chunkX, chunkZ);
            for (BlockToPlace b : blocks) {
                world.getBlockAt(b.x, b.y, b.z).setBlockData(b.data);
                if (plugin.getConfigManager().isDebugEnabled() && b.x == DEBUG_X && b.y == DEBUG_Y && b.z == DEBUG_Z && "world".equals(world.getName())) {
                    plugin.getLogger().info("[Snapshot debug] SET block at (29,67,1298): " + b.data.getAsString());
                }
            }
        };
        try {
            java.lang.reflect.Method m = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, java.util.function.Consumer.class);
            m.invoke(world, chunkX, chunkZ, (Consumer<Chunk>) chunk -> SchedulerAdapter.runAtLocation(plugin, chunkLoc, setBlocks));
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m4 = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class, java.util.function.Consumer.class);
                m4.invoke(world, chunkX, chunkZ, true, (Consumer<Chunk>) chunk -> SchedulerAdapter.runAtLocation(plugin, chunkLoc, setBlocks));
            } catch (ReflectiveOperationException e2) {
                SchedulerAdapter.runAtLocation(plugin, chunkLoc, setBlocks);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Chunk load for restore failed: " + t.getMessage());
            SchedulerAdapter.runAtLocation(plugin, chunkLoc, setBlocks);
        }
    }

    /** One block to restore: world coords + BlockData. */
    private static final class BlockToPlace {
        final int x, y, z;
        final BlockData data;

        BlockToPlace(int x, int y, int z, BlockData data) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }

    /**
     * Restore from .snap (block-by-block YAML). Reliable on Purpur/Folia.
     * YAML load/parse runs async to avoid blocking the server; block placement is scheduled on correct threads.
     */
    private boolean restoreSnapshotSnap(String claimId, String snapshotId, World world, File claimDir, File snapFile) {
        final String fid = claimId;
        final String fsid = snapshotId;
        SchedulerAdapter.runAsyncNow(plugin, () -> {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(snapFile);
                List<?> rawBlocks = yaml.getList("blocks");
                if (rawBlocks == null || rawBlocks.isEmpty()) {
                    plugin.getLogger().warning("[Snapshot] .snap has no blocks list");
                    return;
                }
                File indexF = new File(claimDir, "index.yml");
                if (!indexF.exists()) return;
                YamlConfiguration index = YamlConfiguration.loadConfiguration(indexF);
                List<Map<?, ?>> list = index.getMapList("snapshots");
                if (list == null) return;
                Map<?, ?> entry = null;
                for (Map<?, ?> m : list) {
                    if (Objects.equals(String.valueOf(m.get("id")), snapshotId)) { entry = m; break; }
                }
                if (entry == null || entry.get("originX") == null) return;
                int ox = ((Number) entry.get("originX")).intValue();
                int oy = ((Number) entry.get("originY")).intValue();
                int oz = ((Number) entry.get("originZ")).intValue();

                Map<String, List<BlockToPlace>> byChunk = new HashMap<>();
                for (Object raw : rawBlocks) {
                    if (raw == null) continue;
                    int dx, dy, dz;
                    String dStr;
                    if (raw instanceof ConfigurationSection cs) {
                        dx = cs.getInt("x", Integer.MIN_VALUE);
                        dy = cs.getInt("y", Integer.MIN_VALUE);
                        dz = cs.getInt("z", Integer.MIN_VALUE);
                        dStr = cs.getString("d");
                    } else if (raw instanceof Map) {
                        Map<?, ?> b = (Map<?, ?>) raw;
                        Object vx = b.get("x"), vy = b.get("y"), vz = b.get("z");
                        Object vd = b.get("d");
                        if (vx == null || vy == null || vz == null || !(vd instanceof String)) continue;
                        dx = ((Number) vx).intValue();
                        dy = ((Number) vy).intValue();
                        dz = ((Number) vz).intValue();
                        dStr = (String) vd;
                    } else continue;
                    if (dStr == null || dStr.isEmpty()) continue;
                    if (dx == Integer.MIN_VALUE || dy == Integer.MIN_VALUE || dz == Integer.MIN_VALUE) continue;
                    int wx = ox + dx, wy = oy + dy, wz = oz + dz;
                    BlockData data;
                    try {
                        data = Bukkit.createBlockData(dStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    int cx = wx >> 4, cz = wz >> 4;
                    byChunk.computeIfAbsent(cx + "," + cz, k -> new ArrayList<>()).add(new BlockToPlace(wx, wy, wz, data));
                }

                if (byChunk.isEmpty()) return;

                final Map<String, List<BlockToPlace>> byChunkFinal = byChunk;
                final int total = byChunk.values().stream().mapToInt(List::size).sum();
                SchedulerAdapter.runGlobal(plugin, () -> {
                    if (plugin.getConfigManager().isDebugEnabled() && "world".equals(world.getName())) {
                        boolean hasDebugBlock = byChunkFinal.values().stream().flatMap(List::stream)
                            .anyMatch(b -> b.x == DEBUG_X && b.y == DEBUG_Y && b.z == DEBUG_Z);
                        plugin.getLogger().info("[Snapshot debug] (29,67,1298) in restore zone: " + hasDebugBlock + " origin=" + ox + "," + oy + "," + oz);
                    }
                    if (SchedulerAdapter.isFolia()) {
                        for (Map.Entry<String, List<BlockToPlace>> e : byChunkFinal.entrySet()) {
                            String[] parts = e.getKey().split(",");
                            int chunkX = Integer.parseInt(parts[0]), chunkZ = Integer.parseInt(parts[1]);
                            List<BlockToPlace> blocks = new ArrayList<>(e.getValue());
                            loadChunkAndRestoreBlocks(world, chunkX, chunkZ, blocks);
                        }
                        plugin.getLogger().info("[Snapshot] Scheduled " + total + " blocks in " + byChunkFinal.size() + " chunks for Folia restore (claim " + fid + ")");
                    } else {
                        int placed = 0;
                        for (List<BlockToPlace> blocks : byChunkFinal.values()) {
                            for (BlockToPlace b : blocks) {
                                world.getChunkAt(b.x >> 4, b.z >> 4);
                                world.getBlockAt(b.x, b.y, b.z).setBlockData(b.data);
                                if (plugin.getConfigManager().isDebugEnabled() && b.x == DEBUG_X && b.y == DEBUG_Y && b.z == DEBUG_Z && "world".equals(world.getName())) {
                                    plugin.getLogger().info("[Snapshot debug] SET block at (29,67,1298) [non-Folia]: " + b.data.getAsString());
                                }
                                placed++;
                            }
                        }
                        plugin.getLogger().info("[Snapshot] Restored " + placed + " blocks from .snap for claim " + fid);
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("Snapshot .snap restore failed: " + t.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) t.printStackTrace();
            }
        });
        return true;
    }

    /**
     * Restore from structure NBT (load + place). Fallback for legacy .nbt snapshots.
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
            Structure structure;
            try {
                structure = sm.loadStructure(nbtFile.getAbsoluteFile());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to load structure NBT from " + nbtFile.getAbsolutePath() + ": " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) e.printStackTrace();
                return false;
            }
            if (structure == null) {
                plugin.getLogger().warning("loadStructure returned null for " + nbtFile.getAbsolutePath());
                return false;
            }
            BlockVector size = structure.getSize();
            if (size.getBlockX() <= 0 || size.getBlockY() <= 0 || size.getBlockZ() <= 0) {
                plugin.getLogger().warning("Structure NBT is empty (size " + size.getBlockX() + "," + size.getBlockY() + "," + size.getBlockZ() + ").");
                return false;
            }
            // Use manual block-by-block restore instead of structure.place() — place() is unreliable
            // on Purpur and Folia (blocks often don't get written). Iterating Palette blocks and
            // setting via World.setBlockData works across all platforms.
            List<org.bukkit.structure.Palette> palettes = structure.getPalettes();
            if (palettes == null || palettes.isEmpty()) {
                plugin.getLogger().warning("Structure has no palettes; cannot restore.");
                return false;
            }
            org.bukkit.structure.Palette palette = palettes.get(0);
            List<BlockState> blocks = palette.getBlocks();
            if (blocks == null || blocks.isEmpty()) {
                plugin.getLogger().warning("Palette has no blocks.");
                return false;
            }
            int maxX = ox + size.getBlockX() - 1;
            int maxZ = oz + size.getBlockZ() - 1;
            for (int cx = ox >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = oz >> 4; cz <= maxZ >> 4; cz++) {
                    world.getChunkAt(cx, cz);
                }
            }
            int placed = 0;
            for (BlockState bs : blocks) {
                if (bs == null) continue;
                org.bukkit.Location offLoc = bs.getLocation();
                if (offLoc == null) continue;
                int wx = ox + offLoc.getBlockX();
                int wy = oy + offLoc.getBlockY();
                int wz = oz + offLoc.getBlockZ();
                BlockData data = bs.getBlockData();
                if (data == null) continue;
                world.getBlockAt(wx, wy, wz).setBlockData(data);
                placed++;
            }
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("[Snapshot] Restored " + placed + " blocks at " + ox + "," + oy + "," + oz + " size " + size.getBlockX() + "x" + size.getBlockY() + "x" + size.getBlockZ());
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Structure snapshot restore failed: " + t.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) t.printStackTrace();
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

    /**
     * Remove a snapshot by ID across all claims. Use this when claim ID is unknown.
     */
    public boolean removeSnapshotById(String snapshotId) {
        for (String cid : listClaimIdsWithSnapshots()) {
            for (SnapshotEntry e : listSnapshots(cid)) {
                if (Objects.equals(e.id, snapshotId)) {
                    return removeSnapshot(cid, snapshotId);
                }
            }
        }
        return false;
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
     * Get the origin location of a snapshot (claim min corner). Use this on Folia to schedule restore at the claim's region.
     */
    public Optional<Location> getSnapshotOrigin(String claimId, String snapshotId, World world) {
        File claimDir = new File(dataDir, sanitize(claimId));
        File indexF = new File(claimDir, "index.yml");
        if (!indexF.exists()) return Optional.empty();
        YamlConfiguration index = YamlConfiguration.loadConfiguration(indexF);
        List<Map<?, ?>> list = index.getMapList("snapshots");
        if (list == null) return Optional.empty();
        for (Map<?, ?> m : list) {
            if (!Objects.equals(String.valueOf(m.get("id")), snapshotId)) continue;
            if (m.get("originX") == null) return Optional.empty();
            int ox = ((Number) m.get("originX")).intValue();
            int oy = ((Number) m.get("originY")).intValue();
            int oz = ((Number) m.get("originZ")).intValue();
            return Optional.of(new Location(world, ox, oy, oz));
        }
        return Optional.empty();
    }

    /**
     * Restore the claim to the given snapshot using structure NBT (.nbt). Works on Paper/Spigot and on Folia (run on region for origin).
     */
    public boolean restoreSnapshot(String claimId, String snapshotId, World world) {
        return restoreSnapshot(claimId, snapshotId, world, null);
    }

    /**
     * Check if a claim is currently being restored from a snapshot.
     */
    public boolean isRestoring(String claimId) {
        return restoringClaims.contains(claimId);
    }

    /**
     * Restore and optionally collect block changes for sending to clients. NBT restore does not fill outChanges.
     * @param outChanges unused (kept for API compatibility); may be null
     */
    public boolean restoreSnapshot(String claimId, String snapshotId, World world, List<BlockChange> outChanges) {
        restoringClaims.add(claimId);
        try {
            File claimDir = new File(dataDir, sanitize(claimId));
            File snapFile = new File(claimDir, snapshotId + ".snap");
            if (snapFile.exists()) {
                return restoreSnapshotSnap(claimId, snapshotId, world, claimDir, snapFile);
            }
            File nbtFile = new File(claimDir, snapshotId + ".nbt");
            if (nbtFile.exists()) {
                plugin.getLogger().info("[Snapshot] Using legacy .nbt (create new snapshot with /claim snapshot for .snap)");
                return restoreSnapshotStructureNbt(claimId, snapshotId, world, claimDir, nbtFile);
            }
            plugin.getLogger().warning("[Snapshot] No .snap or .nbt found for " + snapshotId + " in claim " + claimId);
            return false;
        } finally {
            // Clear flag after a short delay to allow block updates to propagate
            SchedulerAdapter.runLaterGlobal(plugin, () -> restoringClaims.remove(claimId), 20L * 3); // 3 seconds
        }
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
