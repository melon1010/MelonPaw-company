/**
 * pages/Chat/HostBubbles.tsx — host-side wrappers around the vendor's
 * AgentScopeRuntime{Request,Response}Card components.
 *
 * Why wrappers:
 * - Plugin extensions (chat.request.render / prepend / append and the
 *   response equivalents) need a render seam SDK doesn't expose.
 * - We register HostRequestCard / HostResponseCard into options.cards so the
 *   SDK Cards dispatcher invokes them instead of the vendor defaults.
 * - The wrapper itself subscribes to the chat extension registry via hooks,
 *   so it re-renders when plugins register/dispose — no need to rebuild the
 *   parent useMemo (and avoid re-mounting bubbles on every plugin change).
 *
 * Vendor response primitives are deep-imported because they are not in the
 * package's top-level exports. If the SDK reorganizes its internal paths,
 * update the imports below.
 */
import React, { useMemo } from "react";
// eslint-disable-next-line import/no-unresolved
import VendorRequestCardOriginal from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Request/Card";
// eslint-disable-next-line import/no-unresolved
import VendorResponseActions from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Actions";
// eslint-disable-next-line import/no-unresolved
import VendorApproval from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Approval";
// eslint-disable-next-line import/no-unresolved
import VendorResponseError from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Error";
// eslint-disable-next-line import/no-unresolved
import VendorResponseMessage from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Message";
// eslint-disable-next-line import/no-unresolved
import VendorTool from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Tool";
// eslint-disable-next-line import/no-unresolved
import AgentScopeRuntimeResponseBuilder from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/Response/Builder";
// eslint-disable-next-line import/no-unresolved
import { useChatAnywhereOptions } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/Context/ChatAnywhereOptionsContext";
import type {
  IAgentScopeRuntimeMessage,
  IAgentScopeRuntimeResponse,
} from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import { AgentScopeRuntimeMessageType } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import { Avatar, Flex } from "antd";
// The patched request card accepts contentPrepend/contentAppend although the
// vendor declaration has not added those props yet.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const VendorRequestCard = VendorRequestCardOriginal as React.ComponentType<any>;
import {
  useChatScalarSnapshot,
  useChatListSnapshot,
} from "../../plugins/registry/useChatExtensions";
import { ChatScalar, ChatList } from "../../plugins/registry/slotKeys";
import { PluginSlotBoundary } from "../../plugins/registry/PluginSlotBoundary";
import type {
  ChatRequestData,
  ChatResponseData,
} from "../../plugins/registry/types";
import ConversationProcess, {
  buildConversationRenderSegments,
  ConversationRunStatus,
  getMessageRunStatus,
  hasVisibleAnswerContent,
  isPersistentToolResultMessage,
  pairToolMessages,
  recoverCompletedAnswer,
} from "../../components/ConversationProcess";
import styles from "./HostBubbles.module.less";

