import { useEffect, useRef, useState } from "react";
import { FileText, LoaderCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import { loadOfficeFile, OfficeFileTooLargeError } from "./loadOfficeFile";
import styles from "./index.module.less";

interface DocxPreviewProps {
  url: string;
}

type PreviewStatus = "loading" | "ready" | "tooLarge" | "error";

export default function DocxPreview({ url }: DocxPreviewProps) {
  const { t } = useTranslation();
  const containerRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<PreviewStatus>("loading");

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const controller = new AbortController();
    let active = true;
    container.replaceChildren();
    setStatus("loading");

    void (async () => {
      try {
        const blob = await loadOfficeFile(url, controller.signal);
        const { renderAsync } = await import("docx-preview");
        if (!active) return;

        await renderAsync(blob, container, container, {
          breakPages: true,
          debug: false,
          experimental: false,
          ignoreHeight: false,
          ignoreLastRenderedPageBreak: true,
          ignoreWidth: false,
          inWrapper: true,
          renderAltChunks: false,
          renderComments: false,
          renderEndnotes: true,
          renderFooters: true,
          renderFootnotes: true,
          renderHeaders: true,
          renderChanges: false,
          useBase64URL: true,
        });
        if (active) setStatus("ready");
      } catch (requestError: unknown) {
        if (
          !active ||
          (requestError as { name?: string })?.name === "AbortError"
        ) {
          return;
        }
        setStatus(
          requestError instanceof OfficeFileTooLargeError
            ? "tooLarge"
            : "error",
        );
      }
    })();

    return () => {
      active = false;
      controller.abort();
      container.replaceChildren();
    };
  }, [url]);

  return (
    <div className={styles.officeStage} aria-busy={status === "loading"}>
      <div
        ref={containerRef}
        className={styles.docxCanvas}
        aria-hidden={status !== "ready"}
      />
      {status !== "ready" && (
        <div className={styles.previewState}>
          {status === "loading" ? (
            <>
              <LoaderCircle
                className={styles.loadingIcon}
                size={24}
                aria-hidden
              />
              <span>{t("common.loading")}</span>
            </>
          ) : (
            <>
              <span className={styles.unsupportedIcon}>
                <FileText size={18} aria-hidden />
              </span>
              <strong>
                {status === "tooLarge"
                  ? t("preview.officeTooLarge")
                  : t("preview.error.LOAD_FAILED")}
              </strong>
              <span>{t("preview.downloadHint")}</span>
            </>
          )}
        </div>
      )}
    </div>
  );
}
