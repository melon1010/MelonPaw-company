package com.melon.app.service;

import com.melon.core.config.AgentConfig;
import com.melon.core.config.ConfigManager;
import com.melon.app.task.EnterpriseTaskService;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * Service for managing tool approvals and plans.
 * Handles approval mode queries, pending approvals, and plan confirmations.
 * Corresponds to Python ToolGuard and PlanGate approval system.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ConfigManager configManager;
    private final AuditLogService auditLogService;
    private final EnterpriseTaskService taskService;

    // agentId + ownerUserId + sessionId -> live approval gate for the paused AgentScope stream
    private final ConcurrentHashMap<String, PendingApprovalSession> pendingApprovalSessions = new ConcurrentHashMap<>();
    // agentId + sessionId -> pending plan data
    private final ConcurrentHashMap<String, Map<String, Object>> pendingPlans = new ConcurrentHashMap<>();

    public ApprovalService(ConfigManager configManager) {
        this(configManager, null, null);
    }

    public ApprovalService(ConfigManager configManager, AuditLogService auditLogService) {
        this(configManager, auditLogService, null);
    }

    @Autowired
    public ApprovalService(ConfigManager configManager, AuditLogService auditLogService,
                           EnterpriseTaskService taskService) {
        this.configManager = configManager;
        this.auditLogService = auditLogService;
        this.taskService = taskService;
    }

    /**
     * Gets the approval mode for an agent.
     */
    public String getApprovalMode(String agentId) {
        var agentConfig = configManager.getConfig().getAgents().get(agentId);
        if (agentConfig == null || agentConfig.getApproval() == null) {
            return "RISK_BASED";
        }
        return agentConfig.getApproval().getLevel();
    }

    /**
     * Sets the approval mode for an agent.
     */
    public void setApprovalMode(String agentId, String mode) {
        var config = configManager.getConfig();
        var agentConfig = config.getAgents().get(agentId);
        if (agentConfig == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        if (agentConfig.getApproval() == null) {
            agentConfig.setApproval(new AgentConfig.ApprovalConfig());
        }
        agentConfig.getApproval().setLevel(mode);
        configManager.save();
        log.info("Approval mode set to {} for agent {}", mode, agentId);
    }

    /**
     * Gets the pending approval for a session.
     */
    public Map<String, Object> getPendingApproval(String agentId, String sessionId) {
        PendingApprovalSession session = findPendingSession(agentId, sessionId, "");
        return session != null ? session.firstApproval() : null;
    }

    /** Returns a pending item only when it belongs to the requested user. */
    public Map<String, Object> getPendingApproval(String agentId, String sessionId, String userId) {
        PendingApprovalSession session = pendingApprovalSessions.get(
                approvalScopeKey(agentId, userId, sessionId));
        return session != null ? session.firstApproval() : null;
    }

    public List<Map<String, Object>> getPendingApprovals(String agentId) {
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (PendingApprovalSession session : pendingApprovalSessions.values()) {
            if (sameAgent(agentId, session.agentId())) {
                approvals.addAll(session.approvals());
            }
        }
        return approvals;
    }

    /** Returns only approvals owned by one user. */
    public List<Map<String, Object>> getPendingApprovals(String agentId, String userId) {
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (PendingApprovalSession session : pendingApprovalSessions.values()) {
            if (sameAgent(agentId, session.agentId()) && session.belongsTo(userId)) {
                approvals.addAll(session.approvals());
            }
        }
        return approvals;
    }

    public Mono<List<ConfirmResult>> openPendingApproval(String agentId, String sessionId, List<ToolUseBlock> toolCalls,
                                                         List<Map<String, Object>> approvalRequests) {
        return openPendingApproval(agentId, sessionId, "", toolCalls, approvalRequests);
    }

    public Mono<List<ConfirmResult>> openPendingApproval(String agentId, String sessionId, String ownerUserId,
                                                         List<ToolUseBlock> toolCalls,
                                                         List<Map<String, Object>> approvalRequests) {
        String key = approvalScopeKey(agentId, ownerUserId, sessionId);
        PendingApprovalSession session = new PendingApprovalSession(
                normalizeAgentId(agentId), normalizeUserId(ownerUserId), normalizeSessionId(sessionId),
                toolCalls, approvalRequests);
        PendingApprovalSession previous = pendingApprovalSessions.put(key, session);
        if (previous != null) {
            recordApprovalCancellation(previous, "replaced by a newer approval gate");
            previous.cancel();
        }
        if (taskService != null && approvalRequests != null) {
            for (Map<String, Object> approval : approvalRequests) {
                String taskId = stringValue(approval.get("task_id"));
                String runId = stringValue(approval.get("run_id"));
                if (!taskId.isBlank() && !runId.isBlank()) {
                    taskService.waitingApproval(taskId, runId, approvalEvent(approval, "PENDING", ""));
                }
            }
        }
        return Mono.fromFuture(session.future());
    }

    public boolean cancelPendingApproval(String agentId, String sessionId) {
        PendingApprovalSession session = findPendingSession(agentId, sessionId, "");
        if (session == null) return false;
        pendingApprovalSessions.remove(session.scopeKey(), session);
        recordApprovalCancellation(session, "approval cancelled");
        session.cancel();
        return true;
    }

    public boolean cancelPendingApproval(String agentId, String sessionId, String ownerUserId) {
        PendingApprovalSession session = pendingApprovalSessions.remove(
                approvalScopeKey(agentId, ownerUserId, sessionId));
        if (session == null) return false;
        recordApprovalCancellation(session, "approval cancelled by task owner");
        session.cancel();
        return true;
    }

    public boolean decidePendingApproval(String agentId, String sessionId, String requestId, boolean approved) {
        return decidePendingApproval(agentId, sessionId, requestId, "", true, approved);
    }

    /** Decides one approval after owner/elevated-role authorization. */
    public boolean decidePendingApproval(String agentId, String sessionId, String requestId,
                                         String actorUserId, boolean elevated, boolean approved) {
        PendingApprovalSession session = elevated
                ? findPendingSession(agentId, sessionId, requestId)
                : pendingApprovalSessions.get(approvalScopeKey(agentId, actorUserId, sessionId));
        if (session == null) return false;
        if (!elevated && !session.belongsTo(actorUserId)) return false;
        ApprovalDecision decision = session.decide(requestId, approved);
        boolean accepted = decision != null;
        boolean done = accepted && session.isDone();
        if (accepted && auditLogService != null) {
            Map<String, Object> approval = new LinkedHashMap<>(
                    decision.approval() != null ? decision.approval() : Map.of());
            approval.put("decision_actor_user_id", normalizeUserId(actorUserId));
            approval.put("decision_actor_scope", elevated ? "ELEVATED" : "OWNER");
            String auditedAgentId = stringValue(approval.getOrDefault("agent_id", session.agentId()), session.agentId());
            String sid = stringValue(approval.getOrDefault("session_id", sessionId), "default");
            String toolName = stringValue(approval.getOrDefault("actual_tool_name",
                    approval.getOrDefault("tool_name", decision.toolCall().getName())), "unknown");
            auditLogService.recordApproval(auditedAgentId, sid, toolName,
                    approval.getOrDefault("tool_params", decision.toolCall().getInput()),
                    approved,
                    approved ? "approved by user" : "denied by user",
                    approval);
        }
        if (accepted && taskService != null) {
            Map<String, Object> approval = decision.approval() != null ? decision.approval() : Map.of();
            String taskId = stringValue(approval.get("task_id"));
            String runId = stringValue(approval.get("run_id"));
            if (!taskId.isBlank() && !runId.isBlank()) {
                Map<String, Object> payload = approvalEvent(
                        approval, approved ? "ALLOW" : "DENY", normalizeUserId(actorUserId));
                taskService.approvalResolved(taskId, runId, payload, done);
            }
        }
        if (done) {
            pendingApprovalSessions.remove(session.scopeKey(), session);
        }
        return accepted;
    }

    private void recordApprovalCancellation(PendingApprovalSession session, String reason) {
        if (taskService == null || session == null) return;
        for (Map<String, Object> approval : session.approvals()) {
            String taskId = stringValue(approval.get("task_id"));
            String runId = stringValue(approval.get("run_id"));
            if (!taskId.isBlank() && !runId.isBlank()) {
                taskService.approvalResolved(taskId, runId,
                        approvalEvent(approval, "CANCELLED", reason), false);
            }
        }
    }

    private Map<String, Object> approvalEvent(Map<String, Object> approval, String decision, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approval_id", stringValue(approval.get("request_id")));
        payload.put("tool_call_id", stringValue(approval.get("request_id")));
        payload.put("tool_name", stringValue(approval.get("tool_name")));
        payload.put("risk", stringValue(approval.get("severity")));
        payload.put("decision", decision);
        if (actor != null && !actor.isBlank()) payload.put("actor", actor);
        return payload;
    }

    /**
     * Gets the current plan for a session.
     */
    public Map<String, Object> getPlan(String agentId, String sessionId) {
        return pendingPlans.get(scopeKey(agentId, sessionId));
    }

    /**
     * Sets a plan for a session.
     * Also creates a CompletableFuture that the agent thread can wait on.
     */
    public void setPlan(String agentId, String sessionId, Map<String, Object> plan) {
        pendingPlans.put(scopeKey(agentId, sessionId), plan);
    }

    /**
     * Confirms a plan.
     * Completes the pending future with true to unblock the agent thread.
     */
    public void confirmPlan(String agentId, String sessionId) {
        pendingPlans.remove(scopeKey(agentId, sessionId));
        log.info("Plan confirmed for agent/session: {}/{}", agentId, sessionId);
    }

    /**
     * Rejects a plan.
     * Completes the pending future with false to signal the agent to revise.
     */
    public void rejectPlan(String agentId, String sessionId, String reason) {
        pendingPlans.remove(scopeKey(agentId, sessionId));
        log.info("Plan rejected for agent/session: {}/{} (reason: {})", agentId, sessionId, reason);
    }

    public boolean cancelPendingPlan(String agentId, String sessionId) {
        return pendingPlans.remove(scopeKey(agentId, sessionId)) != null;
    }

    private String scopeKey(String agentId, String sessionId) {
        return normalizeAgentId(agentId) + "\n" + normalizeSessionId(sessionId);
    }

    private String approvalScopeKey(String agentId, String ownerUserId, String sessionId) {
        return normalizeAgentId(agentId) + "\n" + normalizeUserId(ownerUserId)
                + "\n" + normalizeSessionId(sessionId);
    }

    private PendingApprovalSession findPendingSession(String agentId, String sessionId, String requestId) {
        String normalizedAgent = normalizeAgentId(agentId);
        String normalizedSession = normalizeSessionId(sessionId);
        return pendingApprovalSessions.values().stream()
                .filter(session -> normalizedAgent.equals(session.agentId()))
                .filter(session -> normalizedSession.equals(session.sessionId()))
                .filter(session -> requestId == null || requestId.isBlank() || session.hasRequest(requestId))
                .findFirst()
                .orElse(null);
    }

    private boolean sameAgent(String left, String right) {
        return normalizeAgentId(left).equals(normalizeAgentId(right));
    }

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId.trim();
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    private static final class PendingApprovalSession {
        private final String agentId;
        private final String ownerUserId;
        private final String sessionId;
        private final Map<String, ToolUseBlock> toolCalls = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> approvals = new LinkedHashMap<>();
        private final List<ConfirmResult> results = new ArrayList<>();
        private final CompletableFuture<List<ConfirmResult>> future = new CompletableFuture<>();

        PendingApprovalSession(String agentId, String ownerUserId, String sessionId,
                               List<ToolUseBlock> toolCalls,
                               List<Map<String, Object>> approvalRequests) {
            this.agentId = agentId;
            this.ownerUserId = ownerUserId;
            this.sessionId = sessionId;
            if (toolCalls != null) {
                for (ToolUseBlock toolCall : toolCalls) {
                    if (toolCall != null && toolCall.getId() != null) {
                        this.toolCalls.put(toolCall.getId(), toolCall);
                    }
                }
            }
            if (approvalRequests != null) {
                for (Map<String, Object> request : approvalRequests) {
                    if (request == null) continue;
                    String id = String.valueOf(request.getOrDefault("request_id", ""));
                    if (!id.isBlank()) {
                        this.approvals.put(id, request);
                    }
                }
            }
        }

        synchronized ApprovalDecision decide(String requestId, boolean approved) {
            if (future.isDone()) return null;
            String id = requestId != null && !requestId.isBlank() ? requestId : firstPendingId();
            ToolUseBlock toolCall = toolCalls.remove(id);
            Map<String, Object> approval = approvals.remove(id);
            if (toolCall == null) return null;
            results.add(new ConfirmResult(approved, toolCall));
            if (toolCalls.isEmpty()) {
                future.complete(List.copyOf(results));
            }
            return new ApprovalDecision(toolCall, approval);
        }

        synchronized Map<String, Object> firstApproval() {
            return approvals.values().stream().findFirst().orElse(null);
        }

        synchronized List<Map<String, Object>> approvals() {
            return new ArrayList<>(approvals.values());
        }

        synchronized boolean belongsTo(String userId) {
            return !ownerUserId.isBlank() && ownerUserId.equals(normalizeUserId(userId));
        }

        synchronized boolean hasRequest(String requestId) {
            return toolCalls.containsKey(requestId);
        }

        synchronized boolean isDone() {
            return future.isDone();
        }

        CompletableFuture<List<ConfirmResult>> future() {
            return future;
        }

        String agentId() {
            return agentId;
        }

        String sessionId() {
            return sessionId;
        }

        String scopeKey() {
            return agentId + "\n" + ownerUserId + "\n" + sessionId;
        }

        synchronized void cancel() {
            if (!future.isDone()) {
                List<ConfirmResult> denied = toolCalls.values().stream()
                        .map(toolCall -> new ConfirmResult(false, toolCall))
                        .toList();
                future.complete(denied);
            }
        }

        private String firstPendingId() {
            return toolCalls.keySet().stream().findFirst().orElse("");
        }
    }

    private static String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "" : userId.trim();
    }

    private record ApprovalDecision(ToolUseBlock toolCall, Map<String, Object> approval) {
    }
}