function sortByOrder<T extends { item: { order?: number } }>(arr: T[]): T[] {
  return arr
    .slice()
    .sort((a, b) => (a.item.order ?? 100) - (b.item.order ?? 100));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyCardProps = any;

interface HostDefaultResponseCardProps {
  data: IAgentScopeRuntimeResponse;
  isLast?: boolean;
  contentPrepend?: React.ReactNode;
  contentAppend?: React.ReactNode;
}

function HostDefaultResponseCard({
  data,
  isLast,
  contentPrepend,
  contentAppend,
}: HostDefaultResponseCardProps) {
  const avatar = useChatAnywhereOptions((options) => options?.welcome?.avatar);
  const nick = useChatAnywhereOptions((options) => options?.welcome?.nick);
  const isGenerating = AgentScopeRuntimeResponseBuilder.maybeGenerating(data);
  const isCompleted =
    data.status === ConversationRunStatus.COMPLETED && !data.error;
  const messages = useMemo(
    () =>
      recoverCompletedAnswer(
        pairToolMessages(data.output ?? []),
        isCompleted,
      ),
    [data.output, isCompleted],
  );
  const segments = useMemo(
    () => buildConversationRenderSegments(messages),
    [messages],
  );
  const assistantReplyCompleted =
    isGenerating &&
    (((data as IAgentScopeRuntimeResponse & { assistant_status?: string })
      .assistant_status === "completed") ||
      messages.some((message) => {
        const metadata = (
          message as IAgentScopeRuntimeMessage & {
            metadata?: Record<string, unknown> | null;
          }
        ).metadata;
        return (
          message.type === AgentScopeRuntimeMessageType.MESSAGE &&
          metadata?.phase === "final" &&
          metadata?.terminal === true &&
          getMessageRunStatus(message) === ConversationRunStatus.COMPLETED &&
          hasVisibleAnswerContent([message])
        );
      }));
  const actionData = assistantReplyCompleted
    ? ({
        ...data,
        status: ConversationRunStatus.COMPLETED,
      } as IAgentScopeRuntimeResponse)
    : data;
  const responseActions = (
    <VendorResponseActions
      data={actionData}
      // Do not expose regenerate while AgentScope is still maintaining this turn.
      isLast={assistantReplyCompleted ? false : isLast}
    />
  );

  const identity = avatar ? (
    <Flex align="center" gap={8} style={{ marginBottom: 8 }}>
      <Avatar src={avatar} />
      {nick ? <span>{nick as React.ReactNode}</span> : null}
    </Flex>
  ) : null;

  const renderAnswerMessage = (message: IAgentScopeRuntimeMessage) => {
    switch (message.type) {
      case AgentScopeRuntimeMessageType.MESSAGE:
        return <VendorResponseMessage key={message.id} data={message} />;
      case AgentScopeRuntimeMessageType.ERROR:
        return <VendorResponseError key={message.id} data={message} />;
      case AgentScopeRuntimeMessageType.HEARTBEAT:
        return null;
      default:
        return null;
    }
  };

  if (!messages.length && isGenerating) {
    return (
      <>
        {identity}
        {contentPrepend}
        <ConversationProcess
          messages={[]}
          hasAnswerContent={false}
          isGenerating
        />
        {contentAppend}
        {responseActions}
      </>
    );
  }

  return (
    <>
      {identity}
      {contentPrepend}
      {segments.map((segment, index) => {
        if (segment.type === "answer") {
          return (
            <React.Fragment key={`answer-${index}`}>
              {segment.messages.map(renderAnswerMessage)}
            </React.Fragment>
          );
        }

        const hasLaterAnswer = segments
          .slice(index + 1)
          .some(
            (laterSegment) =>
              laterSegment.type === "answer" &&
              hasVisibleAnswerContent(laterSegment.messages),
          );
        const isComplete = hasLaterAnswer || !isGenerating;
        const segmentKey =
          segment.messages[0]?.id ??
          `process-${index}-${segment.messages.length}`;
        const persistentResults = segment.messages.filter(
          isPersistentToolResultMessage,
        );

        return (
          <React.Fragment key={segmentKey}>
            <ConversationProcess
              messages={segment.messages}
              hasAnswerContent={isComplete}
              isGenerating={isGenerating && !isComplete}
              renderToolDetails={(message, context) => {
                if (isPersistentToolResultMessage(message)) return null;
                return context.isApprovalRequest ? (
                  <VendorApproval data={message} />
                ) : (
                  <VendorTool data={message} />
                );
              }}
            />
            {persistentResults.length ? (
              <div className={styles.persistentResults}>
                {persistentResults.map((message) => (
                  <div key={message.id} className={styles.persistentResult}>
                    <VendorTool data={message} />
                  </div>
                ))}
              </div>
            ) : null}
          </React.Fragment>
        );
      })}
      {data.error ? <VendorResponseError data={data.error} /> : null}
      {contentAppend}
      {responseActions}
    </>
  );
}

export function HostRequestCard(props: { data: ChatRequestData }) {
  const extScalar = useChatScalarSnapshot();
  const extLists = useChatListSnapshot();

  const renderEntry = extScalar[ChatScalar.requestRender];
  const renderFn = renderEntry?.value;
  const prependList = sortByOrder(extLists[ChatList.requestPrepend]);
  const appendList = sortByOrder(extLists[ChatList.requestAppend]);

  // prepend/append routed through vendor's contentPrepend/contentAppend
  // slot so actions stay last. Mirrors HostResponseCard.
  const contentPrepend =
    prependList.length === 0 ? null : (
      <>
        {prependList.map((e) => (
          <PluginSlotBoundary
            key={e.item.id}
            slot={ChatList.requestPrepend}
            pluginId={e.pluginId}
          >
            {e.item.render({ data: props.data })}
          </PluginSlotBoundary>
        ))}
      </>
    );
  const contentAppend =
    appendList.length === 0 ? null : (
      <>
        {appendList.map((e) => (
          <PluginSlotBoundary
            key={e.item.id}
            slot={ChatList.requestAppend}
            pluginId={e.pluginId}
          >
            {e.item.render({ data: props.data })}
          </PluginSlotBoundary>
        ))}
      </>
    );

  const fallback = () => (
    <VendorRequestCard
      data={props.data as AnyCardProps}
      contentPrepend={contentPrepend as AnyCardProps}
      contentAppend={contentAppend as AnyCardProps}
    />
  );

  if (renderFn) {
    return (
      <PluginSlotBoundary
        slot={ChatScalar.requestRender}
        pluginId={renderEntry!.pluginId}
        fallback={fallback()}
      >
        {renderFn({ data: props.data, fallback })}
      </PluginSlotBoundary>
    );
  }
  return fallback();
}

export function HostResponseCard(props: {
  data: ChatResponseData;
  isLast?: boolean;
}) {
  const extScalar = useChatScalarSnapshot();
  const extLists = useChatListSnapshot();

  const renderEntry = extScalar[ChatScalar.responseRender];
  const renderFn = renderEntry?.value;
  const prependList = sortByOrder(extLists[ChatList.responsePrepend]);
  const appendList = sortByOrder(extLists[ChatList.responseAppend]);

  // prepend/append are routed through vendor's contentPrepend/contentAppend
  // slot so they land BETWEEN messages and Actions — actions always last.
  // Vendor change: see Response/Card.js DefaultResponseRender, which now
  // reads props.contentPrepend / props.contentAppend.
  const contentPrepend =
    prependList.length === 0 ? null : (
      <>
        {prependList.map((e) => (
          <PluginSlotBoundary
            key={e.item.id}
            slot={ChatList.responsePrepend}
            pluginId={e.pluginId}
          >
            {e.item.render({ data: props.data, isLast: props.isLast })}
          </PluginSlotBoundary>
        ))}
      </>
    );
  const contentAppend =
    appendList.length === 0 ? null : (
      <>
        {appendList.map((e) => (
          <PluginSlotBoundary
            key={e.item.id}
            slot={ChatList.responseAppend}
            pluginId={e.pluginId}
          >
            {e.item.render({ data: props.data, isLast: props.isLast })}
          </PluginSlotBoundary>
        ))}
      </>
    );

  const fallback = () => (
    <HostDefaultResponseCard
      data={props.data as unknown as IAgentScopeRuntimeResponse}
      isLast={props.isLast}
      contentPrepend={contentPrepend}
      contentAppend={contentAppend}
    />
  );

  if (renderFn) {
    return (
      <PluginSlotBoundary
        slot={ChatScalar.responseRender}
        pluginId={renderEntry!.pluginId}
        fallback={fallback()}
      >
        {renderFn({
          data: props.data,
          isLast: props.isLast,
          fallback,
        })}
      </PluginSlotBoundary>
    );
  }
  return fallback();
}
