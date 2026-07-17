package com.melon.app.controller;

import com.melon.app.auth.EnterprisePrincipal;
import com.melon.app.service.FileGuardService;
import com.melon.core.config.ConfigManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static com.melon.app.auth.EnterprisePermissions.SYSTEM_MANAGE;

@RestController
@RequestMapping("/api/files")
public class FilesPreviewController {

    private static final Set<String> PROTECTED_WORKSPACE_FILES = Set.of(
            "agent.json", "credentials.yaml", "chats.json", "jobs.json", "skill.json"
    );

    private final ConfigManager configManager;
    private final FileGuardService fileGuardService;

    public FilesPreviewController(ConfigManager configManager, FileGuardService fileGuardService) {
        this.configManager = configManager;
        this.fileGuardService = fileGuardService;
    }

    @RequestMapping(value = "/preview/{*filePath}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public Mono<ResponseEntity<?>> preview(
                                           @AuthenticationPrincipal EnterprisePrincipal principal,
                                           @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                           @PathVariable String filePath) {
        return Mono.fromCallable(() -> {
            Path workspace = configManager.resolveWorkspaceDir(AgentRequestSupport.agentId(agentId)).toAbsolutePath().normalize();
            Path file = resolvePreviewPath(workspace, filePath);
            fileGuardService.assertAllowed(file);
            boolean systemAdministrator = principal != null && principal.hasPermission(SYSTEM_MANAGE);
            if (!file.startsWith(workspace)) {
                return ResponseEntity.status(403).body(Map.of("detail", "OUTSIDE_WORKSPACE"));
            }
            if (!systemAdministrator && isProtectedWorkspaceFile(workspace, file)) {
                return ResponseEntity.status(403).body(Map.of("detail", "PROTECTED_WORKSPACE_FILE"));
            }
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                return ResponseEntity.notFound().build();
            }
            if (!systemAdministrator) {
                if (containsSymbolicLink(workspace, file)) {
                    return ResponseEntity.status(403).body(Map.of("detail", "SYMLINK_OUTSIDE_WORKSPACE"));
                }
            }
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, no-store")
                    .header("Referrer-Policy", "no-referrer")
                    .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                    .body(new ByteArrayResource(Files.readAllBytes(file)));
        });
    }

    private boolean containsSymbolicLink(Path workspace, Path file) {
        if (Files.isSymbolicLink(workspace)) {
            return true;
        }
        Path current = workspace;
        for (Path segment : workspace.relativize(file)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtectedWorkspaceFile(Path workspace, Path file) {
        if (!file.startsWith(workspace)) {
            return true;
        }
        Path relative = workspace.relativize(file);
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return relative.getNameCount() == 1
                && PROTECTED_WORKSPACE_FILES.contains(relative.getFileName().toString().toLowerCase());
    }

    private Path resolvePreviewPath(Path workspace, String filePath) {
        String decoded = URLDecoder.decode(cleanCapturedPath(filePath), StandardCharsets.UTF_8);
        Path path = Path.of(decoded);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        // A browser route collapses file:/// to a single captured slash. Recover the absolute
        // path only when it still belongs to this authenticated Agent workspace.
        Path root = workspace.getRoot();
        if (root != null) {
            Path absoluteCandidate = root.resolve(path).normalize();
            if (absoluteCandidate.startsWith(workspace)) {
                return absoluteCandidate;
            }
        }
        return workspace.resolve(decoded).normalize();
    }

    private String cleanCapturedPath(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
