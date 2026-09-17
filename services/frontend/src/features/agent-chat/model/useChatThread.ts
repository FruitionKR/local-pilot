import { useCallback, useEffect, useRef, useState } from "react";
import { fetchChatMessages, setActiveChatSession, getSessionContext } from "@/entities/chat/api/chat";
import { useUserPreferences } from "@/entities/user";
import type { AiModelSelection } from "@/entities/ai";
import { cancelQueryRun, QueryCancelledError, runQueryStream, type QueryRun, type QueryStageEvent } from "@/entities/wiki/api/wiki";
import { publishNotice } from "@/features/document-notifications";
import { fetchMessagesForRequest } from "../lib/chatMessagesRequest";
import { getErrorMessage } from "@/shared/lib/errors";
import { findLastUserMessage } from "@/shared/lib/messages";
import type { ChatMessageRelatedPageResponse, ChatMessageResponse } from "@/entities/chat/model/chat";
import type { QueryRelatedPageResponse } from "@/entities/wiki/model/wiki";

export type ActiveAgentTurn = {
  question: string;
  userMessageId?: string;
  assistantMessage?: ChatMessageResponse;
};

type ActiveQueryRequest = {
  run: QueryRun | null;
  controller: AbortController;
  cancelRequested: boolean;
  cancellation: Promise<void> | null;
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
  const { preferences } = useUserPreferences();
  const queryNotifications = preferences.notifications.query;
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [queryErrorMessage, setQueryErrorMessage] = useState<string | null>(null);
  const [chatLoadErrorMessage, setChatLoadErrorMessage] = useState<string | null>(null);
  const [animatedMessageId, setAnimatedMessageId] = useState<string | null>(null);
  const [activeTurn, setActiveTurn] = useState<ActiveAgentTurn | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [queryStatusMessage, setQueryStatusMessage] = useState<string | null>(null);
  const activeQueryRef = useRef<ActiveQueryRequest | null>(null);
  const [queryStages, setQueryStages] = useState<QueryStageEvent[]>([]);
  // 마지막으로 메시지를 로드한 세션. 초기 자동 로드와 확정 세션이 같으면 중복 요청을 막는다.
  const loadedSessionRef = useRef<string | null>(null);
  const messageRequestRef = useRef(0);

  useEffect(() => () => {
    activeQueryRef.current?.controller.abort();
    activeQueryRef.current = null;
  }, []);

  // 무효화된 요청은 정상 빈 목록([])과 구분하도록 null을 반환한다. 호출부는 null이면 상태를 갱신하지 않는다.
  const refreshMessages = useCallback(async ({ animateLatest = false } = {}) => {
    const nextMessages = await fetchMessagesForRequest(messageRequestRef, fetchChatMessages);
    if (nextMessages === null) return null;
    setMessages(nextMessages);
    if (animateLatest) {
      const latestAssistantMessage = [...nextMessages]
        .reverse()
        .find((message) => message.role === "assistant" && message.status === "completed");
      setAnimatedMessageId(latestAssistantMessage?.id ?? null);
    }
    setChatLoadErrorMessage(null);
    return nextMessages;
  }, []);

  // 선택 세션이 바뀌면 해당 세션 메시지로 교체하고 이전 세션의 진행 상태를 초기화한다.
  useEffect(() => {
    let cancelled = false;

    async function loadSessionMessages() {
      if (activeSessionId) setActiveChatSession(activeSessionId);
      messageRequestRef.current += 1;
      if (!activeSessionId || loadedSessionRef.current !== activeSessionId) {
        setMessages([]);
        setChatLoadErrorMessage(null);
      }
      // 실제 대상 세션을 확정한다. 이미 이 세션을 로드했으면(null→id 확정 등) 재요청하지 않는다.
      const { sessionId } = await getSessionContext();
      if (cancelled || loadedSessionRef.current === sessionId) return;

      setActiveTurn(null);
      setAnimatedMessageId(null);
      setQueryErrorMessage(null);
      setQueryStages([]);
      const nextMessages = await refreshMessages();
      if (cancelled || nextMessages === null) return;
      if (nextMessages.length === 0) {
        const { sessionId: currentSessionId } = await getSessionContext();
        if (currentSessionId !== sessionId) return;
      }
      loadedSessionRef.current = sessionId;
    }

    loadSessionMessages().catch((error: unknown) => {
      if (cancelled) return;
      setChatLoadErrorMessage(getErrorMessage(error, "채팅 기록을 불러오지 못했습니다."));
    });

    return () => {
      cancelled = true;
    };
  }, [activeSessionId, refreshMessages]);

  async function finishCancelledQuery(request: ActiveQueryRequest) {
    if (activeQueryRef.current !== request) return;
    activeQueryRef.current = null;
    request.controller.abort();
    setIsLoading(false);
    setIsCancelling(false);
    setActiveTurn(null);
    setQueryStages([]);
    setQueryErrorMessage(null);
    setQueryStatusMessage("질의를 취소했습니다.");
    await refreshMessages().catch((error: unknown) => {
      setChatLoadErrorMessage(getErrorMessage(error, "채팅 기록을 불러오지 못했습니다."));
    });
  }

  function cancelQuery() {
    const request = activeQueryRef.current;
    if (!request || request.cancellation) return;
    request.cancelRequested = true;
    setIsCancelling(true);
    setQueryErrorMessage(null);
    setQueryStatusMessage("질의를 취소하고 변경을 복구하는 중입니다…");
    // run 생성 응답 전에 누른 Esc도 ID를 받은 직후 서버에 전달한다.
    if (!request.run) return;
    request.cancellation = cancelQueryRun(request.run, request.controller.signal)
      .then(() => finishCancelledQuery(request))
      .catch((error: unknown) => {
        if (activeQueryRef.current !== request) return;
        setIsCancelling(false);
        setQueryStatusMessage(null);
        setQueryErrorMessage(getErrorMessage(error, "질의 취소에 실패했습니다. Esc를 눌러 다시 시도해주세요."));
      })
      .finally(() => { request.cancellation = null; });
  }

  async function submitQuery(question: string, selection: AiModelSelection) {
    if (!question || activeQueryRef.current || isLoading) return;
    const request: ActiveQueryRequest = {
      run: null, controller: new AbortController(), cancelRequested: false, cancellation: null
    };
    activeQueryRef.current = request;

    setQueryErrorMessage(null);
    setQueryStatusMessage(null);
    setIsCancelling(false);
    setAnimatedMessageId(null);
    setQueryStages([]);
    setActiveTurn({ question });
    setIsLoading(true);
    const previousAssistantMessageIds = new Set(
      messages.filter((message) => message.role !== "user").map((message) => message.id)
    );

    let querySucceeded = false;
    let queryRelatedPages: QueryRelatedPageResponse[] = [];
    let querySessionId: string | null = null;
    const shortQuestion = question.length > 30 ? `${question.slice(0, 30)}…` : question;
    try {
      // 질의 대상 세션을 고정해 두고, refresh 반영 시점에 세션이 바뀌었으면 turn을 갱신하지 않는다.
      querySessionId = (await getSessionContext()).sessionId;
      const queryResponse = await runQueryStream(question, selection, {
        signal: request.controller.signal,
        onStarted: (run) => {
          request.run = run;
          if (request.cancelRequested && activeQueryRef.current === request) cancelQuery();
        },
        onStage: (event) => {
          if (activeQueryRef.current === request && !request.cancelRequested) {
            setQueryStages((current) => [...current, event]);
          }
        }
      });
      if (activeQueryRef.current !== request || request.cancelRequested) return;
      querySucceeded = true;
      queryRelatedPages = queryResponse.related_pages ?? [];
      if (queryNotifications) {
        publishNotice({ kind: "completed", title: "질의 완료", message: `"${shortQuestion}" 답변이 도착했습니다.` });
      }
    } catch (error) {
      if (activeQueryRef.current !== request) return;
      if (error instanceof QueryCancelledError) {
        await finishCancelledQuery(request);
        return;
      }
      if (request.cancelRequested && request.run) return;
      setQueryErrorMessage(getErrorMessage(error, "질의에 실패했습니다."));
      setActiveTurn(null);
      setQueryStatusMessage(null);
      if (queryNotifications) {
        publishNotice({ kind: "failed", title: "질의 실패", message: `"${shortQuestion}" 질의에 실패했습니다.` });
      }
    } finally {
      // 취소를 시작했으면 HTTP/SSE 종료와 무관하게 복구 확인이 진행 상태를 소유한다.
      if (activeQueryRef.current === request && !(request.cancelRequested && request.run)) {
        activeQueryRef.current = null;
        setIsLoading(false);
        setIsCancelling(false);
      }
    }

    if (!querySucceeded) return;
    await refreshMessages().then(async (nextMessages) => {
      // 무효화된 refresh거나 다른 세션으로 전환된 뒤라면 이 질의의 turn을 화면에 남기지 않는다.
      if (nextMessages === null) return;
      const { sessionId: currentSessionId } = await getSessionContext();
      if (currentSessionId !== querySessionId) return;
      const nextTurn = buildNextActiveTurn(nextMessages, previousAssistantMessageIds, queryRelatedPages, question);
      setAnimatedMessageId(nextTurn.assistantMessage?.id ?? null);
      setActiveTurn(nextTurn);
    }).catch((error: unknown) => {
      setChatLoadErrorMessage(getErrorMessage(error, "채팅 기록을 불러오지 못했습니다."));
      setActiveTurn(null);
    });
  }

  return {
    messages,
    queryErrorMessage,
    chatLoadErrorMessage,
    animatedMessageId,
    activeTurn,
    isLoading,
    isCancelling,
    queryStatusMessage,
    queryStages,
    refreshMessages,
    submitQuery,
    cancelQuery
  };
}
