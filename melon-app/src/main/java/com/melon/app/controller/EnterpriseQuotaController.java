package com.melon.app.controller;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.config.RuntimeExecutors;
import com.melon.app.task.EnterpriseQuotaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** System management API for enterprise, user, project and Agent quota policies. */
@RestController
@RequestMapping("/api/quotas")
@PreAuthorize("hasAuthority('quotas:manage')")
public class EnterpriseQuotaController {

    private final EnterpriseQuotaService quotaService;
    private final RuntimeExecutors executors;

    public EnterpriseQuotaController(EnterpriseQuotaService quotaService) {
        this(quotaService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseQuotaController(EnterpriseQuotaService quotaService, RuntimeExecutors executors) {
        this.quotaService = quotaService;
        this.executors = executors;
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list() {
        return blocking(() -> ResponseEntity.ok(Map.of(
                "defaults", quotaService.defaults(),
                "policies", quotaService.listPolicies())));
    }

    @PutMapping("/{scopeType}/{scopeId}")
    public Mono<ResponseEntity<?>> save(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> ResponseEntity.ok(
                quotaService.savePolicy(scopeType, scopeId, body, principal.id())));
    }

    @DeleteMapping("/{scopeType}/{scopeId}")
    public Mono<ResponseEntity<?>> delete(
            @PathVariable String scopeType,
            @PathVariable String scopeId) {
        return blocking(() -> ResponseEntity.ok(Map.of(
                "deleted", quotaService.deletePolicy(scopeType, scopeId))));
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable))
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }
}
