import { ChevronDown } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { MarkdownViewer } from "../MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import type { StatusStep } from "./agentData";
import type { QueryStageEvent } from "../../_lib/api";
import type { ActiveAgentTurn } from "./AgentPanel";
import { findSourceNodeByDocumentId } from "../../_lib/graph";
import { findLastUserMessage } from "../../_lib/messages";
import { citedRanks, formatAnswerMarkdown, formatReferenceMeta, formatWikiPageTitle } from "./agentFormatters";
import type { ChatMessageResponse, GraphNode, SourceBlockHighlight } from "../../_lib/types";
import { useSmoothScroll } from "./useSmoothScroll";

const SCROLL_OFFSET_PX = 20;
const MAX_RESULT_CARDS = 3;
const SEARCH_STATUS_TITLE = "서치 명령 실행 중";

// 답변 공개 단계: 1=상태 목록만, 2=결과 카드까지, 3=답변 본문까지 표시
const STAGE_STATUS = 1;
const STAGE_RESULTS = 2;
const STAGE_ANSWER = 3;

function findGraphNode(nodes: GraphNode[] | undefined, pageId: string) {
  return nodes?.find((node) => node.id === pageId);
}

function isKnownPage(nodes: GraphNode[] | undefined, pageId: string) {
  return !nodes || !!findGraphNode(nodes, pageId);
}

function buildRelatedPageCards(message: ChatMessageResponse, nodes: GraphNode[] | undefined) {
  const relatedPages = message.related_pages ?? [];
  if (relatedPages.length > 0) {
    return relatedPages
      .filter((page) => isKnownPage(nodes, page.wiki_page_id))
      .slice(0, MAX_RESULT_CARDS)
      .map((page) => ({
        key: `related-${page.wiki_page_id}`,
        pageId: page.wiki_page_id,
        pageType: page.page_type,
        title: findGraphNode(nodes, page.wiki_page_id)?.label ?? page.title,
        meta: page.role || "관련 자료"
      }));
  }

  const seenPageIds = new Set<string>();
  return message.references
    .map((reference) => ({
      reference,
      pageId: reference.source_document_id
        ? findSourceNodeByDocumentId(nodes, reference.source_document_id)?.id ?? null
        : null
    }))
    .filter(({ pageId }) => {
      if (!pageId || seenPageIds.has(pageId)) return false;
      seenPageIds.add(pageId);
      return true;
    })
    .slice(0, MAX_RESULT_CARDS)
    .map(({ reference, pageId }) => ({
      key: `reference-${reference.id}`,
      pageId: pageId as string,
      pageType: "source",
      title: formatWikiPageTitle(pageId as string, nodes, reference.source_document_id || "근거"),
      meta: formatReferenceMeta(reference)
    }));
}

function sourceTitle(nodes: GraphNode[] | undefined, documentId: string) {
  return findSourceNodeByDocumentId(nodes, documentId)?.label ?? documentId;
}

