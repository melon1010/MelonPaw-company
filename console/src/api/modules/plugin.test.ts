import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("../config", () => ({
  getApiUrl: vi.fn((p: string) => "http://test" + p),
}));
vi.mock("../authHeaders", () => ({
  buildAuthHeaders: vi.fn(() => ({})),
}));

import {
  fetchPlugins,
  installPlugin,
  uploadPlugin,
  uninstallPlugin,
} from "./plugin";
import { buildAuthHeaders } from "../authHeaders";

interface MockResponseOptions {
  ok: boolean;
  status: number;
  json?: unknown;
  text?: string;
}

function mockResponse({
  ok,
  status,
  json,
  text,
}: MockResponseOptions): Response {
  return {
    ok,
    status,
    json: async () => json,
    text: async () => text ?? "",
  } as unknown as Response;
}

describe("plugin module", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("fetchPlugins returns parsed list on success", async () => {
    const plugins = [{ id: "p1", name: "Plugin One" }];
    global.fetch = vi
      .fn()
      .mockResolvedValue(
        mockResponse({ ok: true, status: 200, json: plugins }),
      );

    const result = await fetchPlugins();

    expect(result).toEqual(plugins);
    expect(fetch).toHaveBeenCalledWith("http://test/plugins", {
      headers: {},
    });
  });

  it("fetchPlugins returns empty array on failure and does not throw", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    global.fetch = vi
      .fn()
      .mockResolvedValue(mockResponse({ ok: false, status: 500, json: {} }));

    const result = await fetchPlugins();

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith(
      "[plugin] Failed to fetch plugin list:",
      500,
    );
  });

  it("installPlugin posts JSON with force defaulting to false", async () => {
    const res = { id: "p1", name: "n", message: "ok" };
    global.fetch = vi
      .fn()
      .mockResolvedValue(mockResponse({ ok: true, status: 200, json: res }));

    const result = await installPlugin("/local/path");

    expect(result).toEqual(res);
    expect(fetch).toHaveBeenCalledWith("http://test/plugins/install", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ source: "/local/path", force: false }),
    });
  });

  it("installPlugin forwards force when provided and throws detail on failure", async () => {
    global.fetch = vi.fn().mockResolvedValue(
      mockResponse({
        ok: false,
        status: 400,
        json: { detail: "Already installed" },
      }),
    );

    await expect(installPlugin("http://x/y", { force: true })).rejects.toThrow(
      "Already installed",
    );
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(JSON.parse(init.body as string)).toEqual({
      source: "http://x/y",
      force: true,
    });
  });

  it("uploadPlugin posts FormData and returns json on success", async () => {
    const res = { id: "p1", name: "n" };
    global.fetch = vi
      .fn()
      .mockResolvedValue(mockResponse({ ok: true, status: 200, json: res }));

    const file = new File(["zip"], "p.zip", { type: "application/zip" });
    const result = await uploadPlugin(file);

    expect(result).toEqual(res);
    expect(buildAuthHeaders).toHaveBeenCalled();
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(url).toBe("http://test/plugins/upload");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
  });

  it("uninstallPlugin resolves void on success and DELETEs by id", async () => {
    global.fetch = vi
      .fn()
      .mockResolvedValue(mockResponse({ ok: true, status: 204, json: null }));

    await expect(uninstallPlugin("p1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("http://test/plugins/p1", {
      method: "DELETE",
      headers: {},
    });
  });

  it("uninstallPlugin throws fallback message on failure", async () => {
    global.fetch = vi
      .fn()
      .mockResolvedValue(mockResponse({ ok: false, status: 500, json: {} }));

    await expect(uninstallPlugin("p1")).rejects.toThrow(
      "Uninstall failed (500)",
    );
  });
});
