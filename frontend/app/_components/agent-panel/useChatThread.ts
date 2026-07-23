import { useEffect, useState } from "react";
import { fetchChatMessages, runQueryStream, setActiveChatSession, type QueryStageEvent } from "../../_lib/api";
import { getErrorMessage } from "../../_lib/errors";
import { findLastUserMessage } from "../../_lib/messages";
import type { ChatMessageRelatedPageResponse, ChatMessageResponse, QueryRelatedPageResponse } from "../../_lib/types";

export type ActiveAgentTurn = {
  question: string;
  userMessageId?: string;
  assistantMessage?: ChatMessageResponse;
};

/** 질의 응답의 related page를 채팅 메시지용 related page 형태로 변환한다. */
function toRelatedPageMessage(page: QueryRelatedPageResponse, rank: number): ChatMessageRelatedPageResponse {
  return {
    wiki_page_id: page.id,
    page_type: page.page_type,
    title: page.title,
    slug: page.slug,
    relevance_score: page.relevance_score,
    role: page.role,
    depth: page.depth,
    rank
  };
}

/**
 * 새로 받은 메시지 목록에서 이번 질의로 생성된 assistant 메시지를 찾아
 * 다음 activeTurn 상태를 만든다. 새 assistant 메시지에 related_pages가 없으면
 * 질의 응답의 related_pages로 보강한다.
 */
function buildNextActiveTurn(
  nextMessages: ChatMessageResponse[],
  previousAssistantMessageIds: Set<string>,
  queryRelatedPages: QueryRelatedPageResponse[],
  question: string
): ActiveAgentTurn {
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
        related_pages: queryRelatedPages.map((page, idx) => toRelatedPageMessage(page, idx + 1))
      }
    : nextAssistantMessage;

  return {
    question: nextUserMessage?.content ?? question,
    userMessageId: nextUserMessage?.id,
    assistantMessage
  };
}

/**
 * 채팅 스레드 페칭·폴링·질의 상태를 관리하는 훅.
 * AgentPanel에서 추출했습니다.
 */
export function useChatThread(activeSessionId?: string | null) {
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [queryErrorMessage, setQueryErrorMessage] = useState<string | null>(null);
  const [chatLoadErrorMessage, setChatLoadErrorMessage] = useState<string | null>(null);
  const [animatedMessageId, setAnimatedMessageId] = useState<string | null>(null);
  const [activeTurn, setActiveTurn] = useState<ActiveAgentTurn | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [queryStages, setQueryStages] = useState<QueryStageEvent[]>([]);

  async function refreshMessages() {
    const response = await fetchChatMessages();
    const nextMessages = response.messages ?? [];
    setMessages(nextMessages);
    setChatLoadErrorMessage(null);
    return nextMessages;
  }

  // 선택 세션이 바뀌면 해당 세션 메시지로 교체하고 이전 세션의 진행 상태를 초기화한다.
  useEffect(() => {
    if (activeSessionId) setActiveChatSession(activeSessionId);
    setActiveTurn(null);
    setAnimatedMessageId(null);
    setQueryErrorMessage(null);
    setQueryStages([]);
    void refreshMessages().catch((error: unknown) => {
      setChatLoadErrorMessage(getErrorMessage(error, "채팅 기록을 불러오지 못했습니다."));
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  async function submitQuery(question: string) {
    if (!question || isLoading) return;

    setQueryErrorMessage(null);
    setAnimatedMessageId(null);
    setQueryStages([]);
    setActiveTurn({ question });
    setIsLoading(true);
    const previousAssistantMessageIds = new Set(
      messages.filter((message) => message.role !== "user").map((message) => message.id)
    );

    let querySucceeded = false;
    let queryRelatedPages: QueryRelatedPageResponse[] = [];
    try {
      const queryResponse = await runQueryStream(question, {
        onStage: (event) => setQueryStages((current) => [...current, event])
      });
      querySucceeded = true;
      queryRelatedPages = queryResponse.related_pages ?? [];
    } catch (error) {
      setQueryErrorMessage(getErrorMessage(error, "질의에 실패했습니다."));
      setActiveTurn(null);
    } finally {
      setIsLoading(false);
    }

    if (!querySucceeded) return;
    await refreshMessages().then((nextMessages) => {
      const nextTurn = buildNextActiveTurn(nextMessages, previousAssistantMessageIds, queryRelatedPages, question);
      setAnimatedMessageId(nextTurn.assistantMessage?.id ?? null);
      setActiveTurn(nextTurn);
    }).catch((error: unknown) => {
      setChatLoadErrorMessage(getErrorMessage(error, "채팅 기록을 불러오지 못했습니다."));
      setActiveTurn(null);
    });
  }

  return { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, queryStages, submitQuery };
}
