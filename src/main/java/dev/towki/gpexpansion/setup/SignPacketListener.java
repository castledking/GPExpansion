package dev.towki.gpexpansion.setup;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;

import dev.towki.gpexpansion.GPExpansionPlugin;
import dev.towki.gpexpansion.setup.SetupWizardManager.PendingSignData;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * PacketEvents listener to intercept sign editor opening and inject wizard format.
 * This allows players to see and edit the pre-filled format before clicking Done.
 */
public class SignPacketListener extends PacketListenerAbstract {
    
    private final GPExpansionPlugin plugin;
    private final SetupWizardManager wizardManager;
    
    // Track players we're re-opening signs for to prevent infinite loop
    private final Set<UUID> processingSign = new HashSet<>();
    
    public SignPacketListener(GPExpansionPlugin plugin, SetupWizardManager wizardManager) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.wizardManager = wizardManager;
    }
    
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.OPEN_SIGN_EDITOR) {
            return;
        }
        
        Player player = Bukkit.getPlayer(event.getUser().getUUID());
        if (player == null) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        
        // Skip if we're already processing this player's sign (prevents infinite loop)
        if (processingSign.contains(playerId)) {
            processingSign.remove(playerId);
            return;
        }
        
        // Check if player has pending auto-paste
        if (!wizardManager.hasPendingAutoPaste(playerId)) {
            return;
        }
        
        // Get the pending data
        PendingSignData data = wizardManager.getPendingAutoPaste(playerId);
        if (data == null) {
            return;
        }
        
        // Get sign position from packet
        WrapperPlayServerOpenSignEditor wrapper = new WrapperPlayServerOpenSignEditor(event);
        Vector3i pos = wrapper.getPosition();
        
        plugin.getLogger().info("[PacketEvents] Intercepted sign editor open for " + player.getName());
        plugin.getLogger().info("[PacketEvents] Sign position: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        
        // Cancel the original packet - we'll re-open after updating on main thread
        event.setCancelled(true);
        
        String[] lines = data.getSignLines();
        
        // Schedule on main thread since PacketEvents may be async
        runOnPlayer(player, () -> {
            // Get the sign block on main thread
            Block block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            if (!(block.getState() instanceof Sign sign)) {
                plugin.getLogger().warning("[PacketEvents] Block is not a sign!");
                return;
            }
            
            // Set the lines on the sign
            plugin.getLogger().info("[PacketEvents] Setting sign lines:");
            for (int i = 0; i < 4 && i < lines.length; i++) {
                plugin.getLogger().info("[PacketEvents]   Line " + i + ": " + lines[i]);
                sign.getSide(Side.FRONT).line(i, LegacyComponentSerializer.legacyAmpersand().deserialize(lines[i]));
            }
            sign.update(true, true);
            
            // Send sign content to client FIRST
            java.util.List<net.kyori.adventure.text.Component> signLines = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                signLines.add(sign.getSide(Side.FRONT).line(i));
            }
            player.sendSignChange(block.getLocation(), signLines);
            
            // Consume the pending data now
            wizardManager.consumePendingAutoPaste(playerId);
            
            // Mark that we're re-opening to prevent infinite loop
            processingSign.add(playerId);
            
            // Delay opening the editor to give client time to receive sign content
            runOnPlayerLater(player, () -> {
                // Re-open the sign editor with updated content
                player.openSign(sign, Side.FRONT);
                
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&a✓ Sign format loaded! Edit if needed, then click Done."
                ));
            }, 2L); // 2 tick delay
        });
    }
    
    /**
     * Register this listener with PacketEvents.
     */
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        plugin.getLogger().info("[PacketEvents] SignPacketListener registered");
    }
    
    /**
     * Unregister this listener from PacketEvents.
     */
    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }
    
    private void runOnPlayer(Player player, Runnable task) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runOnEntity(plugin, player, task, null);
    }
    
    private void runOnPlayerLater(Player player, Runnable task, long delayTicks) {
        dev.towki.gpexpansion.scheduler.SchedulerAdapter.runOnEntityLater(plugin, player, task, null, delayTicks);
    }
}
