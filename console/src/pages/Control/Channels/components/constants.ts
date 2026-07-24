import type { TFunction } from "i18next";

export type ChannelKey =
  | "console"
  | "dingtalk"
  | "feishu"
  | "qq"
  | "wechat"
  | "wecom";

// Built-in channel labels
export const CHANNEL_LABELS: Record<ChannelKey, string> = {
  dingtalk: "DingTalk",
  feishu: "Feishu",
  qq: "QQ",
  console: "Console",
  wecom: "WeCom",
  wechat: "WeChat",
};

function formatCustomChannelKey(key: string): string {
  return key
    .split(/[_-]/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

// Per-locale strings under `channels.channelNames.*`; missing keys use `defaultValue` (English labels).
export function getChannelLabel(key: string, t?: TFunction): string {
  const english =
    CHANNEL_LABELS[key as ChannelKey] ?? formatCustomChannelKey(key);
  if (t) {
    return t(`channels.channelNames.${key}`, { defaultValue: english });
  }
  return english;
}
