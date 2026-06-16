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
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  async function refreshMessages() {
    const response = await fetchChatMessages();
    setMessages(response.messages ?? []);
  }

  useEffect(() => {
    void refreshMessages().catch(() => {
      setErrorMessage("채팅 기록을 불러오지 못했습니다.");
    });
  }, []);

  async function submitQuery() {
    const nextQuestion = composerValue.trim();
    if (!nextQuestion || isLoading) return;

    setComposerValue("");
    setErrorMessage(null);
    setIsLoading(true);

    try {
      await queryWiki(nextQuestion);
      await refreshMessages();
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : "질의에 실패했습니다.");
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <aside className="agent-panel">
      <AgentHeader onClose={onClose} />
      <AgentBody
        messages={messages}
        isLoading={isLoading}
        errorMessage={errorMessage}
        onOpenWikiPage={onOpenWikiPage}
      />
      <AgentComposer value={composerValue} isLoading={isLoading} onChange={setComposerValue} onSubmit={submitQuery} />
    </aside>
  );
}
