package com.melon.tools.util;

import com.melon.core.util.WorkspacePathResolver;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 发送文件给用户工具. 对应 Python send_file.py.
 */
public class SendFileToUserTool {

    private final WorkspacePathResolver pathResolver;

    public SendFileToUserTool() {
        this(null);
    }

    public SendFileToUserTool(String workspaceDir) {
        this.pathResolver = new WorkspacePathResolver(workspaceDir);
    }

    @Tool(name = "send_file_to_user", description = "Send a file to the user for download")
    public ToolResultBlock sendFileToUser(
            @ToolParam(name = "file_path", description = "Path to the file to send") String filePath
    ) {
        try {
            Path path = pathResolver.resolve(filePath);
            if (!Files.isRegularFile(path)) {
                return error("File not found: " + filePath);
            }
            ArtifactValidator.Result validation = ArtifactValidator.validate(path);
            if (!validation.valid()) {
                return error("File is not ready for delivery: " + validation.message());
            }
            String mimeType = Files.probeContentType(path);
            DataBlock file = DataBlock.builder()
                    .name(path.getFileName().toString())
                    .source(URLSource.builder().url(path.toUri().toString()).mimeType(mimeType).build())
                    .build();
            return ToolResultBlock.of(file);
        } catch (Exception e) {
            return error("Error sending file: " + e.getMessage());
        }
    }

    private ToolResultBlock error(String message) {
        return ToolResultBlock.error(message).withState(ToolResultState.ERROR);
    }
}
