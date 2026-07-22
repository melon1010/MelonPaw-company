import React, { useState, useCallback } from "react";
import { Button, Input } from "@agentscope-ai/design";
import {
  ShieldCheck,
  ShieldX,
  ShieldAlert,
  KeyRound,
  Unlink,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import api from "../../../../api";
import type { MCPClientOAuthStatus } from "../../../../api/types";
import styles from "../index.module.less";

interface MCPOAuthSectionProps {
  clientKey: string;
  onAuthChanged?: () => void;
  currentOAuthStatus?: MCPClientOAuthStatus | null;
  clientId?: string;
  clientSecret?: string;
  scope?: string;
  tokenEndpoint?: string;
  onClientIdChange?: (v: string) => void;
  onClientSecretChange?: (v: string) => void;
  onScopeChange?: (v: string) => void;
  onTokenEndpointChange?: (v: string) => void;
}

type OAuthPhase = "idle" | "starting" | "success" | "error" | "revoking";

export const MCPOAuthSection: React.FC<MCPOAuthSectionProps> = ({
  clientKey,
  onAuthChanged,
  currentOAuthStatus,
  clientId = "",
  clientSecret = "",
  scope = "",
  tokenEndpoint = "",
  onClientIdChange,
  onClientSecretChange,
  onScopeChange,
  onTokenEndpointChange,
}) => {
  const { t } = useTranslation();

  const [phase, setPhase] = useState<OAuthPhase>("idle");
  const [errorMsg, setErrorMsg] = useState("");

  const isAuthorized =
    phase === "success" ||
    (phase === "idle" && currentOAuthStatus?.authorized === true);

  const isExpired =
    phase === "idle" &&
    (currentOAuthStatus?.expires_at ?? 0) > 0 &&
    (currentOAuthStatus?.expires_at ?? 0) <= Date.now() / 1000;
  const isRevoking = phase === "revoking";
  const credentialsComplete =
    tokenEndpoint.trim().length > 0 &&
    clientId.trim().length > 0 &&
    clientSecret.length > 0;

  const handleStartOAuth = useCallback(async () => {
    if (!clientKey) {
      setErrorMsg(t("mcp.oauth.noClientKey"));
      return;
    }
    if (!credentialsComplete) {
      setErrorMsg(t("mcp.oauth.credentialsRequired"));
      return;
    }

    setPhase("starting");
    setErrorMsg("");

    try {
      const status = await api.configureOAuth(clientKey, {
        grant_type: "client_credentials",
        token_url: tokenEndpoint.trim(),
        client_id: clientId.trim(),
        client_secret: clientSecret,
        scope,
      });
      if (!status.authorized) {
        throw new Error(t("mcp.oauth.authFailed"));
      }
      setPhase("success");
      onClientSecretChange?.("");
      onAuthChanged?.();
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : t("mcp.oauth.startFailed");
      setPhase("error");
      setErrorMsg(msg);
    }
  }, [
    clientKey,
    clientId,
    clientSecret,
    credentialsComplete,
    onAuthChanged,
    onClientSecretChange,
    scope,
    tokenEndpoint,
    t,
  ]);

  const handleRevoke = useCallback(async () => {
    if (!clientKey) return;
    setPhase("revoking");
    try {
      await api.revokeOAuth(clientKey);
      setPhase("idle");
      setErrorMsg("");
      onAuthChanged?.();
    } catch (error) {
      setPhase("idle");
      setErrorMsg(
        error instanceof Error ? error.message : t("mcp.oauth.revokeFailed"),
      );
    }
  }, [clientKey, onAuthChanged, t]);

  return (
    <div className={styles.oauthSection}>
      <div className={styles.oauthStatusRow}>
        {isExpired ? (
          <OAuthBadge
            icon={<ShieldAlert size={14} />}
            label={t("mcp.oauth.expired")}
            tone="warning"
          />
        ) : isAuthorized ? (
          <OAuthBadge
            icon={<ShieldCheck size={14} />}
            label={t("mcp.oauth.authorized")}
            tone="success"
          />
        ) : phase === "error" ? (
          <OAuthBadge
            icon={<ShieldX size={14} />}
            label={t("mcp.oauth.failed")}
            tone="danger"
          />
        ) : (
          <OAuthBadge
            icon={<ShieldX size={14} />}
            label={t("mcp.oauth.notAuthorized")}
            tone="muted"
          />
        )}

        <div className={styles.oauthActions}>
          {(isAuthorized || isExpired) && clientKey && (
            <Button size="small" onClick={handleRevoke} loading={isRevoking}>
              <span className={styles.buttonContent}>
                <Unlink size={12} />
                {t("mcp.oauth.revoke")}
              </span>
            </Button>
          )}
          <Button
            size="small"
            type={isAuthorized && !isExpired ? "default" : "primary"}
            onClick={handleStartOAuth}
            loading={phase === "starting"}
            disabled={!credentialsComplete}
          >
            <span className={styles.buttonContent}>
              <KeyRound size={12} />
              {isAuthorized && !isExpired
                ? t("mcp.oauth.reauthorize")
                : t("mcp.oauth.authorize")}
            </span>
          </Button>
        </div>
      </div>

      {errorMsg && <p className={styles.oauthError}>{errorMsg}</p>}
      <p className={styles.oauthHint}>{t("mcp.oauth.credentialsHint")}</p>
      <div className={styles.oauthFields}>
        <label className={styles.oauthField}>
          <span>{t("mcp.oauth.tokenEndpoint")}</span>
          <Input
            size="small"
            placeholder="https://auth.example.com/oauth/token"
            value={tokenEndpoint}
            onChange={(e) => onTokenEndpointChange?.(e.target.value)}
          />
        </label>

        <label className={styles.oauthField}>
          <span>{t("mcp.oauth.clientId")}</span>
          <Input
            size="small"
            placeholder={t("mcp.oauth.clientIdPlaceholder")}
            value={clientId}
            onChange={(e) => onClientIdChange?.(e.target.value)}
          />
        </label>

        <label className={styles.oauthField}>
          <span>{t("mcp.oauth.clientSecret")}</span>
          <Input.Password
            size="small"
            autoComplete="new-password"
            placeholder={t("mcp.oauth.clientSecretPlaceholder")}
            value={clientSecret}
            onChange={(e) => onClientSecretChange?.(e.target.value)}
          />
        </label>

        <label className={styles.oauthField}>
          <span>{t("mcp.oauth.scope")}</span>
          <Input
            size="small"
            placeholder={t("mcp.oauth.scopePlaceholder")}
            value={scope}
            onChange={(e) => onScopeChange?.(e.target.value)}
          />
        </label>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

interface OAuthBadgeProps {
  icon: React.ReactNode;
  label: string;
  tone: "warning" | "success" | "danger" | "muted";
}

const OAuthBadge: React.FC<OAuthBadgeProps> = ({ icon, label, tone }) => {
  const toneClass = {
    warning: styles.oauthBadgeWarning,
    success: styles.oauthBadgeSuccess,
    danger: styles.oauthBadgeDanger,
    muted: styles.oauthBadgeMuted,
  }[tone];

  return (
    <span className={`${styles.oauthBadge} ${toneClass}`}>
      {icon}
      <span>{label}</span>
    </span>
  );
};
