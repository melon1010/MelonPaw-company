package com.melon.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bounded concurrency settings for blocking runtime infrastructure. */
@ConfigurationProperties(prefix = "melon.runtime")
public class RuntimeConcurrencyProperties {

    private final Pool channel = new Pool(4, 16, 256);
    private final Pool skillInstall = new Pool(2, 2, 20);
    private int historyQueueCapacity = 512;
    private int mcpLifecycleThreads = 4;
    private int channelSchedulerThreads = 4;
    private int channelLongPollThreads = 32;
    private int channelPendingPerSession = 32;
    private int cronThreads = 4;
    private boolean configWatcherEnabled = true;
    private Duration shutdownTimeout = Duration.ofSeconds(5);

    public Pool getChannel() {
        return channel;
    }

    public Pool getSkillInstall() {
        return skillInstall;
    }

    public int getHistoryQueueCapacity() {
        return historyQueueCapacity;
    }

    public void setHistoryQueueCapacity(int historyQueueCapacity) {
        this.historyQueueCapacity = positive(historyQueueCapacity, "historyQueueCapacity");
    }

    public int getMcpLifecycleThreads() {
        return mcpLifecycleThreads;
    }

    public int getChannelSchedulerThreads() {
        return channelSchedulerThreads;
    }

    public void setChannelSchedulerThreads(int channelSchedulerThreads) {
        this.channelSchedulerThreads = positive(channelSchedulerThreads, "channelSchedulerThreads");
    }

    public int getChannelLongPollThreads() {
        return channelLongPollThreads;
    }

    public void setChannelLongPollThreads(int channelLongPollThreads) {
        this.channelLongPollThreads = positive(channelLongPollThreads, "channelLongPollThreads");
    }

    public int getChannelPendingPerSession() {
        return channelPendingPerSession;
    }

    public void setChannelPendingPerSession(int channelPendingPerSession) {
        this.channelPendingPerSession = positive(channelPendingPerSession, "channelPendingPerSession");
    }

    public void setMcpLifecycleThreads(int mcpLifecycleThreads) {
        this.mcpLifecycleThreads = positive(mcpLifecycleThreads, "mcpLifecycleThreads");
    }

    public int getCronThreads() {
        return cronThreads;
    }

    public void setCronThreads(int cronThreads) {
        this.cronThreads = positive(cronThreads, "cronThreads");
    }

    public boolean isConfigWatcherEnabled() {
        return configWatcherEnabled;
    }

    public void setConfigWatcherEnabled(boolean configWatcherEnabled) {
        this.configWatcherEnabled = configWatcherEnabled;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        this.shutdownTimeout = shutdownTimeout;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public static final class Pool {
        private int coreThreads;
        private int maxThreads;
        private int queueCapacity;

        private Pool(int coreThreads, int maxThreads, int queueCapacity) {
            this.coreThreads = coreThreads;
            this.maxThreads = maxThreads;
            this.queueCapacity = queueCapacity;
        }

        public int getCoreThreads() {
            return coreThreads;
        }

        public void setCoreThreads(int coreThreads) {
            this.coreThreads = positive(coreThreads, "coreThreads");
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = positive(maxThreads, "maxThreads");
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
            this.queueCapacity = queueCapacity;
        }

        void validate(String name) {
            if (coreThreads > maxThreads) {
                throw new IllegalArgumentException(
                        name + ".coreThreads must not exceed " + name + ".maxThreads");
            }
        }
    }
}
