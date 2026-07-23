package com.melon.app.config;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.NamedThreadFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/** Watches the one process-level config file with a single OS watch registration. */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);
    private static final long DEBOUNCE_MS = 1000;

    private final ConfigManager configManager;
    private final boolean enabled;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;
    private volatile long lastReloadTime;
    private volatile Path watchedDirectory;

    public ConfigWatcher(ConfigManager configManager, boolean enabled) {
        this.configManager = configManager;
        this.enabled = enabled;
    }

    @Autowired
    public ConfigWatcher(ConfigManager configManager, RuntimeConcurrencyProperties properties) {
        this(configManager, properties.isConfigWatcherEnabled());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || running) {
            if (!enabled) log.info("Config watcher is disabled");
            return;
        }
        Path configPath = configManager.getConfigPath().toAbsolutePath().normalize();
        Path configDir = configPath.getParent();
        if (configDir == null) {
            log.error("Cannot watch config path without a parent: {}", configPath);
            return;
        }

        try {
            Files.createDirectories(configDir);
            WatchService service = FileSystems.getDefault().newWatchService();
            configDir.register(service,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            watchService = service;
            watchedDirectory = configDir;
            running = true;
            watchThread = new NamedThreadFactory("melon-config-watcher")
                    .newThread(() -> watchLoop(configPath.getFileName().toString()));
            watchThread.start();
            log.info("Config watcher started: directory={}, file={}, registrations=1",
                    configDir, configPath.getFileName());
        } catch (IOException e) {
            closeWatchService();
            log.error("Failed to start config watcher: directory={}", configDir, e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        closeWatchService();
        Thread thread = watchThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        watchThread = null;
        watchedDirectory = null;
        log.info("Config watcher stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int registrationCount() {
        return running ? 1 : 0;
    }

    public Path watchedDirectory() {
        return watchedDirectory;
    }

    private void watchLoop(String configFileName) {
        while (running) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path changed
                            && configFileName.equals(changed.getFileName().toString())) {
                        handleConfigChange();
                    }
                }
                if (!key.reset()) {
                    log.warn("Config watch key is no longer valid; watcher stopped");
                    running = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                if (running) log.error("Config watch service failed", e);
                break;
            }
        }
        running = false;
    }

    private void handleConfigChange() {
        long now = System.currentTimeMillis();
        if (now - lastReloadTime < DEBOUNCE_MS) return;
        lastReloadTime = now;
        try {
            if (configManager.reload()) log.info("Config reloaded successfully");
            else log.warn("Config reload skipped; using the last known-good configuration");
        } catch (Exception e) {
            log.error("Failed to reload config", e);
        }
    }

    private void closeWatchService() {
        WatchService service = watchService;
        watchService = null;
        if (service == null) return;
        try {
            service.close();
        } catch (IOException e) {
            log.warn("Failed to close config watch service", e);
        }
    }

    public void triggerReload() {
        handleConfigChange();
    }
}
