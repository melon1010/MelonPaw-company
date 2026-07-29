import { useCallback, useEffect, useState } from "react";
import { useAppMessage } from "../../../hooks/useAppMessage";
import api from "../../../api";
import type { MCPAccessPolicy, MCPClientInfo } from "../../../api/types";
import { useTranslation } from "react-i18next";
import { useAgentStore } from "../../../stores/agentStore";

export function useMCP() {
  const { t } = useTranslation();
  const { selectedAgent, agents } = useAgentStore();
  const [clients, setClients] = useState<MCPClientInfo[]>([]);
  const [canManage, setCanManage] = useState(false);
  const [loading, setLoading] = useState(false);
  const { message } = useAppMessage();

  const selectedAgentSummary = agents.find(
    (agent) => agent.id === selectedAgent,
  );
  const isPersonalScope =
    selectedAgent === "default" ||
    selectedAgentSummary?.scope === "PERSONAL" ||
    (!selectedAgentSummary && selectedAgent.startsWith("personal-"));

  const loadClients = useCallback(
    async (silent = false) => {
      if (!silent) {
        setLoading(true);
        // Team scopes fail closed while capability discovery is in flight;
        // personal scope remains editable by its owner.
        setCanManage(isPersonalScope);
      }
      try {
        const data = await api.listMCPClients();
        setClients(data);
        try {
          const capabilities = await api.getMCPCapabilities();
          // Personal workspaces belong to the signed-in user and remain
          // editable even when an older backend has no capability endpoint.
          // Project workspaces always use the server-provided decision.
          setCanManage(isPersonalScope || capabilities.can_manage === true);
        } catch (capabilityError) {
          // Fail closed for project scopes. Personal scope is the user's own
          // workspace and must not lose its management controls because of a
          // transient capability request failure.
          setCanManage(isPersonalScope);
          console.warn("Failed to load MCP capabilities:", capabilityError);
        }
      } catch (error) {
        console.error("Failed to load MCP clients:", error);
        if (!silent) message.error(t("mcp.loadError"));
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [isPersonalScope, message, t],
  );

  useEffect(() => {
    loadClients();
  }, [loadClients, selectedAgent]);

  useEffect(() => {
    const pending = clients.some(
      (client) =>
        client.status === "connecting" || client.status === "reconnecting",
    );
    if (!pending) return;
    const timer = window.setTimeout(() => void loadClients(true), 2000);
    return () => window.clearTimeout(timer);
  }, [clients, loadClients]);

  const createClient = useCallback(
    async (
      key: string,
      clientData: {
        name: string;
        description?: string;
        command: string;
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
      },
    ) => {
      try {
        await api.createMCPClient({
          client_key: key,
          client: clientData,
        });
        message.success(t("mcp.createSuccess"));
        await loadClients();
        return true;
      } catch (error: any) {
        const errorMsg = error?.message || t("mcp.createError");
        message.error(errorMsg);
        return false;
      }
    },
    [message, t, loadClients],
  );

  const updateClient = useCallback(
    async (
      key: string,
      updates: {
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
      },
    ) => {
      try {
        await api.updateMCPClient(key, updates);
        message.success(t("mcp.updateSuccess"));
        await loadClients();
        return true;
      } catch (error: any) {
        const errorMsg = error?.message || t("mcp.updateError");
        message.error(errorMsg);
        return false;
      }
    },
    [message, t, loadClients],
  );

  const toggleEnabled = useCallback(
    async (client: MCPClientInfo) => {
      try {
        await api.toggleMCPClient(client.key);
        message.success(
          client.enabled ? t("mcp.disableSuccess") : t("mcp.enableSuccess"),
        );
        await loadClients();
      } catch (error) {
        message.error(t("mcp.toggleError"));
      }
    },
    [message, t, loadClients],
  );

  const deleteClient = useCallback(
    async (client: MCPClientInfo) => {
      try {
        await api.deleteMCPClient(client.key);
        message.success(t("mcp.deleteSuccess"));
        await loadClients();
      } catch (error) {
        message.error(t("mcp.deleteError"));
      }
    },
    [message, t, loadClients],
  );

  const updatePolicy = useCallback(
    async (clientKey: string, policy: MCPAccessPolicy) => {
      try {
        await api.updateMCPPolicy(clientKey, policy);
        message.success(t("mcp.access.saveSuccess"));
        await loadClients();
        return true;
      } catch (error: any) {
        const errorMsg = error?.message || t("mcp.access.saveError");
        message.error(errorMsg);
        return false;
      }
    },
    [message, t, loadClients],
  );

  return {
    clients,
    canManage,
    loading,
    createClient,
    updateClient,
    updatePolicy,
    toggleEnabled,
    deleteClient,
    refreshClients: loadClients,
  };
}
