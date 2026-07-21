import { useEffect, useMemo, useState } from "react";
import { FileSpreadsheet, LoaderCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { CellValue, Sheet } from "read-excel-file/browser";
import { loadOfficeFile, OfficeFileTooLargeError } from "./loadOfficeFile";
import styles from "./index.module.less";

interface SpreadsheetPreviewProps {
  url: string;
}

type PreviewStatus = "loading" | "ready" | "tooLarge" | "error";

const MAX_VISIBLE_ROWS = 500;
const MAX_VISIBLE_COLUMNS = 100;

function columnLabel(index: number): string {
  let value = index + 1;
  let label = "";
  while (value > 0) {
    const remainder = (value - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    value = Math.floor((value - 1) / 26);
  }
  return label;
}

export default function SpreadsheetPreview({ url }: SpreadsheetPreviewProps) {
  const { i18n, t } = useTranslation();
  const [status, setStatus] = useState<PreviewStatus>("loading");
  const [sheets, setSheets] = useState<Sheet[]>([]);
  const [activeSheetIndex, setActiveSheetIndex] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setStatus("loading");
    setSheets([]);
    setActiveSheetIndex(0);

    void (async () => {
      try {
        const blob = await loadOfficeFile(url, controller.signal);
        const { default: readXlsxFile } = await import(
          "read-excel-file/browser"
        );
        const workbookSheets = await readXlsxFile(blob);
        if (!active) return;
        setSheets(workbookSheets);
        setStatus("ready");
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
    };
  }, [url]);

  const activeSheet = sheets[activeSheetIndex];
  const rowCount = activeSheet?.data.length ?? 0;
  const columnCount = useMemo(
    () =>
      activeSheet?.data.reduce(
        (largest, row) => Math.max(largest, row.length),
        0,
      ) ?? 0,
    [activeSheet],
  );
  const visibleRows = activeSheet?.data.slice(0, MAX_VISIBLE_ROWS) ?? [];
  const visibleColumnCount = Math.min(columnCount, MAX_VISIBLE_COLUMNS);
  const isTruncated =
    rowCount > MAX_VISIBLE_ROWS || columnCount > MAX_VISIBLE_COLUMNS;
  const numberFormatter = useMemo(
    () => new Intl.NumberFormat(i18n.language, { maximumFractionDigits: 12 }),
    [i18n.language],
  );
  const dateFormatter = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { dateStyle: "medium" }),
    [i18n.language],
  );

  const formatCell = (value: CellValue | null): string => {
    if (value === null || value === undefined) return "";
    if (value instanceof Date) return dateFormatter.format(value);
    if (typeof value === "number") return numberFormatter.format(value);
    if (typeof value === "boolean") return value ? "TRUE" : "FALSE";
    return String(value);
  };

  if (status !== "ready") {
    return (
      <div className={styles.previewState} aria-busy={status === "loading"}>
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
              <FileSpreadsheet size={18} aria-hidden />
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
    );
  }

  if (!activeSheet || rowCount === 0 || columnCount === 0) {
    return (
      <div className={styles.previewState}>
        <span className={styles.unsupportedIcon}>
          <FileSpreadsheet size={18} aria-hidden />
        </span>
        <strong>{t("preview.spreadsheet.empty")}</strong>
      </div>
    );
  }

  return (
    <div className={styles.spreadsheetStage}>
      <div className={styles.sheetBar}>
        <div
          className={styles.sheetTabs}
          role="tablist"
          aria-label={t("preview.spreadsheet.worksheets")}
        >
          {sheets.map((sheet, index) => (
            <button
              key={`${sheet.sheet}-${index}`}
              type="button"
              role="tab"
              aria-selected={index === activeSheetIndex}
              className={index === activeSheetIndex ? styles.activeSheet : ""}
              onClick={() => setActiveSheetIndex(index)}
            >
              {sheet.sheet}
            </button>
          ))}
        </div>
        <span className={styles.sheetSummary}>
          {t("preview.spreadsheet.summary", {
            rows: rowCount,
            columns: columnCount,
          })}
        </span>
      </div>
      {isTruncated && (
        <div className={styles.truncationNotice} role="status">
          {t("preview.spreadsheet.truncated", {
            rows: MAX_VISIBLE_ROWS,
            columns: MAX_VISIBLE_COLUMNS,
          })}
        </div>
      )}
      <div className={styles.sheetViewport}>
        <table aria-label={activeSheet.sheet}>
          <thead>
            <tr>
              <th className={styles.sheetCorner} aria-hidden />
              {Array.from({ length: visibleColumnCount }, (_, columnIndex) => (
                <th key={columnIndex} scope="col">
                  {columnLabel(columnIndex)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                <th scope="row">{rowIndex + 1}</th>
                {Array.from(
                  { length: visibleColumnCount },
                  (_, columnIndex) => (
                    <td key={columnIndex} title={formatCell(row[columnIndex])}>
                      {formatCell(row[columnIndex])}
                    </td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
