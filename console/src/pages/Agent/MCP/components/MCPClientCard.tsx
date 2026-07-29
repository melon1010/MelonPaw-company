import { Card, Button, Modal, Tooltip, Input } from "@agentscope-ai/design";
import type { MCPAccessPolicy, MCPClientInfo } from "../../../../api/types";
import { useTranslation } from "react-i18next";
import React, { useEffect, useState } from "react";
import {
  EyeOutlined,
  EyeInvisibleOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { ShieldCheck, ShieldAlert, ShieldX, KeyRound } from "lucide-react";
import { MCPAccessModal } from "./MCPAccessModal";
import { MCPOAuthSection } from "./MCPOAuthSection";
import styles from "../index.module.less";

interface MCPClientUpdate {
  name?: string;
  description?: string;
  command?: string;
  enabled?: boolean;
  transport?: "stdio" | "streamable_http" | "sse";
  url?: string;
  headers?: Record<string, string>;
  args?: string[];
  env?: Record<string, string>;
  cwd?: string;
  startup_timeout_seconds?: number;
  tool_timeout_seconds?: number;
  tool_timeouts?: Record<string, number>;
  max_inline_result_bytes?: number;
}

interface MCPClientCardProps {
  client: MCPClientInfo;
  canManage?: boolean;
  onToggle: (client: MCPClientInfo, e: React.MouseEvent) => void;
  onDelete: (client: MCPClientInfo, e: React.MouseEvent) => void;
  onUpdate: (key: string, updates: MCPClientUpdate) => Promise<boolean>;
  onUpdatePolicy: (key: string, policy: MCPAccessPolicy) => Promise<boolean>;
  onRefresh?: () => Promise<void>;
}

export const MCPClientCard = React.memo(function MCPClientCard({
  client,
  canManage = false,
  onToggle,
  onDelete,
  onUpdate,
  onUpdatePolicy,
  onRefresh,
}: MCPClientCardProps) {
  const { t } = useTranslation();
  const [isHovered, setIsHovered] = useState(false);
  const [jsonModalOpen, setJsonModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [accessModalOpen, setAccessModalOpen] = useState(false);
  const [editedJson, setEditedJson] = useState("");
  const [isEditing, setIsEditing] = useState(false);
  const [savingConfig, setSavingConfig] = useState(false);
  const [oauthModalOpen, setOauthModalOpen] = useState(false);
  const [oauthClientId, setOauthClientId] = useState(
    client.oauth_status?.client_id || "",
  );
  const [oauthClientSecret, setOauthClientSecret] = useState("");
  const [oauthScope, setOauthScope] = useState(
    client.oauth_status?.scope || "",
  );
  const [oauthTokenEndpoint, setOauthTokenEndpoint] = useState(
    client.oauth_status?.token_url || "",
  );

  useEffect(() => {
    if (canManage) return;
    setDeleteModalOpen(false);
    setAccessModalOpen(false);
    setOauthModalOpen(false);
    setIsEditing(false);
  }, [canManage]);

  // Determine if MCP client is remote or local based on command
  const isRemote =
    client.transport === "streamable_http" || client.transport === "sse";
  const clientType = isRemote ? "Remote" : "Local";

  const oauthStatus = client.oauth_status;
  const now = Date.now() / 1000;
  const isOauthAuthorized =
    !!oauthStatus?.authorized && oauthStatus.expires_at > now;
  const isOauthExpired =
    !!oauthStatus?.authorized && oauthStatus.expires_at <= now;
  const hasOauth = !!oauthStatus;
  const connectionStatus = !client.enabled
    ? "disabled"
    : client.connected
    ? "connected"
    : client.status === "connecting" || client.status === "reconnecting"
    ? client.status
    : client.status === "idle"
    ? "idle"
    : client.error
    ? "error"
    : "disconnected";
  const connectionLabel = t(`mcp.status.${connectionStatus}`);

  const handleToggleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onToggle(client, e);
  };

  const handleDeleteClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!canManage) return;
    setDeleteModalOpen(true);
  };

  const confirmDelete = () => {
    setDeleteModalOpen(false);
    onDelete(client, null as unknown as React.MouseEvent);
  };

  const handleCardClick = () => {
    const jsonStr = JSON.stringify(client, null, 2);
    setEditedJson(jsonStr);
    setIsEditing(false);
    setJsonModalOpen(true);
  };

  const closeJsonModal = () => {
    if (savingConfig) return;
    setJsonModalOpen(false);
  };

  const handleSaveJson = async () => {
    if (savingConfig) return;

    try {
      const parsed = JSON.parse(editedJson);
      const updates = { ...parsed };
      delete updates.key;

      // Send all updates directly to backend, let backend handle env masking check
      setSavingConfig(true);
      try {
        const success = await onUpdate(client.key, updates);
        if (success) {
          setJsonModalOpen(false);
          setIsEditing(false);
        }
      } finally {
        setSavingConfig(false);
      }
    } catch {
      alert("Invalid JSON format");
    }
  };

  const clientJson = JSON.stringify(client, null, 2);

  return (
    <>
      <Card
        hoverable
        onClick={handleCardClick}
        onMouseEnter={() => setIsHovered(true)}
        onMouseLeave={() => setIsHovered(false)}
        className={`${styles.mcpCard} ${
          client.connected ? styles.enabledCard : ""
        } ${connectionStatus === "error" ? styles.errorCard : ""} ${
          connectionStatus === "connecting" ||
          connectionStatus === "reconnecting"
            ? styles.connectingCard
            : ""
        } ${isHovered ? styles.hover : styles.normal}`}
      >
        <div className={styles.cardHeader}>
          <div className={styles.cardTitleRow}>
            <Tooltip title={client.name}>
              <h3 className={styles.mcpTitle}>{client.name}</h3>
            </Tooltip>
            <span
              className={`${styles.typeBadge} ${
                isRemote ? styles.remote : styles.local
              }`}
            >
              {clientType}
            </span>
            {hasOauth && isOauthExpired && (
              <Tooltip title={t("mcp.oauth.expired")}>
                <ShieldAlert size={13} className={styles.statusIconWarning} />
              </Tooltip>
            )}
            {hasOauth && isOauthAuthorized && (
              <Tooltip title={t("mcp.oauth.authorized")}>
                <ShieldCheck size={13} className={styles.statusIconSuccess} />
              </Tooltip>
            )}
            {hasOauth && !isOauthAuthorized && !isOauthExpired && (
              <Tooltip title={t("mcp.oauth.notAuthorized")}>
                <ShieldX size={13} className={styles.statusIconMuted} />
              </Tooltip>
            )}
          </div>
          <Tooltip title={client.error || connectionLabel}>
            <div className={styles.statusContainer}>
              <span className={styles.statusDot} />
              <span className={styles.statusText}>{connectionLabel}</span>
            </div>
          </Tooltip>
        </div>

        <p className={styles.mcpDescription}>{client.description || "-"}</p>
        {client.enabled && client.error ? (
          <Tooltip title={client.error}>
            <p className={styles.connectionError}>{client.error}</p>
          </Tooltip>
        ) : null}

        {canManage && (
          <div className={styles.cardFooter}>
            <Button
              className={styles.toolsButton}
              onClick={(e) => {
                e.stopPropagation();
                setAccessModalOpen(true);
              }}
              icon={<ToolOutlined />}
            >
              {t("mcp.tools")}
            </Button>
            <div
              className={`${styles.cardSecondaryActions} ${
                isRemote
                  ? styles.cardSecondaryActionsThree
                  : styles.cardSecondaryActionsTwo
              }`}
            >
              {isRemote && (
                <Button
                  className={styles.toggleButton}
                  onClick={(e) => {
                    e.stopPropagation();
                    setOauthModalOpen(true);
                  }}
                  data-status={
                    isOauthAuthorized
                      ? "authorized"
                      : isOauthExpired
                      ? "expired"
                      : "idle"
                  }
                >
                  <span className={styles.buttonContent}>
                    {isOauthAuthorized ? (
                      <ShieldCheck size={13} />
                    ) : isOauthExpired ? (
                      <ShieldAlert size={13} />
                    ) : (
                      <KeyRound size={13} />
                    )}
                    {isOauthAuthorized
                      ? t("mcp.oauth.authorized")
                      : isOauthExpired
                      ? t("mcp.oauth.expired")
                      : t("mcp.oauth.authorize")}
                  </span>
                </Button>
              )}
              <Button
                className={styles.toggleButton}
                onClick={(e) => {
                  e.stopPropagation();
                  handleToggleClick(e);
                }}
                icon={
                  client.enabled ? <EyeInvisibleOutlined /> : <EyeOutlined />
                }
              >
                {client.enabled ? t("common.disable") : t("common.enable")}
              </Button>
              <Button
                className={styles.deleteButton}
                danger
                onClick={(e) => {
                  e.stopPropagation();
                  handleDeleteClick(e);
                }}
              >
                {t("common.delete")}
              </Button>
            </div>
          </div>
        )}
      </Card>

      {canManage && (
        <Modal
          title={t("common.confirm")}
          open={deleteModalOpen}
          onOk={confirmDelete}
          onCancel={() => setDeleteModalOpen(false)}
          okText={t("common.confirm")}
          cancelText={t("common.cancel")}
          okButtonProps={{ danger: true }}
        >
          <p>{t("mcp.deleteConfirm")}</p>
        </Modal>
      )}

      <Modal
        title={`${client.name} - Configuration`}
        open={jsonModalOpen}
        onCancel={closeJsonModal}
        closable={!savingConfig}
        maskClosable={!savingConfig}
        keyboard={!savingConfig}
        footer={
          <div className={styles.modalFooter}>
            <Button onClick={closeJsonModal} disabled={savingConfig}>
              {t("common.cancel")}
            </Button>
            {isEditing && canManage ? (
              <Button
                type="primary"
                loading={savingConfig}
                onClick={handleSaveJson}
              >
                {savingConfig ? t("mcp.savingAndValidating") : t("common.save")}
              </Button>
            ) : canManage ? (
              <Button type="primary" onClick={() => setIsEditing(true)}>
                {t("common.edit")}
              </Button>
            ) : null}
          </div>
        }
        width={700}
      >
        <div className={styles.maskedFieldHint}>{t("mcp.maskedFieldHint")}</div>
        {isEditing && canManage ? (
          <Input.TextArea
            value={editedJson}
            onChange={(e) => setEditedJson(e.target.value)}
            disabled={savingConfig}
            autoSize={{ minRows: 15, maxRows: 25 }}
            className={styles.jsonTextArea}
          />
        ) : (
          <pre className={styles.preformattedText}>{clientJson}</pre>
        )}
      </Modal>

      {canManage && (
        <MCPAccessModal
          client={client}
          open={accessModalOpen}
          onClose={() => setAccessModalOpen(false)}
          onSave={(policy) => onUpdatePolicy(client.key, policy)}
        />
      )}

      {/* Dedicated OAuth modal — opened only via the Authorize button */}
      {canManage && (
        <Modal
          title={
            <div className={styles.modalTitleRow}>
              {isOauthAuthorized ? (
                <ShieldCheck size={16} className={styles.statusIconSuccess} />
              ) : isOauthExpired ? (
                <ShieldAlert size={16} className={styles.statusIconWarning} />
              ) : (
                <ShieldX size={16} className={styles.statusIconMuted} />
              )}
              {`${client.name} — ${t("mcp.oauth.manage")}`}
            </div>
          }
          open={oauthModalOpen}
          onCancel={() => setOauthModalOpen(false)}
          footer={
            <div className={styles.modalFooter}>
              <Button onClick={() => setOauthModalOpen(false)}>
                {t("common.close")}
              </Button>
            </div>
          }
          width={560}
        >
          <MCPOAuthSection
            clientKey={client.key}
            currentOAuthStatus={oauthStatus}
            clientId={oauthClientId}
            clientSecret={oauthClientSecret}
            scope={oauthScope}
            tokenEndpoint={oauthTokenEndpoint}
            onClientIdChange={setOauthClientId}
            onClientSecretChange={setOauthClientSecret}
            onScopeChange={setOauthScope}
            onTokenEndpointChange={setOauthTokenEndpoint}
            onAuthChanged={() => {
              onRefresh?.();
            }}
          />
        </Modal>
      )}
    </>
  );
});
