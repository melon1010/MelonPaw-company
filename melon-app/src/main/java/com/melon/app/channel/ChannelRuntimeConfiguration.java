package com.melon.app.channel;

import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelQueueManager;
import com.melon.app.config.RuntimeExecutors;
import com.melon.app.config.RuntimeConcurrencyProperties;
import com.melon.core.config.ConfigManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChannelRuntimeConfiguration {

    @Bean
    public ChannelAdapterRegistry channelAdapterRegistry(RuntimeExecutors executors) {
        return new ChannelAdapterRegistry(
                executors.channel(), executors.channelScheduler(), executors.channelLongPoll());
    }

    @Bean
    public ChannelConfigService channelConfigService(ConfigManager configManager,
                                                     ChannelAdapterRegistry registry,
                                                     ChannelAccessControlStore accessControlStore) {
        return new ChannelConfigService(configManager, registry, accessControlStore);
    }

    @Bean
    public ChannelAccessControlStore channelAccessControlStore(ConfigManager configManager) {
        return new ChannelAccessControlStore(configManager);
    }

    @Bean(destroyMethod = "close")
    public ChannelQueueManager channelQueueManager(
            RuntimeExecutors executors, RuntimeConcurrencyProperties properties) {
        return new ChannelQueueManager(
                executors.channel(), properties.getChannelPendingPerSession());
    }

    @Bean
    public ChannelManager melonPawChannelManager(ChannelAdapterRegistry registry,
                                                 ChannelConfigService configService,
                                                 ChannelAccessControlStore accessControlStore,
                                                 ChannelQueueManager queueManager,
                                                 ChannelRuntimeProcessor processor) {
        return new ChannelManager(registry, configService, accessControlStore, queueManager, processor::process);
    }

}
