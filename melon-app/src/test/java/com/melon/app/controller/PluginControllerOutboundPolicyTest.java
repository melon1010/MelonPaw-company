package com.melon.app.controller;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginControllerOutboundPolicyTest {

    @Test
    void rejectsLocalAndMetadataTargets() {
        assertThrows(SecurityException.class,
                () -> PluginController.requirePublicPluginUri("http://127.0.0.1/plugin.zip"));
        assertThrows(SecurityException.class,
                () -> PluginController.requirePublicPluginUri(
                        "http://169.254.169.254/latest/meta-data/plugin.zip"));
    }

    @Test
    void rejectsHttpsDowngradeAndPrivateRedirects() {
        URI current = PluginController.requirePublicPluginUri(
                "https://8.8.8.8/releases/plugin.zip");
        assertThrows(SecurityException.class,
                () -> PluginController.resolvePublicPluginRedirect(
                        current, "http://8.8.8.8/plugin.zip"));
        assertThrows(IllegalArgumentException.class,
                () -> PluginController.resolvePublicPluginRedirect(
                        current, "http://127.0.0.1/plugin.zip"));
    }

    @Test
    void resolvesPublicRelativeRedirect() {
        URI current = PluginController.requirePublicPluginUri(
                "https://8.8.8.8/releases/v1/plugin.zip");
        assertEquals("https://8.8.8.8/releases/v2/plugin.zip",
                PluginController.resolvePublicPluginRedirect(
                        current, "../v2/plugin.zip").toString());
    }
}
