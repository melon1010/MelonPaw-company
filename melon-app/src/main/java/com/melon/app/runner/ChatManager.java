package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.melon.app.auth.AgentSharingPolicy;
import com.melon.app.service.HistoryStore;
import com.melon.core.agent.AgentScopedStateStore;
import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.core.util.JsonUtils;
import com.melon.core.util.SafePathUtil;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Workspace-scoped chat registry. Matches Python melonPaw's workspace/chats.json.
 */
@Component
public class ChatManager {

    private static final Logger log = LoggerFactory.getLogger(ChatManager.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int DEFAULT_TOOL_OUTPUT_TOKEN_CAP = 3000;
    private final ConfigManager configManager;
    private final HistoryStore historyStore;
    private final AgentStateLookup stateLookup;
    private final AgentStateStore stateStore;
    private final Predicate<String> sharedAgent;
    private final Map<String, String> activeTurns = new LinkedHashMap<>();

    public ChatManager(ConfigManager configManager) {
        this(configManager, null, new JsonFileAgentStateStore(configManager.resolveStateDir()), ignored -> false);
    }

    public ChatManager(ConfigManager configManager, HistoryStore historyStore) {
        this(configManager, historyStore, new JsonFileAgentStateStore(configManager.resolveStateDir()), ignored -> false);
    }

    @Autowired
    public ChatManager(ConfigManager configManager, HistoryStore historyStore,
                       AgentStateStore stateStore, AgentSharingPolicy sharingPolicy) {
        this(configManager, historyStore, stateStore, sharingPolicy::sharesAcrossUsers);
    }

    ChatManager(ConfigManager configManager, HistoryStore historyStore,
                AgentStateStore stateStore, Predicate<String> sharedAgent) {
        this.configManager = configManager;
        this.historyStore = historyStore;
        this.stateStore = stateStore;
        this.stateLookup = new AgentStateLookup(stateStore);
        this.sharedAgent = sharedAgent != null ? sharedAgent : ignored -> false;
    }

    public synchronized List<ChatSpec> list(String agentId, String userId, String channel) {
        List<ChatSpec> chats = load(agentId);
        Map<String, ChatSpec> unique = new LinkedHashMap<>();
        boolean shared = isSharedAgent(agentId);
        for (ChatSpec chat : chats) {
            if ((userId != null && !userId.isBlank() && !shared && !userId.equals(chat.getUserId()))
                    || (channel != null && !channel.isBlank() && !channel.equals(chat.getChannel()))) {
                continue;
            }
            String key = (shared ? "__shared__" : valueOrDefault(chat.getUserId(), "default")) + "\u0000"
                    + valueOrDefault(chat.getChannel(), "console") + "\u0000"
                    + valueOrDefault(chat.getSessionId(), chat.getId());
            ChatSpec existing = unique.get(key);
            if (existing == null || compareUpdatedAt(chat, existing) > 0) unique.put(key, chat);
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(ChatSpec::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private int compareUpdatedAt(ChatSpec left, ChatSpec right) {
        String a = valueOrDefault(left.getUpdatedAt(), valueOrDefault(left.getCreatedAt(), ""));
        String b = valueOrDefault(right.getUpdatedAt(), valueOrDefault(right.getCreatedAt(), ""));
        return a.compareTo(b);
    }

    public synchronized ChatSpec create(String agentId, String name, String userId, String channel, String sessionId) {
        ChatSpec spec = new ChatSpec(UUID.randomUUID().toString(), normalizeAgentId(agentId), nameOrDefault(name));
        spec.setSessionId(sessionId != null && !sessionId.isBlank() ? sessionId : newSessionId());
        spec.setUserId(valueOrDefault(userId, "default"));
        spec.setChannel(valueOrDefault(channel, "console"));
        spec.setStatus("idle");
        upsert(agentId, spec);
        log.info("Chat created: id={}, agent={}, session={}", spec.getId(), agentId, spec.getSessionId());
        return spec;
    }

    public synchronized ChatSpec get(String agentId, String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        return load(agentId).stream()
                .filter(chat -> chatId.equals(chat.getId()))
                .findFirst()
                .orElse(null);
    }

    public synchronized ChatSpec update(String agentId, ChatSpec spec) {
        if (spec == null || spec.getId() == null || spec.getId().isBlank()) {
            throw new IllegalArgumentException("ChatSpec or id is null");
        }
        spec.setUpdatedAt(Instant.now().toString());
        upsert(agentId, spec);
        return spec;
    }

    public synchronized boolean delete(String agentId, String chatId) {
        if (chatId == null || chatId.isBlank()) return false;
        List<ChatSpec> chats = new ArrayList<>(load(agentId));
        ChatSpec deleted = chats.stream()
                .filter(chat -> chatId.equals(chat.getId()))
                .findFirst()
                .orElse(null);
        if (deleted == null) return false;
        if (activeTurns.containsKey(turnLeaseKey(agentId, chatId))) return false;
        for (String stateUserId : stateUsers(
                agentId, deleted.getChannel(), deleted.getSessionId(), deleted.getUserId())) {
            stateStore.delete(AgentScopedStateStore.scopedUserId(agentId, stateUserId),
                    deleted.getSessionId());
        }
        chats.remove(deleted);
        save(agentId, chats);
        clearSession(agentId, deleted.getChannel(), deleted.getUserId(), deleted.getSessionId());
        return true;
    }

    public synchronized ChatSpec getOrCreateForSession(
            String agentId, String sessionId, String userId, String channel, String name) {
        String sid = valueOrDefault(sessionId, newSessionId());
        String uid = valueOrDefault(userId, "default");
        String ch = valueOrDefault(channel, "console");
        boolean shared = isSharedAgent(agentId);
        ChatSpec existing = load(agentId).stream()
                .filter(chat -> sid.equals(chat.getSessionId())
                        && (shared || uid.equals(chat.getUserId()))
                        && ch.equals(chat.getChannel()))
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;
        return create(agentId, name, uid, ch, sid);
    }

    /**
     * Atomically opens one conversation turn and hands completed conversation context to a new
     * project member without carrying over another member's approvals or tool runtime state.
     */
    public synchronized ChatSpec beginTurn(
            String agentId, String sessionId, String userId, String channel, String name) {
        ChatSpec chat = getOrCreateForSession(agentId, sessionId, userId, channel, name);
        return beginTurn(agentId, chat, userId);
    }

    /** Opens a turn for an already persisted chat without relying on a possibly duplicated session id. */
    public synchronized ChatSpec beginExistingTurn(String agentId, String chatId, String userId) {
        ChatSpec chat = get(agentId, chatId);
        if (chat == null) throw new IllegalStateException("Conversation no longer exists");
        return beginTurn(agentId, chat, userId);
    }

    private ChatSpec beginTurn(String agentId, ChatSpec chat, String userId) {
        String leaseKey = turnLeaseKey(agentId, chat.getId());
        if (activeTurns.containsKey(leaseKey)) {
            throw new IllegalStateException("This conversation already has a running turn");
        }
        if ("running".equalsIgnoreCase(chat.getStatus())) {
            log.warn("Recovering stale running conversation: agent={}, chat={}, session={}",
                    agentId, chat.getId(), chat.getSessionId());
        }
        String turnId = UUID.randomUUID().toString();
        activeTurns.put(leaseKey, turnId);
        try {
            if (isSharedAgent(agentId)) {
                handOffConversationState(agentId, chat, userId);
                addStateUser(chat, userId);
            }
            chat.getMeta().put("active_user_id", valueOrDefault(userId, "default"));
            chat.getMeta().put("active_turn_id", turnId);
            chat.getMeta().put("active_turn_started_at", Instant.now().toString());
            chat.setStatus("running");
            return update(agentId, chat);
        } catch (RuntimeException e) {
            activeTurns.remove(leaseKey, turnId);
            throw e;
        }
    }

    /**
     * Finishes only the matching execution lease. A late completion callback from a cancelled
     * turn must never mark a newer turn idle or replace its active actor.
     */
    public synchronized void endTurn(String agentId, String chatId, String userId, String turnId) {
        ChatSpec chat = get(agentId, chatId);
        if (chat == null) return;
        String leaseKey = turnLeaseKey(agentId, chatId);
        String activeTurnId = stringValue(activeTurns.get(leaseKey));
        String persistedTurnId = stringValue(chat.getMeta().get("active_turn_id"));
        if (turnId != null && !turnId.isBlank()
                && ((!activeTurnId.isBlank() && !turnId.equals(activeTurnId))
                    || (!persistedTurnId.isBlank() && !turnId.equals(persistedTurnId)))) {
            log.debug("Ignoring stale conversation completion: agent={}, chat={}, turn={}",
                    agentId, chatId, turnId);
            return;
        }
        String actor = valueOrDefault(userId, "default");
        String activeUserId = stringValue(chat.getMeta().get("active_user_id"));
        if (!activeUserId.isBlank() && !activeUserId.equals(actor)) {
            log.debug("Ignoring conversation completion from non-active user: agent={}, chat={}, user={}",
                    agentId, chatId, actor);
            return;
        }
        if (isSharedAgent(agentId)
                && stateLookup.find(agentId, actor, chat.getSessionId()).isPresent()) {
            chat.getMeta().put("state_user_id", actor);
            addStateUser(chat, actor);
        }
        if (turnId == null || turnId.isBlank()) activeTurns.remove(leaseKey);
        else activeTurns.remove(leaseKey, turnId);
        chat.getMeta().remove("active_user_id");
        chat.getMeta().remove("active_turn_id");
        chat.getMeta().remove("active_turn_started_at");
        chat.setStatus("idle");
        update(agentId, chat);
    }

    public synchronized String activeUserId(String agentId, String chatId, String fallback) {
        ChatSpec chat = get(agentId, chatId);
        return chat == null ? valueOrDefault(fallback, "default")
                : stringValue(chat.getMeta().get("active_user_id"),
                        valueOrDefault(fallback, chat.getUserId()));
    }

    public synchronized String activeTurnId(String agentId, String chatId) {
        ChatSpec chat = get(agentId, chatId);
        return chat == null ? "" : stringValue(chat.getMeta().get("active_turn_id"));
    }

    public boolean isSharedAgent(String agentId) {
        return sharedAgent.test(normalizeAgentId(agentId));
    }

    public String stateUserId(String agentId, String channel, String sessionId, String fallback) {
        if (!isSharedAgent(agentId)) return valueOrDefault(fallback, "default");
        ChatSpec chat = findBySession(agentId, channel, sessionId);
        if (chat == null) return valueOrDefault(fallback, "default");
        return stringValue(chat.getMeta().get("state_user_id"), chat.getUserId());
    }

    public List<String> stateUsers(String agentId, String channel, String sessionId, String fallback) {
        if (!isSharedAgent(agentId)) return List.of(valueOrDefault(fallback, "default"));
        ChatSpec chat = findBySession(agentId, channel, sessionId);
        if (chat == null) return List.of(valueOrDefault(fallback, "default"));
        LinkedHashSet<String> users = new LinkedHashSet<>();
        users.add(valueOrDefault(chat.getUserId(), "default"));
        Object raw = chat.getMeta().get("state_user_ids");
        if (raw instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(value -> !value.isBlank()).forEach(users::add);
        }
        return List.copyOf(users);
    }

    public String storageUserId(String agentId, String channel, String sessionId, String fallback) {
        if (!isSharedAgent(agentId)) return valueOrDefault(fallback, "default");
        ChatSpec chat = findBySession(agentId, channel, sessionId);
        return chat != null ? valueOrDefault(chat.getUserId(), "default")
                : valueOrDefault(fallback, "default");
    }

    public synchronized void resetSharedStateMetadata(
            String agentId, String channel, String sessionId) {
        ChatSpec chat = findBySession(agentId, channel, sessionId);
        if (chat == null || !isSharedAgent(agentId)) return;
        chat.getMeta().remove("state_user_id");
        chat.getMeta().remove("state_user_ids");
        update(agentId, chat);
    }

    public Path sessionFile(String agentId, String channel, String userId, String sessionId) {
        Path sessions = configManager.resolveWorkspaceDir(normalizeAgentId(agentId))
                .resolve("sessions")
                .resolve(valueOrDefault(channel, "console"));
        String fileName = sanitizeFileName(valueOrDefault(userId, "default") + "_" + valueOrDefault(sessionId, "default")) + ".json";
        return SafePathUtil.resolveSafe(sessions, fileName);
    }

    public void saveSessionShadow(String agentId, String channel, String userId, String sessionId, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return;
        String storageUserId = storageUserId(agentId, channel, sessionId, userId);
        Map<String, Object> normalizedState = new LinkedHashMap<>(state);
        normalizeState(agentId, normalizedState);
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("state", normalizedState);
        if (historyStore != null) {
            historyStore.appendSessionAsync(agentId, sessionId, normalizedState);
        }
        agent.put("scroll", stateScrollIndex(agentId, sessionId, normalizedState));
        JsonUtils.save(sessionFile(agentId, channel, storageUserId, sessionId), Map.of("agent", agent));
    }

    public void saveSessionShadowFromStateStore(String agentId, String channel, String userId, String sessionId) {
        saveSessionShadowFromStateStore(agentId, channel, userId, sessionId, List.of());
    }

    public void saveSessionShadowFromStateStore(String agentId, String channel, String userId, String sessionId,
                                                List<Map<String, Object>> prependContext) {
        saveSessionShadowFromStateStore(agentId, channel, userId, sessionId, prependContext, null);
    }

    public void saveSessionShadowFromStateStore(String agentId, String channel, String userId, String sessionId,
                                                List<Map<String, Object>> prependContext,
                                                Map<String, Object> turnUsage) {
        Map<String, Object> state = stateLookup.findAsMap(agentId, userId, sessionId);
        if (state == null || contextSize(state) == 0) {
            Map<String, Object> previous = previousShadowState(agentId, channel, userId, sessionId);
            state = previous.isEmpty() ? new LinkedHashMap<>() : previous;
        }
        mergeFrontendContext(state, prependContext);
        injectTurnUsage(state, turnUsage);
        state = repairCompactedStateFromLog(agentId, sessionId, state, prependContext, turnUsage);
        if (hasCompactionSummary(state)) {
            state = repairCompactedStateFromShadow(
                    state, previousShadowState(agentId, channel, userId, sessionId));
        }
        if (!state.isEmpty()) {
            saveSessionShadow(agentId, channel, userId, sessionId, state);
        }
    }

    public Map<String, Object> loadSessionShadow(String agentId, String channel, String userId, String sessionId) {
        String storageUserId = storageUserId(agentId, channel, sessionId, userId);
        Path file = sessionFile(agentId, channel, storageUserId, sessionId);
        if (!Files.isRegularFile(file)) return Map.of();
        Map<String, Object> raw = JsonUtils.load(file, MAP_TYPE);
        return raw != null ? raw : Map.of();
    }

    /** Removes the persisted frontend shadow and scroll history for one conversation session. */
    public boolean clearSession(String agentId, String channel, String userId, String sessionId) {
        boolean deleted = false;
        try {
            String storageUserId = storageUserId(agentId, channel, sessionId, userId);
            deleted = Files.deleteIfExists(sessionFile(agentId, channel, storageUserId, sessionId));
        } catch (IOException e) {
            log.warn("Failed to clear session shadow: agent={}, session={}", agentId, sessionId, e);
        }
        if (historyStore != null) {
            try {
                historyStore.awaitIdle(5_000);
                historyStore.deleteSession(agentId, sessionId);
            } catch (RuntimeException e) {
                log.warn("Failed to clear session history: agent={}, session={}", agentId, sessionId, e);
            }
        }
        deleteSessionLogs(agentId, sessionId);
        return deleted;
    }

    private void deleteSessionLogs(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        Path workspace = configManager.resolveWorkspaceDir(normalizeAgentId(agentId));
        List<Path> roots = List.of(workspace.resolve("agents"), workspace.resolve("default").resolve("agents"));
        List<String> names = List.of(sessionId + ".log.jsonl", sessionId + ".jsonl");
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(file -> names.contains(file.getFileName().toString())).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                log.warn("Failed to clear AgentScope session logs: agent={}, session={}",
                        agentId, sessionId, e);
            }
        }
    }

    private Map<String, Object> previousShadowState(String agentId, String channel, String userId, String sessionId) {
        Map<String, Object> shadow = loadSessionShadow(agentId, channel,
                storageUserId(agentId, channel, sessionId, userId), sessionId);
        Object agentRaw = shadow.get("agent");
        if (!(agentRaw instanceof Map<?, ?> agentMap)) return Map.of();
        Object stateRaw = asMap(agentMap).get("state");
        if (!(stateRaw instanceof Map<?, ?> stateMap)) return Map.of();
        return asMap(stateMap);
    }

    private void handOffConversationState(String agentId, ChatSpec chat, String targetUserId) {
        String target = valueOrDefault(targetUserId, "default");
        String source = stringValue(chat.getMeta().get("state_user_id"), chat.getUserId());
        if (target.equals(source)) return;
        stateLookup.find(agentId, source, chat.getSessionId()).ifPresent(state -> {
            AgentState conversationState = AgentState.builder()
                    .sessionId(chat.getSessionId())
                    .userId(target)
                    .summary(state.getSummary())
                    .context(state.getContext())
                    .build();
            stateStore.save(AgentScopedStateStore.scopedUserId(agentId, target),
                    chat.getSessionId(), "agent_state", conversationState);
        });
    }

    private void addStateUser(ChatSpec chat, String userId) {
        LinkedHashSet<String> users = new LinkedHashSet<>();
        Object raw = chat.getMeta().get("state_user_ids");
        if (raw instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(value -> !value.isBlank()).forEach(users::add);
        }
        users.add(valueOrDefault(userId, "default"));
        chat.getMeta().put("state_user_ids", List.copyOf(users));
    }

    private ChatSpec findBySession(String agentId, String channel, String sessionId) {
        String ch = valueOrDefault(channel, "console");
        String sid = valueOrDefault(sessionId, "default");
        return load(agentId).stream()
                .filter(chat -> sid.equals(chat.getSessionId()) && ch.equals(chat.getChannel()))
                .findFirst()
                .orElse(null);
    }

    private String turnLeaseKey(String agentId, String chatId) {
        return normalizeAgentId(agentId) + "\u0000" + valueOrDefault(chatId, "default");
    }

    private List<ChatSpec> load(String agentId) {
        importLegacyChats(agentId);
        Path file = chatsFile(agentId);
        Map<String, Object> data = JsonUtils.load(file, MAP_TYPE);
        if (data == null) return List.of();
        Object rawChats = data.get("chats");
        if (!(rawChats instanceof List<?> items)) return List.of();
        List<ChatSpec> chats = new ArrayList<>();
        for (Object item : items) {
            ChatSpec spec = JsonUtils.getMapper().convertValue(item, ChatSpec.class);
            normalize(spec, agentId);
            if (spec.getId() != null && !spec.getId().isBlank()) chats.add(spec);
        }
        return chats;
    }

    private void save(String agentId, List<ChatSpec> chats) {
        List<Map<String, Object>> payload = chats.stream().map(this::payload).toList();
        JsonUtils.saveOrThrow(chatsFile(agentId), Map.of("version", 1, "chats", payload));
    }

    private void upsert(String agentId, ChatSpec spec) {
        normalize(spec, agentId);
        List<ChatSpec> chats = new ArrayList<>(load(agentId));
        boolean replaced = false;
        for (int i = 0; i < chats.size(); i++) {
            if (spec.getId().equals(chats.get(i).getId())) {
                chats.set(i, spec);
                replaced = true;
                break;
            }
        }
        if (!replaced) chats.add(spec);
        save(agentId, chats);
    }

    private void importLegacyChats(String agentId) {
        Path marker = configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve(".legacy_chats_imported");
        if (Files.exists(marker)) return;
        Path legacyDir = configManager.resolveHomeDir().resolve("chats");
        if (!Files.isDirectory(legacyDir)) {
            writeMarker(marker);
            return;
        }
        List<ChatSpec> current = new ArrayList<>(loadWithoutLegacy(agentId));
        try (var stream = Files.list(legacyDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> importLegacyFile(path, agentId, current));
            save(agentId, current);
            writeMarker(marker);
        } catch (Exception e) {
            log.warn("Failed to import legacy chats for agent={}", agentId, e);
        }
    }

    private List<ChatSpec> loadWithoutLegacy(String agentId) {
        Path file = chatsFile(agentId);
        Map<String, Object> data = JsonUtils.load(file, MAP_TYPE);
        if (data == null || !(data.get("chats") instanceof List<?> items)) return new ArrayList<>();
        List<ChatSpec> chats = new ArrayList<>();
        for (Object item : items) {
            ChatSpec spec = JsonUtils.getMapper().convertValue(item, ChatSpec.class);
            normalize(spec, agentId);
            if (spec.getId() != null && !spec.getId().isBlank()) chats.add(spec);
        }
        return chats;
    }

    private void importLegacyFile(Path path, String agentId, List<ChatSpec> current) {
        Map<String, Object> legacy = JsonUtils.load(path, MAP_TYPE);
        if (legacy == null) return;
        String legacyAgent = stringValue(legacy.get("agent_id"));
        if (!legacyAgent.isBlank() && !normalizeAgentId(agentId).equals(legacyAgent)) return;
        String id = valueOrDefault(stringValue(legacy.get("id")), UUID.randomUUID().toString());
        boolean exists = current.stream().anyMatch(chat -> id.equals(chat.getId()));
        if (exists) return;
        ChatSpec spec = new ChatSpec(id, normalizeAgentId(agentId),
                nameOrDefault(valueOrDefault(stringValue(legacy.get("name")), stringValue(legacy.get("title")))));
        spec.setSessionId(valueOrDefault(stringValue(legacy.get("session_id")), id));
        spec.setUserId(valueOrDefault(stringValue(legacy.get("user_id")), "default"));
        spec.setChannel(valueOrDefault(stringValue(legacy.get("channel")), "console"));
        spec.setCreatedAt(valueOrDefault(stringValue(legacy.get("created_at")), Instant.now().toString()));
        spec.setUpdatedAt(valueOrDefault(stringValue(legacy.get("updated_at")), spec.getCreatedAt()));
        spec.setStatus(valueOrDefault(stringValue(legacy.get("status")), "idle"));
        spec.setSource(valueOrDefault(stringValue(legacy.get("source")), "chat"));
        current.add(spec);
    }

    private Map<String, Object> payload(ChatSpec spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("channel", spec.getChannel());
        map.put("created_at", spec.getCreatedAt());
        map.put("id", spec.getId());
        map.put("meta", spec.getMeta());
        map.put("name", spec.getName());
        map.put("pinned", spec.isPinned());
        map.put("session_id", spec.getSessionId());
        map.put("source", spec.getSource());
        map.put("status", spec.getStatus());
        map.put("updated_at", spec.getUpdatedAt());
        map.put("user_id", spec.getUserId());
        return map;
    }

    private void normalize(ChatSpec spec, String agentId) {
        if (spec == null) return;
        String now = Instant.now().toString();
        if (spec.getId() == null || spec.getId().isBlank()) spec.setId(UUID.randomUUID().toString());
        if (spec.getAgentId() == null || spec.getAgentId().isBlank()) spec.setAgentId(normalizeAgentId(agentId));
        spec.setName(nameOrDefault(spec.getName()));
        if (spec.getSessionId() == null || spec.getSessionId().isBlank()) spec.setSessionId(newSessionId());
        spec.setUserId(valueOrDefault(spec.getUserId(), "default"));
        spec.setChannel(valueOrDefault(spec.getChannel(), "console"));
        spec.setCreatedAt(valueOrDefault(spec.getCreatedAt(), now));
        spec.setUpdatedAt(valueOrDefault(spec.getUpdatedAt(), spec.getCreatedAt()));
        spec.setStatus(valueOrDefault(spec.getStatus(), "idle"));
        spec.setSource(valueOrDefault(spec.getSource(), "chat"));
    }

    private Path chatsFile(String agentId) {
        return SafePathUtil.resolveSafe(
                configManager.resolveWorkspaceDir(normalizeAgentId(agentId)), "chats.json");
    }

    private Map<String, Object> repairCompactedStateFromLog(String agentId, String sessionId,
                                                            Map<String, Object> state,
                                                            List<Map<String, Object>> prependContext,
                                                            Map<String, Object> turnUsage) {
        if (!hasCompactionSummary(state)) return state;
        List<Object> logContext = readContextFromSessionLog(agentId, sessionId);
        if (logContext.isEmpty() || logContext.size() <= contextSize(state)) return state;
        Map<String, Object> repaired = new LinkedHashMap<>(state);
        repaired.put("context", logContext);
        mergeFrontendContext(repaired, prependContext);
        injectTurnUsage(repaired, turnUsage);
        log.info("Repaired compacted session shadow from jsonl: agent={}, session={}, messages={}",
                agentId, sessionId, logContext.size());
        return repaired;
    }

    /**
     * Keeps the last complete frontend projection when compaction succeeded but its append-only
     * AgentScope log is unavailable. Stable message IDs are the primary merge key; semantic
     * fallback is limited to the recent tail so an intentionally repeated prompt in a later turn
     * is not removed.
     */
    private Map<String, Object> repairCompactedStateFromShadow(
            Map<String, Object> compacted, Map<String, Object> previous) {
        Object previousRaw = previous.get("context");
        Object compactedRaw = compacted.get("context");
        if (!(previousRaw instanceof List<?> previousContext) || previousContext.isEmpty()
                || !(compactedRaw instanceof List<?> compactedContext)) {
            return compacted;
        }
        List<Object> combined = new ArrayList<>(previousContext);
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Object item : previousContext) {
            if (item instanceof Map<?, ?> message) {
                String id = stringValue(message.get("id"));
                if (!id.isBlank()) seenIds.add(id);
            }
        }
        int recentStart = Math.max(0, previousContext.size() - 32);
        java.util.Set<String> recentFingerprints = new java.util.HashSet<>();
        for (int i = recentStart; i < previousContext.size(); i++) {
            if (previousContext.get(i) instanceof Map<?, ?> message) {
                recentFingerprints.add(contextFingerprint(message));
            }
        }
        for (Object item : compactedContext) {
            if (!(item instanceof Map<?, ?> rawMessage)) continue;
            Map<String, Object> message = asMap(rawMessage);
            if (ConversationVisibility.isModelOnly(message)) continue;
            String id = stringValue(message.get("id"));
            if (!id.isBlank() && seenIds.contains(id)) continue;
            String fingerprint = contextFingerprint(message);
            if (id.isBlank() && recentFingerprints.contains(fingerprint)) continue;
            combined.add(message);
            if (!id.isBlank()) seenIds.add(id);
            recentFingerprints.add(fingerprint);
        }
        Map<String, Object> repaired = new LinkedHashMap<>(compacted);
        repaired.put("context", combined);
        log.info("Repaired compacted session shadow from previous frontend projection: messages={}",
                combined.size());
        return repaired;
    }

    private String contextFingerprint(Map<?, ?> message) {
        return stringValue(message.get("role")).toLowerCase() + ":"
                + JsonUtils.toJson(message.get("content"));
    }

    private boolean hasCompactionSummary(Map<String, Object> state) {
        Object raw = state.get("context");
        if (!(raw instanceof List<?> context)) return false;
        return context.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(message -> "__compaction_summary__".equals(stringValue(message.get("name"))));
    }

    private int contextSize(Map<String, Object> state) {
        Object raw = state.get("context");
        return raw instanceof List<?> context ? context.size() : 0;
    }

    private List<Object> readContextFromSessionLog(String agentId, String sessionId) {
        Path logFile = findSessionLog(agentId, sessionId);
        if (logFile == null) return List.of();
        List<Object> context = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                Map<String, Object> raw = JsonUtils.getMapper().readValue(line, MAP_TYPE);
                if (isInternalLogMessage(raw)) continue;
                Map<String, Object> message = logLineToStateMessage(agentId, raw);
                if (!message.isEmpty() && isNewMessage(context, message)) context.add(message);
            }
        } catch (IOException e) {
            log.warn("Failed to repair session shadow from log: {}", logFile, e);
            return List.of();
        }
        return context;
    }

    private Path findSessionLog(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Path workspace = configManager.resolveWorkspaceDir(normalizeAgentId(agentId));
        List<Path> candidates = List.of(
                workspace.resolve("agents"),
                workspace.resolve("default").resolve("agents")
        );
        for (Path agentsDir : candidates) {
            Path found = findUnderAgents(agentsDir, sessionId + ".log.jsonl");
            if (found != null) return found;
            found = findUnderAgents(agentsDir, sessionId + ".jsonl");
            if (found != null) return found;
        }
        return null;
    }

    private Path findUnderAgents(Path agentsDir, String fileName) {
        if (!Files.isDirectory(agentsDir)) return null;
        try (var stream = Files.find(agentsDir, 4,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(fileName))) {
            return stream.sorted(Comparator.comparing(Path::toString)).findFirst().orElse(null);
        } catch (IOException e) {
            log.debug("Failed to scan session logs under {}", agentsDir, e);
            return null;
        }
    }

    private Map<String, Object> logLineToStateMessage(String agentId, Map<String, Object> raw) {
        String role = stringValue(raw.get("role")).toLowerCase();
        if (role.isBlank()) role = "assistant";
        String content = stringValue(raw.get("content"));
        if ("user".equals(role)) content = ExplicitSkillInvocation.userVisibleText(content);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", valueOrDefault(stringValue(raw.get("id")), UUID.randomUUID().toString()));
        message.put("role", role);
        message.put("name", "user".equals(role) ? "user" : normalizeAgentId(agentId));
        message.put("metadata", Map.of());
        message.put("content", logContentBlocks(role, content, stringValue(raw.get("toolCallId"))));
        return message;
    }

    private List<Object> logContentBlocks(String role, String content, String toolCallId) {
        if ("tool".equals(role) && content.startsWith("[tool_result:")) {
            return List.of(toolResultBlock(content, toolCallId));
        }
        if (!"assistant".equals(role) || !content.contains("[tool_call:")) {
            return List.of(Map.of("type", "text", "text", content));
        }
        List<Object> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (line.startsWith("[tool_call:")) {
                if (!text.isEmpty()) {
                    blocks.add(Map.of("type", "text", "text", text.toString().trim()));
                    text.setLength(0);
                }
                blocks.add(toolCallBlock(line, toolCallId));
            } else {
                if (!text.isEmpty()) text.append('\n');
                text.append(line);
            }
        }
        if (!text.isEmpty()) blocks.add(Map.of("type", "text", "text", text.toString().trim()));
        return blocks;
    }

    private Map<String, Object> toolCallBlock(String line, String fallbackCallId) {
        int marker = line.indexOf("[tool_call:");
        int nameStart = marker + "[tool_call:".length();
        int argsStart = line.indexOf('(', nameStart);
        int end = line.endsWith("]") ? line.length() - 1 : line.length();
        String name;
        String args = "";
        if (argsStart > nameStart) {
            name = line.substring(nameStart, argsStart).trim();
            int argsEnd = line.lastIndexOf(')');
            if (argsEnd > argsStart) args = line.substring(argsStart + 1, argsEnd).trim();
        } else {
            name = line.substring(nameStart, end).trim();
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_call");
        block.put("id", valueOrDefault(fallbackCallId, UUID.randomUUID().toString().replace("-", "")));
        block.put("name", FrontendToolCompat.displayToolName(name));
        block.put("input", FrontendToolCompat.argumentsJson(name, args));
        block.put("state", "finished");
        return block;
    }

    private Map<String, Object> toolResultBlock(String content, String fallbackCallId) {
        int marker = content.indexOf("[tool_result:");
        int end = content.indexOf(']', marker);
        String name = end > marker ? content.substring(marker + "[tool_result:".length(), end).trim() : "unknown";
        String output = end >= 0 ? content.substring(end + 1).trim() : content;
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("id", valueOrDefault(fallbackCallId, UUID.randomUUID().toString().replace("-", "")));
        block.put("name", FrontendToolCompat.displayToolName(name));
        block.put("output", List.of(Map.of("type", "text", "text", output)));
        block.put("state", "success");
        return block;
    }

    private boolean isNewMessage(List<Object> context, Map<String, Object> message) {
        String id = stringValue(message.get("id"));
        if (id.isBlank()) return true;
        return context.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .noneMatch(existing -> id.equals(stringValue(existing.get("id"))));
    }

    private Map<String, Object> stateScrollIndex(String agentId, String sessionId, Map<String, Object> state) {
        List<String> persistedIds = new ArrayList<>();
        List<String> persistedTcids = new ArrayList<>();
        Map<String, Object> seqById = new LinkedHashMap<>();
        Map<String, Object> modelTurnSeq = new LinkedHashMap<>();
        Map<String, Object> modelTurnNblk = new LinkedHashMap<>();
        int seq = 1;
        Object raw = state.get("context");
        if (raw instanceof List<?> context) {
            for (Object item : context) {
                if (!(item instanceof Map<?, ?> rawMessage)) continue;
                Map<String, Object> message = asMap(rawMessage);
                String id = valueOrDefault(stringValue(message.get("id")), UUID.randomUUID().toString().replace("-", ""));
                persistedIds.add(id);
                int blockCount = blockCount(message.get("content"));
                int start = seq;
                int end = seq + Math.max(blockCount, 1) - 1;
                seqById.put(id, List.of(start, end));
                modelTurnSeq.put(id, start);
                modelTurnNblk.put(id, blockCount);
                seq = end + 1;
                collectToolCallIds(message.get("content"), persistedTcids);
            }
        }
        Map<String, Object> scroll = new LinkedHashMap<>();
        scroll.put("persisted_ids", persistedIds);
        scroll.put("persisted_tcids", persistedTcids);
        scroll.put("synthetic_ids", List.of());
        scroll.put("seq_by_id", seqById);
        scroll.put("model_turn_seq", modelTurnSeq);
        scroll.put("model_turn_nblk", modelTurnNblk);
        scroll.put("leaf_by_id", Map.of());
        scroll.put("index", Map.of("session_id", valueOrDefault(sessionId, "default"), "agent_id", normalizeAgentId(agentId), "tiers", List.of()));
        return scroll;
    }

    @SuppressWarnings("unchecked")
    private void normalizeState(String agentId, Map<String, Object> state) {
        Object raw = state.get("context");
        if (!(raw instanceof List<?> context)) return;
        List<Object> normalized = new ArrayList<>();
        Map<String, Object> lastAssistant = null;
        List<String> currentTurnFingerprints = new ArrayList<>();
        java.util.Set<String> seenMessageIds = new java.util.HashSet<>();
        for (Object item : context) {
            if (!(item instanceof Map<?, ?> rawMessage)) {
                normalized.add(item);
                continue;
            }
            Map<String, Object> message = asMap(rawMessage);
            normalizeMessage(agentId, message);
            if (isInternalContextMessage(message)) {
                continue;
            }
            String id = stringValue(message.get("id"));
            if (!id.isBlank() && !seenMessageIds.add(id)) continue;
            String role = stringValue(message.get("role"));
            if ("user".equals(role)) {
                currentTurnFingerprints.clear();
                normalized.add(message);
                lastAssistant = null;
                continue;
            }
            if ("tool".equals(stringValue(message.get("role"))) && mergeToolMessage(lastAssistant, message)) {
                continue;
            }
            List<String> fingerprints = blockFingerprints(message.get("content"));
            if (!fingerprints.isEmpty() && currentTurnFingerprints.containsAll(fingerprints)) continue;
            currentTurnFingerprints.addAll(fingerprints);
            normalized.add(message);
            lastAssistant = "assistant".equals(role) ? message : null;
        }
        state.put("context", normalized);
    }

    private void normalizeMessage(String agentId, Map<String, Object> message) {
        String role = stringValue(message.get("role")).toLowerCase();
        if (role.isBlank()) role = "assistant";
        message.put("role", role);
        if ("user".equals(role)) {
            message.put("name", valueOrDefault(stringValue(message.get("name")), "user"));
        } else if ("assistant".equals(role)) {
            message.put("name", normalizeAgentId(agentId));
        }
        Object content = message.get("content");
        if (!(content instanceof List<?> blocks)) return;
        List<Object> normalized = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> rawBlock)) {
                normalized.add(block);
                continue;
            }
            Map<String, Object> item = asMap(rawBlock);
            normalizeBlock(agentId, item);
            normalized.add(item);
        }
        message.put("content", normalized);
    }

    private void normalizeBlock(String agentId, Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if ("tool_use".equals(type)) {
            block.put("type", "tool_call");
            block.put("state", "finished");
        } else if ("tool_call".equals(type)) {
            block.putIfAbsent("state", "finished");
        } else if ("tool_result".equals(type)) {
            String state = stringValue(block.get("state")).toLowerCase();
            if (state.isBlank() || "running".equals(state) || "allowed".equals(state) || "finished".equals(state)) {
                block.put("state", "success");
            }
            maybeSpillLargeToolOutput(agentId, block);
        }
        String name = stringValue(block.get("name"));
        if (("tool_call".equals(block.get("type")) || "tool_result".equals(block.get("type")))
                && !name.isBlank()) {
            block.put("name", FrontendToolCompat.displayToolName(name));
        }
        if ("tool_call".equals(block.get("type")) && (block.get("input") instanceof Map<?, ?> || block.get("input") instanceof List<?>)) {
            block.put("input", FrontendToolCompat.argumentsJson(name, JsonUtils.toJson(block.get("input"))));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean mergeToolMessage(Map<String, Object> lastAssistant, Map<String, Object> toolMessage) {
        if (lastAssistant == null) return false;
        Object rawContent = toolMessage.get("content");
        if (!(rawContent instanceof List<?> blocks)) return false;
        Object assistantRaw = lastAssistant.get("content");
        List<Object> assistantContent = assistantRaw instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        boolean merged = false;
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> rawBlock) {
                Map<String, Object> item = asMap(rawBlock);
                if ("tool_result".equals(stringValue(item.get("type")))) {
                    String resultId = stringValue(item.get("id"));
                    boolean alreadyPresent = assistantContent.stream()
                            .filter(Map.class::isInstance)
                            .map(Map.class::cast)
                            .filter(existing -> "tool_result".equals(stringValue(existing.get("type"))))
                            .anyMatch(existing -> (!resultId.isBlank() && resultId.equals(stringValue(existing.get("id"))))
                                    || JsonUtils.toJson(existing).equals(JsonUtils.toJson(item)));
                    if (alreadyPresent) continue;
                    assistantContent.add(item);
                    merged = true;
                }
            }
        }
        if (merged) lastAssistant.put("content", assistantContent);
        return merged;
    }

    private void maybeSpillLargeToolOutput(String agentId, Map<String, Object> block) {
        Object output = block.get("output");
        String text = output instanceof String s ? s : JsonUtils.toJson(output);
        if (text == null || text.length() <= largeToolOutputChars(agentId)) return;
        try {
            Path dir = configManager.resolveWorkspaceDir(normalizeAgentId(agentId)).resolve("tool_results");
            Files.createDirectories(dir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + ".txt";
            Path target = SafePathUtil.resolveSafe(dir, fileName);
            Files.writeString(target, text);
            block.put("output", List.of(Map.of(
                    "type", "text",
                    "text", "<system-reminder>Tool output was too large and has been stored at "
                            + target.toAbsolutePath().normalize() + ".</system-reminder>"
            )));
        } catch (Exception e) {
            log.warn("Failed to spill large tool output for agent={}", agentId, e);
        }
    }

    private int largeToolOutputChars(String agentId) {
        AgentConfig agent = configManager.getConfig().getAgent(normalizeAgentId(agentId));
        if (agent == null || agent.getLightContextConfig() == null
                || agent.getLightContextConfig().getScrollConfig() == null) {
            return DEFAULT_TOOL_OUTPUT_TOKEN_CAP * 4;
        }
        int cap = agent.getLightContextConfig().getScrollConfig().getToolOutputTokenCap();
        double divisor = agent.getLightContextConfig().getTokenCountEstimateDivisor();
        if (cap <= 0) cap = DEFAULT_TOOL_OUTPUT_TOKEN_CAP;
        if (divisor <= 0) divisor = 4.0;
        return Math.max(1, (int) Math.ceil(cap * divisor));
    }

    private int blockCount(Object content) {
        if (content instanceof List<?> blocks) return blocks.size();
        if (content == null) return 0;
        return 1;
    }

    private void collectToolCallIds(Object content, List<String> ids) {
        if (!(content instanceof List<?> blocks)) return;
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> rawBlock)) continue;
            String type = stringValue(rawBlock.get("type"));
            if (("tool_call".equals(type) || "tool_result".equals(type)) && rawBlock.get("id") != null) {
                ids.add(stringValue(rawBlock.get("id")));
            }
        }
    }

    private Map<String, Object> asMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private boolean isInternalContextMessage(Map<String, Object> message) {
        return ConversationVisibility.isModelOnly(message);
    }

    private boolean isInternalLogMessage(Map<String, Object> raw) {
        if ("user".equalsIgnoreCase(stringValue(raw.get("role")))
                && ExplicitSkillInvocation.isModelPrompt(stringValue(raw.get("content")))) {
            return false;
        }
        return ConversationVisibility.isModelOnly(raw);
    }

    @SuppressWarnings("unchecked")
    private void mergeFrontendContext(Map<String, Object> state, List<Map<String, Object>> prependContext) {
        if (prependContext == null || prependContext.isEmpty()) return;
        Object raw = state.get("context");
        List<Object> context = raw instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        for (Map<String, Object> item : prependContext) {
            String id = stringValue(item.get("id"));
            boolean exists = !id.isBlank() && context.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(existing -> id.equals(stringValue(existing.get("id"))));
            if (exists) continue;
            if (isCoveredByCurrentTurn(context, item)) continue;
            int matchingUser = findMatchingUserContext(context, item);
            if (matchingUser >= 0 && context.get(matchingUser) instanceof Map<?, ?> rawExisting) {
                Map<String, Object> existing = new LinkedHashMap<>();
                rawExisting.forEach((key, value) -> existing.put(String.valueOf(key), value));
                existing.put("content", item.get("content"));
                context.set(matchingUser, existing);
            } else {
                context.add(insertionIndexForUser(context), item);
            }
        }
        state.put("context", context);
    }

    private boolean isCoveredByCurrentTurn(List<Object> context, Map<String, Object> candidate) {
        if ("user".equalsIgnoreCase(stringValue(candidate.get("role")))) return false;
        List<String> candidateBlocks = blockFingerprints(candidate.get("content"));
        if (candidateBlocks.isEmpty()) return false;

        List<String> existingBlocks = new ArrayList<>();
        for (int i = context.size() - 1; i >= 0; i--) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> raw)) continue;
            if ("user".equalsIgnoreCase(stringValue(raw.get("role")))) break;
            existingBlocks.addAll(blockFingerprints(raw.get("content")));
        }
        return existingBlocks.containsAll(candidateBlocks);
    }

    private List<String> blockFingerprints(Object content) {
        if (!(content instanceof List<?> blocks)) return List.of();
        List<String> fingerprints = new ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw)) continue;
            String type = stringValue(raw.get("type"));
            if ("tool_use".equals(type) || "tool_call".equals(type)) {
                String id = stringValue(raw.get("id"));
                fingerprints.add(id.isBlank()
                        ? "tool_call:" + JsonUtils.toJson(raw)
                        : "tool_call:" + id);
            } else if ("tool_result".equals(type)) {
                String id = stringValue(raw.get("id"));
                fingerprints.add(id.isBlank()
                        ? "tool_result:" + JsonUtils.toJson(raw)
                        : "tool_result:" + id);
            } else if ("thinking".equals(type)) {
                fingerprints.add("thinking:" + stringValue(raw.get("thinking")));
            } else if ("text".equals(type)) {
                fingerprints.add("text:" + stringValue(raw.get("text")));
            } else {
                fingerprints.add(type + ":" + JsonUtils.toJson(raw));
            }
        }
        return fingerprints;
    }

    @SuppressWarnings("unchecked")
    private void injectTurnUsage(Map<String, Object> state, Map<String, Object> turnUsage) {
        if (turnUsage == null || turnUsage.isEmpty()) return;
        Object raw = state.get("context");
        if (!(raw instanceof List<?> context)) return;
        for (int i = context.size() - 1; i >= 0; i--) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> rawMessage)) continue;
            Map<String, Object> message = (Map<String, Object>) rawMessage;
            if (!"assistant".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            Map<String, Object> metadata = message.get("metadata") instanceof Map<?, ?> rawMetadata
                    ? new LinkedHashMap<>((Map<String, Object>) rawMetadata)
                    : new LinkedHashMap<>();
            metadata.put("qwenpaw_turn_usage", turnUsage);
            message.put("metadata", metadata);
            return;
        }
    }

    private int insertionIndexForUser(List<Object> context) {
        int firstAssistant = -1;
        for (int i = 0; i < context.size(); i++) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> raw)) continue;
            String role = stringValue(raw.get("role")).toLowerCase();
            if ("user".equals(role) && !isInternalContextName(stringValue(raw.get("name")))) {
                return context.size();
            }
            if (firstAssistant < 0 && ("assistant".equals(role) || "tool".equals(role))) {
                firstAssistant = i;
            }
        }
        return firstAssistant >= 0 ? firstAssistant : context.size();
    }

    private boolean isInternalContextName(String name) {
        return ConversationVisibility.isInternalName(name);
    }

    @SuppressWarnings("unchecked")
    private int findMatchingUserContext(List<Object> context, Map<String, Object> frontendMessage) {
        String frontendText = extractText(frontendMessage.get("content"));
        if (frontendText.isBlank()) return -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            Object item = context.get(i);
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> message = (Map<String, Object>) raw;
            if (!"user".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            if (frontendText.equals(extractText(message.get("content")))) return i;
        }
        return -1;
    }

    private String extractText(Object content) {
        if (content instanceof String text) return text.trim();
        if (!(content instanceof List<?> blocks)) return "";
        List<String> parts = new ArrayList<>();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> raw && "text".equals(stringValue(raw.get("type")))) {
                parts.add(stringValue(raw.get("text")));
            }
        }
        return String.join("\n", parts).trim();
    }

    private void writeMarker(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Instant.now().toString());
        } catch (Exception e) {
            log.debug("Failed to write legacy import marker {}", marker, e);
        }
    }

    private static String newSessionId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 7);
    }

    private static String normalizeAgentId(String agentId) {
        return valueOrDefault(agentId, "default");
    }

    private static String nameOrDefault(String name) {
        return valueOrDefault(name, "New Chat");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String sanitizeFileName(String name) {
        return SafeJSONSession.sanitizeFileName(name);
    }
}
