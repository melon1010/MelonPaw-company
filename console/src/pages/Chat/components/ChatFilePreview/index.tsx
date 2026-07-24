import { useEffect, useMemo, useState } from "react";
import { Tooltip } from "antd";
import { XMarkdown } from "@ant-design/x-markdown";
import {
  Code2,
  Download,
  Eye,
  File,
  FileSpreadsheet,
  FileText,
  Image as ImageIcon,
  LoaderCircle,
  Maximize2,
  Minimize2,
  Music2,
  Video,
  X,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ChatPreviewFile } from "./context";
import DocxPreview from "./DocxPreview";
import SpreadsheetPreview from "./SpreadsheetPreview";
import { stripFrontmatter } from "../../../../utils/markdown";
import { mermaidComponents } from "../../../../components/MermaidCodeBlock";
import styles from "./index.module.less";

export type { ChatPreviewFile } from "./context";

interface ChatFilePreviewProps {
  file: ChatPreviewFile;
  fullWidth: boolean;
  showFullWidthToggle?: boolean;
  onClose: () => void;
  onToggleFullWidth: () => void;
}

type PreviewKind =
  | "image"
  | "video"
  | "audio"
  | "pdf"
  | "docx"
  | "spreadsheet"
  | "text"
  | "file";

type ViewMode = "preview" | "code";

const IMAGE_EXTENSIONS = new Set([
  "avif",
  "bmp",
  "gif",
  "jpeg",
  "jpg",
  "png",
  "svg",
  "webp",
]);
const VIDEO_EXTENSIONS = new Set(["m4v", "mov", "mp4", "ogg", "webm"]);
const AUDIO_EXTENSIONS = new Set(["aac", "flac", "m4a", "mp3", "oga", "wav"]);
const TEXT_EXTENSIONS = new Set([
  "bash",
  "c",
  "conf",
  "cpp",
  "css",
  "csv",
  "env",
  "go",
  "h",
  "hpp",
  "htm",
  "html",
  "ini",
  "java",
  "js",
  "json",
  "jsx",
  "less",
  "log",
  "markdown",
  "md",
  "properties",
  "py",
  "rb",
  "rs",
  "scss",
  "sh",
  "sql",
  "toml",
  "ts",
  "tsx",
  "txt",
  "vue",
  "xml",
  "yaml",
  "yml",
  "zsh",
]);
const MAX_TEXT_PREVIEW_BYTES = 1024 * 1024;
const HTML_PREVIEW_STORAGE_SHIM = `(() => {
  const createMemoryStorage = () => {
    let entries = Object.create(null);
    return {
      get length() { return Object.keys(entries).length; },
      clear() { entries = Object.create(null); },
      getItem(key) {
        const normalizedKey = String(key);
        return Object.prototype.hasOwnProperty.call(entries, normalizedKey)
          ? entries[normalizedKey]
          : null;
      },
      key(index) { return Object.keys(entries)[Number(index)] ?? null; },
      removeItem(key) { delete entries[String(key)]; },
      setItem(key, value) { entries[String(key)] = String(value); }
    };
  };

  for (const name of ["localStorage", "sessionStorage"]) {
    try {
      void window[name];
    } catch {
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          enumerable: true,
          value: createMemoryStorage()
        });
      } catch {}
    }
  }
})();`;

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function inferFileName(file: ChatPreviewFile): string {
  if (file.name?.trim()) return file.name.trim();
  try {
    const pathname = new URL(file.url, window.location.origin).pathname;
    const lastSegment = pathname.split("/").filter(Boolean).pop();
    return lastSegment ? safeDecode(lastSegment) : "file";
  } catch {
    return "file";
  }
}

