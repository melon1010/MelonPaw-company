package com.melon.app.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelRuntimeProcessorSecurityTest {

    private final ChannelRuntimeProcessor processor = new ChannelRuntimeProcessor(null, null, null, null);

    @Test
    void blocksPrivateAndUnsupportedAttachmentTargets() {
        assertThrows(SecurityException.class,
                () -> processor.validatedRemoteUri("http://127.0.0.1/internal"));
        assertThrows(SecurityException.class,
                () -> processor.validatedRemoteUri("http://169.254.169.254/latest/meta-data"));
        assertThrows(IllegalArgumentException.class,
                () -> processor.validatedRemoteUri("file:///etc/passwd"));
    }

    @Test
    void acceptsPublicHttpTargets() throws Exception {
        assertEquals("https", processor.validatedRemoteUri("https://8.8.8.8/file.txt").getScheme());
    }
}
