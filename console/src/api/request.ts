import { getApiUrl, clearAuthToken } from "./config";
import { buildAuthHeaders } from "./authHeaders";

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly payload: unknown;

  constructor(
    message: string,
    status: number,
    payload: unknown,
    code?: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.payload = payload;
    this.code = code;
  }
}

function parseErrorPayload(text: string, contentType: string): unknown {
  if (!text) return null;
  const trimmed = text.trim();
  if (!contentType.includes("application/json") && !trimmed.startsWith("{")) {
    return text;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

function detailFromPayload(payload: unknown): string | null {
  if (typeof payload === "string" && payload.trim()) return payload.trim();
  if (!payload || typeof payload !== "object") return null;
  const body = payload as Record<string, unknown>;
  for (const key of ["detail", "message", "error"]) {
    const value = body[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

function permissionDeniedMessage(): string {
  const language = (
    localStorage.getItem("language") ||
    navigator.language ||
    "en"
  ).toLowerCase();
  if (language.startsWith("zh")) return "当前账号没有此操作权限";
  if (language.startsWith("ja")) return "この操作を実行する権限がありません";
  if (language.startsWith("ru"))
    return "У вас нет прав для выполнения этой операции";
  if (language.startsWith("pt"))
    return "Você não tem permissão para executar esta operação";
  if (language.startsWith("vi"))
    return "Bạn không có quyền thực hiện thao tác này";
  if (language.startsWith("id"))
    return "Anda tidak memiliki izin untuk melakukan operasi ini";
  return "You do not have permission to perform this action";
}

function buildHeaders(
  method?: string,
  extra?: HeadersInit,
  body?: BodyInit | null,
): Headers {
  // Normalize extra to a Headers instance for consistent handling
  const headers = extra instanceof Headers ? extra : new Headers(extra);

  // Only add Content-Type for methods that typically have a body
  if (method && ["POST", "PUT", "PATCH"].includes(method.toUpperCase())) {
    // Don't override if caller explicitly set Content-Type
    const isMultipart =
      typeof FormData !== "undefined" && body instanceof FormData;
    if (!headers.has("Content-Type") && !isMultipart) {
      headers.set("Content-Type", "application/json");
    }
  }

  for (const [key, value] of Object.entries(buildAuthHeaders())) {
    if (!headers.has(key)) {
      headers.set(key, value);
    }
  }

  if (!headers.has("Accept-Language")) {
    headers.set(
      "Accept-Language",
      localStorage.getItem("language") || navigator.language || "en",
    );
  }

  return headers;
}

export async function request<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = getApiUrl(path);
  const method = options.method || "GET";
  const headers = buildHeaders(method, options.headers, options.body);

  const response = await fetch(url, {
    ...options,
    headers,
  });

  if (!response.ok) {
    if (response.status === 401) {
      clearAuthToken();
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
      throw new Error("Not authenticated");
    }

    const text = await response.text().catch(() => "");
    const contentType = response.headers.get("content-type") || "";
    const payload = parseErrorPayload(text, contentType);
    const detail = detailFromPayload(payload);
    const finalMessage =
      response.status === 403
        ? permissionDeniedMessage()
        : detail || `Request failed: ${response.status} ${response.statusText}`;
    const code =
      payload &&
      typeof payload === "object" &&
      typeof (payload as Record<string, unknown>).code === "string"
        ? ((payload as Record<string, unknown>).code as string)
        : undefined;

    throw new ApiRequestError(finalMessage, response.status, payload, code);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await response.text();
    const trimmed = text.trim();
    if (trimmed.startsWith("{")) {
      try {
        return JSON.parse(trimmed) as T;
      } catch {
        // Keep the original text response when it only happens to start with '{'.
      }
    }
    return text as unknown as T;
  }

  return (await response.json()) as T;
}
