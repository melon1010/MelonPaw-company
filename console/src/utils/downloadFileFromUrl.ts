/** Browser file download helper. */
import { isHttpExternalUrl, resolveExternalUrl } from "./openExternalLink";

export interface DownloadFileOptions {
  headers?: Record<string, string>;
  errorMessage?: string;
  /** Prefer Content-Disposition filenames returned by the server. */
  preferResponseFilename?: boolean;
}

/** Extract a suggested filename from the server's Content-Disposition header. */
function filenameFromContentDisposition(value: string | null): string {
  if (!value) return "";

  const utf8Match = value.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }

  const quotedMatch = value.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) {
    return quotedMatch[1];
  }

  const bareMatch = value.match(/filename=([^;]+)/i);
  return bareMatch?.[1]?.trim() ?? "";
}

/** Trigger a normal browser download by clicking a temporary blob-backed link. */
function triggerBrowserDownload(blob: Blob, filename: string): void {
  const a = document.createElement("a");
  const objectUrl = URL.createObjectURL(blob);
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(objectUrl);
    a.remove();
  }, 0);
}

/** Normalize suggested filenames across supported browser platforms. */
function sanitizeSaveFilename(filename: string): string {
  // Windows-safe names also work on the other supported file systems.
  const sanitized = filename
    .replace(/[<>:"/\\|?*]/g, "_")
    .trim()
    .replace(/[. ]+$/g, "");
  return sanitized || "download";
}

/** Fetch a blob and trigger a regular browser download. */
async function downloadWithBrowser(
  url: string,
  filename: string,
  options: DownloadFileOptions,
): Promise<void> {
  const res = await fetch(url, { headers: options.headers });
  if (!res.ok) {
    const error = new Error(
      options.errorMessage || `Download failed: ${res.status}`,
    ) as Error & { status?: number };
    error.status = res.status;
    throw error;
  }

  const responseFilename = options.preferResponseFilename
    ? filenameFromContentDisposition(res.headers.get("Content-Disposition"))
    : "";
  triggerBrowserDownload(
    await res.blob(),
    responseFilename ? sanitizeSaveFilename(responseFilename) : filename,
  );
}

/** Download a URL in the browser. */
export async function downloadFileFromUrl(
  url: string,
  filename: string,
  options: DownloadFileOptions = {},
): Promise<void> {
  if (!url) {
    throw new Error(options.errorMessage || "Download URL is empty");
  }

  const requestUrl = resolveExternalUrl(url);
  if (!requestUrl || !isHttpExternalUrl(requestUrl)) {
    throw new Error(options.errorMessage || "Download URL is invalid");
  }

  await downloadWithBrowser(
    requestUrl,
    sanitizeSaveFilename(filename),
    options,
  );
}
