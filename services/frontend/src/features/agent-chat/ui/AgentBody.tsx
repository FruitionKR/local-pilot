import { ChevronDown } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import { buildDocumentCommandSteps, type StatusStep } from "../lib/agentData";
import { resolveChatTurnPresentation } from "../lib/markdownAgent";
import type { QueryStageEvent } from "@/entities/wiki/api/wiki";
import type { ActiveAgentTurn } from "../model/useChatThread";
import { findSourceNodeByDocumentId } from "@/entities/graph/lib/graph";
import { citedRanks, formatAnswerMarkdown, formatReferenceMeta, formatWikiPageTitle } from "../lib/agentFormatters";
import type { ChatMessageResponse } from "@/entities/chat/model/chat";
import type { GraphNode } from "@/entities/wiki/model/wiki";
import type { SourceBlockHighlight } from "@/entities/document/model/document";
import { cx } from "@/shared/lib/classNames";
import { useSmoothScroll } from "../lib/useSmoothScroll";
import styles from "./AgentChat.module.css";

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

type ChatMessageGroup = {
  key: string;
  pairId: string | null;
  messages: ChatMessageResponse[];
};

function groupMessagesByPair(messages: ChatMessageResponse[]): ChatMessageGroup[] {
  const groups: ChatMessageGroup[] = [];
  const groupByPairId = new Map<string, ChatMessageGroup>();

  for (const message of messages) {
    if (!message.pair_id) {
      groups.push({ key: message.id, pairId: null, messages: [message] });
      continue;
    }

    let group = groupByPairId.get(message.pair_id);
    if (!group) {
      group = { key: message.pair_id, pairId: message.pair_id, messages: [] };
      groupByPairId.set(message.pair_id, group);
      groups.push(group);
    }
    group.messages.push(message);
  }

  return groups;
}

