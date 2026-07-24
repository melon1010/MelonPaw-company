package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Persists user-approved tool-call grants inside one Agent workspace. */
@Component
public class ToolApprovalGrantService {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGrantService.class);
    private static final int MAX_GRANTS_PER_AGENT = 1000;
    private static final int MAX_LABEL_LENGTH = 240;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ConfigManager configManager;
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    public ToolApprovalGrantService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Map<String, Object> approvalFields(ToolUseBlock call,
                                               ToolRiskClassifier.Assessment assessment) {
        Descriptor descriptor = describe(call, assessment);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rememberable", descriptor.rememberable());
        if (!descriptor.rememberable()) return fields;

        fields.put("is_generalized", !descriptor.similarFingerprint().isBlank());
        fields.put("exact_target", descriptor.exactTarget());
        fields.put("exact_fingerprint", descriptor.exactFingerprint());
        if (!descriptor.similarFingerprint().isBlank()) {
            fields.put("similar_target", descriptor.similarTarget());
            fields.put("similar_fingerprint", descriptor.similarFingerprint());
        }
        return fields;
    }

    public boolean isAllowed(String agentId, String ownerUserId, ToolUseBlock call,
                             ToolRiskClassifier.Assessment assessment) {
        if (ownerUserId == null || ownerUserId.isBlank()) return false;
        Descriptor descriptor = describe(call, assessment);
        if (!descriptor.rememberable()) return false;

        synchronized (lock(agentId)) {
            for (Map<String, Object> grant : grants(agentId)) {
                if (!ownerUserId.trim().equals(text(grant.get("owner_user_id")))) continue;
                String fingerprint = text(grant.get("fingerprint"));
                String scope = text(grant.get("scope"));
                if ("exact".equals(scope) && fingerprint.equals(descriptor.exactFingerprint())) return true;
                if ("similar".equals(scope) && !descriptor.similarFingerprint().isBlank()
                        && fingerprint.equals(descriptor.similarFingerprint())) return true;
            }
        }
        return false;
    }

    /** Stores ALLOW decisions only. Denials, cancellations, and timeouts never call this method. */
    public void remember(String agentId, String ownerUserId, String requestedScope,
                         Map<String, Object> approval) {
        String requested = text(requestedScope).toLowerCase(Locale.ROOT);
        if (!"exact".equals(requested) && !"similar".equals(requested)) return;
        if (ownerUserId == null || ownerUserId.isBlank() || approval == null
                || !Boolean.TRUE.equals(approval.get("rememberable"))) {
            return;
        }
        String scope = requested;
        String fingerprint = text(approval.get(scope + "_fingerprint"));
        if (fingerprint.isBlank()) {
            scope = "exact";
            fingerprint = text(approval.get("exact_fingerprint"));
        }
        if (fingerprint.isBlank()) return;

        String normalizedAgentId = normalizeAgentId(agentId);
        String normalizedOwner = ownerUserId.trim();
        String normalizedScope = scope;
        String normalizedFingerprint = fingerprint;
        synchronized (lock(normalizedAgentId)) {
            List<Map<String, Object>> current = new ArrayList<>(grants(normalizedAgentId));
            boolean exists = current.stream().anyMatch(grant ->
                    normalizedOwner.equals(text(grant.get("owner_user_id")))
                            && normalizedScope.equals(text(grant.get("scope")))
                            && normalizedFingerprint.equals(text(grant.get("fingerprint"))));
            if (exists) return;

            Map<String, Object> grant = new LinkedHashMap<>();
            grant.put("id", UUID.randomUUID().toString());
            grant.put("owner_user_id", normalizedOwner);
            grant.put("tool_name", text(approval.get("actual_tool_name")));
            grant.put("risk_code", text(approval.get("risk_code")));
            grant.put("scope", normalizedScope);
            grant.put("fingerprint", normalizedFingerprint);
            grant.put("created_at", Instant.now().toString());
            current.add(grant);
            if (current.size() > MAX_GRANTS_PER_AGENT) {
                current = new ArrayList<>(current.subList(current.size() - MAX_GRANTS_PER_AGENT, current.size()));
            }
            JsonUtils.saveOrThrow(file(normalizedAgentId), Map.of("version", 1, "grants", current));
            log.info("Remembered tool approval grant: agent={}, user={}, tool={}, scope={}",
                    normalizedAgentId, normalizedOwner, grant.get("tool_name"), normalizedScope);
        }
    }

    private Descriptor describe(ToolUseBlock call, ToolRiskClassifier.Assessment assessment) {
        if (call == null || assessment == null || assessment.decision() != ToolRiskClassifier.Decision.ASK
                || "strict_policy".equals(assessment.code())) {
            return Descriptor.notRememberable();
        }
        String toolName = text(call.getName()).toLowerCase(Locale.ROOT);
        String riskCode = text(assessment.code()).toLowerCase(Locale.ROOT);
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();
        String exactMaterial = "v1\n" + toolName + "\n" + riskCode + "\n" + canonical(input);
        String exactFingerprint = sha256(exactMaterial);
        String exactTarget = exactTarget(toolName, input);
        String family = operationFamily(toolName, riskCode, input);
        String similarFingerprint = family.isBlank()
                ? ""
                : sha256("v1\n" + toolName + "\n" + riskCode + "\n" + family);
        String similarTarget = family.isBlank() ? "" : toolName + " · " + family;
        return new Descriptor(true, exactFingerprint, similarFingerprint,
                truncate(exactTarget), truncate(similarTarget));
    }

    private String operationFamily(String toolName, String riskCode, Map<String, Object> input) {
        if ("execute".equals(toolName) || "execute_shell_command".equals(toolName)) {
            return shellFamily(text(input.get("command")), riskCode);
        }
        if ("skill_manage".equals(toolName)) {
            String action = text(input.get("action")).toLowerCase(Locale.ROOT);
            return action.isBlank() ? "" : "skill:" + action;
        }
        if (toolName.startsWith("mcp_") || toolName.startsWith("mcp__")) {
            return "mcp-tool:" + toolName;
        }
        return "";
    }

    private String shellFamily(String command, String riskCode) {
        String value = WHITESPACE.matcher(command.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
        if (value.isBlank()) return "";
        if ("shell_file_write".equals(riskCode)) {
            String writer = firstMatch(value, "(?:^|[;&|]\\s*)(cp|install|mkdir|mv|tee|touch|truncate)\\b");
            if (!writer.isBlank()) return "workspace-write:" + writer;
            if (value.matches("(?s).*\\bsed\\b.*(?:\\s-i(?:\\s|$)|--in-place).*")) return "workspace-write:sed-in-place";
            if (value.matches("(?s).*(?:^|\\s)(?:>|>>|&>|&>>)(?:\\s|$).*")) return "workspace-write:redirection";
            return "";
        }
        if (!"destructive_shell".equals(riskCode)) return "";

        String family = firstMatch(value,
                "(?:^|[;&|]\\s*)(rm|rmdir|unlink|shred|trash|trash-put|del|erase|rd)\\b");
        if (!family.isBlank()) return "file-delete:" + family;
        if (value.matches("(?s).*(?:^|[;&|]\\s*)gio\\s+trash\\b.*")) return "file-delete:gio-trash";
        if (value.matches("(?s).*(?:^|[;&|]\\s*)(?:powershell|pwsh)\\b.*\\bremove-item\\b.*")) {
            return "file-delete:powershell-remove-item";
        }
        family = firstMatch(value, "(?:^|[;&|]\\s*)(chmod|chown)\\b");
        if (!family.isBlank()) return "permission-change:" + family;
        family = firstMatch(value, "(?:^|[;&|]\\s*)(kill|pkill|killall)\\b");
        if (!family.isBlank()) return "process-stop:" + family;
        family = firstMatch(value, "(?:^|[;&|]\\s*)(shutdown|reboot|halt|poweroff)\\b");
        if (!family.isBlank()) return "system-power:" + family;
        if (value.matches("(?s).*\\bgit\\s+reset\\s+--hard\\b.*")) return "git:reset-hard";
        if (value.matches("(?s).*\\bgit\\s+clean\\s+-[^\\s]*f.*")) return "git:clean-force";
        if (value.matches("(?s).*\\bgit\\s+push\\b.*")) return "git:push";
        if (value.matches("(?s).*\\b(?:drop|truncate)\\s+(?:table|database)\\b.*")) return "database:destructive-ddl";
        var packageMatcher = Pattern.compile(
                "\\b(npm|pnpm|yarn|pip|brew|apt|apt-get|dnf|yum)\\s+(install|add|remove|uninstall)\\b")
                .matcher(value);
        if (packageMatcher.find()) {
            return "package-change:" + packageMatcher.group(1) + ":" + packageMatcher.group(2);
        }
        if (value.matches("(?s).*\\b(?:npm|pnpm|yarn)\\s+publish\\b.*")) return "package:publish";
        family = firstMatch(value, "\\b(docker|podman)\\s+push\\b");
        if (!family.isBlank()) return "container-push:" + family;
        family = firstMatch(value, "\\bkubectl\\s+(apply|create|delete|patch|replace|scale)\\b");
        if (!family.isBlank()) return "kubernetes:" + family;
        family = firstMatch(value, "\\bterraform\\s+(apply|destroy|import)\\b");
        if (!family.isBlank()) return "terraform:" + family;
        family = firstMatch(value, "\\b(?:scp|sftp|ssh|rsync)\\b");
        if (!family.isBlank()) return "remote-access:" + family;
        if (value.matches("(?s).*\\bcurl\\b.*(?:-x (?:post|put|patch|delete)|--data|--upload-file).*")) return "http:write";
        return "";
    }

    private String exactTarget(String toolName, Map<String, Object> input) {
        if ("execute".equals(toolName) || "execute_shell_command".equals(toolName)) {
            return toolName + ": " + text(input.get("command"));
        }
        if ("write_file".equals(toolName) || "edit_file".equals(toolName)) {
            Object path = input.containsKey("path") ? input.get("path") : input.get("file_path");
            return toolName + ": " + text(path);
        }
        if (toolName.startsWith("mcp_") || toolName.startsWith("mcp__")) return toolName;
        return toolName + ": " + canonical(input);
    }

    private String canonical(Object value) {
        try {
            return JsonUtils.getMapper().writeValueAsString(sortRecursively(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to fingerprint tool approval", e);
        }
    }

    private Object sortRecursively(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> sorted.put(String.valueOf(entry.getKey()), sortRecursively(entry.getValue())));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sortRecursively).toList();
        return value;
    }

    private List<Map<String, Object>> grants(String agentId) {
        Object raw = JsonUtils.loadAsMap(file(agentId)).get("grants");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> grant = new LinkedHashMap<>();
            map.forEach((key, value) -> grant.put(String.valueOf(key), value));
            result.add(grant);
        }
        return result;
    }

    private Path file(String agentId) {
        return configManager.resolveWorkspaceDir(normalizeAgentId(agentId))
                .resolve(".melon").resolve("tool-approval-grants.json");
    }

    private Object lock(String agentId) {
        return fileLocks.computeIfAbsent(normalizeAgentId(agentId), ignored -> new Object());
    }

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }

    private String firstMatch(String value, String regex) {
        var matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(value);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_LABEL_LENGTH ? value : value.substring(0, MAX_LABEL_LENGTH - 3) + "...";
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private record Descriptor(boolean rememberable, String exactFingerprint, String similarFingerprint,
                              String exactTarget, String similarTarget) {
        static Descriptor notRememberable() {
            return new Descriptor(false, "", "", "", "");
        }
    }
}
