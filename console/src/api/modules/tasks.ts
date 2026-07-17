import { request } from "../request";

export type EnterpriseTaskStatus =
  | "RUNNING"
  | "WAITING_APPROVAL"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "TIMED_OUT"
  | "REJECTED"
  | "EXPIRED"
  | "INTERRUPTED";

export interface EnterpriseTask {
  task_id: string;
  run_id: string;
  attempt: number;
  task_type: "AGENT" | "AUTOMATION" | "CHANNEL" | string;
  scope_type: "PERSONAL" | "PROJECT" | "SERVICE" | string;
  scope_id: string;
  owner_user_id: string;
  agent_id: string;
  agent_version_id?: string | null;
  root_task_id?: string | null;
  parent_task_id?: string | null;
  delegation_id?: string | null;
  depth?: number;
  title: string;
  status: EnterpriseTaskStatus;
  session_id: string;
  trigger_type: string;
  trigger_ref?: string | null;
  chat_id?: string | null;
  result_summary?: string | null;
  safe_error?: string | null;
  input_tokens: number;
  output_tokens: number;
  created_at: string;
  updated_at: string;
  started_at: string;
  ended_at?: string | null;
  timeout_at?: string | null;
  max_tokens?: number;
  retryable: boolean;
}

export interface EnterpriseTaskRun {
  run_id: string;
  task_id: string;
  attempt: number;
  state: EnterpriseTaskStatus;
  phase: string;
  agent_version_id?: string | null;
  started_at: string;
  ended_at?: string | null;
  error_code?: string | null;
  safe_error?: string | null;
  input_tokens: number;
  output_tokens: number;
  timeout_at?: string | null;
  max_tokens?: number;
}

export interface EnterpriseTaskEvent {
  event_id: string;
  task_id: string;
  run_id: string;
  sequence: number;
  schema_version: number;
  event_type: string;
  occurred_at: string;
  actor_type: string;
  actor_id: string;
  visibility: string;
  payload: Record<string, unknown>;
}

export interface EnterpriseTaskResult {
  task: EnterpriseTask;
  runs: EnterpriseTaskRun[];
  events: EnterpriseTaskEvent[];
}

export const taskApi = {
  listTasks: (
    params: {
      status?: string;
      source?: string;
      q?: string;
      limit?: number;
      offset?: number;
    } = {},
  ) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== "") query.set(key, String(value));
    });
    const queryString = query.toString();
    const suffix = queryString ? `?${queryString}` : "";
    return request<EnterpriseTask[]>(`/tasks${suffix}`);
  },
  getTask: (taskId: string) =>
    request<EnterpriseTask>(`/tasks/${encodeURIComponent(taskId)}`),
  getTaskResult: (taskId: string) =>
    request<EnterpriseTaskResult>(
      `/tasks/${encodeURIComponent(taskId)}/result`,
    ),
  cancelTask: (taskId: string) =>
    request<EnterpriseTask>(`/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: "POST",
    }),
  retryTask: (taskId: string) =>
    request<EnterpriseTask>(`/tasks/${encodeURIComponent(taskId)}/retry`, {
      method: "POST",
    }),
};
