import { getApiToken, getApiUrl } from "../config";

export interface AuthUser {
  id: string;
  username: string;
  display_name?: string | null;
  enabled: boolean;
  roles: string[];
  permissions: string[];
  created_at?: string | null;
  updated_at?: string | null;
}

export interface LoginResponse {
  token: string;
  token_type?: string;
  expires_at?: string;
  user: AuthUser;
  username?: string;
  message?: string;
}

export interface AuthStatusResponse {
  enabled: boolean;
  has_users: boolean;
  bootstrap_required?: boolean;
  authenticated?: boolean;
  auth_disabled?: boolean;
  user?: AuthUser | null;
}

async function readError(res: Response, fallback: string): Promise<Error> {
  const payload = (await res.json().catch(() => ({}))) as {
    detail?: string;
    message?: string;
    error?: string;
  };
  return new Error(payload.detail || payload.message || payload.error || fallback);
}

async function authenticate(
  path: "/auth/login" | "/auth/bootstrap",
  body: Record<string, string | undefined>,
  fallback: string,
): Promise<LoginResponse> {
  const res = await fetch(getApiUrl(path), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await readError(res, fallback);
  return res.json();
}

export const authApi = {
  login: async (username: string, password: string): Promise<LoginResponse> => {
    return authenticate("/auth/login", { username, password }, "Login failed");
  },

  bootstrap: async (
    username: string,
    password: string,
    displayName?: string,
  ): Promise<LoginResponse> => {
    return authenticate(
      "/auth/bootstrap",
      { username, password, display_name: displayName },
      "Initialization failed",
    );
  },

  getStatus: async (): Promise<AuthStatusResponse> => {
    const res = await fetch(getApiUrl("/auth/status"));
    if (!res.ok) throw new Error("Failed to check auth status");
    return res.json();
  },

  getCurrentUser: async (): Promise<AuthUser> => {
    const token = getApiToken();
    const res = await fetch(getApiUrl("/auth/me"), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) throw await readError(res, "Failed to load current user");
    return res.json();
  },

  logout: async (): Promise<void> => {
    const token = getApiToken();
    const res = await fetch(getApiUrl("/auth/logout"), {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok && res.status !== 401) {
      throw await readError(res, "Logout failed");
    }
  },
};
