import { useState, useEffect, useMemo } from "react";
import { Button, Card, Dropdown, Tag, Typography, Space } from "antd";
import { Shield, Clock, ChevronDown } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAgentStore } from "../../stores/agentStore";
import { getAgentDisplayName } from "../../utils/agentDisplayName";
import styles from "./ApprovalCard.module.less";

const { Text } = Typography;

export interface ApprovalCardProps {
  requestId: string;
  toolName: string;
  toolSource?: string;
  severity: string;
  findingsCount: number;
  findingsSummary: string;
  toolParams: Record<string, unknown>;
  createdAt: number;
  timeoutSeconds: number;
  agentId: string;
  ownerAgentId?: string;
  showInboxAgentContext?: boolean;
  compact?: boolean;
  sessionId?: string;
  rootSessionId?: string;
  rememberable?: boolean;
  isGeneralized?: boolean;
  exactTarget?: string;
  similarTarget?: string;
  onApprove: (
    requestId: string,
    scope?: "once" | "exact" | "similar",
  ) => Promise<void>;
  onDeny: (requestId: string) => Promise<void>;
  onAcknowledge?: (requestId: string) => Promise<void>;
}

export function ApprovalCard({
  requestId,
  toolName,
  severity,
  findingsSummary,
  toolParams,
  createdAt,
  timeoutSeconds,
  agentId,
  ownerAgentId,
  showInboxAgentContext = false,
  compact = false,
  sessionId,
  rootSessionId,
  rememberable,
  isGeneralized,
  exactTarget,
  onApprove,
  onDeny,
  onAcknowledge,
}: ApprovalCardProps) {
  const { t } = useTranslation();
  const agents = useAgentStore((state) => state.agents);
  const agentsById = useMemo(
    () => new Map(agents.map((agent) => [agent.id, agent])),
    [agents],
  );
  const [loading, setLoading] = useState<
    | "approve-once"
    | "approve-pattern"
    | "approve-exact"
    | "deny"
    | "acknowledge"
    | null
  >(null);
  const [remaining, setRemaining] = useState<number>(timeoutSeconds);

  // Check if this is a cross-session approval
  const isCrossSession =
    sessionId && rootSessionId && sessionId !== rootSessionId;
  const isTimedOut = showInboxAgentContext && remaining <= 0;
  const executionAgentDisplayName = useMemo(() => {
    const matched = agentsById.get(agentId);
    if (matched) return getAgentDisplayName(matched, t);
    return agentId || t("common.unknown", "Unknown");
  }, [agentsById, agentId, t]);
  const ownerAgentDisplayName = useMemo(() => {
    const ownerId = ownerAgentId || agentId;
    const matched = agentsById.get(ownerId);
    if (matched) return getAgentDisplayName(matched, t);
    return ownerId || t("common.unknown", "Unknown");
  }, [agentsById, ownerAgentId, agentId, t]);
  const shouldShowExecutionAgent =
    showInboxAgentContext && Boolean(isCrossSession);
  const persistsApproval = rememberable ?? Boolean(exactTarget);
  const targetPreview = useMemo(() => {
    const preferredKeys = ["command", "path", "file_path", "action", "url"];
    for (const key of preferredKeys) {
      const value = toolParams?.[key];
      if (typeof value === "string" && value.trim()) return value.trim();
    }
    if (exactTarget && exactTarget !== toolName) {
      const prefix = `${toolName}:`;
      return exactTarget.startsWith(prefix)
        ? exactTarget.slice(prefix.length).trim()
        : exactTarget;
    }
    return "";
  }, [exactTarget, toolName, toolParams]);

  useEffect(() => {
    const elapsed = Date.now() / 1000 - createdAt;
    const initialRemaining = Math.max(0, Math.floor(timeoutSeconds - elapsed));
    setRemaining(initialRemaining);

    const timer = setInterval(() => {
      const newElapsed = Date.now() / 1000 - createdAt;
      const newRemaining = Math.max(0, Math.floor(timeoutSeconds - newElapsed));
      setRemaining(newRemaining);

      if (newRemaining <= 0) {
        clearInterval(timer);
      }
    }, 1000);

    return () => clearInterval(timer);
  }, [createdAt, timeoutSeconds]);

  const handleApprove = async (scope?: "once" | "exact" | "similar") => {
    const loadingKey =
      scope === "once"
        ? "approve-once"
        : scope === "similar"
        ? "approve-pattern"
        : "approve-exact";
    console.log(
      "[ApprovalCard] Approve button clicked:",
      requestId,
      "scope:",
      scope,
    );
    setLoading(loadingKey);
    try {
      await onApprove(requestId, scope);
      console.log("[ApprovalCard] onApprove completed");
    } catch (err) {
      console.error("[ApprovalCard] onApprove failed:", err);
    } finally {
      setLoading(null);
    }
  };

  const handleDeny = async () => {
    setLoading("deny");
    try {
      await onDeny(requestId);
    } finally {
      setLoading(null);
    }
  };

  const handleAcknowledge = async () => {
    if (!onAcknowledge) return;
    setLoading("acknowledge");
    try {
      await onAcknowledge(requestId);
    } finally {
      setLoading(null);
    }
  };

  const getSeverityColor = (sev: string) => {
    const s = sev.toLowerCase();
    if (s === "critical" || s === "high") return "error";
    if (s === "medium") return "warning";
    return "default";
  };

  return (
    <Card
      className={`${styles.approvalCard} ${compact ? styles.composerCard : ""}`}
      bordered={false}
    >
      <div className={styles.header}>
        <Space size={8} align="center" className={styles.titleRow}>
          <Shield size={16} className={styles.icon} />
          <Text className={styles.title}>
            {t("approval.title", "Security Approval Required")}
          </Text>
        </Space>
        <Space size={6} align="center" className={styles.timer}>
          <Clock size={14} className={styles.timerIcon} />
          <Text className={styles.timerText}>
            {Math.floor(remaining / 60)}:
            {String(remaining % 60).padStart(2, "0")}
          </Text>
        </Space>
      </div>

      <div className={styles.content}>
        {showInboxAgentContext ? (
          <>
            <div className={styles.infoRow}>
              <Text className={styles.label}>
                {t("approval.ownerAgent", "Owner Agent")}:
              </Text>
              <Tag color="success" className={styles.ownerAgentTag}>
                {ownerAgentDisplayName}
              </Tag>
            </div>
            {shouldShowExecutionAgent ? (
              <div className={styles.infoRow}>
                <Text className={styles.label}>
                  {t("approval.executingAgent", "Executing Agent")}:
                </Text>
                <Tag color="blue" className={styles.crossSessionTag}>
                  {executionAgentDisplayName}
                </Tag>
              </div>
            ) : null}
          </>
        ) : null}

        <div className={styles.riskLine}>
          <Text className={styles.toolName} code>
            {toolName}
          </Text>
          <Tag
            color={getSeverityColor(severity)}
            className={styles.severityTag}
          >
            {severity.toUpperCase()}
          </Tag>
          {isCrossSession && !showInboxAgentContext ? (
            <Tag color="blue" className={styles.crossSessionTag}>
              {t("approval.subSession", "Sub-Agent")}
            </Tag>
          ) : null}
        </div>

        {targetPreview ? (
          <code className={styles.targetPreview}>{targetPreview}</code>
        ) : null}

        {findingsSummary && (
          <Text className={styles.riskSummary}>{findingsSummary}</Text>
        )}
      </div>

      <div className={styles.actions}>
        {isTimedOut ? (
          <>
            <Text className={styles.timeoutHint}>
              {t("approval.timeoutAutoDenied", "Timed out, auto denied")}
            </Text>
            {onAcknowledge ? (
              <Button
                type="primary"
                onClick={handleAcknowledge}
                loading={loading === "acknowledge"}
                disabled={loading !== null}
              >
                {t("approval.acknowledge", "Got It")}
              </Button>
            ) : null}
          </>
        ) : (
          <>
            <Button
              danger
              className={styles.denyButton}
              onClick={handleDeny}
              loading={loading === "deny"}
              disabled={loading !== null}
            >
              {t("approval.deny", "Deny")}
            </Button>
            {persistsApproval ? (
              <Dropdown.Button
                type="default"
                className={styles.approveDropdown}
                icon={<ChevronDown size={14} />}
                menu={{
                  items: [
                    {
                      key: isGeneralized ? "similar" : "exact",
                      label: t(
                        "approval.approvePattern",
                        "Always allow matching operations",
                      ),
                    },
                  ],
                  onClick: ({ key }) => {
                    void handleApprove(key as "exact" | "similar");
                  },
                }}
                onClick={() => handleApprove("once")}
                loading={loading === "approve-once"}
                disabled={loading !== null}
              >
                {t("approval.approveOnce", "Allow once")}
              </Dropdown.Button>
            ) : (
              <Button
                type="default"
                className={styles.approveButton}
                onClick={() => handleApprove("once")}
                loading={loading === "approve-once"}
                disabled={loading !== null}
              >
                {t("approval.approveOnce", "Allow once")}
              </Button>
            )}
          </>
        )}
      </div>
    </Card>
  );
}
