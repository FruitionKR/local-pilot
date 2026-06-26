import { useEffect, useState } from "react";
import { fetchChatMessages, queryWiki } from "../../_lib/api";
import { findLastUserMessage } from "../../_lib/messages";
import type { ChatMessageRelatedPageResponse, ChatMessageResponse, QueryRelatedPageResponse } from "../../_lib/types";

export type ActiveAgentTurn = {
  question: string;
  userMessageId?: string;
  assistantMessage?: ChatMessageResponse;
};

/**
 * 채팅 스레드 페칭·폴링·질의 상태를 관리하는 훅.
 * AgentPanel에서 추출했습니다.
 */
export function useChatThread() {
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [queryErrorMessage, setQueryErrorMessage] = useState<string | null>(null);
  const [chatLoadErrorMessage, setChatLoadErrorMessage] = useState<string | null>(null);
  const [animatedMessageId, setAnimatedMessageId] = useState<string | null>(null);
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
    void refreshMessages().catch((error: unknown) => {
      setChatLoadErrorMessage(error instanceof Error ? error.message : "채팅 기록을 불러오지 못했습니다.");
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submitQuery(question: string) {
    if (!question || isLoading) return;

    setQueryErrorMessage(null);
    setAnimatedMessageId(null);
    setActiveTurn({ question });
    setIsLoading(true);
    const previousAssistantMessageIds = new Set(
      messages.filter((message) => message.role !== "user").map((message) => message.id)
    );

    let querySucceeded = false;
    let queryRelatedPages: QueryRelatedPageResponse[] = [];
    try {
      const queryResponse = await queryWiki(question);
      querySucceeded = true;
      queryRelatedPages = queryResponse.related_pages ?? [];
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
        ? findLastUserMessage(nextMessages.slice(0, nextAssistantMessageIndex))
        : undefined;

      const assistantMessage: ChatMessageResponse | undefined = nextAssistantMessage && !nextAssistantMessage.related_pages?.length && queryRelatedPages.length
        ? {
            ...nextAssistantMessage,
            related_pages: queryRelatedPages.map((page, idx): ChatMessageRelatedPageResponse => ({
              wiki_page_id: page.id,
              page_type: page.page_type,
              title: page.title,
              slug: page.slug,
              relevance_score: page.relevance_score,
              role: page.role,
              depth: page.depth,
              rank: idx + 1
            }))
          }
        : nextAssistantMessage;

      setAnimatedMessageId(assistantMessage?.id ?? null);
      setActiveTurn({
        question: nextUserMessage?.content ?? question,
        userMessageId: nextUserMessage?.id,
        assistantMessage
      });
    }).catch((error: unknown) => {
      setChatLoadErrorMessage(error instanceof Error ? error.message : "채팅 기록을 불러오지 못했습니다.");
      setActiveTurn(null);
    });
  }

  return { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, submitQuery };
}
