package com.melon.app.controller;

import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelHealth;
import com.melon.channels.ChannelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelCompatControllerLifecycleTest {

    @Mock
    private ChannelConfigService configService;
    @Mock
    private ChannelManager channelManager;
    @Mock
    private ChannelAccessControlStore accessControlStore;
    @Mock
    private ChannelAdapterRegistry registry;

    @Test
    void updatingEnabledChannelReconcilesConnectionWithoutForcingRestart() {
        when(configService.update("personal-test", "qq", Map.of("enabled", true)))
                .thenReturn(Map.of("enabled", true));
        when(channelManager.start("personal-test", "qq"))
                .thenReturn(CompletableFuture.completedFuture(ChannelHealth.of(
                        "qq", "starting", true, true, true, "starting")));
        ChannelCompatController controller = new ChannelCompatController(
                configService, channelManager, accessControlStore, registry);

        controller.updateChannel("personal-test", "qq", Map.of("enabled", true), null).block();

        verify(channelManager).start("personal-test", "qq");
        verify(channelManager, never()).restart("personal-test", "qq");
    }
}
