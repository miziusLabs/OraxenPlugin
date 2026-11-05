package io.th0rgal.oraxen.utils.breaker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomBlockMiningListenerTest {

    @Test
    void convertsNoteBlockProgressToConfiguredHardness() {
        final double nativeProgress = 8.0D / 0.8D / 30.0D;
        final double multiplier = CustomBlockMiningListener.miningSpeedMultiplier(0.8D, 2.0D, 1.0D);

        assertEquals(0.4D, multiplier, 0.000001D);
        assertEquals(8.0D / 2.0D / 30.0D, nativeProgress * multiplier, 0.000001D);
    }

    @Test
    void appliesConfiguredFallbackToolSpeedToCustomHardness() {
        final double nativeProgress = 8.0D / 0.8D / 30.0D;
        final double multiplier = CustomBlockMiningListener.miningSpeedMultiplier(0.8D, 4.0D, 1.0D / 8.0D);

        assertEquals(0.8D / 4.0D / 8.0D, multiplier, 0.000001D);
        assertEquals(1.0D / 4.0D / 30.0D, nativeProgress * multiplier, 0.000001D);
    }
}
