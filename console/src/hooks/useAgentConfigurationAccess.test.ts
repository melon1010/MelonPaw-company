import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const hoisted = vi.hoisted(() => ({
  api: { getAgentCapabilities: vi.fn() },
  store: {
    selectedAgent: "default",
    agents: [] as Array<{ id: string; scope: "PERSONAL" | "PROJECT" }>,
  },
}));

vi.mock("../api", () => ({
  __esModule: true,
  default: hoisted.api,
}));

vi.mock("../stores/agentStore", () => ({
  useAgentStore: () => hoisted.store,
}));

import { useAgentConfigurationAccess } from "./useAgentConfigurationAccess";

describe("useAgentConfigurationAccess", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hoisted.store.selectedAgent = "default";
    hoisted.store.agents = [];
  });

  it("keeps the current user's personal workspace manageable without discovery", () => {
    hoisted.store.selectedAgent = "personal-user-1";

    const { result } = renderHook(() => useAgentConfigurationAccess());

    expect(result.current.isPersonalScope).toBe(true);
    expect(result.current.canManageConfiguration).toBe(true);
    expect(hoisted.api.getAgentCapabilities).not.toHaveBeenCalled();
  });

  it("uses the server capability for a project workspace", async () => {
    hoisted.store.selectedAgent = "project-agent";
    hoisted.store.agents = [{ id: "project-agent", scope: "PROJECT" }];
    hoisted.api.getAgentCapabilities.mockResolvedValue({ can_manage: true });

    const { result } = renderHook(() => useAgentConfigurationAccess());

    expect(result.current.canManageConfiguration).toBe(false);
    await waitFor(() =>
      expect(result.current.canManageConfiguration).toBe(true),
    );
  });

  it("fails closed when project capability discovery fails", async () => {
    hoisted.store.selectedAgent = "project-agent";
    hoisted.store.agents = [{ id: "project-agent", scope: "PROJECT" }];
    hoisted.api.getAgentCapabilities.mockRejectedValue(new Error("offline"));
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});

    const { result } = renderHook(() => useAgentConfigurationAccess());

    await waitFor(() =>
      expect(hoisted.api.getAgentCapabilities).toHaveBeenCalledTimes(1),
    );
    expect(result.current.canManageConfiguration).toBe(false);
    warning.mockRestore();
  });

  it("does not reuse a previous project's capability while switching scopes", async () => {
    hoisted.store.selectedAgent = "project-one";
    hoisted.store.agents = [
      { id: "project-one", scope: "PROJECT" },
      { id: "project-two", scope: "PROJECT" },
    ];
    hoisted.api.getAgentCapabilities.mockResolvedValueOnce({
      can_manage: true,
    });

    const { result, rerender } = renderHook(() =>
      useAgentConfigurationAccess(),
    );
    await waitFor(() =>
      expect(result.current.canManageConfiguration).toBe(true),
    );

    hoisted.store.selectedAgent = "project-two";
    hoisted.api.getAgentCapabilities.mockReturnValueOnce(new Promise(() => {}));
    rerender();

    expect(result.current.canManageConfiguration).toBe(false);
  });
});
