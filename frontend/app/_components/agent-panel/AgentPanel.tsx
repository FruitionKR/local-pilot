"use client";

import { useEffect, useState } from "react";
import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";
import { fetchChatMessages, queryWiki } from "../../_lib/api";
import type { ChatMessageResponse } from "../../_lib/types";

export type ActiveAgentTurn = {
  question: string;
  userMessageId?: string;
  assistantMessage?: ChatMessageResponse;
};

export function AgentPanel({
  onClose,
  onOpenWikiPage
}: {
  onClose: () => void;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
}) {
  const [composerValue, setComposerValue] = useState("");
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [queryErrorMessage, setQueryErrorMessage] = useState<string | null>(null);
  const [chatLoadErrorMessage, setChatLoadErrorMessage] = useState<string | null>(null);
  const [animatedAssistantMessageId, setAnimatedAssistantMessageId] = useState<string | null>(null);
  const [activeTurn, setActiveTurn] = useState<ActiveAgentTurn | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  async function refreshMessages() {
    const response = await fetchChatMessages();
    const nextMessages = response.messages ?? [];
    setMessages(nextMessages);
    setChatLoadErrorMessage(null);
    return nextMessages;
  }

  useEffect(() => {
    void refreshMessages().catch(() => {
      setChatLoadErrorMessage("채팅 기록을 불러오지 못했습니다.");
    });
  }, []);

  async function submitQuery() {
    const nextQuestion = composerValue.trim();
    if (!nextQuestion || isLoading) return;

    setComposerValue("");
    setQueryErrorMessage(null);
    setAnimatedAssistantMessageId(null);
    setActiveTurn({ question: nextQuestion });
    setIsLoading(true);
    const previousAssistantMessageIds = new Set(
      messages.filter((message) => message.role !== "user").map((message) => message.id)
    );

    let querySucceeded = false;
    try {
      await queryWiki(nextQuestion);
      querySucceeded = true;
    } catch (error) {
      setQueryErrorMessage(error instanceof Error ? error.message : "질의에 실패했습니다.");
      setActiveTurn(null);
    } finally {
      setIsLoading(false);
    }

    if (!querySucceeded) return;
    await refreshMessages().then((nextMessages) => {
      const nextAssistantMessage = [...nextMessages]
        .reverse()
        .find((message) => message.role !== "user" && !previousAssistantMessageIds.has(message.id));
      const nextAssistantMessageIndex = nextAssistantMessage
        ? nextMessages.findIndex((message) => message.id === nextAssistantMessage.id)
        : -1;
      const nextUserMessage = nextAssistantMessageIndex > 0
        ? [...nextMessages.slice(0, nextAssistantMessageIndex)].reverse().find((message) => message.role === "user")
        : undefined;
      setAnimatedAssistantMessageId(nextAssistantMessage?.id ?? null);
      setActiveTurn({
        question: nextUserMessage?.content ?? nextQuestion,
        userMessageId: nextUserMessage?.id,
        assistantMessage: nextAssistantMessage
      });
    }).catch(() => {
      setChatLoadErrorMessage("채팅 기록을 불러오지 못했습니다.");
      setActiveTurn(null);
    });
  }

  return (
    <aside className="agent-panel">
      <AgentHeader onClose={onClose} />
      <AgentBody
        messages={messages}
        isLoading={isLoading}
        activeTurn={activeTurn}
        queryErrorMessage={queryErrorMessage}
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedAssistantMessageId}
        onOpenWikiPage={onOpenWikiPage}
      />
      <AgentComposer value={composerValue} isLoading={isLoading} onChange={setComposerValue} onSubmit={submitQuery} />
    </aside>
  );
}
