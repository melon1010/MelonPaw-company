export const MAX_OFFICE_PREVIEW_BYTES = 20 * 1024 * 1024;

export class OfficeFileTooLargeError extends Error {
  constructor() {
    super("Office file exceeds the inline preview limit");
    this.name = "OfficeFileTooLargeError";
  }
}

export async function loadOfficeFile(
  url: string,
  signal: AbortSignal,
): Promise<Blob> {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new Error(String(response.status));

  const declaredSize = Number(response.headers.get("content-length") ?? 0);
  if (declaredSize > MAX_OFFICE_PREVIEW_BYTES) {
    throw new OfficeFileTooLargeError();
  }

  const blob = await response.blob();
  if (blob.size > MAX_OFFICE_PREVIEW_BYTES) {
    throw new OfficeFileTooLargeError();
  }
  return blob;
}
