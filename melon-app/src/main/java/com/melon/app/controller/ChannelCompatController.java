package com.melon.app.controller;

import com.melon.app.config.RuntimeExecutors;
import com.melon.channels.ChannelAccessControlStore;
import com.melon.channels.ChannelAdapterRegistry;
import com.melon.channels.ChannelConfigService;
import com.melon.channels.ChannelHealth;
import com.melon.channels.ChannelManager;
import com.melon.channels.ChannelTypes;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.ProjectAgentService;
import com.melon.app.service.ChannelWebhookSecurityService;
import com.melon.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.melon.core.util.ValueUtils.stringValue;

@RestController
@RequestMapping("/api")
public class ChannelCompatController {

    private static final Logger log = LoggerFactory.getLogger(ChannelCompatController.class);

    private final ChannelConfigService configService;
    private final ChannelManager channelManager;
    private final ChannelAccessControlStore accessControlStore;
    private final ChannelAdapterRegistry registry;
    private final ProjectAgentService projectAgentService;
    private final ChannelWebhookSecurityService webhookSecurityService;
    private final RuntimeExecutors executors;

    public ChannelCompatController(ChannelConfigService configService,
                                   ChannelManager channelManager,
                                   ChannelAccessControlStore accessControlStore,
                                   ChannelAdapterRegistry registry) {
        this(configService, channelManager, accessControlStore, registry, null, null, null);
    }

