package com.melon.app.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class BuiltinSkillResourceTest {

    private static final Set<String> GENERAL_SKILLS = Set.of(
            "create_skill",
            "web_research",
            "document_writer",
            "workspace_organizer",
            "media_inspection");

    @Test
    void builtinSkillsArePackagedAndParseable() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:builtin-skills/*/SKILL.md");
        Set<String> names = new HashSet<>();

        for (Resource resource : resources) {
            String content;
            try (InputStream input = resource.getInputStream()) {
                content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            AgentSkill skill = SkillUtil.createFrom(content, null, "builtin");
            assertNotNull(skill, () -> "Failed to parse " + resource);
            assertNotNull(skill.getName(), () -> "Missing skill name in " + resource);
            assertFalse(skill.getName().isBlank(), () -> "Blank skill name in " + resource);
            assertNotNull(skill.getDescription(), () -> "Missing description in " + resource);
            assertFalse(skill.getDescription().isBlank(), () -> "Blank description in " + resource);
            names.add(skill.getName());
        }

        assertTrue(names.containsAll(GENERAL_SKILLS),
                () -> "Missing general builtin skills: " + difference(GENERAL_SKILLS, names));
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
