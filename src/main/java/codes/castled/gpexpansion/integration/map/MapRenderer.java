package codes.castled.gpexpansion.integration.map;

import java.util.Collection;

/**
 * A map plugin renderer that can draw the current set of claims and clear them again.
 * Implementations are loaded via reflection only when the backing map plugin is present,
 * so this interface must not reference any map-plugin type.
 */
public interface MapRenderer {

    /**
     * Full refresh: the renderer replaces everything it currently shows with these claims.
     */
    void renderAll(Collection<ClaimShape> claims);

    /**
     * Remove everything this renderer has drawn (called on plugin disable/reload).
     */
    void clear();
}