/** CDN URLs for channel logos — used as primary icon with letter-avatar fallback. */
export const CHANNEL_ICON_URLS: Record<string, string> = {
  dingtalk:
    "https://gw.alicdn.com/imgextra/i4/O1CN01g1u9vB1KdEreWzDdv_!!6000000001186-2-tps-400-400.png",
  qq: "https://gw.alicdn.com/imgextra/i3/O1CN014wGNgd27PsTzAyrcj_!!6000000007790-2-tps-400-400.png",
  feishu:
    "https://gw.alicdn.com/imgextra/i4/O1CN01jsn08m225euyUoaFN_!!6000000007069-2-tps-400-400.png",
  xiaoyi:
    "https://gw.alicdn.com/imgextra/i1/O1CN01EPS9Z81OKhIEcwpCd_!!6000000001687-2-tps-476-476.png",
  console:
    "https://gw.alicdn.com/imgextra/i3/O1CN01L3azqd1XIi7O2jumZ_!!6000000002901-2-tps-400-400.png",
  wecom:
    "https://gw.alicdn.com/imgextra/i1/O1CN01HWtzmr1hkK9beQICJ_!!6000000004315-2-tps-400-400.png",
  wechat:
    "https://gw.alicdn.com/imgextra/i4/O1CN01GsAob11fkfDWVIb3R_!!6000000004045-2-tps-400-400.png",
  onebot:
    "https://gw.alicdn.com/imgextra/i3/O1CN01xqM0EN1oKrRiAFX3K_!!6000000005207-2-tps-400-400.png",
  yuanbao:
    "https://img.alicdn.com/imgextra/i4/O1CN0164yBmJ1a2AftSglge_!!6000000003271-2-tps-225-225.png",
};

export const CHANNEL_DEFAULT_ICON_URL =
  "https://gw.alicdn.com/imgextra/i3/O1CN01xqM0EN1oKrRiAFX3K_!!6000000005207-2-tps-400-400.png";

/** Get the CDN icon URL for a channel, with a default fallback. */
export function getChannelIconUrl(channelKey: string): string {
  return CHANNEL_ICON_URLS[channelKey] ?? CHANNEL_DEFAULT_ICON_URL;
}

/** Predefined background colors for letter-avatar icons. */
const LETTER_ICON_COLORS: Record<string, string> = {
  console: "#176B5D",
  onebot: "#6ECB63",
  dingtalk: "#3370FF",
  feishu: "#3370FF",
  qq: "#12B7F5",
  wecom: "#07C160",
  wechat: "#07C160",
  xiaoyi: "#CF1322",
  yuanbao: "#1677FF",
};

/** A palette of fallback colors for channels without a predefined color. */
const FALLBACK_COLORS = [
  "#FF6B6B",
  "#4ECDC4",
  "#45B7D1",
  "#96CEB4",
  "#FFEAA7",
  "#DDA0DD",
  "#98D8C8",
  "#F7DC6F",
  "#BB8FCE",
  "#85C1E9",
  "#F0B27A",
  "#82E0AA",
];

/** Get the background color for a channel's letter-avatar icon. */
export function getChannelLetterColor(channelKey: string): string {
  if (LETTER_ICON_COLORS[channelKey]) {
    return LETTER_ICON_COLORS[channelKey];
  }
  // Deterministic fallback based on string hash
  let hash = 0;
  for (let i = 0; i < channelKey.length; i++) {
    hash = ((hash << 5) - hash + channelKey.charCodeAt(i)) | 0;
  }
  return FALLBACK_COLORS[Math.abs(hash) % FALLBACK_COLORS.length];
}

/** Get the display letter(s) for a channel's letter-avatar icon. */
export function getChannelLetter(channelKey: string): string {
  return channelKey.charAt(0).toUpperCase();
}
