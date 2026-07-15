import { describe, it, expect } from "vitest";
import {
  getChannelIconUrl,
  getChannelLetterColor,
  CHANNEL_DEFAULT_ICON_URL,
} from "./channelIcons";

describe("getChannelIconUrl", () => {
  it("returns specific CDN URL for known channel 'dingtalk'", () => {
    const url = getChannelIconUrl("dingtalk");
    expect(url).toMatch(/^https:\/\/gw\.alicdn\.com/);
    expect(url).toBe(
      "https://gw.alicdn.com/imgextra/i4/O1CN01g1u9vB1KdEreWzDdv_!!6000000001186-2-tps-400-400.png",
    );
  });

  it("returns specific CDN URL for known channel 'wechat'", () => {
    const url = getChannelIconUrl("wechat");
    expect(url).toBe(
      "https://gw.alicdn.com/imgextra/i4/O1CN01GsAob11fkfDWVIb3R_!!6000000004045-2-tps-400-400.png",
    );
  });

  it("returns CHANNEL_DEFAULT_ICON_URL for unknown channel", () => {
    const url = getChannelIconUrl("unknown_channel");
    expect(url).toBe(CHANNEL_DEFAULT_ICON_URL);
  });

  it("CHANNEL_DEFAULT_ICON_URL is a non-empty string starting with 'https://'", () => {
    expect(CHANNEL_DEFAULT_ICON_URL).toBeTruthy();
    expect(typeof CHANNEL_DEFAULT_ICON_URL).toBe("string");
    expect(CHANNEL_DEFAULT_ICON_URL).toMatch(/^https:\/\//);
  });
});

describe("getChannelLetterColor", () => {
  it("returns the company color for known channel 'console'", () => {
    expect(getChannelLetterColor("console")).toBe("#176B5D");
  });

  it("returns predefined color '#07C160' for known channel 'wechat'", () => {
    expect(getChannelLetterColor("wechat")).toBe("#07C160");
  });

  it("returns a color string starting with '#' for unknown channel 'my_custom_bot'", () => {
    const color = getChannelLetterColor("my_custom_bot");
    expect(typeof color).toBe("string");
    expect(color).toMatch(/^#/);
  });

  it("returns the same color on repeated calls for the same unknown channel (deterministic hash)", () => {
    const color1 = getChannelLetterColor("my_custom_bot");
    const color2 = getChannelLetterColor("my_custom_bot");
    expect(color1).toBe(color2);
  });
});
