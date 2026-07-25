package com.melon.app.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertNull;

class ToolControllerAuthorizationTest {

    @Test
    void personalToolsDoNotRequireAClassLevelManagementPermission() {
        PreAuthorize authorization = ToolController.class.getAnnotation(PreAuthorize.class);

        assertNull(authorization);
    }
}
