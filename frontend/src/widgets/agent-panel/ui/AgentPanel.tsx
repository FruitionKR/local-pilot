"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AgentBody } from "@/features/agent-chat/ui/AgentBody";
import { AgentComposer } from "@/features/agent-chat/ui/AgentComposer";
import { AgentHeader } from "@/features/agent-chat/ui/AgentHeader";
import { MarkdownCreatePreview } from "@/features/agent-chat/ui/MarkdownCreatePreview";
import { MarkdownEditPreview } from "@/features/agent-chat/ui/MarkdownEditPreview";
import { useChatThread } from "@/features/agent-chat/model/useChatThread";
import { WikiExportConfirmCard } from "@/features/wiki-export/ui/WikiExportConfirmCard";
import { fetchCurrentChatSessionId } from "@/entities/chat";
import { requestAgentTurn } from "@/features/agent-chat";
import { exportChatWiki, fetchChatWikiExportPreview, type ChatWikiExportResponse } from "@/features/wiki-export";
import { getErrorMessage } from "@/shared/lib/errors";
import {
  buildAgentTurnRequest,
  describeAgentTurnResult,
  prepareMarkdownEditPreview,
  validateMarkdownEditApplication
} from "@/features/agent-chat/lib/markdownAgent";
import type { AgentTurnRequest, AgentTurnResponse, GeneratedMarkdownDraft, MarkdownEditPreview as MarkdownEditPreviewData } from "@/features/agent-chat/lib/markdownAgent";
import { findLastUserMessage } from "@/shared/lib/messages";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import type { SourceBlockHighlight } from "@/entities/document";
import type { GraphNode } from "@/entities/wiki";
import styles from "@/features/agent-chat/ui/AgentChat.module.css";

// 헤더 세션 제목으로 보여줄 마지막 질문의 최대 길이
const SESSION_TITLE_MAX_LENGTH = 12;

/** 마지막 user 질문을 잘라 세션 제목으로 만든다. 없으면 "새 채팅" */
function buildSessionTitle(question: string | undefined): string {
  if (!question) return "새 채팅";
  return question.length > SESSION_TITLE_MAX_LENGTH
    ? `${question.slice(0, SESSION_TITLE_MAX_LENGTH)}…`
    : question;
}

// AgentBody 등이 이 파일에서 ActiveAgentTurn을 import하므로 re-export 유지
export type { ActiveAgentTurn } from "@/features/agent-chat/model/useChatThread";