    public ChannelCompatController(ChannelConfigService configService,
                                   ChannelManager channelManager,
                                   ChannelAccessControlStore accessControlStore,
                                   ChannelAdapterRegistry registry,
                                   ProjectAgentService projectAgentService) {
        this(configService, channelManager, accessControlStore, registry, projectAgentService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelCompatController(ChannelConfigService configService,
                                   ChannelManager channelManager,
                                   ChannelAccessControlStore accessControlStore,
                                   ChannelAdapterRegistry registry,
                                   ProjectAgentService projectAgentService,
                                   ChannelWebhookSecurityService webhookSecurityService,
                                   RuntimeExecutors executors) {
        this.configService = configService;
        this.channelManager = channelManager;
        this.accessControlStore = accessControlStore;
        this.registry = registry;
        this.projectAgentService = projectAgentService;
        this.webhookSecurityService = webhookSecurityService;
        this.executors = executors;
    }

    public ChannelCompatController(ChannelConfigService configService,
                                   ChannelManager channelManager,
                                   ChannelAccessControlStore accessControlStore,
                                   ChannelAdapterRegistry registry,
                                   ProjectAgentService projectAgentService,
                                   ChannelWebhookSecurityService webhookSecurityService) {
        this(configService, channelManager, accessControlStore, registry, projectAgentService,
                webhookSecurityService, null);
    }

    @GetMapping("/config/channels/types")
    public Mono<ResponseEntity<?>> channelTypes() {
        return Mono.just(ResponseEntity.ok(configService.types()));
    }

    @GetMapping("/config/channels/types/meta")
    public Mono<ResponseEntity<?>> channelTypesMeta() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String type : configService.types()) {
            result.put(type, configService.typeMeta(type));
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    @GetMapping("/config/channels")
    public Mono<ResponseEntity<?>> channels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return blocking(() -> ResponseEntity.ok(configService.list(agent(agentId))));
    }

    @PutMapping("/config/channels")
    public Mono<ResponseEntity<?>> updateChannels(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  @AuthenticationPrincipal EnterprisePrincipal principal) {
        String currentAgent = agent(agentId);
        return blocking(() -> versionedMutation(currentAgent, principal, "Project channel configuration updated",
                        () -> configService.updateAll(currentAgent, body)))
                .flatMap(updated -> {
                    var futures = updated.entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .map(entry -> Boolean.TRUE.equals(entry.getValue().get("enabled"))
                                    ? channelManager.start(currentAgent, entry.getKey())
                                    : channelManager.stop(currentAgent, entry.getKey()))
                            .toArray(java.util.concurrent.CompletableFuture[]::new);
                    return Mono.fromFuture(java.util.concurrent.CompletableFuture.allOf(futures))
                            .thenReturn(ResponseEntity.ok(updated));
                });
    }

    @GetMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> channel(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel) {
        return blocking(() -> ResponseEntity.ok(configService.get(agent(agentId), channel)));
    }

    @PutMapping("/config/channels/{channel}")
    public Mono<ResponseEntity<?>> updateChannel(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 @AuthenticationPrincipal EnterprisePrincipal principal) {
        String currentAgent = agent(agentId);
        return blocking(() -> versionedMutation(currentAgent, principal, "Project channel configuration updated",
                        () -> configService.update(currentAgent, channel, body != null ? body : Map.of())))
                .flatMap(updated -> {
                    if (Boolean.TRUE.equals(updated.get("enabled"))) {
                        return Mono.fromFuture(channelManager.start(currentAgent, channel))
                                .thenReturn(ResponseEntity.ok(updated));
                    }
                    return Mono.fromFuture(channelManager.stop(currentAgent, channel))
                            .thenReturn(ResponseEntity.ok(updated));
                });
    }

    @PostMapping("/config/channels/{channel}/start")
    public Mono<ResponseEntity<?>> start(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                         @PathVariable String channel) {
        return Mono.fromFuture(channelManager.start(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/config/channels/{channel}/stop")
    public Mono<ResponseEntity<?>> stop(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                        @PathVariable String channel) {
        return Mono.fromFuture(channelManager.stop(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/config/channels/{channel}/restart")
    public Mono<ResponseEntity<?>> restart(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel) {
        return Mono.fromFuture(channelManager.restart(agent(agentId), channel).thenApply(ChannelHealth::toMap))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/config/channels/{channel}/health")
    public Mono<ResponseEntity<?>> health(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                          @PathVariable String channel) {
        return blocking(() -> ResponseEntity.ok(channelManager.health(agent(agentId), channel).toMap()));
    }

    @GetMapping("/config/channels/health")
    public Mono<ResponseEntity<?>> healthAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return blocking(() -> ResponseEntity.ok(channelManager.healthAll(agent(agentId))));
    }

    @GetMapping("/config/channels/{channel}/qrcode")
    public Mono<ResponseEntity<?>> channelQrcode(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel,
                                                 @RequestParam(required = false) Map<String, String> params) {
        String currentAgent = agent(agentId);
        return Mono.fromCallable(() -> registry.get(channel).qrcode(currentAgent, runtimeConfig(currentAgent, channel, params)))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    @GetMapping("/config/channels/{channel}/qrcode/status")
    public Mono<ResponseEntity<?>> channelQrcodeStatus(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestParam(required = false) String token,
                                                       @RequestParam(required = false) Map<String, String> params) {
        String currentAgent = agent(agentId);
        return Mono.fromCallable(() -> {
                    Map<String, Object> result = registry.get(channel).qrcodeStatus(currentAgent, runtimeConfig(currentAgent, channel, params), token);
                    activateChannelAfterQrSuccess(currentAgent, channel, result);
                    return result;
                })
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    private void activateChannelAfterQrSuccess(String agentId, String channel, Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        if (!"success".equals(status) && !"confirmed".equals(status)) return;
        Object rawCredentials = result.get("credentials");
        if (!(rawCredentials instanceof Map<?, ?> credentials) || credentials.isEmpty()) return;
        Map<String, Object> update = new LinkedHashMap<>();
        credentials.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (value != null && !String.valueOf(value).isBlank() && !"fail_reason".equals(name)) {
                update.put(name, value);
            }
        });
        if (update.isEmpty()) return;
        configService.updateAuthorizedCredentials(agentId, channel, update);
        channelManager.start(agentId, channel).whenComplete((health, error) -> {
            if (error != null) {
                log.warn("Channel activation after QR authorization failed: agent={}, channel={}",
                        agentId, channel, error);
            }
        });
    }

    @PostMapping("/channels/{channel}/webhook")
    public Mono<ResponseEntity<?>> webhook(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String channel,
                                           @RequestHeader Map<String, String> headers,
                                           @RequestParam(required = false) Map<String, String> params,
                                           @RequestBody(required = false) String rawBody) {
        return Mono.defer(() -> {
            String rawPayload = rawBody == null ? "" : rawBody;
            Map<String, Object> payload = rawPayload.isBlank() ? Map.of()
                    : java.util.Optional.ofNullable(JsonUtils.fromJson(rawPayload, Map.class)).orElse(Map.of());
            if (!ChannelTypes.supported(channel)) {
                return Mono.error(new IllegalArgumentException("Unsupported channel in domestic deployment: " + channel));
            }
            String currentAgent = agent(agentId);
            Map<String, Object> channelConfig = runtimeConfig(currentAgent, channel, params);
            ResponseEntity<?> challenge = challengeResponse(channel, payload);
            if (challenge != null) {
                if (webhookSecurityService == null || webhookSecurityService.validChallenge(channel, channelConfig, payload)) {
                    return Mono.just(challenge);
                }
                return Mono.error(new SecurityException("Invalid webhook challenge token"));
            }
            if (webhookSecurityService == null) {
                return Mono.error(new SecurityException("Webhook signature verifier is unavailable"));
            }
            Map<String, String> signedHeaders = new LinkedHashMap<>(headers == null ? Map.of() : headers);
            if (params != null) params.forEach(signedHeaders::putIfAbsent);
            webhookSecurityService.verify(channel, currentAgent, channelConfig, signedHeaders, rawPayload, payload);
            return Mono.fromFuture(channelManager.handleWebhook(currentAgent, channel, payload, headers))
                    .<ResponseEntity<?>>map(out -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        Map<String, Object> meta = out.getMeta() != null ? out.getMeta() : Map.of();
                        result.put("sent", !Boolean.TRUE.equals(meta.get("ignored")));
                        result.put("channel", out.getChannel());
                        result.put("session_id", out.getSessionId());
                        result.put("user_id", out.getUserId());
                        result.put("text", out.getText());
                        result.put("meta", meta);
                        return ResponseEntity.ok(result);
                    });
        }).subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    private ResponseEntity<?> challengeResponse(String channel, Map<String, Object> body) {
        Object challenge = body.get("challenge");
        if (challenge == null) return null;
        if ("feishu".equals(channel)) {
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }
        return ResponseEntity.ok(Map.of("challenge", challenge));
    }

    @GetMapping("/access-control")
    public Mono<ResponseEntity<?>> accessControlAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.all(agent(agentId))));
    }

    @GetMapping("/access-control/pending/all")
    public Mono<ResponseEntity<?>> accessControlPendingAll(@RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.pendingAll(agent(agentId))));
    }

    @GetMapping("/access-control/{channel}")
    public Mono<ResponseEntity<?>> accessControl(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String channel) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.channel(agent(agentId), channel)));
    }

    @PostMapping("/access-control/whitelist/add")
    public Mono<ResponseEntity<?>> addWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "whitelist", entries(body))));
    }

    @PostMapping("/access-control/{channel}/whitelist")
    public Mono<ResponseEntity<?>> addChannelWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "whitelist", List.of(entry(channel, body)))));
    }

    @PostMapping("/access-control/whitelist/remove")
    public Mono<ResponseEntity<?>> removeWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "whitelist", entries(body))));
    }

    @DeleteMapping("/access-control/{channel}/whitelist/{userId}")
    public Mono<ResponseEntity<?>> removeChannelWhitelist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                          @PathVariable String channel,
                                                          @PathVariable String userId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "whitelist", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/blacklist/add")
    public Mono<ResponseEntity<?>> addBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "blacklist", entries(body))));
    }

    @PostMapping("/access-control/{channel}/blacklist")
    public Mono<ResponseEntity<?>> addChannelBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                       @PathVariable String channel,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.addUsers(agent(agentId), "blacklist", List.of(entry(channel, body)))));
    }

    @PostMapping("/access-control/blacklist/remove")
    public Mono<ResponseEntity<?>> removeBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "blacklist", entries(body))));
    }

    @DeleteMapping("/access-control/{channel}/blacklist/{userId}")
    public Mono<ResponseEntity<?>> removeChannelBlacklist(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                          @PathVariable String channel,
                                                          @PathVariable String userId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.removeUsers(agent(agentId), "blacklist", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/remark")
    public Mono<ResponseEntity<?>> updateRemark(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.updateRemark(agent(agentId),
                stringValue(body != null ? body.get("channel") : null, "console"),
                stringValue(body != null ? body.get("user_id") : null),
                stringValue(body != null ? body.get("remark") : null))));
    }

    @PostMapping("/access-control/username")
    public Mono<ResponseEntity<?>> updateUsername(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.updateUsername(agent(agentId),
                stringValue(body != null ? body.get("channel") : null, "console"),
                stringValue(body != null ? body.get("user_id") : null),
                stringValue(body != null ? body.get("username") : null))));
    }

    @PostMapping("/access-control/pending/{action}")
    public Mono<ResponseEntity<?>> pendingAction(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @PathVariable String action,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), action, entries(body))));
    }

    @PostMapping("/access-control/{channel}/pending/{userId}/approve")
    public Mono<ResponseEntity<?>> approveChannelPending(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                         @PathVariable String channel,
                                                         @PathVariable String userId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), "approve", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/{channel}/pending/{userId}/deny")
    public Mono<ResponseEntity<?>> denyChannelPending(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                      @PathVariable String channel,
                                                      @PathVariable String userId) {
        return blocking(() -> ResponseEntity.ok(accessControlStore.pendingAction(agent(agentId), "deny", List.of(Map.of(
                "channel", channel,
                "user_id", userId
        )))));
    }

    @PostMapping("/access-control/pending/remark")
    public Mono<ResponseEntity<?>> pendingRemark(@RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        return updateRemark(agentId, body);
    }

    private String agent(String agentId) {
        return AgentRequestSupport.agentId(agentId);
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable))
                .subscribeOn(executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler());
    }

    private <T> T versionedMutation(String agentId, EnterprisePrincipal principal,
                                    String summary, java.util.concurrent.Callable<T> mutation) throws Exception {
        if (projectAgentService == null || principal == null) return mutation.call();
        return projectAgentService.mutateResource(agentId, principal, summary, mutation);
    }

    private Map<String, Object> runtimeConfig(String agentId, String channel, Map<String, String> params) {
        Map<String, Object> config = new LinkedHashMap<>(configService.runtimeConfig(agentId, channel));
        return config;
    }

    private Map<String, Object> entry(String channel, Map<String, Object> body) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (body != null) entry.putAll(body);
        entry.put("channel", channel);
        Object userId = entry.get("user_id");
        if (userId == null) userId = entry.get("userId");
        if (userId != null) entry.put("user_id", userId);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> body) {
        Object raw = body != null ? body.get("entries") : null;
        if (!(raw instanceof List<?> items)) return List.of();
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    ((Map<?, ?>) item).forEach((key, value) -> copy.put(String.valueOf(key), value));
                    return copy;
                })
                .toList();
    }
}
