package com.melon.app.controller;

import com.melon.app.config.RuntimeExecutors;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.runner.AgentSessionLogReader;
import com.melon.app.runner.ChatManager;
import com.melon.app.runner.ChatSpec;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

import static com.melon.core.util.ValueUtils.stringValue;

/**
 * melonPaw console-compatible chat metadata endpoints.
 */
@RestController
@RequestMapping("/api/chats")
public class ChatsCompatController {

    private final ChatManager chatManager;
    private final AgentSessionLogReader sessionLogReader;
    private final EnterpriseAgentAccessService agentAccessService;
    private final RuntimeExecutors executors;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatsCompatController(ChatManager chatManager, AgentSessionLogReader sessionLogReader,
                                 EnterpriseAgentAccessService agentAccessService,
                                 RuntimeExecutors executors) {
        this.chatManager = chatManager;
        this.sessionLogReader = sessionLogReader;
        this.agentAccessService = agentAccessService;
        this.executors = executors;
    }

    public ChatsCompatController(ChatManager chatManager, AgentSessionLogReader sessionLogReader,
                                 EnterpriseAgentAccessService agentAccessService) {
        this(chatManager, sessionLogReader, agentAccessService, null);
    }

    @GetMapping
    public Mono<ResponseEntity<?>> listChats(@RequestParam(value = "user_id", required = false) String userId,
                                             @RequestParam(value = "channel", required = false) String channel,
                                             @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                             @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            String ownerFilter = agentAccessService.isProjectAgent(resolvedAgentId) ? null : principal.id();
            return ResponseEntity.ok(chatManager.list(resolvedAgentId, ownerFilter, channel));
        });
    }

    @PostMapping
    public Mono<ResponseEntity<?>> createChat(@RequestBody(required = false) Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                              @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            String name = body != null ? stringValue(body.get("name"), stringValue(body.get("title"), "New Chat")) : "New Chat";
            String channel = body != null ? stringValue(body.get("channel"), "console") : "console";
            String sessionId = body != null ? stringValue(body.get("session_id"), null) : null;
            return ResponseEntity.ok(chatManager.create(resolvedAgentId, name, principal.id(), channel, sessionId));
        });
    }

    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<?>> getChat(@PathVariable String chatId,
                                           @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                           @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            ChatSpec spec = chatManager.get(resolvedAgentId, chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            requireChatAccess(resolvedAgentId, spec, principal);
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("messages", messagesFor(resolvedAgentId, spec));
            history.put("status", spec.getStatus() != null ? spec.getStatus() : "idle");
            return ResponseEntity.ok(history);
        });
    }

    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<?>> updateChat(@PathVariable String chatId, @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                              @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            ChatSpec spec = chatManager.get(resolvedAgentId, chatId);
            if (spec == null) {
                return ResponseEntity.notFound().build();
            }
            requireChatAccess(resolvedAgentId, spec, principal);
            if (body.containsKey("title")) spec.setName(String.valueOf(body.get("title")));
            if (body.containsKey("name")) spec.setName(String.valueOf(body.get("name")));
            if (body.get("pinned") instanceof Boolean pinned) spec.setPinned(pinned);
            return ResponseEntity.ok(chatManager.update(resolvedAgentId, spec));
        });
    }

    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<?>> deleteChat(@PathVariable String chatId,
                                              @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                              @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            ChatSpec spec = chatManager.get(resolvedAgentId, chatId);
            if (spec == null) return ResponseEntity.notFound().build();
            requireChatAccess(resolvedAgentId, spec, principal);
            return ResponseEntity.ok(Map.of(
                    "success", chatManager.delete(resolvedAgentId, chatId),
                    "chat_id", chatId,
                    "deleted_count", 1
            ));
        });
    }

    @PostMapping("/batch-delete")
    public Mono<ResponseEntity<?>> batchDelete(@RequestBody List<String> chatIds,
                                               @RequestHeader(value = "X-Agent-Id", defaultValue = "default") String agentId,
                                               @AuthenticationPrincipal EnterprisePrincipal principal) {
        return blocking(() -> {
            String resolvedAgentId = agentAccessService.resolveExecution(principal, agentId);
            int count = 0;
            if (chatIds != null) {
                for (String chatId : chatIds) {
                    ChatSpec spec = chatManager.get(resolvedAgentId, chatId);
                    if (spec != null) {
                        requireChatAccess(resolvedAgentId, spec, principal);
                        if (chatManager.delete(resolvedAgentId, chatId)) count++;
                    }
                }
            }
            return ResponseEntity.ok(Map.of("success", true, "deleted_count", count));
        });
    }

    private List<Map<String, Object>> messagesFor(String agentId, ChatSpec spec) {
        return sessionLogReader.readFrontendMessages(agentId, spec.getUserId(), spec.getChannel(), spec.getSessionId());
    }

    private void requireChatAccess(String agentId, ChatSpec spec, EnterprisePrincipal principal) {
        if (!agentAccessService.isProjectAgent(agentId) && !principal.id().equals(spec.getUserId())) {
            throw new SecurityException("The chat belongs to another user");
        }
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable))
                .subscribeOn(executors == null ? reactor.core.scheduler.Schedulers.boundedElastic()
                        : executors.webBlockingScheduler());
    }
}