export function AgentBody({
  messages,
  isLoading,
  activeTurn,
  queryErrorMessage,
  chatLoadErrorMessage,
  animatedMessageId,
  queryStages = [],
  onOpenWikiPage,
  onOpenSourceBlocks,
  nodes
}: {
  messages: ChatMessageResponse[];
  isLoading: boolean;
  activeTurn: ActiveAgentTurn | null;
  queryErrorMessage: string | null;
  chatLoadErrorMessage: string | null;
  animatedMessageId: string | null;
  queryStages?: QueryStageEvent[];
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  nodes?: GraphNode[];
}) {
  const showAgentStatus = isLoading && activeTurn === null;
  const [visibleAnswerStage, setVisibleAnswerStage] = useState(STAGE_ANSWER);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const activeQuestionRef = useRef<HTMLDivElement | null>(null);
  const animatedQuestionRef = useRef<HTMLDivElement | null>(null);
  const hasScrolledInitialMessagesRef = useRef(false);
  const { scrollToPosition } = useSmoothScroll(bodyRef);

  const scrollToLatestMessage = useCallback(({ immediate = false } = {}) => {
    const body = bodyRef.current;
    if (!body) return;

    scrollToPosition(body.scrollHeight - body.clientHeight, { immediate });
  }, [scrollToPosition]);

  const scrollToQuestionStart = useCallback((questionElement: HTMLDivElement | null, { immediate = false } = {}) => {
    const body = bodyRef.current;
    if (!body || !questionElement) return;

    const bodyRect = body.getBoundingClientRect();
    const questionRect = questionElement.getBoundingClientRect();
    const targetTop = body.scrollTop + questionRect.top - bodyRect.top - SCROLL_OFFSET_PX;
    scrollToPosition(targetTop, { immediate });
  }, [scrollToPosition]);

  // 진행 단계는 SSE StatusList로 실시간 표시하므로, 답변 도착 시 지연 연출 없이 전체를 공개한다.
  useEffect(() => {
    setVisibleAnswerStage(STAGE_ANSWER);
  }, [animatedMessageId]);

  useLayoutEffect(() => {
    if (!activeTurn) return;

    const frameId = window.requestAnimationFrame(() => scrollToQuestionStart(activeQuestionRef.current));
    return () => window.cancelAnimationFrame(frameId);
  }, [activeTurn, scrollToQuestionStart]);

  useLayoutEffect(() => {
    if (!animatedMessageId && !isLoading && !queryErrorMessage) return;

    const frameId = window.requestAnimationFrame(() => {
      if (activeTurn) {
        scrollToQuestionStart(activeQuestionRef.current);
        return;
      }

      if (animatedQuestionRef.current) {
        scrollToQuestionStart(animatedQuestionRef.current);
        return;
      }

      scrollToLatestMessage();
    });
    return () => window.cancelAnimationFrame(frameId);
  }, [animatedMessageId, visibleAnswerStage, isLoading, activeTurn, queryErrorMessage, scrollToLatestMessage, scrollToQuestionStart]);

  useEffect(() => {
    if (messages.length === 0 || animatedMessageId || isLoading || queryErrorMessage) return;
    if (hasScrolledInitialMessagesRef.current) return;

    hasScrolledInitialMessagesRef.current = true;
    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage({ immediate: true }));
    return () => window.cancelAnimationFrame(frameId);
  }, [messages.length, animatedMessageId, isLoading, queryErrorMessage, scrollToLatestMessage]);

  const animatedMessageIndex = messages.findIndex((message) => message.id === animatedMessageId);
  const animatedQuestionId = animatedMessageIndex > 0
    ? findLastUserMessage(messages.slice(0, animatedMessageIndex))?.id ?? null
    : null;
  const activeAssistantMessage = activeTurn?.assistantMessage;
  const shouldReserveScrollSpace = activeTurn !== null && (!activeAssistantMessage || visibleAnswerStage < STAGE_ANSWER);
  const messagesToRender = activeTurn
    ? messages.filter((message) => message.id !== activeTurn.userMessageId && message.id !== activeTurn.assistantMessage?.id)
    : messages;
  // SSE로 받은 실제 진행 단계를 상태 목록으로 표시한다. 마지막 단계는 아직 진행 중이면 active로 둔다.
  const stageSteps: StatusStep[] = queryStages.map((stage, index): StatusStep => [
    stage.message || stage.stage,
    index === queryStages.length - 1 && isLoading ? "active" : "done"
  ]);
  const pendingStatusThread = (
    <div className="agent-thread">
      <StatusList
        title={SEARCH_STATUS_TITLE}
        isLoading={isLoading}
        hasResponse={false}
        steps={stageSteps.length > 0 ? stageSteps : undefined}
      />
    </div>
  );

  return (
    <div className="agent-body" ref={bodyRef}>
      {messagesToRender.map((message) => (
        message.role === "user" ? (
          <div
            className="question-bubble"
            key={message.id}
            ref={message.id === animatedQuestionId ? animatedQuestionRef : undefined}
          >
            {message.content}
          </div>
        ) : (
          <AssistantThread
            key={message.id}
            message={message}
            isAnimated={message.id === animatedMessageId}
            visibleAnswerStage={visibleAnswerStage}
            nodes={nodes}
            onOpenWikiPage={onOpenWikiPage}
            onOpenSourceBlocks={onOpenSourceBlocks}
          />
        )
      ))}

      {activeTurn && (
        <>
          <div className="question-bubble" ref={activeQuestionRef}>{activeTurn.question}</div>
          {activeAssistantMessage
            ? (
              <AssistantThread
                message={activeAssistantMessage}
                isAnimated={activeAssistantMessage.id === animatedMessageId}
                visibleAnswerStage={visibleAnswerStage}
                nodes={nodes}
                onOpenWikiPage={onOpenWikiPage}
                onOpenSourceBlocks={onOpenSourceBlocks}
              />
            )
            : pendingStatusThread}
        </>
      )}
      {showAgentStatus && pendingStatusThread}

      {queryErrorMessage && <p className="query-error">{queryErrorMessage}</p>}
      {chatLoadErrorMessage && <p className="query-error">{chatLoadErrorMessage}</p>}

      {isLoading && <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요…</div>}
      {shouldReserveScrollSpace && <div className="agent-scroll-reserve" aria-hidden />}
    </div>
  );
}

