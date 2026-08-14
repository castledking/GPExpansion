package codes.castled.gpexpansion.listener;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Temporarily hides a managed sign header while normal-priority sign integrations run.
 */
final class SignMarkerShield<K, H> {

    private final ConcurrentMap<K, H> originalHeaders = new ConcurrentHashMap<>();

    H shield(K eventKey, H originalHeader, boolean managedHeader, H maskedHeader) {
        if (!managedHeader) {
            return originalHeader;
        }
        originalHeaders.putIfAbsent(eventKey, originalHeader);
        return maskedHeader;
    }

    Optional<H> restore(K eventKey) {
        return Optional.ofNullable(originalHeaders.remove(eventKey));
    }
}
