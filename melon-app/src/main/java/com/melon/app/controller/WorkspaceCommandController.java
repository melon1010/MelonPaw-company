package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

/** Server-authoritative catalogue for executable slash commands. */
@RestController
@RequestMapping("/api/workspace/commands")
public class WorkspaceCommandController {

    private final EnterpriseAgentAccessService agentAccessService;
    private final SkillService skillService;

    public WorkspaceCommandController(EnterpriseAgentAccessService agentAccessService,
                                      SkillService skillService) {
        this.agentAccessService = agentAccessService;
        this.skillService = skillService;
    }

    @GetMapping("/available")
    public ResponseEntity<?> available(
            @RequestHeader(value = "X-Agent-Id", required = false) String requestedAgentId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        String agentId = agentAccessService.resolve(principal, requestedAgentId);
        List<Map<String, Object>> commands = new ArrayList<>();
        commands.add(command("clear", "system", "/clear", "Clear the current conversation context"));
        commands.add(command("compact", "system", "/compact [instruction]", "Compact the current context now"));
        commands.add(command("skills", "system", "/skills", "List and manage available workspace skills"));
        commands.add(command("goal", "loop", "/goal <objective>", "Continue until the objective passes its completion rubric"));
        commands.add(command("mission", "loop", "/mission <task>", "Delegate work to isolated subagents and reconcile the result"));

        for (Map<String, Object> skill : skillService.listSkills(agentId)) {
            if (!Boolean.TRUE.equals(skill.get("enabled"))) continue;
            String name = stringValue(skill.get("name"), "").trim();
            if (name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>(command(
                    name, "skill", "/" + name + " <request>",
                    stringValue(skill.get("description"), name)));
            item.put("skill", true);
            commands.add(item);
        }
        return ResponseEntity.ok(Map.of("commands", commands));
    }

    private Map<String, Object> command(String name, String category, String syntax, String description) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("name", name);
        command.put("category", category);
        command.put("syntax", syntax);
        command.put("description", description);
        command.put("available", true);
        return command;
    }
}
