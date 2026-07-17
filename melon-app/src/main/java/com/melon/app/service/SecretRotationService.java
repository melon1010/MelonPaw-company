package com.melon.app.service;

import com.melon.app.auth.EnterpriseAgentDefinitionService;
import com.melon.core.config.ConfigManager;
import com.melon.core.provider.ProviderManager;
import com.melon.core.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Rewrites every known secret-bearing configuration with the active master key. */
@Service
public class SecretRotationService {

    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final EnterpriseAgentDefinitionService definitions;

    public SecretRotationService(ConfigManager configManager, ProviderManager providerManager) {
        this(configManager, providerManager, null);
    }

    @Autowired
    public SecretRotationService(ConfigManager configManager, ProviderManager providerManager,
                                 EnterpriseAgentDefinitionService definitions) {
        this.configManager = configManager;
        this.providerManager = providerManager;
        this.definitions = definitions;
    }

    public synchronized Map<String, Object> reencryptAll() {
        configManager.save();
        Set<Path> files = new LinkedHashSet<>();
        Path home = configManager.resolveHomeDir();
        files.add(home.resolve("providers.json"));
        files.add(configManager.resolveStateDir().resolve("mcp-clients.json"));
        for (String agentId : configManager.getConfig().getAgents().keySet()) {
            files.add(configManager.resolveWorkspaceDir(agentId)
                    .resolve(".melon")
                    .resolve("mcp-clients.json"));
        }
        int rewritten = 1;
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            Map<String, Object> stored = JsonUtils.loadAsMap(file);
            Object runtime = configManager.unprotectSecrets(stored);
            JsonUtils.save(file, configManager.protectSecrets(runtime));
            rewritten++;
        }
        providerManager.refreshProviderConfig();
        int snapshots = definitions == null ? 0 : definitions.reencryptResourceSnapshots(configManager);
        return Map.of(
                "rotated", true,
                "files_rewritten", rewritten,
                "resource_snapshots_rewritten", snapshots,
                "previous_keys_required_until_restart", false);
    }
}
