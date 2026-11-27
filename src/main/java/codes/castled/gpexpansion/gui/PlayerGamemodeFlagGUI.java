package codes.castled.gpexpansion.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Sub-menu opened from {@link ClaimFlagsGUI} when clicking GPFlags's {@code PlayerGamemode} flag.
 * Backed by {@link GPFlagChoiceGUI}.
 */
public class PlayerGamemodeFlagGUI extends GPFlagChoiceGUI {

    enum ModeOption {
        SURVIVAL("survival", "Survival", Material.IRON_SWORD, 10,
                "&7Standard survival gameplay with", "&7health, hunger, and resource gathering."),
        CREATIVE("creative", "Creative", Material.GRASS_BLOCK, 12,
                "&7Flight, unlimited blocks, and", "&7invulnerability inside this claim."),
        ADVENTURE("adventure", "Adventure", Material.MAP, 14,
                "&7Restricts breaking and placing blocks", "&7without matching tool rules."),
        SPECTATOR("spectator", "Spectator", Material.ENDER_EYE, 16,
                "&7Pass through blocks and observe", "&7the claim without interacting.");

        final String parameter;
        final String displayName;
        final Material material;
        final int slot;
        final String[] description;

        ModeOption(String parameter, String displayName, Material material, int slot, String... description) {
            this.parameter = parameter;
            this.displayName = displayName;
            this.material = material;
            this.slot = slot;
            this.description = description;
        }

        Choice toChoice() {
            return new Choice(parameter, displayName, material, slot, Arrays.asList(description));
        }
    }

    private static final List<Choice> CHOICES = Arrays.stream(ModeOption.values())
            .map(ModeOption::toChoice)
            .toList();

    public PlayerGamemodeFlagGUI(GUIManager manager, Player player, Object claim, String claimId, int returnPage) {
        super(
                manager,
                player,
                claim,
                claimId,
                ClaimFlagsGUI.PLAYER_GAMEMODE_FLAG,
                "&b&lPlayer Gamemode",
                "Choose a gamemode to enforce inside this claim.",
                CHOICES,
                returnPage
        );
    }

    static String formatModeName(String raw) {
        return formatLabel(raw);
    }
}
