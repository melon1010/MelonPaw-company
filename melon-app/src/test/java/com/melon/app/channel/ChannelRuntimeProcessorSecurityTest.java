package com.melon.app.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelRuntimeProcessorSecurityTest {

    private final ChannelRuntimeProcessor processor = new ChannelRuntimeProcessor(null, null, null, null);

    @TempDir
    Path tempDir;

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

    @Test
    void decryptsAesCbcChannelAttachments() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] content = "real channel attachment".getBytes(StandardCharsets.UTF_8);
        int padding = 16 - content.length % 16;
        byte[] padded = Arrays.copyOf(content, content.length + padding);
        Arrays.fill(padded, content.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(key, 0, 16));
        Path target = tempDir.resolve("attachment.enc");
        Files.write(target, cipher.doFinal(padded));

        processor.decryptAttachmentIfRequired(target, Map.of(
                "encryption", "aes-256-cbc",
                "aes_key", Base64.getEncoder().encodeToString(key)));

        assertEquals("real channel attachment", Files.readString(target));
    }

    @Test
    void decryptsWechatAesEcbAttachmentsWithHexKeys() throws Exception {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] content = "wechat image bytes".getBytes(StandardCharsets.UTF_8);
        int padding = 16 - content.length % 16;
        byte[] padded = Arrays.copyOf(content, content.length + padding);
        Arrays.fill(padded, content.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        Path target = tempDir.resolve("wechat-image.enc");
        Files.write(target, cipher.doFinal(padded));

        processor.decryptAttachmentIfRequired(target, Map.of(
                "encryption", "aes-ecb-pkcs7",
                "aes_key", java.util.HexFormat.of().formatHex(key)));

        assertEquals("wechat image bytes", Files.readString(target));
    }
}
