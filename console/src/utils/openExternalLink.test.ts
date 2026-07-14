// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { downloadFileFromUrl } from "./downloadFileFromUrl";
import { openExternalLink } from "./openExternalLink";

describe("browser link and download helpers", () => {
  const windowOpen = vi.fn();
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:download"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    windowOpen.mockReset();
    vi.spyOn(window, "open").mockImplementation(windowOpen);
    window.history.replaceState(null, "", "/");
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("opens supported browser protocols", () => {
    openExternalLink("https://docs.example.com/guide");
    openExternalLink("mailto:support@example.com");

    expect(windowOpen).toHaveBeenNthCalledWith(
      1,
      "https://docs.example.com/guide",
      "_blank",
      "noopener,noreferrer",
    );
    expect(windowOpen).toHaveBeenNthCalledWith(
      2,
      "mailto:support@example.com",
      "_blank",
      "noopener,noreferrer",
    );
  });

  it("ignores unsafe, fragment-only, and ambiguous links", () => {
    openExternalLink("javascript:alert(1)");
    openExternalLink("#section");
    openExternalLink("http:example.com");

    expect(windowOpen).not.toHaveBeenCalled();
  });

  it("resolves app-relative links in the browser", () => {
    openExternalLink("/docs/faq");

    expect(windowOpen).toHaveBeenCalledWith(
      "http://localhost:3000/docs/faq",
      "_blank",
      "noopener,noreferrer",
    );
  });

  it("downloads authenticated blobs and honors response filenames", async () => {
    fetchMock.mockResolvedValue(
      new Response("zip", {
        headers: {
          "Content-Disposition": "attachment; filename*=UTF-8''server.zip",
        },
      }),
    );
    const click = vi.fn();
    const createElement = vi.spyOn(document, "createElement");
    createElement.mockImplementation((tagName: string) => {
      const element = document.createElementNS(
        "http://www.w3.org/1999/xhtml",
        tagName,
      ) as HTMLElement;
      if (tagName === "a") element.click = click;
      return element;
    });

    await downloadFileFromUrl("/api/backups/abc/export", "backup.zip", {
      headers: { Authorization: "Bearer token" },
      preferResponseFilename: true,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:3000/api/backups/abc/export",
      { headers: { Authorization: "Bearer token" } },
    );
    expect(click).toHaveBeenCalled();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:download");
  });
});
