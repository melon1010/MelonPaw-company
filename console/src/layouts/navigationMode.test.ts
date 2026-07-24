import { describe, expect, it } from "vitest";
import { shouldUseSettingsNavigation } from "./navigationMode";

const appPrimaryEntries = [{ path: "/projects" }, { path: "/cron-jobs" }];

describe("shouldUseSettingsNavigation", () => {
  it.each(["/skills", "/mcp", "/agent-config", "/members", "/models"])(
    "restores the settings sidebar for %s",
    (path) => {
      expect(shouldUseSettingsNavigation(path, appPrimaryEntries)).toBe(true);
    },
  );

  it.each([
    "/",
    "/chat",
    "/chat/session-1",
    "/projects",
    "/projects/p1",
    "/cron-jobs",
  ])("keeps the application sidebar for %s", (path) => {
    expect(shouldUseSettingsNavigation(path, appPrimaryEntries)).toBe(false);
  });
});
