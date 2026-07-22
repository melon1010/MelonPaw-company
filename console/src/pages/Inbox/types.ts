export interface InboxSummary {
  pushMessages: {
    total: number;
    unread: number;
  };
}

export interface PushMessage {
  id: string;
  channelType:
    | "system"
    | "cron"
    | "memory"
    | "heartbeat"
    | "skill";
  channelName: string;
  title: string;
  content: string;
  sender: {
    userId: string;
    username: string;
  };
  createdAt: Date;
  read: boolean;
  metadata?: {
    priority?: "low" | "normal" | "high" | "urgent";
    sourceType?: string;
    sourceId?: string;
    eventType?: string;
    status?: string;
    severity?: string;
    trigger?: string;
    durationMs?: number;
    agentId?: string;
    payload?: Record<string, unknown>;
  };
}