function fileExtension(name: string): string {
  const cleanName = name.split(/[?#]/, 1)[0];
  const match = cleanName.match(/\.([^.]+)$/);
  return match?.[1]?.toLowerCase() ?? "";
}

function previewKind(file: ChatPreviewFile, name: string): PreviewKind {
  const mime = file.type?.toLowerCase() ?? "";
  const extension = fileExtension(name);

  if (mime.startsWith("image/") || IMAGE_EXTENSIONS.has(extension))
    return "image";
  if (mime.startsWith("video/") || VIDEO_EXTENSIONS.has(extension))
    return "video";
  if (mime.startsWith("audio/") || AUDIO_EXTENSIONS.has(extension))
    return "audio";
  if (mime === "application/pdf" || extension === "pdf") return "pdf";
  if (
    mime ===
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
    extension === "docx"
  ) {
    return "docx";
  }
  if (
    mime ===
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
    extension === "xlsx"
  ) {
    return "spreadsheet";
  }
  if (
    mime.startsWith("text/") ||
    mime.includes("json") ||
    mime.includes("xml") ||
    TEXT_EXTENSIONS.has(extension)
  ) {
    return "text";
  }
  return "file";
}

function formatFileSize(size: number | undefined): string | null {
  if (!size || size < 0) return null;
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function createHtmlPreviewDocument(source: string, sourceUrl: string): string {
  try {
    const document = new DOMParser().parseFromString(source, "text/html");
    if (!document.querySelector("base")) {
      const base = document.createElement("base");
      base.href = new URL(sourceUrl, window.location.href).href;
      document.head.prepend(base);
    }
    const bootstrap = document.createElement("script");
    bootstrap.dataset.melonpawPreviewBootstrap = "storage";
    bootstrap.textContent = HTML_PREVIEW_STORAGE_SHIM;
    document.head.prepend(bootstrap);
    return `<!doctype html>\n${document.documentElement.outerHTML}`;
  } catch {
    return source;
  }
}

function KindIcon({ kind }: { kind: PreviewKind }) {
  if (kind === "image") return <ImageIcon size={17} aria-hidden />;
  if (kind === "video") return <Video size={17} aria-hidden />;
  if (kind === "audio") return <Music2 size={17} aria-hidden />;
  if (kind === "spreadsheet") return <FileSpreadsheet size={17} aria-hidden />;
  if (kind === "pdf" || kind === "docx" || kind === "text")
    return <FileText size={17} aria-hidden />;
  return <File size={17} aria-hidden />;
}

export default function ChatFilePreview({
  file,
  fullWidth,
  showFullWidthToggle = true,
  onClose,
  onToggleFullWidth,
}: ChatFilePreviewProps) {
  const { t } = useTranslation();
  const name = useMemo(() => inferFileName(file), [file]);
  const kind = useMemo(() => previewKind(file, name), [file, name]);
  const extension = fileExtension(name);
  const mime = file.type?.toLowerCase() ?? "";
  const size = formatFileSize(file.size);
  const isMarkdown =
    kind === "text" &&
    (["md", "markdown"].includes(extension) ||
      ["text/markdown", "text/x-markdown"].includes(mime));
  const isHtml =
    kind === "text" &&
    (["htm", "html"].includes(extension) || mime === "text/html");
  const isSvg =
    kind === "image" && (extension === "svg" || mime === "image/svg+xml");
  const canToggleView = isMarkdown || isHtml || isSvg;
  const defaultViewMode: ViewMode = canToggleView ? "preview" : "code";
  const shouldLoadSource = kind === "text" || isSvg;
  const [viewMode, setViewMode] = useState<ViewMode>(defaultViewMode);
  const [sourceContent, setSourceContent] = useState("");
  const [sourceLoading, setSourceLoading] = useState(shouldLoadSource);
  const [sourceError, setSourceError] = useState("");
  const [sourceTooLarge, setSourceTooLarge] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const htmlPreviewDocument = useMemo(
    () => (isHtml ? createHtmlPreviewDocument(sourceContent, file.url) : ""),
    [file.url, isHtml, sourceContent],
  );

  useEffect(() => {
    setViewMode(defaultViewMode);
    setPreviewError("");
  }, [defaultViewMode, file.url]);

  useEffect(() => {
    setSourceError("");
    setSourceTooLarge(false);
    setSourceContent("");
    setSourceLoading(shouldLoadSource);

    if (!shouldLoadSource) return;

    const controller = new AbortController();
    void fetch(file.url, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(String(response.status));

        const declaredSize = Number(
          response.headers.get("content-length") ?? 0,
        );
        if (declaredSize > MAX_TEXT_PREVIEW_BYTES) {
          setSourceTooLarge(true);
          return;
        }

        const blob = await response.blob();
        if (blob.size > MAX_TEXT_PREVIEW_BYTES) {
          setSourceTooLarge(true);
          return;
        }
        setSourceContent(await blob.text());
      })
      .catch((requestError: unknown) => {
        if ((requestError as { name?: string })?.name !== "AbortError") {
          setSourceError(t("preview.error.LOAD_FAILED"));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setSourceLoading(false);
      });

    return () => controller.abort();
  }, [file.url, shouldLoadSource, t]);

  const renderSourceState = () => {
    if (sourceLoading) {
      return (
        <div className={styles.previewState}>
          <LoaderCircle className={styles.loadingIcon} size={24} aria-hidden />
          <span>{t("common.loading")}</span>
        </div>
      );
    }

    if (sourceError) {
      return (
        <div className={styles.previewState}>
          <File size={28} aria-hidden />
          <strong>{sourceError}</strong>
          <span>{name}</span>
        </div>
      );
    }

    if (sourceTooLarge) {
      return (
        <div className={styles.previewState}>
          <span className={styles.unsupportedIcon}>
            <KindIcon kind={kind} />
          </span>
          <strong>{t("preview.textTooLarge")}</strong>
          <span>{t("preview.downloadHint")}</span>
        </div>
      );
    }

    return null;
  };

  const renderCode = () => {
    const sourceState = renderSourceState();
    if (sourceState) return sourceState;

    return (
      <pre className={styles.textPreview} tabIndex={0}>
        <code>{sourceContent}</code>
      </pre>
    );
  };

  const renderContent = () => {
    if (
      (canToggleView && viewMode === "code") ||
      (kind === "text" && !canToggleView)
    ) {
      return renderCode();
    }

    if (previewError) {
      return (
        <div className={styles.previewState}>
          <File size={28} aria-hidden />
          <strong>{previewError}</strong>
          <span>{name}</span>
        </div>
      );
    }

    if (kind === "file") {
      return (
        <div className={styles.previewState}>
          <span className={styles.unsupportedIcon}>
            <KindIcon kind={kind} />
          </span>
          <strong>{t("preview.unsupported")}</strong>
          <span>{t("preview.downloadHint")}</span>
        </div>
      );
    }

    if (kind === "image") {
      return (
        <div className={styles.imageStage}>
          <img
            src={file.url}
            alt={name}
            onError={() => setPreviewError(t("preview.error.LOAD_FAILED"))}
          />
        </div>
      );
    }

    if (kind === "video") {
      return (
        <div className={styles.mediaStage}>
          <video
            src={file.url}
            controls
            onError={() => setPreviewError(t("preview.error.LOAD_FAILED"))}
          />
        </div>
      );
    }

    if (kind === "audio") {
      return (
        <div className={styles.audioStage}>
          <span className={styles.audioIcon}>
            <Music2 size={28} aria-hidden />
          </span>
          <strong>{name}</strong>
          <audio
            src={file.url}
            controls
            onError={() => setPreviewError(t("preview.error.LOAD_FAILED"))}
          />
        </div>
      );
    }

    if (kind === "pdf") {
      return (
        <iframe className={styles.documentFrame} src={file.url} title={name} />
      );
    }

    if (isHtml) {
      const sourceState = renderSourceState();
      if (sourceState) return sourceState;
      return (
        <iframe
          className={styles.documentFrame}
          srcDoc={htmlPreviewDocument}
          title={name}
          sandbox="allow-downloads allow-forms allow-modals allow-popups allow-scripts"
        />
      );
    }

    if (isMarkdown) {
      const sourceState = renderSourceState();
      if (sourceState) return sourceState;
      return (
        <div className={styles.markdownPreview}>
          <XMarkdown
            content={stripFrontmatter(sourceContent)}
            components={mermaidComponents}
            dompurifyConfig={{
              ADD_TAGS: ["pre", "code"],
              ADD_ATTR: ["data-block", "data-state", "data-lang", "class"],
            }}
          />
        </div>
      );
    }

    if (kind === "docx") {
      return <DocxPreview url={file.url} />;
    }

    if (kind === "spreadsheet") {
      return <SpreadsheetPreview url={file.url} />;
    }

    return renderCode();
  };

  return (
    <aside className={styles.previewPanel} aria-label={t("preview.fileTitle")}>
      <header className={styles.previewHeader}>
        <span className={styles.fileKindIcon}>
          <KindIcon kind={kind} />
        </span>
        <span className={styles.fileIdentity}>
          <strong title={name}>{name}</strong>
          <span>
            {[extension ? extension.toUpperCase() : null, size]
              .filter(Boolean)
              .join(" / ") || t("preview.fileTitle")}
          </span>
        </span>
        {canToggleView && (
          <div
            className={styles.viewModeControl}
            role="group"
            aria-label={`${t("common.preview")} / ${t("preview.code")}`}
          >
            <button
              type="button"
              className={
                viewMode === "preview" ? styles.viewModeActive : undefined
              }
              aria-pressed={viewMode === "preview"}
              onClick={() => setViewMode("preview")}
            >
              <Eye size={14} aria-hidden />
              <span>{t("common.preview")}</span>
            </button>
            <button
              type="button"
              className={
                viewMode === "code" ? styles.viewModeActive : undefined
              }
              aria-pressed={viewMode === "code"}
              onClick={() => setViewMode("code")}
            >
              <Code2 size={14} aria-hidden />
              <span>{t("preview.code")}</span>
            </button>
          </div>
        )}
        <span className={styles.previewActions}>
          {showFullWidthToggle && (
            <Tooltip
              title={
                fullWidth ? t("preview.restoreSplit") : t("preview.fullWidth")
              }
            >
              <button
                type="button"
                className={styles.iconButton}
                aria-label={
                  fullWidth ? t("preview.restoreSplit") : t("preview.fullWidth")
                }
                aria-pressed={fullWidth}
                onClick={onToggleFullWidth}
              >
                {fullWidth ? (
                  <Minimize2 size={17} aria-hidden />
                ) : (
                  <Maximize2 size={17} aria-hidden />
                )}
              </button>
            </Tooltip>
          )}
          <Tooltip title={t("common.download")}>
            <a
              className={styles.iconButton}
              href={file.url}
              download={name}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={t("common.download")}
            >
              <Download size={17} aria-hidden />
            </a>
          </Tooltip>
          <Tooltip title={t("common.close")}>
            <button
              type="button"
              className={styles.iconButton}
              aria-label={t("common.close")}
              onClick={onClose}
            >
              <X size={18} aria-hidden />
            </button>
          </Tooltip>
        </span>
      </header>
      <div className={styles.previewBody}>{renderContent()}</div>
    </aside>
  );
}