/** assistant 메시지 하나를 상태 목록·결과 카드·답변 본문 순서로 렌더링한다. */
function AssistantThread({
  message,
  isAnimated,
  visibleAnswerStage,
  nodes,
  onOpenWikiPage,
  onOpenSourceBlocks
}: {
  message: ChatMessageResponse;
  isAnimated: boolean;
  visibleAnswerStage: number;
  nodes?: GraphNode[];
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
}) {
  const resultCards = buildRelatedPageCards(message, nodes);
  const ranksInAnswer = citedRanks(message.content);
  const citationReferenceByRank = new Map(
    message.references
      .filter((item) => item.rank && ranksInAnswer.has(item.rank) && item.source_document_id && item.source_block_ids?.length)
      .map((item) => [item.rank as number, item])
  );
  const canOpenCitation = (rank: number) => citationReferenceByRank.has(rank);
  const openCitation = (rank: number) => {
    const reference = citationReferenceByRank.get(rank);
    if (!reference?.source_document_id || !reference.source_block_ids?.length) return;
    const highlights = reference.source_block_ids.map((blockId) => ({ block_id: blockId, rank }));
    onOpenSourceBlocks(reference.source_document_id, sourceTitle(nodes, reference.source_document_id), highlights);
  };

  return (
    <div className="agent-thread">
      {(!isAnimated || visibleAnswerStage >= STAGE_STATUS) && (
        <div className={isAnimated ? "agent-stage" : undefined}>
          <StatusList title={SEARCH_STATUS_TITLE} isLoading={false} hasResponse />
        </div>
      )}

      {resultCards.length > 0 && (!isAnimated || visibleAnswerStage >= STAGE_RESULTS) && (
        <div className={`results ${isAnimated ? "agent-stage" : ""}`}>
          <p>찾은 자료 {resultCards.length}건</p>
          {resultCards.map((card) => (
            <AgentResultCard
              key={card.key}
              title={card.title}
              meta={card.meta}
              pageType={card.pageType}
              onClick={() => onOpenWikiPage(card.pageId, card.title, card.pageType)}
            />
          ))}
        </div>
      )}

      {(!isAnimated || visibleAnswerStage >= STAGE_ANSWER) && (
        <section className={`agent-answer ${isAnimated ? "agent-stage" : ""}`} aria-label="실행 중 발견 사항">
          <div className="answer-section-title">
            <span>실행 중 발견 사항</span>
            <ChevronDown size={8} />
          </div>
          <MarkdownViewer
            markdown={formatAnswerMarkdown(message.content)}
            onCitationClick={openCitation}
            canClickCitation={canOpenCitation}
          />
        </section>
      )}
    </div>
  );
}
