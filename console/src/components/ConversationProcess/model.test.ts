import { describe, expect, it } from "vitest";
import type { IAgentScopeRuntimeMessage } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import {
  ConversationContentType,
  ConversationMessageType,
  ConversationRunStatus,
  buildConversationRenderSegments,
  isConversationProcessMessage,
  pairToolMessages,
} from "./model";

function message(
  id: string,
  type: IAgentScopeRuntimeMessage["type"],
  content: IAgentScopeRuntimeMessage["content"],
): IAgentScopeRuntimeMessage {
  return {
    id,
    type,
    content,
    role: "assistant",
    status: ConversationRunStatus.COMPLETED,
  };
}

function toolContent(data: Record<string, unknown>) {
  return {
    type: ConversationContentType.DATA,
    data,
    status: ConversationRunStatus.COMPLETED,
  } as const;
}

describe("ConversationProcess model", () => {
  it("pairs tool input and output by call id", () => {
    const input = message("call-message", ConversationMessageType.PLUGIN_CALL, [
      toolContent({
        call_id: "call-1",
        name: "read_file",
        arguments: '{"path":"README.md"}',
      }),
    ]);
    const output = message(
      "output-message",
      ConversationMessageType.PLUGIN_CALL_OUTPUT,
      [toolContent({ call_id: "call-1", name: "read_file", output: "ok" })],
    );

    const result = pairToolMessages([input, output]);

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("call-message");
    expect(result[0].type).toBe(ConversationMessageType.PLUGIN_CALL_OUTPUT);
    expect(result[0].content).toHaveLength(2);
  });

  it("keeps an unmatched tool output by creating a display input", () => {
    const output = message(
      "output-message",
      ConversationMessageType.TOOL_CALL_OUTPUT,
      [toolContent({ call_id: "call-2", name: "search", output: "found" })],
    );

    const result = pairToolMessages([output]);

    expect(result).toHaveLength(1);
    expect(result[0].content).toHaveLength(2);
    expect(
      (result[0].content[0] as unknown as { data: { name: string } }).data.name,
    ).toBe("search");
  });

  it("classifies reasoning, tools and approval as process messages", () => {
    const content = [toolContent({ name: "tool" })];

    expect(
      isConversationProcessMessage(
        message("reasoning", ConversationMessageType.REASONING, content),
      ),
    ).toBe(true);
    expect(
      isConversationProcessMessage(
        message("tool", ConversationMessageType.COMPONENT_CALL, content),
      ),
    ).toBe(true);
    expect(
      isConversationProcessMessage(
        message(
          "approval",
          ConversationMessageType.MCP_APPROVAL_REQUEST,
          content,
        ),
      ),
    ).toBe(true);
    expect(
      isConversationProcessMessage(
        message("answer", ConversationMessageType.MESSAGE, content),
      ),
    ).toBe(false);
  });

  it("preserves alternating process and answer order", () => {
    const content = [toolContent({ name: "tool" })];
    const segments = buildConversationRenderSegments([
      message("reasoning-1", ConversationMessageType.REASONING, content),
      message("tool-1", ConversationMessageType.PLUGIN_CALL, content),
      message("answer-1", ConversationMessageType.MESSAGE, content),
      message("reasoning-2", ConversationMessageType.REASONING, content),
      message("answer-2", ConversationMessageType.MESSAGE, content),
    ]);

    expect(segments.map((segment) => segment.type)).toEqual([
      "process",
      "answer",
      "process",
      "answer",
    ]);
    expect(segments[0].messages).toHaveLength(2);
  });
});
