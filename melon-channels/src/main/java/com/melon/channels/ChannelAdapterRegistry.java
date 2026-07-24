package com.melon.channels;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class ChannelAdapterRegistry implements AutoCloseable {

    private final Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();
    private final ChannelExecutionContext executionContext;

    public ChannelAdapterRegistry() {
        this(ChannelExecutionContext.standalone());
    }

    public ChannelAdapterRegistry(
            ExecutorService worker, ScheduledExecutorService scheduler) {
        this(worker, scheduler, worker);
    }

    public ChannelAdapterRegistry(
            ExecutorService worker, ScheduledExecutorService scheduler,
            ExecutorService longPoll) {
        this(ChannelExecutionContext.managed(worker, scheduler, longPoll));
    }

    private ChannelAdapterRegistry(ChannelExecutionContext executionContext) {
        this.executionContext = executionContext;
        register(new BasicChannelAdapter("console", true, false, true, false));
        register(new HttpChannelAdapter("dingtalk", true, true, false, executionContext.worker()));
        register(new HttpChannelAdapter("feishu", true, true, false, executionContext.worker()));
        register(new QQChannelAdapter(executionContext.worker(), executionContext.scheduler()));
        register(new WeChatChannelAdapter(
                executionContext.worker(), executionContext.scheduler(), executionContext.longPoll()));
        register(new WeComChannelAdapter(executionContext.worker(), executionContext.scheduler()));
    }

    public void register(ChannelAdapter adapter) {
        if (adapter == null || !ChannelTypes.supported(adapter.type())) {
            throw new IllegalArgumentException("Unsupported channel in domestic deployment");
        }
        adapters.put(adapter.type().trim().toLowerCase(), adapter);
    }

    public ChannelAdapter get(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase();
        if (!ChannelTypes.supported(normalized)) {
            throw new IllegalArgumentException("Unsupported channel in domestic deployment: " + type);
        }
        ChannelAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new IllegalStateException("Domestic channel adapter is not registered: " + normalized);
        }
        return adapter;
    }

    public Map<String, ChannelAdapter> all() {
        return new LinkedHashMap<>(adapters);
    }

    @Override
    public void close() {
        adapters.values().forEach(ChannelAdapter::close);
        executionContext.close();
    }
}
