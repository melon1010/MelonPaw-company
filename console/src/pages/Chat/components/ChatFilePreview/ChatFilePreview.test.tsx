import { afterEach, describe, expect, it, vi } from "vitest";
import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@/i18n";
import { renderWithProviders } from "@/test/common_setup";
import ChatFilePreview from ".";

vi.mock("@ant-design/x-markdown", () => ({
  XMarkdown: ({ content }: { content: string }) => (
    <article data-testid="markdown-preview">{content}</article>
  ),
}));

vi.mock("@/components/MermaidCodeBlock", () => ({
  mermaidComponents: {},
}));

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

  it("hides the desktop split-width control when rendered in a mobile drawer", () => {
    renderWithProviders(
      <ChatFilePreview
        file={{ name: "diagram.png", url: "/preview/diagram.png" }}
        fullWidth={false}
        showFullWidthToggle={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(
      screen.queryByRole("button", {
        name: "Expand preview to full width",
      }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
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

  it("switches Markdown files between rendered preview and source code", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers(),
        blob: async () => new Blob(["# Release notes"], { type: "text/plain" }),
      }),
    );

    renderWithProviders(
      <ChatFilePreview
        file={{ name: "notes.md", url: "/preview/notes.md" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(await screen.findByTestId("markdown-preview")).toHaveTextContent(
      "# Release notes",
    );
    await user.click(screen.getByRole("button", { name: "Code" }));
    expect(screen.getByText("# Release notes")).toBeInTheDocument();
    expect(screen.queryByTestId("markdown-preview")).not.toBeInTheDocument();
  });

  it("switches HTML files between sandboxed preview and source code", async () => {
    const user = userEvent.setup();
    type MockFetchResponse = {
      ok: boolean;
      headers: Headers;
      blob: () => Promise<Blob>;
    };
    let resolveFetch!: (response: MockFetchResponse) => void;
    const pendingFetch = new Promise<MockFetchResponse>((resolve) => {
      resolveFetch = resolve;
    });
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(pendingFetch));

    const { container } = renderWithProviders(
      <ChatFilePreview
        file={{ name: "dashboard.html", url: "/preview/dashboard.html" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(screen.getByText("Loading...")).toBeInTheDocument();
    await act(async () => {
      resolveFetch({
        ok: true,
        headers: new Headers(),
        blob: async () =>
          new Blob(
            [
              '<h1>Dashboard</h1><script>localStorage.getItem("dashboard")</script>',
            ],
            { type: "text/html" },
          ),
      });
    });
    const frame = container.querySelector("iframe");
    if (!frame) throw new Error("Expected the HTML preview iframe");
    const srcDoc = frame.getAttribute("srcdoc") ?? "";
    expect(frame).not.toHaveAttribute("src");
    expect(srcDoc).toContain("<h1>Dashboard</h1>");
    expect(srcDoc).toContain('data-melonpaw-preview-bootstrap="storage"');
    expect(srcDoc.indexOf("createMemoryStorage")).toBeLessThan(
      srcDoc.indexOf('localStorage.getItem("dashboard")'),
    );
    expect(srcDoc).toContain(
      `<base href="${
        new URL("/preview/dashboard.html", window.location.href).href
      }">`,
    );
    expect(frame.getAttribute("sandbox")).not.toContain("allow-same-origin");
    await user.click(screen.getByRole("button", { name: "Code" }));
    expect(screen.getByRole("code")).toHaveTextContent("<h1>Dashboard</h1>");
  });

  it("does not offer source mode for raster images", () => {
    renderWithProviders(
      <ChatFilePreview
        file={{ name: "diagram.png", url: "/preview/diagram.png" }}
        fullWidth={false}
        onClose={() => {}}
        onToggleFullWidth={() => {}}
      />,
    );

    expect(
      screen.queryByRole("button", { name: "Code" }),
    ).not.toBeInTheDocument();
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
