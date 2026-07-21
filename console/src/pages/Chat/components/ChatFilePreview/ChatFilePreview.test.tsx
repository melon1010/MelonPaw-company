import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@/i18n";
import { renderWithProviders } from "@/test/common_setup";
import ChatFilePreview from ".";

vi.mock("docx-preview", () => ({
  renderAsync: vi.fn(async (_file: Blob, container: HTMLElement) => {
    const page = document.createElement("section");
    page.textContent = "Rendered DOCX content";
    container.appendChild(page);
  }),
}));

vi.mock("read-excel-file/browser", () => ({
  default: vi.fn(async () => [
    {
      sheet: "Overview",
      data: [
        ["Metric", "Value"],
        ["Output", 128],
      ],
    },
    {
      sheet: "Details",
      data: [["Completed"]],
    },
  ]),
}));

describe("ChatFilePreview", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("renders an image and closes the preview", async () => {
    const onClose = vi.fn();
    const onToggleFullWidth = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <ChatFilePreview
        file={{ name: "diagram.png", url: "/preview/diagram.png" }}
        fullWidth={false}
        onClose={onClose}
        onToggleFullWidth={onToggleFullWidth}
      />,
    );

    expect(screen.getByRole("img", { name: "diagram.png" })).toHaveAttribute(
      "src",
      "/preview/diagram.png",
    );
    await user.click(
      screen.getByRole("button", { name: "Expand preview to full width" }),
    );
    expect(onToggleFullWidth).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("loads text files into the inline preview", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers(),
        blob: async () =>
          new Blob(["const answer = 42;"], { type: "text/plain" }),
      }),
    );

    renderWithProviders(
      <ChatFilePreview
        file={{ name: "answer.ts", url: "/preview/answer.ts" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(await screen.findByText("const answer = 42;")).toBeInTheDocument();
  });

  it("keeps download in the preview header for unsupported files", () => {
    renderWithProviders(
      <ChatFilePreview
        file={{ name: "archive.zip", url: "/preview/archive.zip" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    const download = screen.getByRole("link", { name: "Download" });
    expect(download).toHaveAttribute("href", "/preview/archive.zip");
    expect(download).toHaveAttribute("download", "archive.zip");
  });

  it("renders DOCX files in the split preview", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers(),
        blob: async () => new Blob(["docx"]),
      }),
    );

    renderWithProviders(
      <ChatFilePreview
        file={{ name: "report.docx", url: "/preview/report.docx" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(
      await screen.findByText("Rendered DOCX content"),
    ).toBeInTheDocument();
  });

  it("renders XLSX worksheets and switches between them", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers(),
        blob: async () => new Blob(["xlsx"]),
      }),
    );

    renderWithProviders(
      <ChatFilePreview
        file={{ name: "metrics.xlsx", url: "/preview/metrics.xlsx" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(await screen.findByText("Output")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Details" }));
    expect(screen.getByText("Completed")).toBeInTheDocument();
  });
});
