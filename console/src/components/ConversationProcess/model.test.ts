import { describe, expect, it } from "vitest";
import type { IAgentScopeRuntimeMessage } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import {
  ConversationContentType,
  ConversationMessageType,
  ConversationRunStatus,
  buildConversationRenderSegments,
  isConversationProcessMessage,
  isProgressMessage,
  isPersistentToolResultMessage,
  pairToolMessages,
  recoverCompletedAnswer,
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

  it("keeps sent file results visible outside the collapsed process", () => {
    const input = message("file-call", ConversationMessageType.TOOL_CALL, [
      toolContent({
        call_id: "file-1",
        name: "send_file_to_user",
        arguments: { file_path: "report.pdf" },
      }),
    ]);
    const output = message(
      "file-output",
      ConversationMessageType.TOOL_CALL_OUTPUT,
      [
        toolContent({
          call_id: "file-1",
          name: "send_file_to_user",
          output: [{ type: "file", url: "/preview/report.pdf" }],
        }),
      ],
    );

    const [paired] = pairToolMessages([input, output]);

    expect(isPersistentToolResultMessage(paired)).toBe(true);
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

  it("keeps non-terminal assistant narration in the process segment", () => {
    const progress = {
      ...message("progress", ConversationMessageType.MESSAGE, [
        {
          type: ConversationContentType.TEXT,
          text: "I will inspect the workspace.",
          status: ConversationRunStatus.COMPLETED,
        },
      ]),
      metadata: { phase: "progress", terminal: false, turn_id: "turn-1" },
    } as IAgentScopeRuntimeMessage & {
      metadata: Record<string, unknown>;
    };
    const answer = message("answer", ConversationMessageType.MESSAGE, [
      {
        type: ConversationContentType.TEXT,
        text: "Done.",
        status: ConversationRunStatus.COMPLETED,
      },
    ]);

    expect(isProgressMessage(progress)).toBe(true);
    expect(buildConversationRenderSegments([progress, answer]).map((segment) => segment.type))
      .toEqual(["process", "answer"]);
  });

  it("recovers the last visible progress message for completed legacy history", () => {
    const firstProgress = {
      ...message("progress-1", ConversationMessageType.MESSAGE, [
        {
          type: ConversationContentType.TEXT,
          text: "I am checking.",
          status: ConversationRunStatus.COMPLETED,
        },
      ]),
      metadata: { phase: "progress", terminal: false },
    } as IAgentScopeRuntimeMessage;
    const lastProgress = {
      ...message("progress-2", ConversationMessageType.MESSAGE, [
        {
          type: ConversationContentType.TEXT,
          text: "Completed result.",
          status: ConversationRunStatus.COMPLETED,
        },
      ]),
      metadata: { phase: "progress", terminal: false },
    } as IAgentScopeRuntimeMessage;

    const recovered = recoverCompletedAnswer(
      [firstProgress, lastProgress],
      true,
    );

    expect(isProgressMessage(recovered[0])).toBe(true);
    expect(isProgressMessage(recovered[1])).toBe(false);
    expect(
      (recovered[1] as IAgentScopeRuntimeMessage & {
        metadata: Record<string, unknown>;
      }).metadata,
    ).toMatchObject({ phase: "final", terminal: true });
  });

  it("does not recover progress while a response is still running", () => {
    const progress = {
      ...message("progress", ConversationMessageType.MESSAGE, [
        {
          type: ConversationContentType.TEXT,
          text: "Still running.",
          status: ConversationRunStatus.COMPLETED,
        },
      ]),
      metadata: { phase: "progress", terminal: false },
    } as IAgentScopeRuntimeMessage;

    expect(recoverCompletedAnswer([progress], false)).toEqual([progress]);
  });
});
