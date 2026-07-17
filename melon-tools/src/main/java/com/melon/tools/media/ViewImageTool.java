package com.melon.tools.media;

import reactor.core.publisher.Mono;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.core.util.SafePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads an image file and returns it as a base64-encoded block for the LLM.
 * Corresponds to Python view_image tool.
 */
public class ViewImageTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(ViewImageTool.class);
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024; // 10MB

    private final Path workspaceDir;

    public ViewImageTool(Path workspaceDir) {
        super(ToolBase.builder()
            .name("view_image")
            .description("Read an image file from the workspace and return it as base64-encoded data for visual analysis.")
            .inputSchema(parseSchema("""
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "Path to the image file (relative to workspace or absolute)"
                    }
                  },
                  "required": ["file_path"]
                }"""))
            .readOnly(true)
            .concurrencySafe(true));
        this.workspaceDir = workspaceDir;
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String filePath = (String) param.getInput().get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return Mono.just(error("file_path is required"));
        }

        Path resolved = SafePathUtil.resolveSafe(workspaceDir, filePath);
        if (resolved == null) {
            return Mono.just(error("Path traversal detected: " + filePath));
        }
        if (!Files.exists(resolved)) {
            return Mono.just(error("File not found: " + filePath));
        }
        if (!Files.isRegularFile(resolved)) {
            return Mono.just(error("Not a regular file: " + filePath));
        }

        try {
            long size = Files.size(resolved);
            if (size > MAX_IMAGE_SIZE) {
                return Mono.just(error("Image too large (" + size + " bytes). Max: " + MAX_IMAGE_SIZE));
            }

            byte[] data = Files.readAllBytes(resolved);
            String base64 = Base64.getEncoder().encodeToString(data);
            String mimeType = guessMimeType(resolved.getFileName().toString());
            ImageBlock image = ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mimeType).data(base64).build())
                    .build();
            TextBlock summary = TextBlock.builder()
                    .text("Image loaded: " + resolved.getFileName() + " (" + size + " bytes, " + mimeType + ")")
                    .build();
            return Mono.just(ToolResultBlock.of(List.of(summary, image)));
        } catch (Exception e) {
            log.error("Failed to read image: {}", filePath, e);
            return Mono.just(error("Failed to read image: " + e.getMessage()));
        }
    }

    private ToolResultBlock error(String message) {
        return ToolResultBlock.error(message).withState(ToolResultState.ERROR);
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
