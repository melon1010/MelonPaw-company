package com.melon.tools.util;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 获取当前时间工具. 对应 Python get_current_time.py.
 */
public class GetCurrentTimeTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (E)", Locale.ENGLISH);

    private ZoneId userTimezone = ZoneId.systemDefault();

    public GetCurrentTimeTool() {
    }

    public GetCurrentTimeTool(String timezone) {
        setUserTimezone(timezone);
    }

    @Tool(name = "get_current_time", description = "Get the current date and time in the user's timezone", readOnly = true, concurrencySafe = true)
    public String getCurrentTime() {
        ZonedDateTime now = ZonedDateTime.now(userTimezone);
        return now.format(FORMATTER);
    }

    public void setUserTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return;
        try {
            this.userTimezone = ZoneId.of(timezone);
        } catch (Exception ignored) {
            // Keep the last valid timezone when a user-provided value is invalid.
        }
    }
}
