import { request } from "../request";

export interface ManagementAuditEvent {
  event_id: string;
  occurred_at: string;
  actor_user_id: string;
  actor_username: string;
  action: string;
  target_type: string;
  target_id: string;
  request_id: string;
  source_ip: string;
  result: string;
  before: unknown;
  after: unknown;
  metadata: unknown;
  previous_hash: string;
  event_hash: string;
}

export interface AuditChainVerification {
  valid: boolean;
  events_checked: number;
  last_hash?: string;
  broken_event_id?: string;
}

export const auditApi = {
  listManagement: (params: { actorUserId?: string; action?: string; targetType?: string; result?: string; limit?: number; offset?: number } = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== "") query.set(key, String(value));
    });
    return request<ManagementAuditEvent[]>(`/audit/management?${query.toString()}`);
  },
  verifyManagement: () => request<AuditChainVerification>("/audit/management/verify"),
};
