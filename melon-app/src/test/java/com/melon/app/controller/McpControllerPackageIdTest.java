package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.auth.ProjectAgentService;
import com.melon.app.mcp.McpAccessPolicyService;
import com.melon.app.mcp.McpClientManager;
import com.melon.app.mcp.McpClientRegistry;
import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpControllerPackageIdTest {

    private static final String PACKAGE_ID = "@amap/amap-maps-mcp-server";

    @TempDir
    Path tempDir;

    @Test
    void listsAndLoadsPolicyForPackageStyleServerId() {
        ConfigManager configManager = new ConfigManager();
        configManager.setHomeDir(tempDir.toString());
        McpClientManager manager = new McpClientManager(
                configManager, tempDir.resolve("mcp-clients.json"));
        manager.register(McpClientManager.McpServerConfig.fromMap(Map.of(
                "id", PACKAGE_ID,
                "name", "Amap",
                "transport", "stdio",
                "command", "npx",
                "enabled", false)));
        McpAccessPolicyService policyService = new McpAccessPolicyService(configManager);
        policyService.save(PACKAGE_ID, Map.of("default_effect", "ask"));

        EnterpriseAgentAccessService accessService = mock(EnterpriseAgentAccessService.class);
        when(accessService.resolveExecution(any(), any())).thenReturn("default");
        EnterprisePrincipal principal = mock(EnterprisePrincipal.class);
        McpController controller = new McpController(
                new McpClientRegistry(configManager, manager),
                configManager,
                mock(MultiAgentManager.class),
                policyService,
                accessService,
                mock(ProjectAgentService.class));
        WebTestClient client = WebTestClient.bindToController(controller)
                .argumentResolvers(configurer -> configurer.addCustomResolver(
                        authenticationPrincipalResolver(principal)))
                .build();

        client.get().uri("/api/mcp")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].key").isEqualTo(PACKAGE_ID)
                .jsonPath("$[0].access_summary.default_effect").isEqualTo("ask");

        client.get().uri(builder -> builder.path("/api/mcp/client/policy")
                        .queryParam("client_key", PACKAGE_ID)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.default_effect").isEqualTo("ask");
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver(
            EnterprisePrincipal principal) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Mono<Object> resolveArgument(MethodParameter parameter,
                                                BindingContext bindingContext,
                                                ServerWebExchange exchange) {
                return Mono.just(principal);
            }
        };
    }
}
