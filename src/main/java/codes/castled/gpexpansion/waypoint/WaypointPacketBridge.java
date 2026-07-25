package codes.castled.gpexpansion.waypoint;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends vanilla locator-bar waypoint packets with no backing entity.
 *
 * <p>{@code ClientboundTrackedWaypointPacket} is self-contained — an identifier, an icon and a
 * position — and the client renders whatever it is told. The entity, the transmit-range attribute
 * and {@code ServerWaypointManager} are only vanilla's mechanism for deciding when to emit these
 * packets, and that mechanism is what drags in chunk loading: {@code LivingEntity#onRemoval}
 * untracks the waypoint when its chunk unloads. Emitting the packets ourselves removes every one
 * of those constraints: no entities, no chunk tickets, no {@code hideEntity} bookkeeping, and
 * exact per-player control because we choose the recipients.
 *
 * <p>Reflection rather than an NMS dependency because GPExpansion builds against {@code paper-api}
 * only. Paper has shipped Mojang mappings at runtime since 1.20.5 and stopped versioning the
 * CraftBukkit package, so these names are stable; every handle was verified against the 26.2
 * server jar. Everything resolves once in the static initialiser, and any failure flips
 * {@link #isAvailable()} so callers degrade to CrowBar-only delivery instead of throwing per send.
 */
final class WaypointPacketBridge {

    private static final Method GET_HANDLE;
    private static final Field CONNECTION;
    private static final Method SEND;
    private static final Method ADD_WAYPOINT_POSITION;
    private static final Method REMOVE_WAYPOINT;
    private static final Constructor<?> ICON_CONSTRUCTOR;
    private static final Field ICON_STYLE;
    private static final Field ICON_COLOR;
    private static final Object BOWTIE_STYLE;
    private static final Constructor<?> VEC3I_CONSTRUCTOR;
    private static final Throwable INIT_ERROR;

    static {
        Method getHandle = null;
        Field connection = null;
        Method send = null;
        Method addWaypointPosition = null;
        Method removeWaypoint = null;
        Constructor<?> iconConstructor = null;
        Field iconStyle = null;
        Field iconColor = null;
        Object bowtieStyle = null;
        Constructor<?> vec3iConstructor = null;
        Throwable error = null;
        try {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");

            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            connection = serverPlayer.getField("connection");

            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
            send = connection.getType().getMethod("send", packet);

            Class<?> icon = Class.forName("net.minecraft.world.waypoints.Waypoint$Icon");
            iconConstructor = icon.getConstructor();
            iconStyle = icon.getField("style");
            iconColor = icon.getField("color");

            Class<?> styleAssets = Class.forName("net.minecraft.world.waypoints.WaypointStyleAssets");
            bowtieStyle = styleAssets.getField("BOWTIE").get(null);

            Class<?> vec3i = Class.forName("net.minecraft.core.Vec3i");
            vec3iConstructor = vec3i.getConstructor(int.class, int.class, int.class);

            Class<?> waypointPacket =
                Class.forName("net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket");
            addWaypointPosition = waypointPacket.getMethod("addWaypointPosition", UUID.class, icon, vec3i);
            removeWaypoint = waypointPacket.getMethod("removeWaypoint", UUID.class);
        } catch (Throwable throwable) {
            error = throwable;
        }
        GET_HANDLE = getHandle;
        CONNECTION = connection;
        SEND = send;
        ADD_WAYPOINT_POSITION = addWaypointPosition;
        REMOVE_WAYPOINT = removeWaypoint;
        ICON_CONSTRUCTOR = iconConstructor;
        ICON_STYLE = iconStyle;
        ICON_COLOR = iconColor;
        BOWTIE_STYLE = bowtieStyle;
        VEC3I_CONSTRUCTOR = vec3iConstructor;
        INIT_ERROR = error;
    }

    private WaypointPacketBridge() {}

    static boolean isAvailable() {
        return INIT_ERROR == null;
    }

    /** Why the bridge failed to initialise, for a single startup log line. Null when available. */
    static @Nullable Throwable initError() {
        return INIT_ERROR;
    }

    /**
     * Shows (or repositions/recolours) a bowtie waypoint on this player's locator bar.
     *
     * <p>The client stores waypoints in a map keyed by identifier, so re-sending an add for an
     * existing identifier replaces it in place — there is no separate update path to manage.
     *
     * @param rgb icon colour, or null for the client's identifier-derived default
     */
    static void sendAdd(Player viewer, UUID waypointId, int x, int y, int z, @Nullable Integer rgb) {
        try {
            Object icon = ICON_CONSTRUCTOR.newInstance();
            ICON_STYLE.set(icon, BOWTIE_STYLE);
            ICON_COLOR.set(icon, Optional.ofNullable(rgb));
            Object position = VEC3I_CONSTRUCTOR.newInstance(x, y, z);
            Object packet = ADD_WAYPOINT_POSITION.invoke(null, waypointId, icon, position);
            sendPacket(viewer, packet);
        } catch (ReflectiveOperationException ignored) {
            // Resolution succeeded at init, so a per-send failure means the player is
            // disconnecting; there is nobody left to show the waypoint to.
        }
    }

    /** Removes a waypoint from this player's locator bar. */
    static void sendRemove(Player viewer, UUID waypointId) {
        try {
            sendPacket(viewer, REMOVE_WAYPOINT.invoke(null, waypointId));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void sendPacket(Player viewer, Object packet) throws ReflectiveOperationException {
        Object handle = GET_HANDLE.invoke(viewer);
        Object connection = CONNECTION.get(handle);
        // Netty queues the write, so this is safe from any thread — including Folia regions.
        SEND.invoke(connection, packet);
    }
}
