package com.melon.app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reports the execution prerequisites without starting a sandbox. */
@Service
public class SandboxHealthService {

    private final String mode;
    private final String image;
    private final String network;

    public SandboxHealthService(
            @Value("${melon.execution.sandbox-mode:docker}") String mode,
            @Value("${MELON_SANDBOX_IMAGE:melonpaw-company-sandbox:latest}") String image,
            @Value("${MELON_SANDBOX_NETWORK:bridge}") String network) {
        this.mode = normalize(mode);
        this.image = image == null || image.isBlank() ? "melonpaw-company-sandbox:latest" : image.trim();
        this.network = network == null || network.isBlank() ? "bridge" : network.trim();
    }

    public Map<String, Object> inspect() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("image", image);
        result.put("network", network);
        if ("local".equals(mode)) {
            result.put("ready", true);
            result.put("warning", "Local execution is for development only");
            return result;
        }
        Check daemon = run("docker", "info", "--format", "{{.ServerVersion}}");
        Check imageCheck = daemon.ok() ? run("docker", "image", "inspect", image) : new Check(false, "daemon unavailable");
        result.put("docker", daemon.ok());
        result.put("docker_version", daemon.output());
        result.put("image_present", imageCheck.ok());
        result.put("ready", daemon.ok() && imageCheck.ok());
        if (!daemon.ok()) result.put("error", daemon.output());
        else if (!imageCheck.ok()) result.put("error", "Sandbox image is not available: " + image);
        return result;
    }

    private Check run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Check(false, "command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return new Check(process.exitValue() == 0, output.length() > 500 ? output.substring(0, 500) : output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(false, "command interrupted");
        } catch (IOException e) {
            return new Check(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private String normalize(String value) {
        String normalized = value == null ? "docker" : value.trim().toLowerCase();
        if (!"docker".equals(normalized) && !"local".equals(normalized)) return "docker";
        return normalized;
    }

    private record Check(boolean ok, String output) {}
}
