export interface BaseChannelConfig {
  enabled: boolean;
  bot_prefix: string;
  filter_tool_messages?: boolean;
  filter_thinking?: boolean;
  dm_policy?: "open" | "allowlist";
  group_policy?: "open" | "allowlist";
  allow_from?: string[];
  require_mention?: boolean;
  no_text_debounce?: boolean;
}

export type ConsoleConfig = BaseChannelConfig;

export interface DingTalkConfig extends BaseChannelConfig {
  client_id: string;
  client_secret: string;
  message_type: string;
  cron_message_type: string;
  card_template_id: string;
  card_template_key: string;
  robot_code: string;
  at_sender_on_reply?: boolean;
  streaming_enabled?: boolean;
}

export interface FeishuConfig extends BaseChannelConfig {
  app_id: string;
  app_secret: string;
  encrypt_key: string;
  verification_token: string;
  streaming_enabled?: boolean;
  share_session_in_group?: boolean;
}

export interface QQConfig extends BaseChannelConfig {
  app_id: string;
  client_secret: string;
  ack_message?: string;
  user_openid?: string;
}

export interface WecomConfig extends BaseChannelConfig {
  bot_id: string;
  secret: string;
  welcome_text?: string;
  share_session_in_group?: boolean;
  max_reconnect_attempts?: number;
  streaming_enabled?: boolean;
}

export interface XiaoYiConfig extends BaseChannelConfig {
  ak: string;
  sk: string;
  agent_id: string;
  task_timeout_ms?: number;
}

export interface WeChatConfig extends BaseChannelConfig {
  bot_token: string;
  message_merge_enabled?: boolean;
  message_merge_delay_ms?: number;
}

export interface YuanbaoConfig extends BaseChannelConfig {
  app_id: string;
  app_secret: string;
  accept_bot_messages?: boolean;
}

export interface OneBotConfig extends BaseChannelConfig {
  access_token: string;
  share_session_in_group: boolean;
}

export interface ChannelConfig {
  console: ConsoleConfig;
  dingtalk: DingTalkConfig;
  feishu: FeishuConfig;
  qq: QQConfig;
  wechat: WeChatConfig;
  wecom: WecomConfig;
  yuanbao: YuanbaoConfig;
  xiaoyi: XiaoYiConfig;
  onebot: OneBotConfig;
}

export type SingleChannelConfig = ChannelConfig[keyof ChannelConfig];
