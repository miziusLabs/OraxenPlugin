package io.th0rgal.oraxen.utils.breaker;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * Prevents the client from completing a vanilla block break while Oraxen owns the break progress.
 * The temporary attribute modifier is synchronized to the client and removed when Oraxen releases
 * the break. BreakerSystem caches its server-side progress before applying the modifier.
 */
public final class ClientSideBlockBreakSuppressor {

    private static final BreakSpeedModifier BREAK_SPEED_MODIFIER = new BreakSpeedModifier(
            NamespacedKey.fromString("oraxen:client_break_suppression"));

    private ClientSideBlockBreakSuppressor() {
    }

    public static boolean isSupported() {
        return BreakSpeedModifier.isSupported();
    }

    static void suppress(final Player player) {
        BREAK_SPEED_MODIFIER.apply(player, 0.0D);
    }

    static void restore(final Player player) {
        BREAK_SPEED_MODIFIER.clear(player);
    }
}
