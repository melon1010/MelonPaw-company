package com.melon.app.controller;

import com.melon.app.auth.EnterpriseAgentAccessService;
import com.melon.app.service.BuiltinSkillService;
import com.melon.app.service.SecuritySettingsService;
import com.melon.app.service.SkillService;
import com.melon.core.agent.MultiAgentManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SkillControllerOutboundPolicyTest {

    @Test
    void rejectsPrivateInitialSkillUrl() {
        assertThrows(SecurityException.class,
                () -> SkillController.requirePublicSkillUri("http://127.0.0.1/private.zip"));
        assertThrows(SecurityException.class,
                () -> SkillController.requirePublicSkillUri("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void rejectsPrivateRedirectTarget() {
        URI current = SkillController.requirePublicSkillUri("https://8.8.8.8/releases/skill.zip");

        assertThrows(SecurityException.class,
                () -> SkillController.resolvePublicSkillRedirect(
                        current, "http://10.0.0.5/internal/skill.zip"));
    }

    @Test
    void rejectsHttpsDowngradeRedirect() {
        URI current = SkillController.requirePublicSkillUri("https://8.8.8.8/releases/skill.zip");

        assertThrows(SecurityException.class,
                () -> SkillController.resolvePublicSkillRedirect(
                        current, "http://8.8.8.8/releases/skill.zip"));
    }

    @Test
    void acceptsPublicRelativeRedirect() {
        URI current = SkillController.requirePublicSkillUri("https://8.8.8.8/releases/v1/skill.zip");

        URI next = SkillController.resolvePublicSkillRedirect(current, "../v2/skill.zip");

        assertEquals("https://8.8.8.8/releases/v2/skill.zip", next.toString());
    }

    @Test
    void validatesRedirectBeforeIssuingNextRequest() throws Exception {
        String property = "melon.outbound.allow-private-for-tests";
        String previous = System.getProperty(property);
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/start", exchange -> {
            System.clearProperty(property);
            exchange.getResponseHeaders().add(
                    "Location", "http://127.0.0.1:" + port + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        System.setProperty(property, "true");
        try {
            assertThrows(SecurityException.class,
                    () -> controller().downloadRemoteZip(
                            "http://127.0.0.1:" + port + "/start"));
            assertEquals(0, targetHits.get());
        } finally {
            server.stop(0);
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private SkillController controller() {
        return new SkillController(
                mock(SkillService.class),
                mock(BuiltinSkillService.class),
                mock(SecuritySettingsService.class),
                mock(MultiAgentManager.class),
                mock(EnterpriseAgentAccessService.class));
    }
}
