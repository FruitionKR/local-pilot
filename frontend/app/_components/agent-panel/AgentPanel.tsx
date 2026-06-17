"use client";

import { useEffect, useState } from "react";
import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";
import { fetchChatMessages, queryWiki } from "../../_lib/api";
import type { ChatMessageResponse } from "../../_lib/types";

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
    } finally {
      setIsLoading(false);
    }

    if (!querySucceeded) return;
    await refreshMessages().then((nextMessages) => {
      const nextAssistantMessage = [...nextMessages]
        .reverse()
        .find((message) => message.role !== "user" && !previousAssistantMessageIds.has(message.id));
      setAnimatedAssistantMessageId(nextAssistantMessage?.id ?? null);
    }).catch(() => {
      setChatLoadErrorMessage("채팅 기록을 불러오지 못했습니다.");
    });
  }

  return (
    <aside className="agent-panel">
      <AgentHeader onClose={onClose} />
      <AgentBody
        messages={messages}
        isLoading={isLoading}
        queryErrorMessage={queryErrorMessage}
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedAssistantMessageId}
        onOpenWikiPage={onOpenWikiPage}
      />
      <AgentComposer value={composerValue} isLoading={isLoading} onChange={setComposerValue} onSubmit={submitQuery} />
    </aside>
  );
}
