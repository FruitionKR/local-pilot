import { ChevronDown } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { MarkdownViewer } from "../MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import type { ActiveAgentTurn } from "./AgentPanel";
import { makeSourceId, nodeIdToPageType } from "../../_lib/graph";
import { findLastUserMessage } from "../../_lib/messages";
import { citedRanks, formatAnswerMarkdown, formatReferenceMeta, formatWikiPageTitle } from "./agentFormatters";
import type { ChatMessageResponse, GraphNode, SourceBlockHighlight } from "../../_lib/types";
import { useSmoothScroll } from "./useSmoothScroll";

const SCROLL_OFFSET_PX = 20;
const REVEAL_RESULTS_DELAY_MS = 1000;
const REVEAL_ANSWER_DELAY_MS = 2000;
const MAX_RESULT_CARDS = 3;

export function AgentBody({
  messages,
  isLoading,
  activeTurn,
  queryErrorMessage,
  chatLoadErrorMessage,
  animatedMessageId,
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
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  nodes?: GraphNode[];
}) {
  const showAgentStatus = isLoading && activeTurn === null;
  const [visibleAnswerStage, setVisibleAnswerStage] = useState(3);
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

  useEffect(() => {
    if (!animatedMessageId) {
      setVisibleAnswerStage(3);
      return;
    }

    setVisibleAnswerStage(1);
    const revealResults = window.setTimeout(() => setVisibleAnswerStage(2), REVEAL_RESULTS_DELAY_MS);
    const revealAnswer = window.setTimeout(() => setVisibleAnswerStage(3), REVEAL_ANSWER_DELAY_MS);

    return () => {
      window.clearTimeout(revealResults);
      window.clearTimeout(revealAnswer);
    };
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

  function findGraphNode(pageId: string) {
    return nodes?.find((node) => node.id === pageId);
  }

  function isKnownPage(pageId: string) {
    return !nodes || !!findGraphNode(pageId);
  }

  function buildRelatedPageCards(message: ChatMessageResponse) {
    const relatedPages = message.related_pages ?? [];
    if (relatedPages.length > 0) {
      return relatedPages
        .filter((page) => isKnownPage(page.wiki_page_id))
        .slice(0, MAX_RESULT_CARDS)
        .map((page) => ({
          key: `related-${page.wiki_page_id}`,
          pageId: page.wiki_page_id,
          pageType: page.page_type,
          title: findGraphNode(page.wiki_page_id)?.label ?? page.title,
          meta: page.role || "관련 자료"
        }));
    }

    const seenPageIds = new Set<string>();
    return message.references
      .filter((reference) => {
        const pageId = reference.source_document_id ? makeSourceId(reference.source_document_id) : null;
        if (!pageId || seenPageIds.has(pageId) || !isKnownPage(pageId)) return false;
        seenPageIds.add(pageId);
        return true;
      })
      .slice(0, MAX_RESULT_CARDS)
      .map((reference) => {
        const pageId = makeSourceId(reference.source_document_id ?? "");
        const pageType = nodeIdToPageType(pageId) ?? "source";
        return {
          key: `reference-${reference.id}`,
          pageId,
          pageType,
          title: formatWikiPageTitle(pageId, nodes, reference.source_document_id || "근거"),
          meta: formatReferenceMeta(reference)
        };
      });
  }

  function sourceTitle(documentId: string) {
    return findGraphNode(makeSourceId(documentId))?.label ?? documentId;
  }

  const animatedMessageIndex = messages.findIndex((message) => message.id === animatedMessageId);
  const animatedQuestionId = animatedMessageIndex > 0
    ? findLastUserMessage(messages.slice(0, animatedMessageIndex))?.id ?? null
    : null;
  const activeAssistantMessage = activeTurn?.assistantMessage;
  const shouldReserveScrollSpace = activeTurn !== null && (!activeAssistantMessage || visibleAnswerStage < 3);
  const messagesToRender = activeTurn
    ? messages.filter((message) => message.id !== activeTurn.userMessageId && message.id !== activeTurn.assistantMessage?.id)
    : messages;

  function renderAssistantThread(message: ChatMessageResponse, isAnimated: boolean, threadKey?: string) {
    const resultCards = buildRelatedPageCards(message);
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
      onOpenSourceBlocks(reference.source_document_id, sourceTitle(reference.source_document_id), highlights);
    };

    return (
      <div className="agent-thread" key={threadKey}>
        {(!isAnimated || visibleAnswerStage >= 1) && (
          <div className={isAnimated ? "agent-stage" : undefined}>
            <StatusList title="서치 명령 실행 중" isLoading={false} hasResponse />
          </div>
        )}

        {resultCards.length > 0 && (!isAnimated || visibleAnswerStage >= 2) && (
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

        {(!isAnimated || visibleAnswerStage >= 3) && (
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
          renderAssistantThread(message, message.id === animatedMessageId, message.id)
        )
      ))}

      {activeTurn && (
        <>
          <div className="question-bubble" ref={activeQuestionRef}>{activeTurn.question}</div>
          {activeAssistantMessage
            ? renderAssistantThread(activeAssistantMessage, activeAssistantMessage.id === animatedMessageId)
            : <div className="agent-thread"><StatusList title="서치 명령 실행 중" isLoading={isLoading} hasResponse={false} /></div>}
        </>
      )}
      {showAgentStatus && <div className="agent-thread"><StatusList title="서치 명령 실행 중" isLoading={isLoading} hasResponse={false} /></div>}

      {queryErrorMessage && <p className="query-error">{queryErrorMessage}</p>}
      {chatLoadErrorMessage && <p className="query-error">{chatLoadErrorMessage}</p>}

      {isLoading && <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요…</div>}
      {shouldReserveScrollSpace && <div className="agent-scroll-reserve" aria-hidden />}
    </div>
  );
}
