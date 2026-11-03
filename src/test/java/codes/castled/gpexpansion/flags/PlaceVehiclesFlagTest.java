package codes.castled.gpexpansion.flags;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Place Vehicles decides boat and minecart placement, and claims that leave it unset keep
 * following Place Entities (the flag that covered vehicles before Place Vehicles existed).
 */
class PlaceVehiclesFlagTest {

    private final Map<ClaimFlag, Boolean> claimValues = new EnumMap<>(ClaimFlag.class);
    private ClaimFlagListener listener;
    private Claim claim;
    private Player outsider;

    @BeforeEach
    void setUp() {
        claim = mock(Claim.class);
        outsider = mock(Player.class);
        ClaimFlagManager manager = mock(ClaimFlagManager.class);
        when(manager.claimAt(any())).thenReturn(claim);
        when(manager.isAvailable(any())).thenReturn(true);
        when(manager.isActive(any())).thenReturn(true);
        when(manager.resolve(any(), eq(claim)))
            .thenAnswer(inv -> claimValues.get(inv.<ClaimFlag>getArgument(0)));
        when(manager.isDenied(any(), eq(claim)))
            .thenAnswer(inv -> Boolean.FALSE.equals(claimValues.get(inv.<ClaimFlag>getArgument(0))));
        when(manager.isOutsider(eq(outsider), eq(claim), any())).thenReturn(true);
        listener = new ClaimFlagListener(manager);
    }

    @Test
    void placeVehiclesIsABuildTrustPlayerFlag() {
        assertSame(ClaimFlag.PLACE_VEHICLES, ClaimFlag.fromId("place_vehicles"));
        assertEquals(ClaimFlag.Category.PLAYER, ClaimFlag.PLACE_VEHICLES.category());
        assertEquals(ClaimFlag.Trust.BUILD, ClaimFlag.PLACE_VEHICLES.trust());
        assertTrue(ClaimFlag.byCategory(ClaimFlag.Category.PLAYER).contains(ClaimFlag.PLACE_VEHICLES));
    }

    @Test
    void vehiclesFallBackToPlaceEntitiesWhilePlaceVehiclesIsUnset() {
        for (Entity vehicle : vehicles()) {
            claimValues.clear();
            claimValues.put(ClaimFlag.ENTITY_PLACE, false);
            assertTrue(denied(vehicle), "Place Entities: Deny must still block " + vehicle);
            assertFalse(allowed(vehicle));

            claimValues.put(ClaimFlag.ENTITY_PLACE, true);
            assertFalse(denied(vehicle));
            assertTrue(allowed(vehicle), "Place Entities: Allow must still admit " + vehicle);
        }
    }

    @Test
    void placeVehiclesOverridesPlaceEntitiesForVehicles() {
        for (Entity vehicle : vehicles()) {
            claimValues.clear();
            claimValues.put(ClaimFlag.ENTITY_PLACE, false);
            claimValues.put(ClaimFlag.PLACE_VEHICLES, true);
            assertFalse(denied(vehicle), "Place Vehicles: Allow must win over Place Entities: Deny");
            assertTrue(allowed(vehicle));

            claimValues.put(ClaimFlag.ENTITY_PLACE, true);
            claimValues.put(ClaimFlag.PLACE_VEHICLES, false);
            assertTrue(denied(vehicle), "Place Vehicles: Deny must win over Place Entities: Allow");
            assertFalse(allowed(vehicle));
        }
    }

    @Test
    void placeVehiclesDoesNotCoverOtherPlacedEntities() {
        for (Entity other : List.of(entity(ArmorStand.class), entity(EnderCrystal.class))) {
            claimValues.clear();
            claimValues.put(ClaimFlag.ENTITY_PLACE, false);
            claimValues.put(ClaimFlag.PLACE_VEHICLES, true);
            assertTrue(denied(other), "Place Vehicles must not let outsiders place " + other);
            assertFalse(allowed(other));
        }
    }

    private List<Entity> vehicles() {
        return List.of(entity(Boat.class), entity(ChestBoat.class), entity(Minecart.class),
            entity(StorageMinecart.class));
    }

    private static Entity entity(Class<? extends Entity> type) {
        Entity entity = mock(type, type.getSimpleName());
        when(entity.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        return entity;
    }

    /** Runs the Deny path: whether the listener cancels an outsider placing this entity. */
    private boolean denied(Entity placed) {
        EntityPlaceEvent event = placeEvent(placed);
        listener.onEntityPlace(event);
        return event.isCancelled();
    }

    /** Runs the Allow path: whether a GriefPrevention build denial would be lifted. */
    private boolean allowed(Entity placed) {
        return listener.allowsOverride(placeEvent(placed), claim, ClaimFlag.Trust.BUILD);
    }

    private EntityPlaceEvent placeEvent(Entity placed) {
        return new EntityPlaceEvent(placed, outsider, mock(Block.class), BlockFace.UP, EquipmentSlot.HAND);
    }
}
