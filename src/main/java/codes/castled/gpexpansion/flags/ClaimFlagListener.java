package codes.castled.gpexpansion.flags;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Enforces built-in claim flags.
 *
 * <p>This listener handles {@code Deny}: it cancels actions GriefPrevention would otherwise let
 * through (outsiders dropping items, mobs griefing, crops growing...). {@code Allow} on player
 * flags is applied by {@link ClaimFlagGPListener} through GriefPrevention's permission check
 * event, using {@link #overrideCandidates(Event)} to see which flags an action falls under, so
 * outsiders are let through without GriefPrevention's "no permission" message.
 */
public final class ClaimFlagListener implements Listener {

    /** A flag an action falls under; filter flags also carry the entry that's tested. */
    record Candidate(ClaimFlag flag, String filterEntry) {
        static Candidate of(ClaimFlag flag) {
            return new Candidate(flag, null);
        }
    }

    private final ClaimFlagManager manager;

    public ClaimFlagListener(ClaimFlagManager manager) {
        this.manager = manager;
    }

    // ================================ shared helpers ================================

    /**
     * Deny a player's action when the first set flag among the candidates says Deny and the
     * player is an outsider for it.
     */
    private boolean denyPlayer(Player player, Location location, Cancellable event, List<Candidate> candidates) {
        if (player == null || candidates.isEmpty() || !anyActive(candidates)) return false;
        Claim claim = manager.claimAt(location);
        if (claim == null) return false;
        Candidate decisive = decisiveCandidate(claim, candidates);
        if (decisive == null || !isDenied(decisive, claim)) return false;
        if (!manager.isOutsider(player, claim, decisive.flag().trust())) return false;
        event.setCancelled(true);
        manager.notifyDenied(player, decisive.flag());
        return true;
    }

    private boolean denyPlayer(Player player, Location location, Cancellable event, ClaimFlag flag) {
        return denyPlayer(player, location, event, List.of(Candidate.of(flag)));
    }

    /** The most specific candidate the claim has a value for (own, parent or default). */
    private Candidate decisiveCandidate(Claim claim, List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (!manager.isAvailable(candidate.flag())) continue;
            if (candidate.flag().isFilter()) {
                if (manager.resolveFilter(candidate.flag(), claim) != null) return candidate;
            } else if (manager.resolve(candidate.flag(), claim) != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isDenied(Candidate candidate, Claim claim) {
        if (candidate.flag().isFilter()) {
            ClaimFlagStore.FilterSetting setting = manager.resolveFilter(candidate.flag(), claim);
            return setting != null && !setting.allows(candidate.filterEntry());
        }
        return manager.isDenied(candidate.flag(), claim);
    }

    private boolean anyActive(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (manager.isActive(candidate.flag())) return true;
        }
        return false;
    }

    private boolean anyActive(ClaimFlag... flags) {
        for (ClaimFlag flag : flags) {
            if (manager.isActive(flag)) return true;
        }
        return false;
    }

    /** True when the most specific of the given flags (in order) resolves to Deny here. */
    private boolean environmentDenied(Location location, ClaimFlag... flags) {
        if (!anyActive(flags)) return false;
        Claim claim = manager.claimAt(location);
        if (claim == null) return false;
        for (ClaimFlag flag : flags) {
            if (!manager.isAvailable(flag)) continue;
            Boolean value = manager.resolve(flag, claim);
            if (value != null) return !value;
        }
        return false;
    }

    private static Player playerSource(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    // =========================== action -> flag mapping ===========================

    /** Flags a block click falls under, most specific first. */
    static List<Candidate> blockUseCandidates(Block block, Action action, ItemStack item) {
        List<Candidate> candidates = new ArrayList<>(3);
        if (block == null) return candidates;
        Material type = block.getType();
        if (action == Action.PHYSICAL) {
            if (type == Material.FARMLAND || type == Material.TURTLE_EGG) {
                candidates.add(Candidate.of(ClaimFlag.BLOCK_TRAMP));
            } else if (Tag.PRESSURE_PLATES.isTagged(type)) {
                candidates.add(Candidate.of(ClaimFlag.USE_PLATES));
            } else if (type == Material.TRIPWIRE) {
                candidates.add(Candidate.of(ClaimFlag.USE_TRIPWIRE));
            }
            return candidates;
        }
        if (action != Action.RIGHT_CLICK_BLOCK) return candidates;

        ClaimFlag itemFlag = itemOnBlockFlag(item, type);
        if (itemFlag != null) candidates.add(Candidate.of(itemFlag));
        ClaimFlag blockFlag = blockFlag(block);
        if (blockFlag != null) candidates.add(Candidate.of(blockFlag));
        if (type.isInteractable()) {
            candidates.add(new Candidate(ClaimFlag.BLOCK_INTERACT_FILTER, key(type)));
        }
        return candidates;
    }

    private static ClaimFlag itemOnBlockFlag(ItemStack item, Material clicked) {
        if (item == null) return null;
        Material type = item.getType();
        if (type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE) return ClaimFlag.USE_LIGHTER;
        if (type.name().endsWith("_SPAWN_EGG")) return ClaimFlag.USE_SPAWN_EGGS;
        if (type == Material.BONE_MEAL) return ClaimFlag.BLOCK_FERTILIZE;
        if (type == Material.FIREWORK_ROCKET) return ClaimFlag.USE_FIREWORKS;
        return null;
    }

    private static ClaimFlag blockFlag(Block block) {
        Material type = block.getType();
        String name = type.name();
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) return ClaimFlag.USE_DOORS;
        if (Tag.BUTTONS.isTagged(type) || type == Material.LEVER) return ClaimFlag.USE_BUTTONS;
        if (Tag.BEDS.isTagged(type)) return ClaimFlag.USE_BEDS;
        if (type == Material.CAKE || Tag.CANDLE_CAKES.isTagged(type)) return ClaimFlag.USE_CAKES;
        if (Tag.CAULDRONS.isTagged(type)) return ClaimFlag.USE_CAULDRONS;
        if (Tag.ANVIL.isTagged(type)) return ClaimFlag.USE_ANVILS;
        if (Tag.FLOWER_POTS.isTagged(type)) return ClaimFlag.USE_POTS;
        if (Tag.ALL_SIGNS.isTagged(type)) return ClaimFlag.USE_SIGNS;
        if (name.contains("COPPER_GOLEM_STATUE")) return ClaimFlag.USE_COPPER_GOLEM_STATUES;
        if (type == Material.SWEET_BERRY_BUSH || type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT) {
            return ClaimFlag.BLOCK_HARVEST;
        }
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(type)) {
            return ClaimFlag.CHEST_ACCESS;
        }
        if (name.equals("DECORATED_POT")) return ClaimFlag.USE_POTS;
        if (type.isInteractable() && isContainer(block)) return ClaimFlag.CONTAINER_ACCESS;
        return null;
    }

    private static boolean isContainer(Block block) {
        BlockState state = block.getState();
        return state instanceof Container || block.getType() == Material.LECTERN || block.getType() == Material.JUKEBOX;
    }

    /** Flags an entity click falls under, most specific first. */
    static List<Candidate> entityUseCandidates(Entity entity) {
        List<Candidate> candidates = new ArrayList<>(2);
        if (entity == null) return candidates;
        if (entity instanceof AbstractVillager) {
            candidates.add(Candidate.of(ClaimFlag.USE_VILLAGERS));
        } else if (entity instanceof ArmorStand) {
            candidates.add(Candidate.of(ClaimFlag.USE_ARMOR_STANDS));
        } else if (entity instanceof ItemFrame) {
            candidates.add(Candidate.of(ClaimFlag.USE_ITEM_FRAMES));
        } else if (entity instanceof Minecart || entity instanceof Boat) {
            candidates.add(Candidate.of(ClaimFlag.USE_VEHICLES));
        }
        if (!(entity instanceof Player)) {
            candidates.add(new Candidate(ClaimFlag.MOB_INTERACTION_FILTER, key(entity.getType())));
        }
        return candidates;
    }

    /** Flag for a player hurting an entity, or null when no flag covers the victim. */
    static ClaimFlag damageFlag(Entity victim) {
        if (victim instanceof Player) return ClaimFlag.PVP;
        if (victim instanceof AbstractVillager) return ClaimFlag.PLAYER_DAMAGE_VILLAGERS;
        if (victim instanceof Animals) return ClaimFlag.PLAYER_DAMAGE_ANIMALS;
        if (victim instanceof ArmorStand || victim instanceof ItemFrame) return ClaimFlag.PLAYER_DAMAGE_DECORATIONS;
        return null;
    }

    private static boolean isGrownCrop(Block block) {
        return block.getBlockData() instanceof Ageable ageable
            && ageable.getAge() >= ageable.getMaximumAge()
            && (Tag.CROPS.isTagged(block.getType()) || block.getType() == Material.NETHER_WART
                || block.getType() == Material.COCOA || block.getType() == Material.SWEET_BERRY_BUSH);
    }

    /**
     * The flags GriefPrevention's permission check for {@code trigger} falls under, most specific
     * first. Used to apply Allow; empty when no flag covers the action.
     */
    List<Candidate> overrideCandidates(Event trigger) {
        if (trigger instanceof BlockBreakEvent e) {
            List<Candidate> list = new ArrayList<>(2);
            if (isGrownCrop(e.getBlock())) list.add(Candidate.of(ClaimFlag.BLOCK_HARVEST));
            list.add(Candidate.of(ClaimFlag.BLOCK_BREAK));
            return list;
        }
        if (trigger instanceof BlockPlaceEvent) return List.of(Candidate.of(ClaimFlag.BLOCK_PLACE));
        if (trigger instanceof PlayerBucketFillEvent) return List.of(Candidate.of(ClaimFlag.FILL_BUCKETS));
        if (trigger instanceof PlayerBucketEmptyEvent) return List.of(Candidate.of(ClaimFlag.BLOCK_PLACE));
        if (trigger instanceof PlayerInteractEvent e) return blockUseCandidates(e.getClickedBlock(), e.getAction(), e.getItem());
        if (trigger instanceof PlayerInteractEntityEvent e) return entityUseCandidates(e.getRightClicked());
        if (trigger instanceof EntityMountEvent e) return entityUseCandidates(e.getMount());
        if (trigger instanceof EntityDamageByEntityEvent e) {
            ClaimFlag flag = damageFlag(e.getEntity());
            return flag == null || flag == ClaimFlag.PVP ? List.of() : List.of(Candidate.of(flag));
        }
        if (trigger instanceof HangingPlaceEvent) return List.of(Candidate.of(ClaimFlag.PLACE_HANGINGS));
        if (trigger instanceof HangingBreakByEntityEvent) return List.of(Candidate.of(ClaimFlag.BREAK_HANGINGS));
        if (trigger instanceof VehicleDestroyEvent || trigger instanceof VehicleDamageEvent) {
            return List.of(Candidate.of(ClaimFlag.BREAK_VEHICLES));
        }
        if (trigger instanceof EntityPlaceEvent) return List.of(Candidate.of(ClaimFlag.ENTITY_PLACE));
        if (trigger instanceof BlockIgniteEvent) return List.of(Candidate.of(ClaimFlag.USE_LIGHTER));
        if (trigger instanceof BlockFertilizeEvent) return List.of(Candidate.of(ClaimFlag.BLOCK_FERTILIZE));
        if (trigger instanceof CauldronLevelChangeEvent) return List.of(Candidate.of(ClaimFlag.USE_CAULDRONS));
        if (trigger instanceof PlayerTakeLecternBookEvent) return List.of(Candidate.of(ClaimFlag.CONTAINER_ACCESS));
        if (trigger instanceof PlayerHarvestBlockEvent) return List.of(Candidate.of(ClaimFlag.BLOCK_HARVEST));
        if (trigger instanceof EntityChangeBlockEvent e
                && (e.getBlock().getType() == Material.FARMLAND || e.getBlock().getType() == Material.TURTLE_EGG)) {
            return List.of(Candidate.of(ClaimFlag.BLOCK_TRAMP));
        }
        return List.of();
    }

    /**
     * Whether Allow on a flag covering {@code trigger} should let the player through a
     * GriefPrevention denial that needed {@code required} trust. A flag only ever grants up to
     * its own trust level, so e.g. "Use Doors" can't satisfy a build check.
     */
    boolean allowsOverride(Event trigger, Claim claim, ClaimFlag.Trust required) {
        for (Candidate candidate : overrideCandidates(trigger)) {
            ClaimFlag flag = candidate.flag();
            if (!manager.isActive(flag) || flag.trust().rank() < required.rank()) continue;
            if (flag.isFilter()) {
                ClaimFlagStore.FilterSetting setting = manager.resolveFilter(flag, claim);
                if (setting == null) continue;
                return setting.mode() == ClaimFlag.FilterMode.WHITELIST && setting.contains(candidate.filterEntry());
            }
            Boolean value = manager.resolve(flag, claim);
            if (value != null) return value;
        }
        return false;
    }

    // ================================= player: blocks =================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        List<Candidate> candidates = new ArrayList<>(2);
        if (isGrownCrop(event.getBlock())) candidates.add(Candidate.of(ClaimFlag.BLOCK_HARVEST));
        candidates.add(Candidate.of(ClaimFlag.BLOCK_BREAK));
        denyPlayer(event.getPlayer(), event.getBlock().getLocation(), event, candidates);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        denyPlayer(event.getPlayer(), event.getBlockPlaced().getLocation(), event, ClaimFlag.BLOCK_PLACE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        denyPlayer(event.getPlayer(), event.getBlockClicked().getLocation(), event, ClaimFlag.FILL_BUCKETS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        denyPlayer(event.getPlayer(), target.getLocation(), event, ClaimFlag.BLOCK_PLACE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        denyPlayer(event.getPlayer(), event.getHarvestedBlock().getLocation(), event, ClaimFlag.BLOCK_HARVEST);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent event) {
        denyPlayer(event.getPlayer(), event.getLectern().getLocation(), event, ClaimFlag.CONTAINER_ACCESS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            denyPlayer(player, event.getBlock().getLocation(), event, ClaimFlag.USE_CAULDRONS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            denyPlayer(event.getPlayer(), event.getBlock().getLocation(), event, ClaimFlag.USE_LIGHTER);
            return;
        }
        if (event.getCause() == BlockIgniteEvent.IgniteCause.LIGHTNING
                && environmentDenied(event.getBlock().getLocation(), ClaimFlag.LIGHTNING_DAMAGE)) {
            event.setCancelled(true);
        } else if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD
                && environmentDenied(event.getBlock().getLocation(), ClaimFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            denyPlayer(player, event.getBlock().getLocation(), event, ClaimFlag.BLOCK_FERTILIZE);
        } else if (environmentDenied(event.getBlock().getLocation(), ClaimFlag.BLOCK_FERTILIZE)) {
            event.setCancelled(true);
        }
    }

    // In "deny" mode GriefPrevention may already have cancelled the interaction; ignoreCancelled
    // keeps us from double-messaging. useInteractedBlock is what GP uses, so check that too.
    private static final ClaimFlag[] BLOCK_USE_FLAGS = {
        ClaimFlag.BLOCK_TRAMP, ClaimFlag.USE_PLATES, ClaimFlag.USE_TRIPWIRE, ClaimFlag.USE_LIGHTER,
        ClaimFlag.USE_SPAWN_EGGS, ClaimFlag.BLOCK_FERTILIZE, ClaimFlag.USE_FIREWORKS, ClaimFlag.USE_DOORS,
        ClaimFlag.USE_BUTTONS, ClaimFlag.USE_BEDS, ClaimFlag.USE_CAKES, ClaimFlag.USE_CAULDRONS,
        ClaimFlag.USE_ANVILS, ClaimFlag.USE_POTS, ClaimFlag.USE_SIGNS, ClaimFlag.USE_COPPER_GOLEM_STATUES,
        ClaimFlag.BLOCK_HARVEST, ClaimFlag.CHEST_ACCESS, ClaimFlag.CONTAINER_ACCESS, ClaimFlag.BLOCK_INTERACT_FILTER
    };

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        ItemStack item = event.getItem();

        if (block != null && event.useInteractedBlock() != Event.Result.DENY
                && (action == Action.PHYSICAL || action == Action.RIGHT_CLICK_BLOCK)
                && anyActive(BLOCK_USE_FLAGS)) {
            List<Candidate> candidates = blockUseCandidates(block, action, item);
            if (denyPlayer(player, block.getLocation(), event, candidates)) {
                return;
            }
        }

        // Item uses that don't depend on a clicked block
        if (item != null && event.useItemInHand() != Event.Result.DENY
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            Material type = item.getType();
            ClaimFlag itemFlag = null;
            if (type == Material.GOAT_HORN) {
                itemFlag = ClaimFlag.USE_HORNS;
            } else if (type == Material.FIREWORK_ROCKET && action == Action.RIGHT_CLICK_AIR) {
                itemFlag = ClaimFlag.USE_FIREWORKS;
            } else if (type == Material.ENDER_EYE
                    && (block == null || block.getType() != Material.END_PORTAL_FRAME)) {
                itemFlag = ClaimFlag.THROW_ENDER_EYES;
            }
            if (itemFlag != null) {
                Location where = block != null ? block.getLocation() : player.getLocation();
                denyPlayer(player, where, event, itemFlag);
            }
        }
    }

    // ================================ player: entities ================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        denyPlayer(event.getPlayer(), target.getLocation(), event, entityUseCandidates(target));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player) {
            denyPlayer(player, event.getMount().getLocation(), event, entityUseCandidates(event.getMount()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            denyPlayer(event.getPlayer(), event.getEntity().getLocation(), event, ClaimFlag.ENTITY_PLACE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            denyPlayer(event.getPlayer(), event.getEntity().getLocation(), event, ClaimFlag.PLACE_HANGINGS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = playerSource(event.getRemover());
        if (player != null) {
            denyPlayer(player, event.getEntity().getLocation(), event, ClaimFlag.BREAK_HANGINGS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = playerSource(event.getAttacker());
        if (player != null) {
            denyPlayer(player, event.getVehicle().getLocation(), event, ClaimFlag.BREAK_VEHICLES);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = playerSource(event.getAttacker());
        if (player != null) {
            denyPlayer(player, event.getVehicle().getLocation(), event, ClaimFlag.BREAK_VEHICLES);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = playerSource(event.getDamager());
        if (attacker == null) return;
        Entity victim = event.getEntity();
        ClaimFlag flag = damageFlag(victim);
        if (flag == null || (victim instanceof Player && victim.equals(attacker))) return;
        if (flag == ClaimFlag.PVP) {
            if (!manager.isActive(ClaimFlag.PVP)) return;
            // Either side standing in a no-PvP claim is enough to stop the hit.
            Claim victimClaim = manager.claimAt(victim.getLocation());
            Claim attackerClaim = manager.claimAt(attacker.getLocation());
            boolean denied = (victimClaim != null && manager.isDenied(ClaimFlag.PVP, victimClaim))
                || (attackerClaim != null && manager.isDenied(ClaimFlag.PVP, attackerClaim));
            if (denied && !attacker.hasPermission(ClaimFlagManager.BYPASS_PERMISSION)) {
                event.setCancelled(true);
                manager.notifyDenied(attacker, ClaimFlag.PVP);
            }
            return;
        }
        denyPlayer(attacker, victim.getLocation(), event, flag);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !manager.isActive(ClaimFlag.PLAYER_INCOMING_DAMAGE_FILTER)) return;
        Claim claim = manager.claimAt(player.getLocation());
        if (claim == null) return;
        ClaimFlagStore.FilterSetting setting = manager.resolveFilter(ClaimFlag.PLAYER_INCOMING_DAMAGE_FILTER, claim);
        // Blacklist: listed causes don't hurt here. Whitelist: only listed causes do.
        if (setting != null && !setting.allows(key(event.getCause()))) {
            event.setCancelled(true);
        }
    }

    // =================================== player: items ===================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        denyPlayer(event.getPlayer(), event.getPlayer().getLocation(), event, ClaimFlag.DROP_ITEMS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && manager.isActive(ClaimFlag.PICKUP_ITEMS)) {
            Claim claim = manager.claimAt(event.getItem().getLocation());
            if (claim != null && manager.isDenied(ClaimFlag.PICKUP_ITEMS, claim)
                    && manager.isOutsider(player, claim, ClaimFlag.PICKUP_ITEMS.trust())) {
                // Pickup fires every tick while standing on items; skip the message.
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.CHORUS_FRUIT) {
            denyPlayer(event.getPlayer(), event.getPlayer().getLocation(), event, ClaimFlag.USE_CHORUS_FRUITS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        String cause = event.getCause().name();
        ClaimFlag flag = switch (cause) {
            case "ENDER_PEARL" -> ClaimFlag.USE_ENDER_PEARLS;
            case "CHORUS_FRUIT", "CONSUMABLE_EFFECT" -> ClaimFlag.USE_CHORUS_FRUITS;
            default -> null;
        };
        if (flag == null || event.getTo() == null) return;
        if (!denyPlayer(event.getPlayer(), event.getTo(), event, flag)) {
            denyPlayer(event.getPlayer(), event.getFrom(), event, flag);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;
        ClaimFlag flag;
        if (projectile instanceof EnderPearl) {
            flag = ClaimFlag.USE_ENDER_PEARLS;
        } else if (projectile instanceof Egg) {
            flag = ClaimFlag.THROW_EGGS;
        } else if (projectile instanceof ThrownPotion) {
            flag = ClaimFlag.THROW_POTIONS;
        } else if (projectile instanceof Trident) {
            flag = ClaimFlag.THROW_TRIDENTS;
        } else if (projectile instanceof AbstractArrow) {
            flag = ClaimFlag.SHOOT_BOW;
        } else if (projectile.getType().name().equals("WIND_CHARGE")) {
            flag = ClaimFlag.THROW_WIND_CHARGES;
        } else {
            return;
        }
        denyPlayer(player, player.getLocation(), event, flag);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        ClaimFlag flag = switch (event.getCause()) {
            case NETHER_PORTAL -> ClaimFlag.USE_NETHER_PORTAL;
            case END_PORTAL -> ClaimFlag.USE_END_PORTAL;
            default -> null;
        };
        if (flag != null) {
            denyPlayer(event.getPlayer(), event.getFrom(), event, flag);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!manager.isActive(ClaimFlag.COMMAND_USAGE_FILTER)) return;
        String message = event.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        denyPlayer(event.getPlayer(), event.getPlayer().getLocation(), event,
            List.of(new Candidate(ClaimFlag.COMMAND_USAGE_FILTER, label)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer().getUniqueId());
    }

    // =================================== mobs & entities ===================================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!anyActive(ClaimFlag.SPAWN_AMBIENTS, ClaimFlag.SPAWN_ANIMALS, ClaimFlag.SPAWN_MONSTERS,
                ClaimFlag.SPAWN_FISHES, ClaimFlag.MOB_SPAWN_FILTER)) return;
        if (manager.isSpawnReasonIgnored(event.getSpawnReason().name())) return;
        Entity entity = event.getEntity();
        ClaimFlag category = entity instanceof Ambient ? ClaimFlag.SPAWN_AMBIENTS
            : entity instanceof Animals ? ClaimFlag.SPAWN_ANIMALS
            : entity instanceof Monster ? ClaimFlag.SPAWN_MONSTERS
            : entity instanceof WaterMob ? ClaimFlag.SPAWN_FISHES
            : null;
        Claim claim = manager.claimAt(event.getLocation());
        if (claim == null) return;
        if (category != null && manager.isAvailable(category)) {
            Boolean value = manager.resolve(category, claim);
            if (value != null) {
                if (!value) event.setCancelled(true);
                return;
            }
        }
        if (manager.isAvailable(ClaimFlag.MOB_SPAWN_FILTER)) {
            ClaimFlagStore.FilterSetting setting = manager.resolveFilter(ClaimFlag.MOB_SPAWN_FILTER, claim);
            if (setting != null && !setting.allows(key(entity.getType()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        Block block = event.getBlock();
        Material from = block.getType();
        if (from == Material.FARMLAND || from == Material.TURTLE_EGG) {
            Player player = entity instanceof Player p ? p : null;
            if (player != null) {
                denyPlayer(player, block.getLocation(), event, ClaimFlag.BLOCK_TRAMP);
                return;
            }
            if (environmentDenied(block.getLocation(), ClaimFlag.BLOCK_TRAMP)) {
                event.setCancelled(true);
                return;
            }
        }
        ClaimFlag flag = null;
        if (entity instanceof Enderman) {
            flag = ClaimFlag.ENDERMAN_GRIEF;
        } else if (entity instanceof Silverfish) {
            flag = ClaimFlag.SILVERFISH_INFEST;
        } else if (entity instanceof Ravager) {
            flag = ClaimFlag.RAVAGER_GRIEF;
        } else if (entity instanceof EnderDragon) {
            flag = ClaimFlag.ENDER_DRAGON_GRIEF;
        } else if (entity instanceof Villager) {
            flag = ClaimFlag.VILLAGER_FARM;
        } else if (entity instanceof Animals) {
            flag = ClaimFlag.ANIMAL_GRIEF;
        } else if (entity instanceof Wither) {
            flag = ClaimFlag.WITHER_BLOCK_DAMAGE;
        }
        if (flag != null && environmentDenied(block.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (event.getEntity() instanceof Snowman && environmentDenied(event.getBlock().getLocation(), ClaimFlag.SNOWMAN_TRAIL)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        ClaimFlag specific = entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart ? ClaimFlag.TNT_BLOCK_DAMAGE
            : entity instanceof org.bukkit.entity.Creeper ? ClaimFlag.CREEPER_BLOCK_DAMAGE
            : entity instanceof Wither || entity instanceof WitherSkull ? ClaimFlag.WITHER_BLOCK_DAMAGE
            : entity instanceof EnderCrystal ? ClaimFlag.END_CRYSTAL_BLOCK_DAMAGE
            : entity instanceof EnderDragon ? ClaimFlag.ENDER_DRAGON_GRIEF
            : entity instanceof Fireball ? ClaimFlag.FIREBALL_BLOCK_DAMAGE
            : null;
        filterExplodedBlocks(event.blockList(), specific);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplodedBlocks(event.blockList(), null);
    }

    /** Spare blocks inside claims whose explosion flag says Deny. */
    private void filterExplodedBlocks(List<Block> blocks, ClaimFlag specific) {
        boolean specificActive = specific != null && manager.isActive(specific);
        if (!specificActive && !manager.isActive(ClaimFlag.EXPLOSION_DAMAGE_BLOCKS)) return;
        Claim cached = null;
        Boolean cachedDenied = null;
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            Claim claim = manager.claimAt(block.getLocation());
            if (claim == null) continue;
            if (claim != cached) {
                cached = claim;
                cachedDenied = explosionDenied(claim, specific);
            }
            if (Boolean.TRUE.equals(cachedDenied)) {
                iterator.remove();
            }
        }
    }

    private boolean explosionDenied(Claim claim, ClaimFlag specific) {
        if (specific != null && manager.isAvailable(specific)) {
            Boolean value = manager.resolve(specific, claim);
            if (value != null) return !value;
        }
        return manager.isAvailable(ClaimFlag.EXPLOSION_DAMAGE_BLOCKS) && manager.isDenied(ClaimFlag.EXPLOSION_DAMAGE_BLOCKS, claim);
    }

    // ===================================== environment =====================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (environmentDenied(event.getBlock().getLocation(), ClaimFlag.FIRE_DAMAGE_BLOCKS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (environmentDenied(event.getBlock().getLocation(), ClaimFlag.LEAVES_DECAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLightning(LightningStrikeEvent event) {
        String cause = event.getCause().name();
        if (cause.equals("COMMAND") || cause.equals("CUSTOM")) return;
        if (environmentDenied(event.getLightning().getLocation(), ClaimFlag.LIGHTNING_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        TreeType species = event.getSpecies();
        boolean mushroom = species == TreeType.RED_MUSHROOM || species == TreeType.BROWN_MUSHROOM;
        ClaimFlag flag = mushroom ? ClaimFlag.MUSHROOM_GROW : ClaimFlag.TREE_GROW;
        if (environmentDenied(event.getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        Material type = event.getNewState().getType();
        Material old = event.getBlock().getType();
        ClaimFlag specific;
        if (type == Material.CACTUS || old == Material.CACTUS) {
            specific = ClaimFlag.CACTUS_GROW;
        } else if (type == Material.SUGAR_CANE || old == Material.SUGAR_CANE) {
            specific = ClaimFlag.SUGAR_CANE_GROW;
        } else if (type == Material.TURTLE_EGG || old == Material.TURTLE_EGG) {
            specific = ClaimFlag.TURTLE_EGG_HATCH;
        } else if (type.name().contains("AMETHYST_BUD") || type == Material.AMETHYST_CLUSTER) {
            specific = ClaimFlag.AMETHYST_GROW;
        } else if (type == Material.VINE || old == Material.VINE) {
            specific = ClaimFlag.VINE_GROW;
        } else {
            specific = null;
        }
        if (environmentDenied(event.getBlock().getLocation(), specific != null
                ? new ClaimFlag[]{specific, ClaimFlag.CROP_GROW} : new ClaimFlag[]{ClaimFlag.CROP_GROW})) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Material source = event.getSource().getType();
        Material result = event.getNewState().getType();
        ClaimFlag flag = switch (source) {
            case BAMBOO, BAMBOO_SAPLING -> ClaimFlag.BAMBOO_GROW;
            case CAVE_VINES, CAVE_VINES_PLANT -> ClaimFlag.CAVE_VINES_GROW;
            case KELP, KELP_PLANT -> ClaimFlag.KELP_GROW;
            case TWISTING_VINES, TWISTING_VINES_PLANT -> ClaimFlag.TWISTING_VINES_GROW;
            case WEEPING_VINES, WEEPING_VINES_PLANT -> ClaimFlag.WEEPING_VINES_GROW;
            case BUDDING_AMETHYST -> ClaimFlag.AMETHYST_SPREAD;
            case FIRE, SOUL_FIRE -> ClaimFlag.FIRE_SPREAD;
            case GRASS_BLOCK -> ClaimFlag.GRASS_SPREAD;
            case MYCELIUM -> ClaimFlag.MYCELIUM_SPREAD;
            case VINE -> ClaimFlag.VINE_SPREAD;
            case RED_MUSHROOM, BROWN_MUSHROOM -> ClaimFlag.MUSHROOM_GROW;
            default -> result == Material.FIRE ? ClaimFlag.FIRE_SPREAD : null;
        };
        if (flag != null && environmentDenied(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (event instanceof EntityBlockFormEvent) return;
        Material result = event.getNewState().getType();
        ClaimFlag flag = result == Material.SNOW ? ClaimFlag.SNOW_FORM
            : result == Material.ICE ? ClaimFlag.ICE_FORM
            : null;
        if (flag != null && environmentDenied(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        ClaimFlag flag;
        if (Tag.CORAL_BLOCKS.isTagged(type) || Tag.CORALS.isTagged(type) || Tag.WALL_CORALS.isTagged(type)) {
            flag = ClaimFlag.CORAL_DIE;
        } else if (type == Material.FARMLAND) {
            flag = ClaimFlag.FARMLAND_DRY;
        } else if (type == Material.FIRE || type == Material.SOUL_FIRE) {
            flag = ClaimFlag.FIRE_BURN_OUT;
        } else if (type == Material.ICE || type == Material.FROSTED_ICE) {
            flag = ClaimFlag.ICE_MELT;
        } else if (type == Material.SNOW || type == Material.SNOW_BLOCK) {
            flag = ClaimFlag.SNOW_MELT;
        } else {
            return;
        }
        if (environmentDenied(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Material type = event.getBlock().getType();
        ClaimFlag flag = type == Material.LAVA ? ClaimFlag.LAVA_FLOW
            : type == Material.WATER ? ClaimFlag.WATER_FLOW
            : null;
        if (flag == null || !manager.isActive(flag)) return;
        Claim target = manager.claimAt(event.getToBlock().getLocation());
        if (target == null || !manager.isDenied(flag, target)) return;
        // Only fluid entering from outside the claim is stopped.
        Claim source = manager.claimAt(event.getBlock().getLocation());
        if (source == null || !java.util.Objects.equals(source.getID(), target.getID())) {
            event.setCancelled(true);
        }
    }
}