export function AgentPanel({
  onClose,
  onOpenWikiPage,
  onOpenSourceBlocks,
  onCreateMarkdownDocument,
  markdownEditContext,
  onDocumentExported,
  nodes
}: {
  onClose: () => void;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  onCreateMarkdownDocument: (draft: GeneratedMarkdownDraft) => Promise<void>;
  markdownEditContext?: ActiveMarkdownEditContext | null;
  onDocumentExported?: (response: ChatWikiExportResponse) => Promise<void> | void;
  nodes?: GraphNode[];
}) {
  const [composerValue, setComposerValue] = useState("");
  const [exportPreview, setExportPreview] = useState<string | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportErrorMessage, setExportErrorMessage] = useState<string | null>(null);
  const [selectedPairIds, setSelectedPairIds] = useState<Set<string>>(new Set());
  const [agentTurnResponse, setAgentTurnResponse] = useState<AgentTurnResponse | null>(null);
  const [agentTurnRequest, setAgentTurnRequest] = useState<AgentTurnRequest | null>(null);
  const [agentTurnErrorMessage, setAgentTurnErrorMessage] = useState<string | null>(null);
  const [agentTurnSuccessMessage, setAgentTurnSuccessMessage] = useState<string | null>(null);
  const [isAgentTurnLoading, setIsAgentTurnLoading] = useState(false);
  const [isCreatingMarkdown, setIsCreatingMarkdown] = useState(false);
  const [markdownCreateErrorMessage, setMarkdownCreateErrorMessage] = useState<string | null>(null);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [activeSessionTitle, setActiveSessionTitle] = useState<string | null>(null);
  const { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, queryStages, submitQuery } = useChatThread(activeSessionId);
  const isSubmitting = isLoading || isAgentTurnLoading || isCreatingMarkdown;
  const hasAssistantMessage = messages.some((message) => message.role !== "user");
  // 채팅→문서 부분 편입용 문답(pair) 목록. 각 pair는 user 질문으로 라벨링한다.
  const exportPairs = useMemo(() => {
    const byPair = new Map<string, { pairId: string; question: string }>();
    for (const message of messages) {
      if (!message.pair_id || byPair.has(message.pair_id)) continue;
      if (message.role === "user") byPair.set(message.pair_id, { pairId: message.pair_id, question: message.content });
    }
    return [...byPair.values()];
  }, [messages]);
  const togglePair = (pairId: string) =>
    setSelectedPairIds((current) => {
      const next = new Set(current);
      if (next.has(pairId)) next.delete(pairId);
      else next.add(pairId);
      return next;
    });
  // 마운트 시 현재 활성 세션을 확보해 헤더 강조·메시지 로드의 기준으로 삼는다.
  useEffect(() => {
    fetchCurrentChatSessionId().then(setActiveSessionId).catch(() => undefined);
  }, []);
  const lastQuestion = activeTurn?.question ?? findLastUserMessage(messages)?.content;
  const sessionTitle = lastQuestion ? buildSessionTitle(lastQuestion) : (activeSessionTitle ?? "새 채팅");
  const composerPlaceholder = "AI 에이전트에게 무엇이든 물어보세요.";
  const editPreviewState = useMemo<{
    preview: MarkdownEditPreviewData | null;
    validationError: string | null;
  } | null>(() => {
    if (!agentTurnRequest || !agentTurnResponse || agentTurnResponse.result.action !== "markdown_edit") return null;
    try {
      const preview = prepareMarkdownEditPreview(agentTurnRequest, agentTurnResponse);
      if (!markdownEditContext) {
        return { preview, validationError: "편집 중인 문서를 다시 열고 재생성해주세요." };
      }
      try {
        validateMarkdownEditApplication(agentTurnRequest, agentTurnResponse, markdownEditContext);
        return { preview, validationError: null };
      } catch (error) {
        return { preview, validationError: getErrorMessage(error, "편집 결과를 적용할 수 없습니다.") };
      }
    } catch (error) {
      return { preview: null, validationError: getErrorMessage(error, "편집 응답 계약이 올바르지 않습니다.") };
    }
  }, [agentTurnRequest, agentTurnResponse, markdownEditContext]);

  const submitAgentTurn = useCallback((question: string, context: ActiveMarkdownEditContext) => {
    const request = buildAgentTurnRequest(question, context);
    setAgentTurnRequest(request);
    setAgentTurnResponse(null);
    setAgentTurnErrorMessage(null);
    setAgentTurnSuccessMessage(null);
    setMarkdownCreateErrorMessage(null);
    setIsAgentTurnLoading(true);
    requestAgentTurn(request)
      .then(setAgentTurnResponse)
      .catch((error: unknown) => {
        setAgentTurnErrorMessage(getErrorMessage(error, "AI 편집 요청에 실패했습니다."));
      })
      .finally(() => setIsAgentTurnLoading(false));
  }, []);

  function handleSubmit() {
    const question = composerValue.trim();
    if (!question || isSubmitting) return;
    setComposerValue("");
    if (markdownEditContext) {
      submitAgentTurn(question, markdownEditContext);
      return;
    }
    void submitQuery(question);
  }

  function dismissAgentTurnResult() {
    setAgentTurnRequest(null);
    setAgentTurnResponse(null);
    setAgentTurnErrorMessage(null);
    setMarkdownCreateErrorMessage(null);
  }

  function regenerateAgentTurn() {
    if (!agentTurnRequest || !markdownEditContext) {
      setAgentTurnErrorMessage("편집 중인 문서를 다시 열고 재생성해주세요.");
      return;
    }
    submitAgentTurn(agentTurnRequest.message, markdownEditContext);
  }

  function applyMarkdownEdit() {
    if (!agentTurnRequest || !agentTurnResponse || !markdownEditContext || !editPreviewState?.preview) return;
    try {
      validateMarkdownEditApplication(agentTurnRequest, agentTurnResponse, markdownEditContext);
      const applied = markdownEditContext.applyMarkdown(
        agentTurnRequest.editorSnapshot.markdown,
        editPreviewState.preview.nextMarkdown
      );
      if (!applied) throw new Error("적용 직전에 문서가 변경되었습니다. 최신 내용으로 재생성해주세요.");
      setAgentTurnSuccessMessage("AI 편집 결과를 적용했으며 자동 저장을 시작했습니다.");
      setAgentTurnRequest(null);
      setAgentTurnResponse(null);
      setAgentTurnErrorMessage(null);
    } catch (error) {
      setAgentTurnErrorMessage(getErrorMessage(error, "편집 결과를 적용하지 못했습니다."));
    }
  }

  function createMarkdownDocument() {
    const draft = agentTurnResponse?.result.generated_markdown;
    if (!draft) return;
    setIsCreatingMarkdown(true);
    setMarkdownCreateErrorMessage(null);
    onCreateMarkdownDocument(draft)
      .then(() => {
        setAgentTurnSuccessMessage("AI 초안을 새 Markdown 문서로 만들었습니다.");
        setAgentTurnRequest(null);
        setAgentTurnResponse(null);
      })
      .catch((error: unknown) => {
        setMarkdownCreateErrorMessage(getErrorMessage(error, "새 Markdown 문서를 만들지 못했습니다."));
      })
      .finally(() => setIsCreatingMarkdown(false));
  }

  function openExportPreview() {
    setExportErrorMessage(null);
    setIsExporting(true);
    fetchChatWikiExportPreview()
      .then(setExportPreview)
      .catch((error: unknown) => setExportErrorMessage(getErrorMessage(error, "채팅 문서 미리보기에 실패했습니다.")))
      .finally(() => setIsExporting(false));
  }

  function acceptExport() {
    setExportErrorMessage(null);
    setIsExporting(true);
    exportChatWiki([...selectedPairIds])
      .then(async (response) => {
        setExportPreview(null);
        setSelectedPairIds(new Set());
        await onDocumentExported?.(response);
        setAgentTurnSuccessMessage("채팅을 문서로 편입해 AI 처리 파이프라인에 전달했습니다.");
      })
      .catch((error: unknown) => setExportErrorMessage(getErrorMessage(error, "채팅을 문서로 편입하지 못했습니다.")))
      .finally(() => setIsExporting(false));
  }

  return (
    <aside
      className={`agent-panel${editPreviewState?.preview ? " is-markdown-reviewing" : ""}`}
      onClick={(event) => event.stopPropagation()}
    >
      <AgentHeader
        sessionTitle={sessionTitle}
        onClose={onClose}
        activeSessionId={activeSessionId}
        onSelectSession={(sessionId, title) => {
          setActiveSessionId(sessionId);
          setActiveSessionTitle(title);
          // 이전 세션에서 고른 부분 편입 선택이 새 세션으로 새어나가지 않도록 초기화한다.
          setSelectedPairIds(new Set());
        }}
      />
      <AgentBody
        messages={messages}
        isLoading={isSubmitting}
        activeTurn={activeTurn}
        queryErrorMessage={agentTurnErrorMessage ?? queryErrorMessage}
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedMessageId}
        queryStages={queryStages}
        onOpenWikiPage={onOpenWikiPage}
        onOpenSourceBlocks={onOpenSourceBlocks}
        nodes={nodes}
      />
      {agentTurnResponse
        && agentTurnResponse.result.action !== "markdown_edit"
        && agentTurnResponse.result.action !== "markdown_create" && (
        <div className={styles["agent-turn-notice"]} role="status">
          <strong>{describeAgentTurnResult(agentTurnResponse.result)}</strong>
        </div>
      )}
      {agentTurnResponse?.result.action === "markdown_create" && agentTurnResponse.result.generated_markdown && (
        <MarkdownCreatePreview
          draft={agentTurnResponse.result.generated_markdown}
          isSubmitting={isCreatingMarkdown}
          errorMessage={markdownCreateErrorMessage}
          onCancel={dismissAgentTurnResult}
          onRegenerate={regenerateAgentTurn}
          onCreate={createMarkdownDocument}
        />
      )}
      {editPreviewState?.preview && (
        <MarkdownEditPreview
          preview={editPreviewState.preview}
          validationError={editPreviewState.validationError}
          isLoading={isAgentTurnLoading}
          onApply={applyMarkdownEdit}
          onCancel={dismissAgentTurnResult}
          onRegenerate={regenerateAgentTurn}
        />
      )}
      {editPreviewState && !editPreviewState.preview && (
        <div className={styles["agent-turn-notice"]} role="alert">
          <strong>{editPreviewState.validationError}</strong>
          <span>원문은 변경되지 않았습니다. 최신 문서에서 다시 요청해주세요.</span>
        </div>
      )}
      {agentTurnSuccessMessage && (
        <div className={styles["agent-turn-notice"]} role="status">
          <strong>{agentTurnSuccessMessage}</strong>
        </div>
      )}
      {hasAssistantMessage && (
        <div className="wiki-export-trigger">
          {exportPairs.length > 1 && (
            <details className="wiki-export-selection">
              <summary>부분 선택 {selectedPairIds.size > 0 ? `(${selectedPairIds.size}개 문답)` : "(전체)"}</summary>
              <ul>
                {exportPairs.map((pair) => (
                  <li key={pair.pairId}>
                    <label>
                      <input
                        type="checkbox"
                        checked={selectedPairIds.has(pair.pairId)}
                        onChange={() => togglePair(pair.pairId)}
                      />
                      <span>{pair.question}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </details>
          )}
          <button type="button" disabled={isExporting} onClick={openExportPreview}>
            채팅을 문서로 편입{selectedPairIds.size > 0 ? ` (${selectedPairIds.size}개 선택)` : ""}
          </button>
          {exportErrorMessage && <p role="alert">{exportErrorMessage}</p>}
        </div>
      )}
      <AgentComposer
        value={composerValue}
        isLoading={isSubmitting}
        placeholder={composerPlaceholder}
        onChange={setComposerValue}
        onSubmit={handleSubmit}
      />

      {exportPreview !== null && (
        <WikiExportConfirmCard
          title="채팅 내용을 문서로 편입할까요?"
          previewContent={exportPreview}
          isSubmitting={isExporting}
          onCancel={() => setExportPreview(null)}
          onAccept={acceptExport}
        />
      )}
    </aside>
  );
}
