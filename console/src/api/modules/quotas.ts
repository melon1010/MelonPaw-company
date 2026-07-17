import { request } from "../request";

export type QuotaScope = "ENTERPRISE" | "USER" | "PROJECT" | "AGENT";

export interface QuotaPolicy {
  scope_type: QuotaScope;
  scope_id: string;
  max_concurrent: number;
  daily_task_limit: number;
  daily_token_limit: number;
  task_token_limit: number;
  max_runtime_seconds: number;
  updated_by?: string;
  updated_at?: string;
}

export interface QuotaCatalogue {
  defaults: Record<QuotaScope, QuotaPolicy>;
  policies: QuotaPolicy[];
}

export interface QuotaPolicyInput {
  max_concurrent: number;
  daily_task_limit: number;
  daily_token_limit: number;
  task_token_limit: number;
  max_runtime_seconds: number;
}

export const quotaApi = {
  list: () => request<QuotaCatalogue>("/quotas"),
  save: (scopeType: QuotaScope, scopeId: string, body: QuotaPolicyInput) =>
    request<QuotaPolicy>(
      `/quotas/${encodeURIComponent(scopeType)}/${encodeURIComponent(scopeId)}`,
      { method: "PUT", body: JSON.stringify(body) },
    ),
  remove: (scopeType: QuotaScope, scopeId: string) =>
    request<{ deleted: boolean }>(
      `/quotas/${encodeURIComponent(scopeType)}/${encodeURIComponent(scopeId)}`,
      { method: "DELETE" },
    ),
};
