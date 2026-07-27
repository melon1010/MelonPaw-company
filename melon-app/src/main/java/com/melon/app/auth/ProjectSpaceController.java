package com.melon.app.auth;

import com.melon.app.config.RuntimeExecutors;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.Map;

/** Project spaces provide the data and authorization boundary inside one private deployment. */
@RestController
@RequestMapping({"/api/project-spaces", "/api/projects"})
public class ProjectSpaceController {

    private final EnterpriseIdentityStore store;
    private final ProjectWorkspaceService workspaceService;
    private final ProjectAgentService projectAgentService;
    private final RuntimeExecutors executors;

    public ProjectSpaceController(
            EnterpriseIdentityStore store,
            ProjectWorkspaceService workspaceService) {
        this(store, workspaceService, null, null);
    }

    @Autowired
    public ProjectSpaceController(
            EnterpriseIdentityStore store,
            ProjectWorkspaceService workspaceService,
            ProjectAgentService projectAgentService,
            RuntimeExecutors executors) {
        this.store = store;
        this.workspaceService = workspaceService;
        this.projectAgentService = projectAgentService;
        this.executors = executors;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list(@AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.ok(
                store.listProjects(principal).stream()
                        .map(EnterpriseIdentityStore.ProjectSpaceRecord::toMap)
                        .toList()));
    }

    @GetMapping("/{projectId}")
    public Mono<ResponseEntity<?>> get(
            @PathVariable String projectId, @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> store.findProject(projectId, principal)
                .<ResponseEntity<?>>map(project -> ResponseEntity.ok(project.toMap()))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('projects:manage')")
    public Mono<ResponseEntity<?>> create(
            @RequestBody Map<String, Object> body, @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.status(201).body(store.createProject(
                text(body, "code"),
                text(body, "name"),
                text(body, "description"),
                principal.id()
        ).toMap()));
    }

