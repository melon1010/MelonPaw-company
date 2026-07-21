import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@/i18n";
import { renderWithProviders } from "@/test/common_setup";
import { ChatFilePreviewContext } from "@/pages/Chat/components/ChatFilePreview/context";
import MediaPreview from "./MediaPreview";

vi.mock("@agentscope-ai/chat", () => ({
  Attachments: {
    FileCard: ({ item }: { item: { name: string } }) => <div>{item.name}</div>,
  },
}));

describe("MediaPreview file cards", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("opens the chat split preview when the file card is clicked", async () => {
    const user = userEvent.setup();
    const openPreview = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, status: 200 }),
    );

    renderWithProviders(
      <ChatFilePreviewContext.Provider value={openPreview}>
        <MediaPreview
          media={{
            type: "file",
            name: "report.pdf",
            url: "/preview/report.pdf",
          }}
        />
      </ChatFilePreviewContext.Provider>,
    );

    await user.click(
      screen.getByRole("button", { name: "Preview: report.pdf" }),
    );
    expect(openPreview).toHaveBeenCalledWith({
      name: "report.pdf",
      url: "/preview/report.pdf",
    });
  });
});
