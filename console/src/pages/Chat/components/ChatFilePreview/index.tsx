import { useEffect, useMemo, useState } from "react";
import { Tooltip } from "antd";
import {
  Download,
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
import styles from "./index.module.less";

export type { ChatPreviewFile } from "./context";

interface ChatFilePreviewProps {
  file: ChatPreviewFile;
  fullWidth: boolean;
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
  "html",
  "ini",
  "java",
  "js",
  "json",
  "jsx",
  "less",
  "log",
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
  onClose,
  onToggleFullWidth,
}: ChatFilePreviewProps) {
  const { t } = useTranslation();
  const name = useMemo(() => inferFileName(file), [file]);
  const kind = useMemo(() => previewKind(file, name), [file, name]);
  const extension = fileExtension(name);
  const size = formatFileSize(file.size);
  const [textContent, setTextContent] = useState("");
  const [loading, setLoading] = useState(kind === "text");
  const [error, setError] = useState("");
  const [tooLarge, setTooLarge] = useState(false);

  useEffect(() => {
    setError("");
    setTooLarge(false);
    setTextContent("");
    setLoading(kind === "text");

    if (kind !== "text") return;

    const controller = new AbortController();
    void fetch(file.url, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(String(response.status));

        const declaredSize = Number(
          response.headers.get("content-length") ?? 0,
        );
        if (declaredSize > MAX_TEXT_PREVIEW_BYTES) {
          setTooLarge(true);
          return;
        }

        const blob = await response.blob();
        if (blob.size > MAX_TEXT_PREVIEW_BYTES) {
          setTooLarge(true);
          return;
        }
        setTextContent(await blob.text());
      })
      .catch((requestError: unknown) => {
        if ((requestError as { name?: string })?.name !== "AbortError") {
          setError(t("preview.error.LOAD_FAILED"));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [file.url, kind, t]);

  const renderContent = () => {
    if (loading) {
      return (
        <div className={styles.previewState}>
          <LoaderCircle className={styles.loadingIcon} size={24} aria-hidden />
          <span>{t("common.loading")}</span>
        </div>
      );
    }

    if (error) {
      return (
        <div className={styles.previewState}>
          <File size={28} aria-hidden />
          <strong>{error}</strong>
          <span>{name}</span>
        </div>
      );
    }

    if (tooLarge || kind === "file") {
      return (
        <div className={styles.previewState}>
          <span className={styles.unsupportedIcon}>
            <KindIcon kind={kind} />
          </span>
          <strong>
            {tooLarge ? t("preview.textTooLarge") : t("preview.unsupported")}
          </strong>
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
            onError={() => setError(t("preview.error.LOAD_FAILED"))}
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
            onError={() => setError(t("preview.error.LOAD_FAILED"))}
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
            onError={() => setError(t("preview.error.LOAD_FAILED"))}
          />
        </div>
      );
    }

    if (kind === "pdf") {
      return (
        <iframe className={styles.documentFrame} src={file.url} title={name} />
      );
    }

    if (kind === "docx") {
      return <DocxPreview url={file.url} />;
    }

    if (kind === "spreadsheet") {
      return <SpreadsheetPreview url={file.url} />;
    }

    return (
      <pre className={styles.textPreview} tabIndex={0}>
        <code>{textContent}</code>
      </pre>
    );
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
        <span className={styles.previewActions}>
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
