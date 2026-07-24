package com.melon.channels;

import java.util.List;
import java.util.Set;

public final class ChannelTypes {

    /** Channels exposed by the domestic enterprise Web product. */
    public static final List<String> DOMESTIC = List.of(
            "console",
            "dingtalk",
            "feishu",
            "qq",
            "wechat",
            "wecom"
    );

    public static final List<String> BUILTIN = DOMESTIC;

    public static boolean supported(String channel) {
        return channel != null && DOMESTIC.contains(channel.trim().toLowerCase());
    }

    public static final Set<String> SECRET_KEYS = Set.of(
            "token",
            "bot_token",
            "access_token",
            "client_secret",
            "app_secret",
            "encrypt_key",
            "verification_token",
            "secret",
            "password",
            "sk",
            "dashscope_api_key",
            "auth_token"
    );

    private ChannelTypes() {
    }
}
