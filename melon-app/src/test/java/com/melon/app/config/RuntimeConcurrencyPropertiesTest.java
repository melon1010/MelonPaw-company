package com.melon.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeConcurrencyPropertiesTest {

    @Test
    void bindsExternalConcurrencySettings() {
        var source = new MapConfigurationPropertySource(Map.of(
                "melon.runtime.channel.core-threads", "6",
                "melon.runtime.channel.max-threads", "12",
                "melon.runtime.channel.queue-capacity", "128",
                "melon.runtime.history-queue-capacity", "64",
                "melon.runtime.channel-pending-per-session", "8",
                "melon.runtime.config-watcher-enabled", "false"));

        RuntimeConcurrencyProperties properties = new Binder(source)
                .bind("melon.runtime", Bindable.of(RuntimeConcurrencyProperties.class))
                .orElseThrow(() -> new AssertionError("Runtime concurrency properties were not bound"));

        assertEquals(6, properties.getChannel().getCoreThreads());
        assertEquals(12, properties.getChannel().getMaxThreads());
        assertEquals(128, properties.getChannel().getQueueCapacity());
        assertEquals(64, properties.getHistoryQueueCapacity());
        assertEquals(8, properties.getChannelPendingPerSession());
        assertFalse(properties.isConfigWatcherEnabled());
    }
}
