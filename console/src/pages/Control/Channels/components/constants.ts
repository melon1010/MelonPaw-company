import type { TFunction } from "i18next";

// Channel key type - now accepts any string for custom channels
export type ChannelKey = string;

// Built-in channel labels
export const CHANNEL_LABELS: Record<string, string> = {
  dingtalk: "DingTalk",
  feishu: "Feishu",
  qq: "QQ",
  console: "Console",
  wecom: "WeCom",
  xiaoyi: "XiaoYi",
  wechat: "WeChat",
  onebot: "OneBot",
  yuanbao: "Yuanbao",
};

function formatCustomChannelKey(key: string): string {
  return key
    .split(/[_-]/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

// Per-locale strings under `channels.channelNames.*`; missing keys use `defaultValue` (English labels).
export function getChannelLabel(key: string, t?: TFunction): string {
  const english = CHANNEL_LABELS[key] ?? formatCustomChannelKey(key);
  if (t) {
    return t(`channels.channelNames.${key}`, { defaultValue: english });
  }
  return english;
}