    @PutMapping("/{projectId}")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireProjectManager(projectId, principal);
            Boolean enabled = body != null && body.containsKey("enabled")
                    ? Boolean.parseBoolean(String.valueOf(body.get("enabled")))
                    : null;
            return ResponseEntity.ok(store.updateProject(
                    projectId,
                    nullableText(body, "name"),
                    nullableText(body, "description"),
                    enabled
            ).toMap());
        });
    }

    @GetMapping("/{projectId}/members")
    public Mono<ResponseEntity<?>> members(
            @PathVariable String projectId, @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            if (store.findProject(projectId, principal).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(store.listProjectMembers(projectId).stream()
                    .map(EnterpriseIdentityStore.ProjectMemberRecord::toMap)
                    .toList());
        });
    }

    @PutMapping("/{projectId}/members/{userId}")
    public Mono<ResponseEntity<?>> putMember(
            @PathVariable String projectId,
            @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireProjectManager(projectId, principal);
            return ResponseEntity.ok(store.putProjectMember(
                    projectId, userId, text(body, "project_role")).toMap());
        });
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public Mono<ResponseEntity<?>> deleteMember(
            @PathVariable String projectId,
            @PathVariable String userId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireProjectManager(projectId, principal);
            store.deleteProjectMember(projectId, userId);
            return ResponseEntity.ok(Map.of("deleted", true, "user_id", userId));
        });
    }

    @GetMapping("/{projectId}/workspace")
    public Mono<ResponseEntity<?>> workspace(
            @PathVariable String projectId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            EnterpriseIdentityStore.ProjectSpaceRecord project = store.findProject(projectId, principal)
                    .orElse(null);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                    "project_id", projectId,
                    "editable", canEditAnyWorkspaceDocument(project, principal),
                    "documents", workspaceService.list(projectId)
            ));
        });
    }

    @PutMapping("/{projectId}/workspace/{fileName}")
    public Mono<ResponseEntity<?>> updateWorkspaceDocument(
            @PathVariable String projectId,
            @PathVariable String fileName,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            EnterpriseIdentityStore.ProjectSpaceRecord project = store.findProject(projectId, principal)
                    .orElse(null);
            if (project == null) {
                return ResponseEntity.notFound().build();
            }
            if (!canEditWorkspaceDocument(project, principal, fileName)) {
                return ResponseEntity.status(403).body(Map.of(
                        "detail", "Project workspace is read-only for this member"));
            }
            String mutationAgentId = projectAgentService != null
                    ? projectAgentService.beginResourceMutation(projectId) : null;
            String previous = "";
            try {
                previous = workspaceService.list(projectId).stream()
                        .filter(document -> fileName.equalsIgnoreCase(String.valueOf(document.get("name"))))
                        .map(document -> String.valueOf(document.getOrDefault("content", "")))
                        .findFirst()
                        .orElse("");
                Map<String, Object> updated = workspaceService.write(
                        projectId, fileName, rawText(body, "content"));
                if (mutationAgentId != null) {
                    try {
                        projectAgentService.publishResourceMutation(
                                mutationAgentId, principal, "Project document updated: " + fileName);
                    } catch (IOException | RuntimeException e) {
                        try {
                            workspaceService.write(projectId, fileName, previous);
                        } catch (IOException restoreError) {
                            e.addSuppressed(restoreError);
                        }
                        throw e;
                    }
                }
                return ResponseEntity.ok(updated);
            } finally {
                if (mutationAgentId != null) {
                    projectAgentService.endResourceMutation(mutationAgentId);
                }
            }
        });
    }

    @GetMapping("/{projectId}/agent")
    public Mono<ResponseEntity<?>> projectAgent(
            @PathVariable String projectId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            EnterpriseIdentityStore.ProjectSpaceRecord project = store.findProject(projectId, principal)
                    .orElse(null);
            if (project == null) return ResponseEntity.notFound().build();
            Map<String, Object> agent = projectAgentService.get(projectId);
            Map<String, Object> result = new java.util.LinkedHashMap<>(agent);
            result.put("configured", !agent.isEmpty());
            result.put("can_manage", store.canManageProject(projectId, principal));
            result.put("can_execute", project.enabled()
                    && !"VIEWER".equals(project.memberRole())
                    && principal.hasPermission("agents:use"));
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/{projectId}/agent")
    public Mono<ResponseEntity<?>> createProjectAgent(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return projectAgentMutation(() -> {
            requireProjectManager(projectId, principal);
            return ResponseEntity.status(201).body(
                    projectAgentService.create(projectId, body, principal));
        });
    }

    @PutMapping("/{projectId}/agent")
    public Mono<ResponseEntity<?>> updateProjectAgent(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return projectAgentMutation(() -> {
            requireProjectManager(projectId, principal);
            return ResponseEntity.ok(projectAgentService.update(projectId, body, principal));
        });
    }

    @GetMapping("/{projectId}/agent/versions")
    public Mono<ResponseEntity<?>> projectAgentVersions(
            @PathVariable String projectId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            if (store.findProject(projectId, principal).isEmpty()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(projectAgentService.versions(projectId));
        });
    }

    @PostMapping("/{projectId}/agent/versions/{versionId}/rollback")
    public Mono<ResponseEntity<?>> rollbackProjectAgent(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return projectAgentMutation(() -> {
            requireProjectManager(projectId, principal);
            return ResponseEntity.ok(projectAgentService.rollback(projectId, versionId, principal));
        });
    }

    private boolean canEditAnyWorkspaceDocument(
            EnterpriseIdentityStore.ProjectSpaceRecord project,
            EnterprisePrincipal principal) {
        if (principal.hasPermission("projects:manage")) {
            return true;
        }
        return "OWNER".equals(project.memberRole())
                || "MANAGER".equals(project.memberRole())
                || "MEMBER".equals(project.memberRole());
    }

    private boolean canEditWorkspaceDocument(
            EnterpriseIdentityStore.ProjectSpaceRecord project,
            EnterprisePrincipal principal,
            String fileName) {
        if (principal.hasPermission("projects:manage")
                || "OWNER".equals(project.memberRole())
                || "MANAGER".equals(project.memberRole())) {
            return true;
        }
        return "MEMBER".equals(project.memberRole())
                && "KNOWLEDGE.md".equalsIgnoreCase(fileName == null ? "" : fileName.trim());
    }

    private void requireProjectManager(String projectId, EnterprisePrincipal principal) {
        if (!store.canManageProject(projectId, principal)) {
            throw new SecurityException("Project manager permission is required");
        }
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable))
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    private Mono<ResponseEntity<?>> projectAgentMutation(
            java.util.concurrent.Callable<ResponseEntity<?>> callable) {
        return blocking(callable).onErrorResume(IllegalStateException.class,
                error -> Mono.just(ResponseEntity.status(409).body(Map.of("detail", error.getMessage()))))
                .onErrorResume(java.io.IOException.class,
                        error -> Mono.just(ResponseEntity.badRequest().body(
                                Map.of("detail", error.getMessage()))));
    }

    private String nullableText(Map<String, Object> body, String key) {
        return body == null || !body.containsKey(key) ? null : text(body, key);
    }

    private String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
    }

    private String rawText(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key));
    }
}
