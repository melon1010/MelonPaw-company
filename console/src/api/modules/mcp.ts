import { request } from "../request";
import type {
  MCPClientInfo,
  MCPCapabilities,
  MCPClientCreateRequest,
  MCPClientUpdateRequest,
  MCPToolInfo,
  MCPAccessPrincipalOption,
  MCPAccessPolicy,
  MCPOAuthConfigureRequest,
  MCPOAuthConfigureResponse,
  MCPOAuthStatusResponse,
} from "../types";

const clientEndpoint = (suffix: string, clientKey: string) =>
  `/mcp/client${suffix}?client_key=${encodeURIComponent(clientKey)}`;

export const mcpApi = {
  /**
   * List all MCP clients
   */
  listMCPClients: () => request<MCPClientInfo[]>("/mcp"),

  /** Return mutation capabilities for the currently selected Agent scope. */
  getMCPCapabilities: () => request<MCPCapabilities>("/mcp/capabilities"),

  /**
   * Get details of a specific MCP client
   */
  getMCPClient: (clientKey: string) =>
    request<MCPClientInfo>(clientEndpoint("", clientKey)),

  /**
   * Create a new MCP client
   */
  createMCPClient: (body: MCPClientCreateRequest) =>
    request<MCPClientInfo>("/mcp", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * Update an existing MCP client
   */
  updateMCPClient: (clientKey: string, body: MCPClientUpdateRequest) =>
    request<MCPClientInfo>(clientEndpoint("", clientKey), {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  /**
   * Toggle MCP client enabled status
   */
  toggleMCPClient: (clientKey: string) =>
    request<MCPClientInfo>(clientEndpoint("/toggle", clientKey), {
      method: "PATCH",
    }),

  /**
   * Delete an MCP client
   */
  deleteMCPClient: (clientKey: string) =>
    request<{ message: string }>(clientEndpoint("", clientKey), {
      method: "DELETE",
    }),

  /**
   * List tools from a connected MCP server
   */
  listMCPTools: (clientKey: string) =>
    request<MCPToolInfo[]>(clientEndpoint("/tools", clientKey)),

  /**
   * List recent source-scoped principals for MCP access rules.
   */
  listMCPAccessPrincipals: () =>
    request<MCPAccessPrincipalOption[]>("/mcp/access-principals"),

  /**
   * Get saved MCP access policy. Does not require the MCP server to be online.
   */
  getMCPPolicy: (clientKey: string) =>
    request<MCPAccessPolicy>(clientEndpoint("/policy", clientKey)),

  /**
   * Update saved MCP access policy. Does not require the MCP server to be online.
   */
  updateMCPPolicy: (clientKey: string, body: MCPAccessPolicy) =>
    request<MCPAccessPolicy>(clientEndpoint("/policy", clientKey), {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  /**
   * Update tool whitelist for an MCP client
   */
  updateMCPToolWhitelist: (clientKey: string, tools: string[] | null) =>
    request<MCPToolInfo[]>(clientEndpoint("/tools", clientKey), {
      method: "PUT",
      body: JSON.stringify({ tools }),
    }),

  /** Configure and verify OAuth 2.0 client-credentials for a remote MCP client. */
  configureOAuth: (clientKey: string, body: MCPOAuthConfigureRequest) =>
    request<MCPOAuthConfigureResponse>(
      clientEndpoint("/oauth/configure", clientKey),
      {
        method: "POST",
        body: JSON.stringify(body),
      },
    ),

  /**
   * Get current OAuth token status for an MCP client.
   */
  getOAuthStatus: (clientKey: string) =>
    request<MCPOAuthStatusResponse>(clientEndpoint("/oauth/status", clientKey)),

  /**
   * Revoke / clear OAuth tokens for an MCP client.
   */
  revokeOAuth: (clientKey: string) =>
    request<{ message: string }>(clientEndpoint("/oauth", clientKey), {
      method: "DELETE",
    }),
};
