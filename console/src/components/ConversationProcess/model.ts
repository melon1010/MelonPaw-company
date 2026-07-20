import type {
  IAgentScopeRuntimeMessage,
  IAgentScopeRuntimeResponse,
  AgentScopeRuntimeContentType,
  AgentScopeRuntimeMessageType,
  AgentScopeRuntimeRunStatus,
} from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";

export const ConversationMessageType = {
  MESSAGE: "message" as AgentScopeRuntimeMessageType.MESSAGE,
  REASONING: "reasoning" as AgentScopeRuntimeMessageType.REASONING,
  FUNCTION_CALL: "function_call" as AgentScopeRuntimeMessageType.FUNCTION_CALL,
  FUNCTION_CALL_OUTPUT:
    "function_call_output" as AgentScopeRuntimeMessageType.FUNCTION_CALL_OUTPUT,
  PLUGIN_CALL: "plugin_call" as AgentScopeRuntimeMessageType.PLUGIN_CALL,
  PLUGIN_CALL_OUTPUT:
    "plugin_call_output" as AgentScopeRuntimeMessageType.PLUGIN_CALL_OUTPUT,
  TOOL_CALL: "tool_call" as AgentScopeRuntimeMessageType.TOOL_CALL,
  TOOL_CALL_OUTPUT:
    "tool_call_output" as AgentScopeRuntimeMessageType.TOOL_CALL_OUTPUT,
  COMPONENT_CALL:
    "component_call" as AgentScopeRuntimeMessageType.COMPONENT_CALL,
  COMPONENT_CALL_OUTPUT:
    "component_call_output" as AgentScopeRuntimeMessageType.COMPONENT_CALL_OUTPUT,
  MCP_CALL: "mcp_call" as AgentScopeRuntimeMessageType.MCP_CALL,
  MCP_CALL_OUTPUT:
    "mcp_call_output" as AgentScopeRuntimeMessageType.MCP_CALL_OUTPUT,
  MCP_APPROVAL_REQUEST:
    "mcp_approval_request" as AgentScopeRuntimeMessageType.MCP_APPROVAL_REQUEST,
  MCP_APPROVAL_RESPONSE:
    "mcp_approval_response" as AgentScopeRuntimeMessageType.MCP_APPROVAL_RESPONSE,
} as const;

export const ConversationContentType = {
  TEXT: "text" as AgentScopeRuntimeContentType.TEXT,
  DATA: "data" as AgentScopeRuntimeContentType.DATA,
  REFUSAL: "refusal" as AgentScopeRuntimeContentType.REFUSAL,
} as const;

export const ConversationRunStatus = {
  CREATED: "created" as AgentScopeRuntimeRunStatus.Created,
  IN_PROGRESS: "in_progress" as AgentScopeRuntimeRunStatus.InProgress,
  COMPLETED: "completed" as AgentScopeRuntimeRunStatus.Completed,
  CANCELED: "canceled" as AgentScopeRuntimeRunStatus.Canceled,
  FAILED: "failed" as AgentScopeRuntimeRunStatus.Failed,
  REJECTED: "rejected" as AgentScopeRuntimeRunStatus.Rejected,
  UNKNOWN: "unknown" as AgentScopeRuntimeRunStatus.Unknown,
} as const;

const TOOL_INPUT_TYPES = new Set<AgentScopeRuntimeMessageType>([
  ConversationMessageType.FUNCTION_CALL,
  ConversationMessageType.PLUGIN_CALL,
  ConversationMessageType.TOOL_CALL,
  ConversationMessageType.COMPONENT_CALL,
  ConversationMessageType.MCP_CALL,
]);

const TOOL_OUTPUT_TYPES = new Set<AgentScopeRuntimeMessageType>([
  ConversationMessageType.FUNCTION_CALL_OUTPUT,
  ConversationMessageType.PLUGIN_CALL_OUTPUT,
  ConversationMessageType.TOOL_CALL_OUTPUT,
  ConversationMessageType.COMPONENT_CALL_OUTPUT,
  ConversationMessageType.MCP_CALL_OUTPUT,
]);

export type ConversationRenderSegment =
  | { type: "process"; messages: IAgentScopeRuntimeMessage[] }
  | { type: "answer"; messages: IAgentScopeRuntimeMessage[] };

export function isToolInputMessage(
  message: IAgentScopeRuntimeMessage,
): boolean {
  return TOOL_INPUT_TYPES.has(message.type);
}

export function isToolOutputMessage(
  message: IAgentScopeRuntimeMessage,
): boolean {
  return TOOL_OUTPUT_TYPES.has(message.type);
}

export function isApprovalRequestMessage(
  message: IAgentScopeRuntimeMessage,
): boolean {
  return message.type === ConversationMessageType.MCP_APPROVAL_REQUEST;
}

export function isConversationProcessMessage(
  message: IAgentScopeRuntimeMessage,
): boolean {
  return (
    message.type === ConversationMessageType.REASONING ||
    message.type === ConversationMessageType.MCP_APPROVAL_REQUEST ||
    message.type === ConversationMessageType.MCP_APPROVAL_RESPONSE ||
    isToolInputMessage(message) ||
    isToolOutputMessage(message)
  );
}

