import { act, fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import type { MCPClientInfo } from "../../../../api/types";
import { MCPClientCard } from "./MCPClientCard";

vi.mock("@agentscope-ai/design", async () => {
  const antd = await vi.importActual<typeof import("antd")>("antd");
  return {
    Button: antd.Button,
    Card: antd.Card,
    Input: antd.Input,
    Modal: antd.Modal,
    Tooltip: antd.Tooltip,
  };
});

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock("./MCPAccessModal", () => ({
  MCPAccessModal: () => null,
}));

vi.mock("./MCPOAuthSection", () => ({
  MCPOAuthSection: () => null,
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe("MCPClientCard", () => {
  it("does not render management controls without capability", async () => {
    const client = {
      key: "docs",
      name: "Docs MCP",
      description: "Documentation server",
      command: "npx",
      enabled: true,
      connected: true,
      transport: "stdio",
    } as MCPClientInfo;

    renderWithProviders(
      <MCPClientCard
        client={client}
        onToggle={vi.fn()}
        onDelete={vi.fn()}
        onUpdate={vi.fn()}
        onUpdatePolicy={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "mcp.tools" })).toBeNull();
    expect(screen.queryByRole("button", { name: "common.disable" })).toBeNull();
    expect(screen.queryByRole("button", { name: "common.delete" })).toBeNull();

    fireEvent.click(screen.getByText("Docs MCP"));
    expect(screen.queryByRole("button", { name: "common.edit" })).toBeNull();
  });

  it("shows connection validation progress and prevents duplicate saves", async () => {
    const pendingSave = deferred<boolean>();
    const onUpdate = vi.fn(() => pendingSave.promise);
    const client = {
      key: "docs",
      name: "Docs MCP",
      description: "Documentation server",
      command: "npx",
      enabled: true,
      connected: true,
      transport: "stdio",
    } as MCPClientInfo;

    renderWithProviders(
      <MCPClientCard
        client={client}
        canManage
        onToggle={vi.fn()}
        onDelete={vi.fn()}
        onUpdate={onUpdate}
        onUpdatePolicy={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText("Docs MCP"));
    fireEvent.click(await screen.findByRole("button", { name: "common.edit" }));
    fireEvent.click(screen.getByRole("button", { name: "common.save" }));

    const validatingButton = await screen.findByRole("button", {
      name: /mcp\.savingAndValidating/,
    });
    expect(validatingButton).toHaveClass("ant-btn-loading");
    expect(
      screen.getByRole("button", { name: "common.cancel" }),
    ).toBeDisabled();

    fireEvent.click(validatingButton);
    expect(onUpdate).toHaveBeenCalledTimes(1);

    await act(async () => {
      pendingSave.resolve(true);
    });
  });
});
