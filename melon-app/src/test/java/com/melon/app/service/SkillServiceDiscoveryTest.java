package com.melon.app.service;

import com.melon.core.agent.WorkspaceManager;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillServiceDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyPromotedSkillsWithSkillDocuments() throws Exception {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        Path skills = tempDir.resolve("workspaces/default/skills");
        Files.createDirectories(skills.resolve("_drafts/proposal"));
        Files.writeString(skills.resolve("_drafts/proposal/SKILL.md"), "# draft");
        Files.createDirectories(skills.resolve("approved"));
        Files.writeString(skills.resolve("approved/SKILL.md"), "---\ndescription: ready\n---\n# approved\n");
        Files.createDirectories(skills.resolve("empty"));

        SkillService service = new SkillService(configManager, new WorkspaceManager(),
                new SecuritySettingsService(configManager));
        List<Map<String, Object>> listed = service.listSkills("default");

        assertEquals(List.of("approved"), listed.stream().map(item -> item.get("name")).toList());
    }
}
