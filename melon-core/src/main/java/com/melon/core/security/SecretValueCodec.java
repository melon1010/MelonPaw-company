package com.melon.core.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Encrypts persisted configuration secrets while keeping runtime configuration plaintext. */
public final class SecretValueCodec {

    private static final String PREFIX = "ENC[v1]:";
    private static final String MASTER_KEY_ENV = "MELON_MASTER_KEY";
    private static final String PREVIOUS_KEYS_ENV = "MELON_MASTER_KEY_PREVIOUS";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Set<String> SENSITIVE_CONTAINERS = Set.of(
            "credentials", "credential", "env", "headers", "custom_headers", "oauth",
            "extra_token_params", "project_documents", "skill_files");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "ak", "sk", "api_key", "apikey", "access_key", "secret_key", "private_key",
            "secret", "client_secret", "app_secret", "bot_secret", "bot_token", "access_token",
            "refresh_token", "verification_token", "encrypt_key", "password", "authorization",
            "cookie", "cookies");

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec currentKey;
    private final List<SecretKeySpec> decryptionKeys;

    private SecretValueCodec(byte[] current, List<byte[]> previous) {
        this.currentKey = key(current);
        List<SecretKeySpec> keys = new ArrayList<>();
        keys.add(currentKey);
        if (previous != null) previous.forEach(value -> keys.add(key(value)));
        this.decryptionKeys = List.copyOf(keys);
    }

    public static SecretValueCodec forHome(Path homeDir) {
        Path home = homeDir.toAbsolutePath().normalize();
        byte[] current = configuredKey(MASTER_KEY_ENV);
        if (current == null) current = fileKey(home.resolve("master.key"));
        return new SecretValueCodec(current, configuredPreviousKeys());
    }

    public Object protect(Object value) {
        return transform(value, false, true);
    }

    public Object unprotect(Object value) {
        return transform(value, false, false);
    }

    /** Returns true when a configuration tree still contains an unencrypted sensitive value. */
    public boolean hasPlaintextSecrets(Object value) {
        return hasPlaintextSecrets(value, false);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || plaintext.startsWith(PREFIX)) return plaintext;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt persisted secret", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || !encrypted.startsWith(PREFIX)) return encrypted;
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(encrypted.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Persisted secret has an invalid encoding", e);
        }
        if (payload.length <= IV_BYTES) throw new IllegalStateException("Persisted secret is truncated");
        byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
        for (SecretKeySpec candidate : decryptionKeys) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, candidate, new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException ignored) {
                // Try an explicitly configured previous key before failing closed.
            }
        }
        throw new IllegalStateException(
                "Cannot decrypt persisted secret; MELON_MASTER_KEY does not match this installation");
    }

    private Object transform(Object value, boolean sensitiveScope, boolean encrypt) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((rawKey, child) -> {
                String name = String.valueOf(rawKey);
                boolean sensitive = sensitiveScope || sensitiveName(name) || sensitiveContainer(name);
                result.put(name, transform(child, sensitive, encrypt));
            });
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            collection.forEach(child -> result.add(transform(child, sensitiveScope, encrypt)));
            return result;
        }
        if (value instanceof String text) {
            if (!encrypt && text.startsWith(PREFIX)) return decrypt(text);
            if (encrypt && sensitiveScope && !text.isBlank()) return encrypt(text);
        }
        return value;
    }

    private boolean hasPlaintextSecrets(Object value, boolean sensitiveScope) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                boolean sensitive = sensitiveScope || sensitiveName(name) || sensitiveContainer(name);
                if (hasPlaintextSecrets(entry.getValue(), sensitive)) return true;
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object child : collection) {
                if (hasPlaintextSecrets(child, sensitiveScope)) return true;
            }
            return false;
        }
        return sensitiveScope && value instanceof String text && !text.isBlank() && !text.startsWith(PREFIX);
    }

    private static boolean sensitiveContainer(String name) {
        return SENSITIVE_CONTAINERS.contains(normalize(name));
    }

    private static boolean sensitiveName(String name) {
        String normalized = normalize(name);
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.endsWith("_secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_token")
                || normalized.endsWith("_api_key")
                || normalized.endsWith("_private_key");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static SecretKeySpec key(byte[] raw) {
        if (raw == null || raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("Melon master keys must contain exactly 32 bytes");
        }
        return new SecretKeySpec(raw.clone(), "AES");
    }

    private static byte[] configuredKey(String environmentName) {
        String value = System.getProperty(environmentName);
        if (value == null || value.isBlank()) value = System.getenv(environmentName);
        return value == null || value.isBlank() ? null : decodeKey(value.trim());
    }

    private static List<byte[]> configuredPreviousKeys() {
        String value = System.getProperty(PREVIOUS_KEYS_ENV);
        if (value == null || value.isBlank()) value = System.getenv(PREVIOUS_KEYS_ENV);
        if (value == null || value.isBlank()) return List.of();
        List<byte[]> keys = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) keys.add(decodeKey(item.trim()));
        }
        return keys;
    }

    private static byte[] decodeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == KEY_BYTES) return decoded;
        } catch (IllegalArgumentException ignored) {
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length == KEY_BYTES) return decoded;
        } catch (IllegalArgumentException ignored) {
        }
        if (value.matches("[0-9a-fA-F]{64}")) {
            byte[] result = new byte[KEY_BYTES];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
            return result;
        }
        throw new IllegalArgumentException(
                "MELON_MASTER_KEY must be a Base64 or 64-character hexadecimal 256-bit key");
    }

    private static byte[] fileKey(Path keyFile) {
        try {
            Files.createDirectories(keyFile.getParent());
            if (Files.isRegularFile(keyFile)) {
                return decodeKey(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            }
            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
            Path temporary = keyFile.resolveSibling(keyFile.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.writeString(temporary, encoded, StandardCharsets.US_ASCII);
                restrictPermissions(temporary);
                try {
                    Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(temporary);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    try {
                        Files.move(temporary, keyFile);
                    } catch (FileAlreadyExistsException alreadyExists) {
                        Files.deleteIfExists(temporary);
                    }
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            restrictPermissions(keyFile);
            return decodeKey(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize Melon master key at " + keyFile, e);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        if (Files.exists(path) && Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }
}
