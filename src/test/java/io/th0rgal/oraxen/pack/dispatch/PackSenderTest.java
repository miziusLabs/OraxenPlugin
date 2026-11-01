package io.th0rgal.oraxen.pack.dispatch;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PackSenderTest {

    private final UUID playerId = UUID.randomUUID();

    @AfterEach
    void clearPackTracking() {
        PackSender.forgetPack(playerId);
    }

    @Test
    void preJoinRequiresSupportedVersion() {
        assertTrue(PackSender.isPreJoinSupported(true));
        assertFalse(PackSender.isPreJoinSupported(false));
    }

    @Test
    void removesPreviousPackBeforeSendingUpdatedPack() {
        Audience audience = mock(Audience.class);
        UUID previousPackId = UUID.randomUUID();
        UUID updatedPackId = UUID.randomUUID();
        ResourcePackRequest previousRequest = mock(ResourcePackRequest.class);
        ResourcePackRequest updatedRequest = mock(ResourcePackRequest.class);

        PackSender.sendResourcePack(audience, playerId, previousPackId, previousRequest);
        PackSender.sendResourcePack(audience, playerId, updatedPackId, updatedRequest);

        var order = inOrder(audience);
        order.verify(audience).sendResourcePacks(previousRequest);
        order.verify(audience).removeResourcePacks(eq(previousPackId));
        order.verify(audience).sendResourcePacks(updatedRequest);
    }

    @Test
    void keepsCurrentPackWhenThePackIdHasNotChanged() {
        Audience audience = mock(Audience.class);
        UUID packId = UUID.randomUUID();

        PackSender.sendResourcePack(audience, playerId, packId, mock(ResourcePackRequest.class));
        PackSender.sendResourcePack(audience, playerId, packId, mock(ResourcePackRequest.class));

        verify(audience, never()).removeResourcePacks(any(UUID.class), any(UUID[].class));
    }
}
