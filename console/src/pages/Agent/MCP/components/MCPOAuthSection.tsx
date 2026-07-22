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
    phase === "success" || (phase === "idle" && currentOAuthStatus?.authorized === true);

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
    <div style={sectionStyle}>
      {/* Status badge */}
      <div style={statusRowStyle}>
        {isExpired ? (
          <OAuthBadge
            icon={<ShieldAlert size={14} />}
            label={t("mcp.oauth.expired")}
            color="#e67e22"
          />
        ) : isAuthorized ? (
          <OAuthBadge
            icon={<ShieldCheck size={14} />}
            label={t("mcp.oauth.authorized")}
            color="#27ae60"
          />
        ) : phase === "error" ? (
          <OAuthBadge
            icon={<ShieldX size={14} />}
            label={t("mcp.oauth.failed")}
            color="#c0392b"
          />
        ) : (
          <OAuthBadge
            icon={<ShieldX size={14} />}
            label={t("mcp.oauth.notAuthorized")}
            color="#7f8c8d"
          />
        )}

        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          {(isAuthorized || isExpired) && clientKey && (
            <Button size="small" onClick={handleRevoke} loading={isRevoking}>
              <span
                style={{ display: "inline-flex", alignItems: "center", gap: 4 }}
              >
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
            <span
              style={{ display: "inline-flex", alignItems: "center", gap: 4 }}
            >
              <KeyRound size={12} />
              {isAuthorized && !isExpired
                ? t("mcp.oauth.reauthorize")
                : t("mcp.oauth.authorize")}
            </span>
          </Button>
        </div>
      </div>

      {errorMsg && <p style={errorStyle}>{errorMsg}</p>}
      <p style={hintStyle}>{t("mcp.oauth.credentialsHint")}</p>
      <div style={advancedStyle}>
        <label style={labelStyle}>{t("mcp.oauth.tokenEndpoint")}</label>
        <Input
          size="small"
          placeholder="https://auth.example.com/oauth/token"
          value={tokenEndpoint}
          onChange={(e) => onTokenEndpointChange?.(e.target.value)}
        />

        <label style={{ ...labelStyle, marginTop: 8 }}>
          {t("mcp.oauth.clientId")}
        </label>
        <Input
          size="small"
          placeholder={t("mcp.oauth.clientIdPlaceholder")}
          value={clientId}
          onChange={(e) => onClientIdChange?.(e.target.value)}
        />

        <label style={{ ...labelStyle, marginTop: 8 }}>
          {t("mcp.oauth.clientSecret")}
        </label>
        <Input.Password
          size="small"
          autoComplete="new-password"
          placeholder={t("mcp.oauth.clientSecretPlaceholder")}
          value={clientSecret}
          onChange={(e) => onClientSecretChange?.(e.target.value)}
        />

        <label style={{ ...labelStyle, marginTop: 8 }}>
          {t("mcp.oauth.scope")}
        </label>
        <Input
          size="small"
          placeholder={t("mcp.oauth.scopePlaceholder")}
          value={scope}
          onChange={(e) => onScopeChange?.(e.target.value)}
        />
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
  color: string;
}

const OAuthBadge: React.FC<OAuthBadgeProps> = ({ icon, label, color }) => (
  <span style={{ ...badgeStyle, color, borderColor: color }}>
    {icon}
    <span style={{ marginLeft: 4 }}>{label}</span>
  </span>
);

// ---------------------------------------------------------------------------
// Inline styles (keeps JSX concise, avoids extra CSS module entries)
// ---------------------------------------------------------------------------

const sectionStyle: React.CSSProperties = {
  background: "#f8f9fa",
  border: "1px solid #e9ecef",
  borderRadius: 8,
  padding: "12px 14px",
  marginTop: 8,
};

const statusRowStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 8,
};

const badgeStyle: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  fontSize: 12,
  padding: "2px 8px",
  borderRadius: 12,
  border: "1px solid",
  background: "white",
};

const errorStyle: React.CSSProperties = {
  color: "#c0392b",
  fontSize: 12,
  marginTop: 6,
  marginBottom: 0,
};

const hintStyle: React.CSSProperties = {
  color: "#666",
  fontSize: 12,
  margin: "10px 0 0",
};

const advancedStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: 4,
  marginTop: 10,
  padding: "10px 12px",
  background: "white",
  borderRadius: 6,
  border: "1px solid #e9ecef",
};

const labelStyle: React.CSSProperties = {
  fontSize: 11,
  color: "#888",
  marginBottom: 2,
};