export function getToolData(
  message: IAgentScopeRuntimeMessage,
  index = 0,
): Record<string, unknown> {
  const content = message.content?.[index] as { data?: unknown } | undefined;
  return content?.data && typeof content.data === "object"
    ? (content.data as Record<string, unknown>)
    : {};
}

export function getToolName(message: IAgentScopeRuntimeMessage): string {
  const inputData = getToolData(message, 0);
  const outputData = getToolData(message, 1);
  const candidate = inputData.name ?? outputData.name;
  if (typeof candidate === "string" && candidate.trim()) {
    return candidate.trim();
  }
  return isApprovalRequestMessage(message) ? "mcp_approval_request" : "tool";
}

function getToolMessageKey(message: IAgentScopeRuntimeMessage): string | null {
  const data = getToolData(message);
  const candidate =
    data.call_id ?? data.tool_call_id ?? data.id ?? data.name ?? message.id;
  return typeof candidate === "string" && candidate ? candidate : null;
}

function createSyntheticToolInput(
  outputMessage: IAgentScopeRuntimeMessage,
): IAgentScopeRuntimeMessage["content"][number] | null {
  const outputContent = outputMessage.content?.[0];
  if (!outputContent) return null;

  const outputData = getToolData(outputMessage);
  return {
    ...outputContent,
    type: ConversationContentType.DATA,
    data: {
      name: outputData.name ?? "tool",
      server_label: outputData.server_label,
      call_id: outputData.call_id ?? outputData.tool_call_id ?? outputData.id,
      arguments: outputData.arguments ?? outputData.input ?? {},
    },
  };
}

export function pairToolMessages(
  output: IAgentScopeRuntimeResponse["output"] = [],
): IAgentScopeRuntimeMessage[] {
  const inputIndexByKey = new Map<string, number>();
  const messages: IAgentScopeRuntimeMessage[] = [];

  for (const message of output) {
    if (isToolInputMessage(message) && message.content?.length) {
      const key = getToolMessageKey(message);
      if (key) inputIndexByKey.set(key, messages.length);
      messages.push(message);
      continue;
    }

    if (isToolOutputMessage(message) && message.content?.length) {
      const key = getToolMessageKey(message);
      const inputIndex = key ? inputIndexByKey.get(key) : undefined;

      if (inputIndex !== undefined) {
        const inputMessage = messages[inputIndex];
        messages[inputIndex] = {
          ...message,
          id: inputMessage.id,
          content: [...inputMessage.content, message.content[0]],
        };
        continue;
      }

      const syntheticInput = createSyntheticToolInput(message);
      messages.push(
        syntheticInput
          ? {
              ...message,
              content: [syntheticInput, message.content[0]],
            }
          : message,
      );
      continue;
    }

    messages.push(message);
  }

  return messages;
}

export function buildConversationRenderSegments(
  messages: IAgentScopeRuntimeMessage[],
): ConversationRenderSegment[] {
  const segments: ConversationRenderSegment[] = [];

  for (const message of messages) {
    const type = isConversationProcessMessage(message) ? "process" : "answer";
    const last = segments[segments.length - 1];
    if (last?.type === type) {
      last.messages.push(message);
    } else {
      segments.push({ type, messages: [message] } as ConversationRenderSegment);
    }
  }

  return segments;
}

export function getMessageText(message: IAgentScopeRuntimeMessage): string {
  return (message.content ?? [])
    .map((content) => {
      if (content.type === ConversationContentType.TEXT) {
        return content.text ?? "";
      }
      if (content.type === ConversationContentType.REFUSAL) {
        return content.refusal ?? "";
      }
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

export function hasVisibleAnswerContent(
  messages: IAgentScopeRuntimeMessage[],
): boolean {
  return messages.some((message) => {
    if (message.type !== ConversationMessageType.MESSAGE) return false;
    return (message.content ?? []).some((content) => {
      if (content.type === ConversationContentType.DATA) return false;
      if (
        content.type === ConversationContentType.TEXT ||
        content.type === ConversationContentType.REFUSAL
      ) {
        return Boolean(getMessageText(message).trim());
      }
      return true;
    });
  });
}

export function getMessageRunStatus(
  message: IAgentScopeRuntimeMessage,
): AgentScopeRuntimeRunStatus {
  const statuses = [
    message.status,
    ...(message.content ?? []).map((content) => content.status),
  ].filter(Boolean);

  if (statuses.includes(ConversationRunStatus.FAILED)) {
    return ConversationRunStatus.FAILED;
  }
  if (statuses.includes(ConversationRunStatus.REJECTED)) {
    return ConversationRunStatus.REJECTED;
  }
  if (statuses.includes(ConversationRunStatus.CANCELED)) {
    return ConversationRunStatus.CANCELED;
  }
  if (statuses.includes(ConversationRunStatus.IN_PROGRESS)) {
    return ConversationRunStatus.IN_PROGRESS;
  }
  if (statuses.includes(ConversationRunStatus.CREATED)) {
    return ConversationRunStatus.CREATED;
  }
  if (statuses.includes(ConversationRunStatus.UNKNOWN)) {
    return ConversationRunStatus.UNKNOWN;
  }
  return ConversationRunStatus.COMPLETED;
}
