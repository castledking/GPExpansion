package codes.castled.gpexpansion.flags;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in claim flags. The set mirrors ExcellentClaims' claim rules so claims behave the same
 * way without GPFlags, mapped onto GriefPrevention's trust levels.
 *
 * <p>Player flags decide what outsiders may do. "Outsider" means a player without the flag's
 * {@link Trust} level in the claim (and who is not the owner): {@code Allow} grants it to them
 * despite GriefPrevention, {@code Deny} takes it away even where GriefPrevention would allow it.
 * Players with the trust level are never affected, except by {@link #PVP}, which applies to
 * everyone. Mob and environment flags only ever restrict: {@code Deny} blocks the event,
 * {@code Allow} leaves vanilla and GriefPrevention behaviour alone. A flag nobody has set on a
 * claim (or its parent claim) falls back to the server default, and without one does nothing.
 */
public enum ClaimFlag {

    // ===================================== Player =====================================
    BLOCK_BREAK("block_break", Category.PLAYER, Trust.BUILD, "Block Break", "IRON_PICKAXE",
        "Whether outsiders without build trust can break blocks."),
    BLOCK_PLACE("block_place", Category.PLAYER, Trust.BUILD, "Block Place", "STONE_BRICKS",
        "Whether outsiders without build trust can place blocks."),
    BLOCK_HARVEST("block_harvest", Category.PLAYER, Trust.BUILD, "Block Harvest", "SWEET_BERRIES",
        "Whether outsiders without build trust can harvest grown crops and berries."),
    BLOCK_INTERACT_FILTER("block_interact_filter", Category.PLAYER, Trust.ACCESS, "Block Interactions", "CRAFTING_TABLE",
        "Which blocks outsiders without access trust can use. More specific flags override this one.",
        Filter.MATERIAL, FilterMode.WHITELIST),
    CHEST_ACCESS("chest_access", Category.PLAYER, Trust.CONTAINER, "Chest Access", "CHEST",
        "Whether outsiders without container trust can open chests, barrels and shulker boxes."),
    CONTAINER_ACCESS("container_access", Category.PLAYER, Trust.CONTAINER, "Container Access", "FURNACE",
        "Whether outsiders without container trust can open other containers."),
    USE_ANVILS("use_anvils", Category.PLAYER, Trust.ACCESS, "Use Anvils", "ANVIL",
        "Whether outsiders without access trust can use anvils."),
    USE_BEDS("use_beds", Category.PLAYER, Trust.ACCESS, "Use Beds", "RED_BED",
        "Whether outsiders without access trust can use beds."),
    USE_BUTTONS("use_buttons", Category.PLAYER, Trust.ACCESS, "Use Buttons", "STONE_BUTTON",
        "Whether outsiders without access trust can press buttons and flip levers."),
    USE_CAKES("use_cakes", Category.PLAYER, Trust.ACCESS, "Use Cakes", "CAKE",
        "Whether outsiders without access trust can eat placed cakes."),
    USE_CAULDRONS("use_cauldrons", Category.PLAYER, Trust.ACCESS, "Use Cauldrons", "CAULDRON",
        "Whether outsiders without access trust can use cauldrons."),
    USE_COPPER_GOLEM_STATUES("use_copper_golem_statues", Category.PLAYER, Trust.ACCESS, "Use Copper Golems", "COPPER_GOLEM_STATUE",
        "Whether outsiders without access trust can use copper golem statues."),
    USE_DOORS("use_doors", Category.PLAYER, Trust.ACCESS, "Use Doors", "OAK_DOOR",
        "Whether outsiders without access trust can use doors, trapdoors and gates."),
    USE_PLATES("use_plates", Category.PLAYER, Trust.ACCESS, "Use Plates", "STONE_PRESSURE_PLATE",
        "Whether outsiders without access trust can trigger pressure plates."),
    USE_POTS("use_pots", Category.PLAYER, Trust.ACCESS, "Use Pots", "FLOWER_POT",
        "Whether outsiders without access trust can use flower pots."),
    USE_SIGNS("use_signs", Category.PLAYER, Trust.ACCESS, "Use Signs", "OAK_SIGN",
        "Whether outsiders without access trust can use signs."),
    USE_TRIPWIRE("use_tripwire", Category.PLAYER, Trust.ACCESS, "Use Tripwire", "TRIPWIRE_HOOK",
        "Whether outsiders without access trust can trigger tripwires."),
    PVP("pvp", Category.PLAYER, Trust.NONE, "PvP", "DIAMOND_SWORD",
        "Whether players can damage each other here. Applies to everyone, trusted or not."),
    PLAYER_DAMAGE_VILLAGERS("player_damage_villagers", Category.PLAYER, Trust.CONTAINER, "Player Damage Villagers", "VILLAGER_SPAWN_EGG",
        "Whether outsiders without container trust can damage villagers."),
    PLAYER_DAMAGE_ANIMALS("player_damage_animals", Category.PLAYER, Trust.CONTAINER, "Player Damage Animals", "PIG_SPAWN_EGG",
        "Whether outsiders without container trust can damage animals."),
    PLAYER_DAMAGE_DECORATIONS("player_damage_decorations", Category.PLAYER, Trust.BUILD, "Player Damage Armor Stands", "ARMOR_STAND",
        "Whether outsiders without build trust can damage armor stands."),
    PLAYER_INCOMING_DAMAGE_FILTER("player_incoming_damage_filter", Category.PLAYER, Trust.NONE, "Incoming Player Damage", "IRON_SWORD",
        "Which damage types hurt players here. More specific flags override this one.",
        Filter.DAMAGE_CAUSE, FilterMode.BLACKLIST),
    USE_CHORUS_FRUITS("use_chorus_fruits", Category.PLAYER, Trust.ACCESS, "Use Chorus Fruits", "CHORUS_FRUIT",
        "Whether outsiders without access trust can teleport with chorus fruit."),
    USE_ENDER_PEARLS("use_ender_pearls", Category.PLAYER, Trust.ACCESS, "Use Ender Pearls", "ENDER_PEARL",
        "Whether outsiders without access trust can use ender pearls."),
    USE_FIREWORKS("use_fireworks", Category.PLAYER, Trust.ACCESS, "Use Fireworks", "FIREWORK_ROCKET",
        "Whether outsiders without access trust can use fireworks."),
    USE_HORNS("use_horns", Category.PLAYER, Trust.ACCESS, "Use Horns", "GOAT_HORN",
        "Whether outsiders without access trust can use goat horns."),
    USE_LIGHTER("use_lighter", Category.PLAYER, Trust.BUILD, "Use Lighter", "FLINT_AND_STEEL",
        "Whether outsiders without build trust can use flint and steel or fire charges."),
    USE_SPAWN_EGGS("use_spawn_eggs", Category.PLAYER, Trust.BUILD, "Use Spawn Eggs", "ZOMBIE_SPAWN_EGG",
        "Whether outsiders without build trust can use spawn eggs."),
    THROW_EGGS("throw_eggs", Category.PLAYER, Trust.ACCESS, "Throw Eggs", "EGG",
        "Whether outsiders without access trust can throw eggs."),
    THROW_ENDER_EYES("throw_ender_eyes", Category.PLAYER, Trust.ACCESS, "Throw Ender Eyes", "ENDER_EYE",
        "Whether outsiders without access trust can throw eyes of ender."),
    THROW_POTIONS("throw_potions", Category.PLAYER, Trust.ACCESS, "Throw Potions", "SPLASH_POTION",
        "Whether outsiders without access trust can throw potions."),
    THROW_TRIDENTS("throw_tridents", Category.PLAYER, Trust.ACCESS, "Throw Tridents", "TRIDENT",
        "Whether outsiders without access trust can throw tridents."),
    THROW_WIND_CHARGES("throw_wind_charges", Category.PLAYER, Trust.ACCESS, "Throw Wind Charges", "WIND_CHARGE",
        "Whether outsiders without access trust can throw wind charges."),
    SHOOT_BOW("shoot_bow", Category.PLAYER, Trust.ACCESS, "Shoot Arrows", "ARROW",
        "Whether outsiders without access trust can shoot arrows."),
    MOB_INTERACTION_FILTER("mob_interaction_filter", Category.PLAYER, Trust.CONTAINER, "Mob Interactions", "SADDLE",
        "Which mobs and entities outsiders without container trust can interact with. More specific flags override this one.",
        Filter.ENTITY_TYPE, FilterMode.WHITELIST),
    USE_VILLAGERS("use_villagers", Category.PLAYER, Trust.CONTAINER, "Use Villagers", "EMERALD",
        "a36e9841794a37eb99524925668b47a62b5cb72e096a9f8f95e106804ae13e1b",
        "Whether outsiders without container trust can trade with villagers."),
    USE_ARMOR_STANDS("use_armor_stands", Category.PLAYER, Trust.BUILD, "Use Armor Stands", "ARMOR_STAND",
        "Whether outsiders without build trust can use armor stands."),
    USE_ITEM_FRAMES("use_item_frames", Category.PLAYER, Trust.BUILD, "Use Item Frames", "ITEM_FRAME",
        "Whether outsiders without build trust can use item frames."),
    USE_VEHICLES("use_vehicles", Category.PLAYER, Trust.ACCESS, "Use Vehicles", "MINECART",
        "Whether outsiders without access trust can ride boats and minecarts."),
    ENTITY_PLACE("entity_place", Category.PLAYER, Trust.BUILD, "Place Entities", "OAK_BOAT",
        "Whether outsiders without build trust can place vehicles and armor stands."),
    PLACE_HANGINGS("place_hangings", Category.PLAYER, Trust.BUILD, "Place Hangings", "PAINTING",
        "Whether outsiders without build trust can place paintings and item frames."),
    BREAK_HANGINGS("break_hangings", Category.PLAYER, Trust.BUILD, "Break Hangings", "PAINTING",
        "Whether outsiders without build trust can break paintings and item frames."),
    BREAK_VEHICLES("break_vehicles", Category.PLAYER, Trust.BUILD, "Destroy Vehicles", "MINECART",
        "Whether outsiders without build trust can break vehicles."),
    DROP_ITEMS("drop_items", Category.PLAYER, Trust.ACCESS, "Drop Items", "STICK",
        "Whether outsiders without access trust can drop items."),
    PICKUP_ITEMS("pickup_items", Category.PLAYER, Trust.ACCESS, "Pickup Items", "HOPPER",
        "Whether outsiders without access trust can pick up items."),
    FILL_BUCKETS("fill_buckets", Category.PLAYER, Trust.BUILD, "Fill Buckets", "BUCKET",
        "Whether outsiders without build trust can fill buckets."),
    USE_NETHER_PORTAL("use_nether_portal", Category.PLAYER, Trust.ACCESS, "Use Nether Portals", "OBSIDIAN",
        "Whether outsiders without access trust can use nether portals."),
    USE_END_PORTAL("use_end_portal", Category.PLAYER, Trust.ACCESS, "Use End Portals", "END_PORTAL_FRAME",
        "Whether outsiders without access trust can use end portals."),
    COMMAND_USAGE_FILTER("command_usage_filter", Category.PLAYER, Trust.ACCESS, "Command Usage", "COMMAND_BLOCK",
        "Which commands outsiders without access trust can use here.",
        Filter.COMMAND, FilterMode.BLACKLIST),

    // ================================= Mobs & Entities =================================
    BLOCK_TRAMP("block_tramp", Category.ENTITY, Trust.ACCESS, "Block Trample", "WHEAT_SEEDS",
        "Whether mobs and outsiders without access trust can trample farmland and turtle eggs."),
    SPAWN_AMBIENTS("spawn_ambients", Category.ENTITY, Trust.NONE, "Spawn Ambients", "BAT_SPAWN_EGG",
        "Whether ambient mobs can spawn here."),
    SPAWN_ANIMALS("spawn_animals", Category.ENTITY, Trust.NONE, "Spawn Animals", "PIG_SPAWN_EGG",
        "Whether animals can spawn here."),
    SPAWN_MONSTERS("spawn_monsters", Category.ENTITY, Trust.NONE, "Spawn Monsters", "ZOMBIE_HEAD",
        "Whether monsters can spawn here."),
    SPAWN_FISHES("spawn_fishes", Category.ENTITY, Trust.NONE, "Spawn Water Mobs", "COD",
        "Whether water mobs can spawn here."),
    MOB_SPAWN_FILTER("mob_spawn_filter", Category.ENTITY, Trust.NONE, "Mob Spawn", "SPAWNER",
        "Which mobs can spawn here. More specific flags override this one.",
        Filter.ENTITY_TYPE, FilterMode.BLACKLIST),
    TNT_BLOCK_DAMAGE("tnt_block_damage", Category.ENTITY, Trust.NONE, "TNT Block Damage", "TNT",
        "Whether TNT can damage blocks here."),
    CREEPER_BLOCK_DAMAGE("creeper_block_damage", Category.ENTITY, Trust.NONE, "Creeper Block Damage", "CREEPER_HEAD",
        "Whether creepers can damage blocks here."),
    WITHER_BLOCK_DAMAGE("wither_block_damage", Category.ENTITY, Trust.NONE, "Wither Damage", "WITHER_SKELETON_SKULL",
        "Whether withers can damage blocks here."),
    FIREBALL_BLOCK_DAMAGE("fireball_block_damage", Category.ENTITY, Trust.NONE, "Fireball Block Damage", "FIRE_CHARGE",
        "Whether fireballs can damage blocks here."),
    END_CRYSTAL_BLOCK_DAMAGE("end_crystal_block_damage", Category.ENTITY, Trust.NONE, "End Crystal Block Damage", "END_CRYSTAL",
        "Whether end crystals can damage blocks here."),
    ANIMAL_GRIEF("animal_grief", Category.ENTITY, Trust.NONE, "Animal Grief", "WHITE_WOOL",
        "84e5cdb0edb362cb454586d1fd0ebe971423f015b0b1bfc95f8d5af8afe7e810",
        "Whether animals can change blocks here."),
    VILLAGER_FARM("villager_farm", Category.ENTITY, Trust.NONE, "Villager Farm", "WHEAT",
        "ef15fe4b7623c753393524f557b36ec81258a75daba159b39a6f800f7171a475",
        "Whether villagers can farm crops here."),
    SILVERFISH_INFEST("silverfish_infest", Category.ENTITY, Trust.NONE, "Silverfish Infest", "STONE_BRICKS",
        "Whether silverfish can infest blocks here."),
    ENDERMAN_GRIEF("enderman_grief", Category.ENTITY, Trust.NONE, "Enderman Grief", "ENDER_PEARL",
        "4f24767c8138b3dfec02f77bd151994d480d4e869664ce09a26b19289212162b",
        "Whether endermen can pick up blocks here."),
    ENDER_DRAGON_GRIEF("ender_dragon_grief", Category.ENTITY, Trust.NONE, "Ender Dragon Grief", "DRAGON_HEAD",
        "Whether the ender dragon can destroy blocks here."),
    RAVAGER_GRIEF("ravager_grief", Category.ENTITY, Trust.NONE, "Ravager Grief", "SADDLE",
        "cd20bf52ec390a0799299184fc678bf84cf732bb1bd78fd1c4b441858f0235a8",
        "Whether ravagers can destroy blocks here."),
    SNOWMAN_TRAIL("snowman_trail", Category.ENTITY, Trust.NONE, "Snowman Trail", "SNOW_BLOCK",
        "126ab3ed98ff470e4aa03fc69c745f61c0b614f3e1ecb42bac1c929223364789",
        "Whether snow golems can leave snow here."),

    // =================================== Environment ===================================
    EXPLOSION_DAMAGE_BLOCKS("explosion_damage_blocks", Category.NATURAL, Trust.NONE, "Explosion Destroy Blocks", "TNT",
        "Whether explosions can destroy blocks here. More specific flags override this one."),
    FIRE_DAMAGE_BLOCKS("fire_damage_blocks", Category.NATURAL, Trust.NONE, "Fire Damage Blocks", "FLINT_AND_STEEL",
        "Whether fire can destroy blocks here."),
    LEAVES_DECAY("leaves_decay", Category.NATURAL, Trust.NONE, "Leaves Decay", "OAK_LEAVES",
        "Whether leaves can decay here."),
    LIGHTNING_DAMAGE("lightning_damage", Category.NATURAL, Trust.NONE, "Lightning Damage", "LIGHTNING_ROD",
        "Whether lightning can strike here, start fires or hurt entities."),
    BLOCK_FERTILIZE("block_fertilize", Category.NATURAL, Trust.BUILD, "Block Fertilize", "BONE_MEAL",
        "Whether dispensers and outsiders without build trust can use bone meal."),
    TREE_GROW("tree_grow", Category.NATURAL, Trust.NONE, "Tree Grow", "OAK_SAPLING",
        "Whether trees can grow here."),
    MUSHROOM_GROW("mushroom_grow", Category.NATURAL, Trust.NONE, "Mushroom Grow", "RED_MUSHROOM",
        "Whether mushrooms can grow and spread here."),
    AMETHYST_GROW("amethyst_grow", Category.NATURAL, Trust.NONE, "Amethyst Grow", "MEDIUM_AMETHYST_BUD",
        "Whether amethyst buds can grow here."),
    CACTUS_GROW("cactus_grow", Category.NATURAL, Trust.NONE, "Cactus Grow", "CACTUS",
        "Whether cactus can grow here."),
    CROP_GROW("crop_grow", Category.NATURAL, Trust.NONE, "Crop Grow", "WHEAT",
        "Whether crops and plants can grow here. More specific flags override this one."),
    SUGAR_CANE_GROW("sugar_cane_grow", Category.NATURAL, Trust.NONE, "Sugar Cane Grow", "SUGAR_CANE",
        "Whether sugar cane can grow here."),
    TURTLE_EGG_HATCH("turtle_egg_hatch", Category.NATURAL, Trust.NONE, "Turtle Egg Hatch", "TURTLE_EGG",
        "Whether turtle eggs can hatch here."),
    VINE_GROW("vine_grow", Category.NATURAL, Trust.NONE, "Vine Grow", "VINE",
        "Whether vines can grow on the same block here."),
    BAMBOO_GROW("bamboo_grow", Category.NATURAL, Trust.NONE, "Bamboo Grow", "BAMBOO",
        "Whether bamboo can grow here."),
    CAVE_VINES_GROW("cave_vines_grow", Category.NATURAL, Trust.NONE, "Cave Vine Grow", "GLOW_BERRIES",
        "Whether cave vines can grow here (berries are unaffected)."),
    KELP_GROW("kelp_grow", Category.NATURAL, Trust.NONE, "Kelp Grow", "KELP",
        "Whether kelp can grow here."),
    TWISTING_VINES_GROW("twisting_vines_grow", Category.NATURAL, Trust.NONE, "Twisting Vines Grow", "TWISTING_VINES",
        "Whether twisting vines can grow here."),
    WEEPING_VINES_GROW("weeping_vines_grow", Category.NATURAL, Trust.NONE, "Weeping Vines Grow", "WEEPING_VINES",
        "Whether weeping vines can grow here."),
    AMETHYST_SPREAD("amethyst_spread", Category.NATURAL, Trust.NONE, "Amethyst Spread", "SMALL_AMETHYST_BUD",
        "Whether budding amethyst can sprout new buds here."),
    FIRE_SPREAD("fire_spread", Category.NATURAL, Trust.NONE, "Fire Spread", "CAMPFIRE",
        "Whether fire can spread here."),
    GRASS_SPREAD("grass_spread", Category.NATURAL, Trust.NONE, "Grass Spread", "GRASS_BLOCK",
        "Whether grass can spread onto dirt here."),
    MYCELIUM_SPREAD("mycelium_spread", Category.NATURAL, Trust.NONE, "Mycelium Spread", "MYCELIUM",
        "Whether mycelium can spread here."),
    VINE_SPREAD("vine_spread", Category.NATURAL, Trust.NONE, "Vine Spread", "VINE",
        "Whether vines can spread onto nearby blocks here."),
    SNOW_FORM("snow_form", Category.NATURAL, Trust.NONE, "Snow Form", "SNOW",
        "Whether snow can form during snowfall here."),
    ICE_FORM("ice_form", Category.NATURAL, Trust.NONE, "Ice Form", "ICE",
        "Whether ice can form here."),
    CORAL_DIE("coral_die", Category.NATURAL, Trust.NONE, "Coral Die", "BRAIN_CORAL",
        "Whether coral can die here."),
    FARMLAND_DRY("farmland_dry", Category.NATURAL, Trust.NONE, "Farmland Dry", "FARMLAND",
        "Whether farmland can dry out here."),
    FIRE_BURN_OUT("fire_burn_out", Category.NATURAL, Trust.NONE, "Fire Burn Out", "FLINT_AND_STEEL",
        "Whether fire can burn out here."),
    ICE_MELT("ice_melt", Category.NATURAL, Trust.NONE, "Ice Melt", "PACKED_ICE",
        "Whether ice can melt here."),
    SNOW_MELT("snow_melt", Category.NATURAL, Trust.NONE, "Snow Melt", "SNOW_BLOCK",
        "Whether snow can melt here."),
    LAVA_FLOW("lava_flow", Category.NATURAL, Trust.NONE, "Lava Flow", "LAVA_BUCKET",
        "Whether lava can flow into this claim from outside."),
    WATER_FLOW("water_flow", Category.NATURAL, Trust.NONE, "Water Flow", "WATER_BUCKET",
        "Whether water can flow into this claim from outside.");

    /** Menu tabs, matching ExcellentClaims' rule categories. */
    public enum Category { PLAYER, ENTITY, NATURAL }

    /**
     * The GriefPrevention trust a player needs to be unaffected by a player flag, ranked so a
     * higher level includes the lower ones (build includes container includes access).
     */
    public enum Trust {
        NONE(0), ACCESS(1), CONTAINER(2), BUILD(3);

        private final int rank;

        Trust(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    /** What a filter flag's entries are. */
    public enum Filter { NONE, MATERIAL, ENTITY_TYPE, COMMAND, DAMAGE_CAUSE }

    /** Whitelist: only listed entries pass. Blacklist: listed entries are blocked. */
    public enum FilterMode { WHITELIST, BLACKLIST }

    private static final Map<String, ClaimFlag> BY_ID = new HashMap<>();

    static {
        for (ClaimFlag flag : values()) {
            BY_ID.put(flag.id, flag);
        }
        // ExcellentClaims spells it "pearles"; accept that id too.
        BY_ID.put("use_ender_pearles", USE_ENDER_PEARLS);
    }

    private final String id;
    private final Category category;
    private final Trust trust;
    private final String displayName;
    private final String iconMaterial;
    private final String headTexture;
    private final String description;
    private final Filter filter;
    private final FilterMode defaultFilterMode;

    ClaimFlag(String id, Category category, Trust trust, String displayName, String iconMaterial, String description) {
        this(id, category, trust, displayName, iconMaterial, null, description, Filter.NONE, null);
    }

    ClaimFlag(String id, Category category, Trust trust, String displayName, String iconMaterial,
              String headTexture, String description) {
        this(id, category, trust, displayName, iconMaterial, headTexture, description, Filter.NONE, null);
    }

    ClaimFlag(String id, Category category, Trust trust, String displayName, String iconMaterial,
              String description, Filter filter, FilterMode defaultFilterMode) {
        this(id, category, trust, displayName, iconMaterial, null, description, filter, defaultFilterMode);
    }

    ClaimFlag(String id, Category category, Trust trust, String displayName, String iconMaterial,
              String headTexture, String description, Filter filter, FilterMode defaultFilterMode) {
        this.id = id;
        this.category = category;
        this.trust = trust;
        this.displayName = displayName;
        this.iconMaterial = iconMaterial;
        this.headTexture = headTexture;
        this.description = description;
        this.filter = filter;
        this.defaultFilterMode = defaultFilterMode;
    }

    public static ClaimFlag fromId(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public String id() {
        return id;
    }

    public Category category() {
        return category;
    }

    public Trust trust() {
        return trust;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Head texture hash (textures.minecraft.net) for the menu icon, or null for a plain material. */
    public String headTexture() {
        return headTexture;
    }

    public boolean isFilter() {
        return filter != Filter.NONE;
    }

    public Filter filter() {
        return filter;
    }

    public FilterMode defaultFilterMode() {
        return defaultFilterMode;
    }

    public String permission() {
        return "griefprevention.claim.flag." + id;
    }

    /** The menu icon material, resolved by name so newer items fall back on older servers. */
    public Material iconMaterial() {
        Material material = Material.matchMaterial(iconMaterial);
        if (material == null || !material.isItem()) {
            return category == Category.NATURAL ? Material.GRASS_BLOCK
                : category == Category.ENTITY ? Material.EGG : Material.PLAYER_HEAD;
        }
        return material;
    }

    public static List<ClaimFlag> byCategory(Category category) {
        return java.util.Arrays.stream(values()).filter(flag -> flag.category == category).toList();
    }
}
