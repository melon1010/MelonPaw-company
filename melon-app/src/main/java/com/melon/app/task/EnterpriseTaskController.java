package com.melon.app.task;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.runner.AgentRunner;
import com.melon.app.service.ApprovalService;
import com.melon.core.agent.MultiAgentManager;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Authenticated task timeline and lifecycle API used by the enterprise console. */
@RestController
@RequestMapping("/api/tasks")
public class EnterpriseTaskController {

    private final EnterpriseTaskService tasks;
    private final EnterpriseTaskRetryCoordinator retryCoordinator;
    private final AgentRunner agentRunner;
    private final ApprovalService approvalService;
    private final MultiAgentManager multiAgentManager;

    public EnterpriseTaskController(EnterpriseTaskService tasks, EnterpriseTaskRetryCoordinator retryCoordinator,
                                    AgentRunner agentRunner,
                                    ApprovalService approvalService, MultiAgentManager multiAgentManager) {
        this.tasks = tasks;
        this.retryCoordinator = retryCoordinator;
        this.agentRunner = agentRunner;
        this.approvalService = approvalService;
        this.multiAgentManager = multiAgentManager;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.ok(tasks.list(
                principal.id(), canReadAll(principal), status, source, query, limit, offset)));
    }

    @GetMapping("/{taskId}")
    public Mono<ResponseEntity<?>> get(@PathVariable String taskId,
                                       @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> readable(taskId, principal));
    }

    @GetMapping("/{taskId}/runs")
    public Mono<ResponseEntity<?>> runs(@PathVariable String taskId,
                                        @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            ResponseEntity<?> access = readable(taskId, principal);
            if (!access.getStatusCode().is2xxSuccessful()) return access;
            return ResponseEntity.ok(tasks.runs(taskId));
        });
    }

    @GetMapping("/{taskId}/events")
    public Mono<ResponseEntity<?>> events(@PathVariable String taskId,
                                          @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            ResponseEntity<?> access = readable(taskId, principal);
            if (!access.getStatusCode().is2xxSuccessful()) return access;
            return ResponseEntity.ok(tasks.events(taskId));
        });
    }

    @GetMapping("/{taskId}/result")
    public Mono<ResponseEntity<?>> result(@PathVariable String taskId,
                                          @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            Map<String, Object> task = authorizedTask(taskId, principal);
            if (task == null) return ResponseEntity.status(403).body(Map.of("detail", "Task is not accessible"));
            if (task.isEmpty()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of(
                    "task", task,
                    "runs", tasks.runs(taskId),
                    "events", tasks.events(taskId)));
        });
    }

    @PostMapping("/{taskId}/retry")
    public Mono<ResponseEntity<?>> retry(@PathVariable String taskId,
                                         @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            Map<String, Object> task = tasks.get(taskId);
            if (task.isEmpty()) return ResponseEntity.notFound().build();
            boolean manager = principal.hasAnyPermission("agents:manage", "system:manage");
            if (!manager && !principal.id().equals(task.get("owner_user_id"))) {
                return ResponseEntity.status(403).body(Map.of("detail", "Task is not retryable by this user"));
            }
            try {
                retryCoordinator.retry(taskId, principal.id());
                return ResponseEntity.accepted().body(tasks.get(taskId));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
            } catch (IllegalStateException e) {
                return ResponseEntity.status(409).body(Map.of("detail", e.getMessage()));
            }
        });
    }

    @PostMapping("/{taskId}/cancel")
    public Mono<ResponseEntity<?>> cancel(@PathVariable String taskId,
                                          @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            Map<String, Object> task = tasks.get(taskId);
            if (task.isEmpty()) return ResponseEntity.notFound().build();
            boolean manager = principal.hasAnyPermission("agents:manage", "system:manage");
            if (!tasks.canManage(task, principal.id(), manager)) {
                return ResponseEntity.status(403).body(Map.of("detail", "Task is not cancellable by this user"));
            }
            if (tasks.isTerminal(task)) return ResponseEntity.ok(task);

            String agentId = String.valueOf(task.get("agent_id"));
            String owner = String.valueOf(task.get("owner_user_id"));
            String sessionId = String.valueOf(task.get("session_id"));
            Map<String, Object> parent = task.get("parent_task_id") == null ? Map.of()
                    : tasks.get(String.valueOf(task.get("parent_task_id")));
            String executionSession = parent.isEmpty() ? sessionId : String.valueOf(parent.get("session_id"));
            boolean executionCancelled = agentRunner.cancel(agentId, owner, executionSession);
            boolean approvalCancelled = approvalService.cancelPendingApproval(agentId, executionSession, owner);
            boolean interrupted = false;
            try {
                HarnessAgent agent = multiAgentManager.getAgent(agentId);
                if (agent != null) {
                    agent.getDelegate().interrupt(owner, executionSession);
                    interrupted = true;
                }
            } catch (RuntimeException ignored) {
                // Subscription cancellation above remains authoritative.
            }
            tasks.cancel(taskId, String.valueOf(task.get("run_id")), "cancelled by " + principal.id());
            Map<String, Object> result = new LinkedHashMap<>(tasks.get(taskId));
            result.put("execution_cancelled", executionCancelled);
            result.put("approval_cancelled", approvalCancelled);
            result.put("interrupted", interrupted);
            return ResponseEntity.ok(result);
        });
    }

    private ResponseEntity<?> readable(String taskId, EnterprisePrincipal principal) {
        Map<String, Object> task = authorizedTask(taskId, principal);
        if (task == null) return ResponseEntity.status(403).body(Map.of("detail", "Task is not accessible"));
        return task.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(task);
    }

    /** null means forbidden; an empty map means not found. */
    private Map<String, Object> authorizedTask(String taskId, EnterprisePrincipal principal) {
        Map<String, Object> task = tasks.get(taskId);
        return tasks.canRead(task, principal.id(), canReadAll(principal)) ? task : (task.isEmpty() ? task : null);
    }

    private boolean canReadAll(EnterprisePrincipal principal) {
        return principal.hasAnyPermission("audit:read", "agents:manage", "system:manage");
    }

    private <T> Mono<T> blocking(Callable<T> task) {
        return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
    }
}
