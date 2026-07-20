import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { IAgentScopeRuntimeMessage } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import ConversationProcess from "./index";
import {
  ConversationContentType,
  ConversationMessageType,
  ConversationRunStatus,
} from "./model";

const labels: Record<string, string> = {
  "chat.executionProcess.starting": "Starting to think",
  "chat.executionProcess.active": "Thinking",
  "chat.executionProcess.completed": "Finished thinking",
  "chat.executionProcess.finished": "Completed",
  "chat.executionProcess.steps": "steps",
  "chat.executionProcess.tools": "tools",
  "chat.executionProcess.showMore": "Show more",
  "chat.executionProcess.showLess": "Collapse",
  "chat.executionProcess.reasoning": "Reasoning",
};

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string, options?: { count?: number }) =>
      options?.count === undefined
        ? labels[key] ?? key
        : `${options.count} ${labels[key] ?? key}`,
  }),
}));

function reasoningMessage(
  id: string,
  text: string,
  status: IAgentScopeRuntimeMessage["status"] = ConversationRunStatus.IN_PROGRESS,
): IAgentScopeRuntimeMessage {
  return {
    id,
    role: "assistant",
    type: ConversationMessageType.REASONING,
    status,
    content: [
      {
        type: ConversationContentType.TEXT,
        text,
        status,
      },
    ],
  };
}

describe("ConversationProcess", () => {
  it("opens while generating and collapses after the answer completes", async () => {
    const user = userEvent.setup();
    const reasoningText =
      "Inspecting the workspace and preparing a detailed answer.";
    const processMessage = reasoningMessage("reasoning-1", reasoningText);
    const { rerender } = render(
      <ConversationProcess
        messages={[processMessage]}
        hasAnswerContent={false}
        isGenerating
      />,
    );

    expect(screen.getByText("Thinking")).toBeInTheDocument();
    expect(screen.getByText(reasoningText)).toBeInTheDocument();

    rerender(
      <ConversationProcess
        messages={[
          {
            ...processMessage,
            status: ConversationRunStatus.COMPLETED,
            content: processMessage.content.map((content) => ({
              ...content,
              status: ConversationRunStatus.COMPLETED,
            })),
          },
        ]}
        hasAnswerContent
        isGenerating={false}
      />,
    );

    expect(screen.getByText("Finished thinking")).toBeInTheDocument();
    expect(screen.queryByText(reasoningText)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Finished thinking/ }));
    expect(screen.getByText(reasoningText)).toBeInTheDocument();
    expect(screen.getByText("Completed")).toBeInTheDocument();
  });

  it("limits completed history to four steps until expanded", async () => {
    const user = userEvent.setup();
    const messages = Array.from({ length: 5 }, (_, index) =>
      reasoningMessage(
        `reasoning-${index}`,
        `Reasoning step ${index + 1}`,
        ConversationRunStatus.COMPLETED,
      ),
    );

    render(
      <ConversationProcess
        messages={messages}
        hasAnswerContent
        isGenerating={false}
      />,
    );

    await user.click(screen.getByRole("button", { name: /Finished thinking/ }));
    expect(screen.getByText("Reasoning step 4")).toBeInTheDocument();
    expect(screen.queryByText("Reasoning step 5")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Show more" }));
    expect(screen.getByText("Reasoning step 5")).toBeInTheDocument();
  });
});
