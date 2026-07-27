package com.melon.app.runner;

import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionLogReaderSkillPrivacyTest {

    @TempDir
    Path home;

    @Test
    void restoresSlashCommandAndHidesInternalSkillLoaderFromExistingLog() throws Exception {
        ConfigManager configManager = configManager();
        ChatManager chatManager = new ChatManager(configManager);
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);
        Path sessionDir = home.resolve("workspaces/default/agents/worker/sessions");
        Files.createDirectories(sessionDir);

        String prompt = ExplicitSkillInvocation.modelPrompt(
                "public_api", "SECRET INTERNAL SKILL CONTENT", "这个有什么作用？");
        List<Map<String, Object>> lines = List.of(
                logMessage("u1", "USER", prompt, null),
                logMessage("a1", "ASSISTANT",
                        "[tool_call: load_skill_through_path({\"skillId\":\"public_api\",\"path\":\"SKILL.md\"})]", "call-1"),
                logMessage("t1", "TOOL",
                        "[tool_result: load_skill_through_path] SECRET INTERNAL SKILL CONTENT", "call-1"),
                logMessage("a2", "ASSISTANT", "这是公开 API 查找技能。", null));
        Files.writeString(sessionDir.resolve("skill-session.log.jsonl"),
                String.join("\n", lines.stream().map(JsonUtils::toJson).toList()));

        List<Map<String, Object>> messages = reader.readFrontendMessages("default", "skill-session");

        assertEquals(2, messages.size());
        assertEquals("/public_api 这个有什么作用？", firstText(messages.get(0)));
        assertEquals("这是公开 API 查找技能。", firstText(messages.get(1)));
        assertFalse(messages.toString().contains("SECRET INTERNAL SKILL CONTENT"));
        assertFalse(messages.toString().contains("load_skill_through_path"));
    }

    @Test
    void keepsVisibleUserBeforeAssistantWhenStateContainsInternalSkillPrompt() throws Exception {
        ConfigManager configManager = configManager();
        String prompt = ExplicitSkillInvocation.modelPrompt(
                "public_api", "SECRET INTERNAL SKILL CONTENT", "这个有什么作用？");
        Path stateDir = home.resolve("state/__anon__/ordered-session");
        Files.createDirectories(stateDir);
        JsonUtils.save(stateDir.resolve("agent_state.json"), Map.of(
                "session_id", "ordered-session",
                "context", List.of(
                        message("internal-user", "USER", "__internal__", prompt),
                        Map.of(
                                "id", "assistant",
                                "role", "ASSISTANT",
                                "name", "default",
                                "content", List.of(
                                        Map.of("type", "tool_use", "id", "call-1",
                                                "name", "load_skill_through_path", "input", Map.of()),
                                        Map.of("type", "text", "text", "这是公开 API 查找技能。")),
                                "metadata", Map.of()),
                        Map.of(
                                "id", "tool-result",
                                "role", "TOOL",
                                "content", List.of(Map.of(
                                        "type", "tool_result", "id", "call-1",
                                        "name", "load_skill_through_path",
                                        "output", List.of(Map.of(
                                                "type", "text",
                                                "text", "SECRET INTERNAL SKILL CONTENT")))),
                                "metadata", Map.of()))));

        ChatManager chatManager = new ChatManager(configManager);
        chatManager.saveSessionShadowFromStateStore(
                "default", "console", "default", "ordered-session", List.of(
                        message("visible-user", "USER", "user", "/public_api 这个有什么作用？"),
                        message("visible-assistant", "ASSISTANT", "default", "这是公开 API 查找技能。")));
        Map<String, Object> shadow = chatManager.loadSessionShadow(
                "default", "console", "default", "ordered-session");
        Map<?, ?> agent = (Map<?, ?>) shadow.get("agent");
        Map<?, ?> state = (Map<?, ?>) agent.get("state");
        List<?> shadowContext = (List<?>) state.get("context");
        assertEquals(2, shadowContext.size());
        assertEquals("user", String.valueOf(((Map<?, ?>) shadowContext.get(0)).get("role")));

        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);

        List<Map<String, Object>> messages = reader.readFrontendMessages(
                "default", "default", "console", "ordered-session");

        assertEquals("user", String.valueOf(messages.get(0).get("role")).toLowerCase());
        assertEquals("/public_api 这个有什么作用？", firstText(messages.get(0)));
        assertEquals(2, messages.size());
        assertFalse(messages.toString().contains("SECRET INTERNAL SKILL CONTENT"));
    }

    @Test
    void restoresCommandWithoutSyntheticDefaultRequest() {
        String prompt = ExplicitSkillInvocation.modelPrompt("news", "instructions", "");

        assertEquals("/news", ExplicitSkillInvocation.userVisibleText(prompt));
        assertTrue(ExplicitSkillInvocation.isModelPrompt(prompt));
    }

    @Test
    void hidesSystemReminderWithoutOpeningANewVisibleTurnAndDeduplicatesAnswer() throws Exception {
        ConfigManager configManager = configManager();
        Path stateDir = home.resolve("state/__anon__/reminder-session");
        Files.createDirectories(stateDir);
        JsonUtils.save(stateDir.resolve("agent_state.json"), Map.of(
                "session_id", "reminder-session",
                "context", List.of(
                        message("u1", "USER", "user", "给我最终结论"),
                        message("r1", "USER", "system",
                                "<system-reminder>researcher task completed</system-reminder>"),
                        message("a1", "ASSISTANT", "default", "这是最终结论。"),
                        message("a2", "ASSISTANT", "default", "这是最终结论。"))));

        ChatManager chatManager = new ChatManager(configManager);
        chatManager.saveSessionShadowFromStateStore(
                "default", "console", "default", "reminder-session");
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);

        List<Map<String, Object>> messages = reader.readFrontendMessages(
                "default", "default", "console", "reminder-session");

        assertEquals(2, messages.size());
        assertEquals("给我最终结论", firstText(messages.get(0)));
        assertEquals("这是最终结论。", firstText(messages.get(1)));
        assertFalse(messages.toString().contains("system-reminder"));
    }

    @Test
    void keepsCompleteShadowWhenCompactionLogIsUnavailable() throws Exception {
        ConfigManager configManager = configManager();
        ChatManager chatManager = new ChatManager(configManager);
        chatManager.saveSessionShadow("default", "console", "default", "compact-fallback", Map.of(
                "context", List.of(
                        message("u1", "USER", "user", "first"),
                        message("a1", "ASSISTANT", "default", "first answer"),
                        message("u2", "USER", "user", "second"),
                        message("a2", "ASSISTANT", "default", "second answer"))));
        Path stateDir = home.resolve("state/__anon__/compact-fallback");
        Files.createDirectories(stateDir);
        JsonUtils.save(stateDir.resolve("agent_state.json"), Map.of(
                "session_id", "compact-fallback",
                "context", List.of(
                        message("summary", "USER", "__compaction_summary__",
                                "You are in the middle of a conversation that has been summarized."),
                        message("u2", "USER", "user", "second"),
                        message("a2", "ASSISTANT", "default", "second answer"))));

        chatManager.saveSessionShadowFromStateStore(
                "default", "console", "default", "compact-fallback");
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);
        List<Map<String, Object>> messages = reader.readFrontendMessages(
                "default", "default", "console", "compact-fallback");

        assertEquals(4, messages.size());
        assertEquals("first", firstText(messages.get(0)));
        assertEquals("second answer", firstText(messages.get(3)));
    }

    @Test
    void preservesExplicitTerminalAnswerWhenLaterProgressMessagesExist() {
        ConfigManager configManager = configManager();
        ChatManager chatManager = new ChatManager(configManager);
        chatManager.saveSessionShadow("default", "console", "default", "phase-session", Map.of(
                "context", List.of(
                        message("u1", "USER", "user", "执行任务"),
                        messageWithPhase("p1", "正在查询。", "runtime-turn", "progress", false),
                        messageWithPhase("a1", "这是最终结果。", "runtime-turn", "final", true),
                        messageWithPhase("p2", "正在整理后台统计。", "runtime-turn", "progress", false))));
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);

        List<Map<String, Object>> messages = reader.readFrontendMessages(
                "default", "default", "console", "phase-session");

        assertEquals(List.of("input", "progress", "final", "progress"), messages.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item.get("metadata")).get("phase")))
                .toList());
        assertEquals("runtime-turn", ((Map<?, ?>) messages.get(2).get("metadata")).get("turn_id"));
        assertEquals(true, ((Map<?, ?>) messages.get(2).get("metadata")).get("terminal"));
        assertEquals(false, ((Map<?, ?>) messages.get(3).get("metadata")).get("terminal"));
    }

    @Test
    void doesNotPromoteProtocolProgressWhileTurnIsStillRunning() {
        ConfigManager configManager = configManager();
        ChatManager chatManager = new ChatManager(configManager);
        chatManager.saveSessionShadow("default", "console", "default", "running-phase-session", Map.of(
                "context", List.of(
                        message("u1", "USER", "user", "执行任务"),
                        messageWithPhase("p1", "仍在执行。", "runtime-turn", "progress", false))));
        AgentSessionLogReader reader = new AgentSessionLogReader(configManager, chatManager);

        List<Map<String, Object>> messages = reader.readFrontendMessages(
                "default", "default", "console", "running-phase-session");

        Map<?, ?> metadata = (Map<?, ?>) messages.get(1).get("metadata");
        assertEquals("progress", metadata.get("phase"));
        assertEquals(false, metadata.get("terminal"));
    }

    private ConfigManager configManager() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(home.toString());
        return configManager;
    }

    private Map<String, Object> logMessage(String id, String role, String content, String toolCallId) {
        java.util.LinkedHashMap<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("type", "message");
        message.put("id", id);
        message.put("timestamp", 1.0);
        message.put("role", role);
        message.put("content", content);
        if (toolCallId != null) message.put("toolCallId", toolCallId);
        return message;
    }

    private Map<String, Object> message(String id, String role, String name, String text) {
        return Map.of(
                "id", id,
                "role", role,
                "name", name,
                "content", List.of(Map.of("type", "text", "text", text)),
                "metadata", Map.of());
    }

    private Map<String, Object> messageWithPhase(
            String id, String text, String turnId, String phase, boolean terminal) {
        return Map.of(
                "id", id,
                "role", "ASSISTANT",
                "name", "default",
                "content", List.of(Map.of("type", "text", "text", text)),
                "metadata", Map.of("turn_id", turnId, "phase", phase, "terminal", terminal));
    }

    private String firstText(Map<String, Object> message) {
        List<?> content = (List<?>) message.get("content");
        return String.valueOf(((Map<?, ?>) content.get(0)).get("text"));
    }
}