export function AgentBody({
  messages,
  isLoading,
  isDocumentCommandLoading,
  documentCommandQuestion,
  activeSessionId,
  activeTurn,
  queryErrorMessage,
  chatLoadErrorMessage,
  animatedMessageId,
  queryStages = [],
  onOpenWikiPage,
  onOpenSourceBlocks,
  isPairSelectionMode,
  selectablePairIds,
  excludedPairIds,
  selectedPairIds,
  onSelectPair,
  nodes
}: {
  messages: ChatMessageResponse[];
  isLoading: boolean;
  isDocumentCommandLoading: boolean;
  documentCommandQuestion: string | null;
  activeSessionId: string | null;
  activeTurn: ActiveAgentTurn | null;
  queryErrorMessage: string | null;
  chatLoadErrorMessage: string | null;
  animatedMessageId: string | null;
  queryStages?: QueryStageEvent[];
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  isPairSelectionMode: boolean;
  selectablePairIds: string[];
  excludedPairIds: string[];
  selectedPairIds: string[];
  onSelectPair: (pairId: string) => void;
  nodes?: GraphNode[];
}) {
  const showAgentStatus = isLoading && activeTurn === null;
  const [visibleAnswerStage, setVisibleAnswerStage] = useState(STAGE_ANSWER);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const hasScrolledInitialMessagesRef = useRef(false);
  const { scrollToPosition } = useSmoothScroll(bodyRef);

  useEffect(() => {
    hasScrolledInitialMessagesRef.current = false;
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }, [activeSessionId]);

  const scrollToLatestMessage = useCallback(({ immediate = false } = {}) => {
    const body = bodyRef.current;
    if (!body) return;

    scrollToPosition(body.scrollHeight - body.clientHeight, { immediate });
  }, [scrollToPosition]);

  // 진행 단계는 SSE StatusList로 실시간 표시하므로, 답변 도착 시 지연 연출 없이 전체를 공개한다.
  useEffect(() => {
    setVisibleAnswerStage(STAGE_ANSWER);
  }, [animatedMessageId]);

  // 질문·답변이 항상 채팅창 맨 아래에 붙어 보이도록 최신 메시지로 스크롤한다.
  useLayoutEffect(() => {
    if (!activeTurn) return;

    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage());
    return () => window.cancelAnimationFrame(frameId);
  }, [activeTurn, scrollToLatestMessage]);

  useLayoutEffect(() => {
    if (!animatedMessageId && !isLoading && !queryErrorMessage) return;

    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage());
    return () => window.cancelAnimationFrame(frameId);
  }, [animatedMessageId, visibleAnswerStage, isLoading, activeTurn, queryErrorMessage, scrollToLatestMessage]);

  // 전송 직후 추가된 질문·진행 UI가 화면 아래에 가려지지 않도록 한 번만 즉시 내린다.
  // 이후 단계 갱신에는 반응하지 않아 사용자가 직접 올린 스크롤 위치를 방해하지 않는다.
  useLayoutEffect(() => {
    if (!isLoading && !isDocumentCommandLoading) return;

    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage({ immediate: true }));
    return () => window.cancelAnimationFrame(frameId);
  }, [isDocumentCommandLoading, isLoading, scrollToLatestMessage]);

  useEffect(() => {
    if (messages.length === 0 || animatedMessageId || isLoading || queryErrorMessage) return;
    if (hasScrolledInitialMessagesRef.current) return;

    hasScrolledInitialMessagesRef.current = true;
    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage({ immediate: true }));
    return () => window.cancelAnimationFrame(frameId);
  }, [messages.length, animatedMessageId, isLoading, queryErrorMessage, scrollToLatestMessage]);

  const activeAssistantMessage = activeTurn?.assistantMessage;
  const messagesToRender = activeTurn
    ? messages.filter((message) => message.id !== activeTurn.userMessageId && message.id !== activeTurn.assistantMessage?.id)
    : messages;
  const messageGroups = groupMessagesByPair(messagesToRender);
  const selectablePairIdSet = new Set(selectablePairIds);
  const excludedPairIdSet = new Set(excludedPairIds);
  const selectedPairIdSet = new Set(selectedPairIds);
  const selectedRangeStart = selectedPairIds[0] ?? null;
  const selectedRangeEnd = selectedPairIds.at(-1) ?? null;
  // SSE로 받은 실제 진행 단계를 상태 목록으로 표시한다. 마지막 단계는 아직 진행 중이면 active로 둔다.
  const stageSteps: StatusStep[] = queryStages.map((stage, index): StatusStep => [
    stage.message || stage.stage,
    index === queryStages.length - 1 && isLoading ? "active" : "done"
  ]);
  const pendingStatusThread = (
    <div className={styles["agent-thread"]}>
      <StatusList
        title={SEARCH_STATUS_TITLE}
        isLoading={isLoading}
        hasResponse={false}
        steps={stageSteps.length > 0 ? stageSteps : undefined}
      />
    </div>
  );

  return (
    <div className={cx(styles["agent-body"], isPairSelectionMode && styles["is-pair-selecting"])} ref={bodyRef}>
      {messageGroups.map((group) => {
        const isSelectable = group.pairId !== null && selectablePairIdSet.has(group.pairId);
        const isExcluded = group.pairId !== null && excludedPairIdSet.has(group.pairId);
        const isSelected = group.pairId !== null && selectedPairIdSet.has(group.pairId);
        const isRangeStart = group.pairId !== null && group.pairId === selectedRangeStart;
        const isRangeEnd = group.pairId !== null && group.pairId === selectedRangeEnd;

        return (
          <div
            key={group.key}
            className={cx(
              styles["chat-pair"],
              isPairSelectionMode && isSelectable && styles["is-selection-candidate"],
              isPairSelectionMode && isExcluded && styles["is-excluded"],
              isPairSelectionMode && !isSelected && !isExcluded && styles["is-dimmed"],
              isSelected && styles["is-selected"],
              isRangeStart && styles["is-range-start"],
              isRangeEnd && styles["is-range-end"]
            )}
            aria-disabled={isPairSelectionMode && isExcluded ? true : undefined}
          >
            {isPairSelectionMode && isSelectable && (
              <button
                type="button"
                className={styles["chat-selection-overlay"]}
                aria-pressed={isSelected}
                aria-label="이 문답을 편입 범위로 선택"
                onClick={() => onSelectPair(group.pairId as string)}
              />
            )}
            <div className={styles["chat-pair-content"]} inert={isPairSelectionMode}>
              {group.messages.map((message) => (
              message.role === "user" ? (
                <div className={styles["question-bubble"]} key={message.id}>
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
            </div>
          </div>
        );
      })}

      {activeTurn && (
        <>
          <div className={styles["question-bubble"]}>{activeTurn.question}</div>
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

      {isDocumentCommandLoading && documentCommandQuestion && (
        <>
          <div className={styles["question-bubble"]}>{documentCommandQuestion}</div>
          <div className={styles.typing}><i /><i /><i /> 명령을 해석 중입니다…</div>
        </>
      )}

      {queryErrorMessage && <p className={styles["query-error"]}>{queryErrorMessage}</p>}
      {chatLoadErrorMessage && <p className={styles["query-error"]}>{chatLoadErrorMessage}</p>}
      {isLoading && <div className={styles.typing}><i /><i /><i /> 답변을 작성하고 있어요…</div>}
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
  const presentation = resolveChatTurnPresentation(message.action);
  const documentCommandAction = presentation.kind === "document-command" ? presentation.action : null;
  const isSearchAnswer = presentation.kind === "query" && presentation.grounded;
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
    <div className={cx(styles["agent-thread"], documentCommandAction && styles["is-document-command"])}>
      {(documentCommandAction || isSearchAnswer) && (!isAnimated || visibleAnswerStage >= STAGE_STATUS) && (
        <div className={isAnimated ? styles["agent-stage"] : undefined}>
          <StatusList
            title={documentCommandAction ? "문서 명령 실행 완료" : SEARCH_STATUS_TITLE}
            isLoading={false}
            hasResponse
            steps={documentCommandAction
              ? buildDocumentCommandSteps(documentCommandAction, false, true)
              : undefined}
          />
        </div>
      )}

      {isSearchAnswer && resultCards.length > 0 && (!isAnimated || visibleAnswerStage >= STAGE_RESULTS) && (
        <div className={cx(styles.results, isAnimated && styles["agent-stage"])}>
          <p>찾은 자료 {resultCards.length}건</p>
          {resultCards.map((card) => (
            <AgentResultCard
              key={card.key}
              title={card.title}
              meta={card.meta}
              pageType={card.pageType}
              // source/concept wiki page는 내부 구성(블록 참조 등)이 그대로 노출되므로 미리보기를 열지 않는다.
              onClick={["source", "concept"].includes(card.pageType.toLowerCase())
                ? undefined
                : () => onOpenWikiPage(card.pageId, card.title, card.pageType)}
            />
          ))}
        </div>
      )}

      {(!isAnimated || visibleAnswerStage >= STAGE_ANSWER) && (
        <section
          className={cx(styles["agent-answer"], isAnimated && styles["agent-stage"])}
          aria-label={isSearchAnswer ? "실행 중 발견 사항" : "답변"}
        >
          {isSearchAnswer && (
            <div className={styles["answer-section-title"]}>
              <span>실행 중 발견 사항</span>
              <ChevronDown size={8} />
            </div>
          )}
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
