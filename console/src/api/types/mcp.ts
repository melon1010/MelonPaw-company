/**
 * MCP (Model Context Protocol) client types
 */

export interface MCPClientOAuthStatus {
  /** Whether a valid access token is present */
  authorized: boolean;
  /** Unix timestamp when the access token expires (0 = unknown) */
  expires_at: number;
  /** Granted OAuth scope(s) */
  scope: string;
  /** OAuth client_id used */
  client_id: string;
  /** OAuth token endpoint used for client-credentials authentication */
  token_url: string;
}

export type MCPAccessEffect = "allow" | "ask" | "deny";

export interface MCPAccessSummary {
  default_effect: MCPAccessEffect;
  overrides_count: number;
}

export interface MCPClientInfo {
  /** Unique client key identifier */
  key: string;
  /** Client display name */
  name: string;
  /** Client description */
  description: string;
  /** Whether the client is enabled */
  enabled: boolean;
  /** Whether the MCP transport is currently connected */
  connected?: boolean;
  /** Live connection lifecycle state */
  status?:
    | "connected"
    | "idle"
    | "connecting"
    | "reconnecting"
    | "disconnected"
    | "disabled"
    | "error";
  /** Latest safe connection error */
  error?: string;
  /** Number of reconnect attempts since process start */
  restart_count?: number;
  last_checked_at?: string;
  next_retry_at?: string | null;
  /** MCP transport type */
  transport: "stdio" | "streamable_http" | "sse";
  /** Remote MCP endpoint URL for HTTP/SSE transport */
  url: string;
  /** HTTP headers for remote transport */
  headers: Record<string, string>;
  /** Command to launch the MCP server */
  command: string;
  /** Command-line arguments */
  args: string[];
  /** Environment variables */
  env: Record<string, string>;
  /** Working directory for stdio command */
  cwd: string;
  /** Startup and tool-call governance */
  startup_timeout_seconds?: number;
  tool_timeout_seconds?: number;
  tool_timeouts?: Record<string, number>;
  max_inline_result_bytes?: number;
  /** Tool whitelist (null means all tools enabled) */
  tools: string[] | null;
  /** OAuth status (null if OAuth not configured) */
  oauth_status: MCPClientOAuthStatus | null;
  /** Summarised MCP access policy */
  access_summary: MCPAccessSummary;
}

export interface MCPOAuthConfigureRequest {
  grant_type: "client_credentials";
  token_url: string;
  client_id: string;
  client_secret: string;
  /** OAuth scope(s) to request */
  scope?: string;
}

export type MCPOAuthConfigureResponse = MCPClientOAuthStatus;

export type MCPOAuthStatusResponse = MCPClientOAuthStatus;

export interface MCPClientCreateRequest {
  /** Unique client key identifier */
  client_key: string;
  /** Client configuration */
  client: {
    /** Client display name */
    name: string;
    /** Client description */
    description?: string;
    /** Whether to enable the client */
    enabled?: boolean;
    /** MCP transport type */
    transport?: "stdio" | "streamable_http" | "sse";
    /** Remote MCP endpoint URL for HTTP/SSE transport */
    url?: string;
    /** HTTP headers for remote transport */
    headers?: Record<string, string>;
    /** Command to launch the MCP server */
    command?: string;
    /** Command-line arguments */
    args?: string[];
    /** Environment variables */
    env?: Record<string, string>;
    /** Working directory for stdio command */
    cwd?: string;
    startup_timeout_seconds?: number;
    tool_timeout_seconds?: number;
    tool_timeouts?: Record<string, number>;
    max_inline_result_bytes?: number;
  };
}

export interface MCPToolInfo {
  /** Tool name */
  name: string;
  /** Tool description */
  description: string;
  /** Whether this tool is enabled (passes the whitelist) */
  enabled: boolean;
  /** JSON Schema for the tool's input parameters */
  input_schema: Record<string, unknown>;
}

export type MCPAccessSourceType = "channel" | (string & {});
export type MCPAccessSubjectType = "all" | "user";

export interface MCPAccessRule {
  /** Where the tool call comes from */
  source_type: MCPAccessSourceType;
  /** Concrete source, e.g. console, dingtalk */
  source_value: string;
  /** Object scope within the source */
  subject_type: MCPAccessSubjectType;
  /** Concrete object value when subject_type is user */
  subject_value: string;
  /** Access effect for this tool */
  effect: MCPAccessEffect;
}

export interface MCPAccessPrincipalOption {
  /** Where the tool call comes from */
  source_type: MCPAccessSourceType;
  /** Concrete source, e.g. console, dingtalk */
  source_value: string;
  /** Selectable object type */
  subject_type: "user";
  /** User identifier within the selected source */
  subject_value: string;
  /** Display label */
  label: string;
  /** Recent chat UUID */
  chat_id: string;
  /** Recent chat name */
  chat_name: string;
  /** Recent session ID */
  session_id: string;
  /** Most recent chat update timestamp */
  updated_at: string | null;
}

export interface MCPToolDefaultPolicy {
  /** MCP tool name */
  tool_name: string;
  /** Default effect for this tool */
  effect: MCPAccessEffect;
}

export interface MCPToolAccessOverride extends MCPAccessRule {
  /** MCP tool name */
  tool_name: string;
}

export interface MCPAccessPolicy {
  /** Default effect when no MCP rule matches */
  default_effect: MCPAccessEffect;
  /** Console-managed MCP-wide source/object overrides */
  client_overrides: MCPAccessRule[];
  /** Console-managed default effects for individual tools */
  tool_defaults: MCPToolDefaultPolicy[];
  /** Console-managed per-source/per-object/per-tool overrides */
  tool_overrides: MCPToolAccessOverride[];
  /** Preserved rules not editable from the MCP console */
  unmanaged_rules_count: number;
}

export interface MCPClientUpdateRequest {
  /** Client display name */
  name?: string;
  /** Client description */
  description?: string;
  /** Whether to enable the client */
  enabled?: boolean;
  /** MCP transport type */
  transport?: "stdio" | "streamable_http" | "sse";
  /** Remote MCP endpoint URL for HTTP/SSE transport */
  url?: string;
  /** HTTP headers for remote transport */
  headers?: Record<string, string>;
  /** Command to launch the MCP server */
  command?: string;
  /** Command-line arguments */
  args?: string[];
  /** Environment variables */
  env?: Record<string, string>;
  /** Working directory for stdio command */
  cwd?: string;
  startup_timeout_seconds?: number;
  tool_timeout_seconds?: number;
  tool_timeouts?: Record<string, number>;
  max_inline_result_bytes?: number;
}
