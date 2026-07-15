package com.melon.app.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ToolControllerAuthorizationTest {

    @Test
    void toolManagementRequiresAgentManagementPermissionAtControllerBoundary() {
        PreAuthorize authorization = ToolController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(authorization);
        assertEquals("hasAuthority('system:manage')", authorization.value());
    }
}
