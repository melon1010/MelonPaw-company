package com.melon.app.auth;

import com.melon.app.config.RuntimeExecutors;
import com.melon.app.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

/** System-administrator API for persistent enterprise users. */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final AuthService authService;
    private final EnterpriseIdentityStore store;
    private final EnterpriseAgentAccessService agentAccessService;
    private final SkillService skillService;
    private final RuntimeExecutors executors;

    @Autowired
    public UserAdminController(AuthService authService, EnterpriseIdentityStore store,
                               EnterpriseAgentAccessService agentAccessService,
                               SkillService skillService,
                               RuntimeExecutors executors) {
        this.authService = authService;
        this.store = store;
        this.agentAccessService = agentAccessService;
        this.skillService = skillService;
        this.executors = executors;
    }

    public UserAdminController(AuthService authService, EnterpriseIdentityStore store,
                               EnterpriseAgentAccessService agentAccessService) {
        this(authService, store, agentAccessService, null, null);
    }

    public UserAdminController(AuthService authService, EnterpriseIdentityStore store,
                               EnterpriseAgentAccessService agentAccessService,
                               SkillService skillService) {
        this(authService, store, agentAccessService, skillService, null);
    }

    public UserAdminController(AuthService authService, EnterpriseIdentityStore store) {
        this(authService, store, null, null, null);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users:manage') or hasAuthority('projects:manage')")
    public Mono<ResponseEntity<?>> list() {
        return blocking(() -> ResponseEntity.ok(
                store.listUsers().stream().map(EnterprisePrincipal::toMap).toList()));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:manage') or hasAuthority('projects:manage')")
    public Mono<ResponseEntity<?>> get(@PathVariable String userId) {
        return blocking(() -> store.findUserById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(user.toMap()))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            Set<String> roles = stringSet(body.get("roles"));
            List<String> defaultSkills = initialSkillNames(body);
            if (skillService != null) {
                defaultSkills = skillService.validatePoolSkillNames(defaultSkills);
            } else if (!defaultSkills.isEmpty()) {
                throw new IllegalStateException("Skill service is unavailable");
            }
            if (roles.isEmpty()) {
                roles = Set.of("USER");
            }
            requireNoSystemAdminGrant(principal, roles);
            EnterprisePrincipal created = authService.createUser(
                    text(body, "username"),
                    text(body, "display_name"),
                    text(body, "password"),
                    booleanValue(body.get("enabled"), true),
                    roles
            );
            if (agentAccessService != null && created.hasAnyPermission(
                    EnterprisePermissions.AGENTS_USE,
                    EnterprisePermissions.PERSONAL_WORKSPACE,
                    EnterprisePermissions.PERSONAL_SKILLS,
                    EnterprisePermissions.PERSONAL_CHANNELS,
                    EnterprisePermissions.PERSONAL_AUTOMATIONS)) {
                try {
                    String agentId = agentAccessService.ensurePersonalAgent(created);
                    if (!defaultSkills.isEmpty()) {
                        skillService.syncPoolSkillsToWorkspace(agentId, defaultSkills);
                    }
                } catch (Exception e) {
                    rollbackProvisioning(created, principal);
                    throw e;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>(created.toMap());
            payload.put("initialized_skills", defaultSkills);
            return ResponseEntity.status(201).body(payload);
        });
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> delete(
            @PathVariable String userId,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireCanAdminister(principal, userId);
            if (principal.id().equals(userId)) {
                throw new IllegalArgumentException("Users cannot delete themselves");
            }
            EnterpriseAgentAccessService.PersonalAgentDeletion deletion = agentAccessService == null
                    ? null : agentAccessService.preparePersonalAgentDeletion(userId);
            try {
                authService.deleteUser(userId, principal.id());
            } catch (RuntimeException e) {
                if (agentAccessService != null) {
                    agentAccessService.restorePersonalAgent(deletion);
                }
                throw e;
            }
            EnterpriseAgentAccessService.PersonalAgentCleanupResult cleanup = agentAccessService == null
                    ? new EnterpriseAgentAccessService.PersonalAgentCleanupResult(false, false)
                    : agentAccessService.deletePersonalAgentFiles(deletion);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", true);
            result.put("user_id", userId);
            result.put("agent_id", deletion == null || deletion.agentId() == null ? "" : deletion.agentId());
            result.put("workspace_removed", cleanup.workspaceRemoved());
            result.put("state_removed", cleanup.stateRemoved());
            return ResponseEntity.ok(result);
        });
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> update(
            @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireCanAdminister(principal, userId);
            String displayName = nullableText(body, "display_name");
            String password = nullableText(body, "password");
            Boolean enabled = body != null && body.containsKey("enabled")
                    ? booleanValue(body.get("enabled"), true)
                    : null;
            return ResponseEntity.ok(authService.updateUser(userId, displayName, enabled, password).toMap());
        });
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('users:manage')")
    public Mono<ResponseEntity<?>> replaceRoles(
            @PathVariable String userId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            requireCanAdminister(principal, userId);
            Set<String> roles = stringSet(body == null ? null : body.get("roles"));
            requireNoSystemAdminGrant(principal, roles);
            if (!principal.hasPermission(SYSTEM_MANAGE) && principal.id().equals(userId)) {
                throw new SecurityException("Organization administrators cannot change their own roles");
            }
            return ResponseEntity.ok(store.replaceUserRoles(userId, roles).toMap());
        });
    }

    private void requireCanAdminister(EnterprisePrincipal principal, String userId) {
        if (principal.hasPermission(SYSTEM_MANAGE)) {
            return;
        }
        EnterprisePrincipal target = store.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (target.hasRole("SYSTEM_ADMIN")) {
            throw new SecurityException("Only a system administrator can modify a system administrator");
        }
    }

    private void requireNoSystemAdminGrant(EnterprisePrincipal principal, Set<String> roles) {
        if (!principal.hasPermission(SYSTEM_MANAGE) && roles.contains("SYSTEM_ADMIN")) {
            throw new SecurityException("Only a system administrator can grant the SYSTEM_ADMIN role");
        }
    }

    private void rollbackProvisioning(EnterprisePrincipal created, EnterprisePrincipal administrator) {
        EnterpriseAgentAccessService.PersonalAgentDeletion deletion =
                agentAccessService.preparePersonalAgentDeletion(created.id());
        try {
            authService.deleteUser(created.id(), administrator.id());
        } catch (RuntimeException rollbackFailure) {
            agentAccessService.restorePersonalAgent(deletion);
            throw rollbackFailure;
        }
        agentAccessService.deletePersonalAgentFiles(deletion);
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable))
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    private Set<String> stringSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim().toUpperCase());
                }
            }
        }
        return result;
    }

    private List<String> initialSkillNames(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }
        Object value = body.containsKey("default_skills")
                ? body.get("default_skills") : body.get("skill_names");
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).trim());
            }
        }
        return List.copyOf(result);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private String nullableText(Map<String, Object> body, String key) {
        return body == null || !body.containsKey(key) ? null : text(body, key);
    }

    private String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
    }
}
