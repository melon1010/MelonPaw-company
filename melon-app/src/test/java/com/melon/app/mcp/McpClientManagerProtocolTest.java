package com.melon.app.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpClientManagerProtocolTest {

    @AfterEach
    void clearMode() {
        System.clearProperty("MELON_MCP_STDIO_MODE");
    }

    @Test
    void supportsHttpAndLocalStdioProtocolLifecycle() throws Exception {
        System.setProperty("MELON_MCP_STDIO_MODE", "local");
        McpClientManagerSelfCheck.main(new String[0]);
    }
}
