"use client";

import { useMemo, useState } from "react";
import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";
import { MarkdownCreatePreview } from "./MarkdownCreatePreview";
import { MarkdownEditPreview } from "./MarkdownEditPreview";
import { useChatThread } from "./useChatThread";
import { WikiExportConfirmCard } from "../modals/WikiExportConfirmCard";
import { exportChatWiki, fetchChatWikiExportPreview, requestAgentTurn } from "../../_lib/api";
import { getErrorMessage } from "../../_lib/errors";
import {
  buildAgentTurnRequest,
  describeAgentTurnResult,
  prepareMarkdownEditPreview,
  validateMarkdownEditApplication
} from "../../_lib/markdownAgent";
import type { AgentTurnRequest, AgentTurnResponse, GeneratedMarkdownDraft, MarkdownEditPreview as MarkdownEditPreviewData } from "../../_lib/markdownAgent";
import { findLastUserMessage } from "../../_lib/messages";
import type { ActiveMarkdownEditContext } from "../../_lib/markdownEditContext";
import type { GraphNode, SourceBlockHighlight } from "../../_lib/types";

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
export type { ActiveAgentTurn } from "./useChatThread";

export function AgentPanel({
  onClose,
  onOpenWikiPage,
  onOpenSourceBlocks,
  onCreateMarkdownDocument,
  markdownEditContext,
  nodes
}: {
  onClose: () => void;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  onCreateMarkdownDocument: (draft: GeneratedMarkdownDraft) => Promise<void>;
  markdownEditContext?: ActiveMarkdownEditContext | null;
  nodes?: GraphNode[];
}) {
  const [composerValue, setComposerValue] = useState("");
  const [exportPreview, setExportPreview] = useState<string | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportErrorMessage, setExportErrorMessage] = useState<string | null>(null);
  const [agentTurnResponse, setAgentTurnResponse] = useState<AgentTurnResponse | null>(null);
  const [agentTurnRequest, setAgentTurnRequest] = useState<AgentTurnRequest | null>(null);
  const [agentTurnErrorMessage, setAgentTurnErrorMessage] = useState<string | null>(null);
  const [agentTurnSuccessMessage, setAgentTurnSuccessMessage] = useState<string | null>(null);
  const [isAgentTurnLoading, setIsAgentTurnLoading] = useState(false);
  const [isCreatingMarkdown, setIsCreatingMarkdown] = useState(false);
  const [markdownCreateErrorMessage, setMarkdownCreateErrorMessage] = useState<string | null>(null);
  const { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, submitQuery } = useChatThread();
  const isSubmitting = isLoading || isAgentTurnLoading || isCreatingMarkdown;
  const hasAssistantMessage = messages.some((message) => message.role !== "user");
  const sessionTitle = buildSessionTitle(activeTurn?.question ?? findLastUserMessage(messages)?.content);
  const composerPlaceholder = markdownEditContext
    ? `${markdownEditContext.editorSnapshot.target.type === "selection" ? "선택 영역" : markdownEditContext.editorSnapshot.target.type === "current_section" ? "현재 섹션" : "문서 전체"}을 어떻게 편집할까요?`
    : "AI 에이전트에게 무엇이든 물어보세요.";
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

  function submitAgentTurn(question: string, context: ActiveMarkdownEditContext) {
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
  }

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
      .catch((error: unknown) => setExportErrorMessage(getErrorMessage(error, "위키 내보내기에 실패했습니다.")))
      .finally(() => setIsExporting(false));
  }

  function acceptExport() {
    setIsExporting(true);
    exportChatWiki()
      .then(() => setExportPreview(null))
      .catch((error: unknown) => setExportErrorMessage(getErrorMessage(error, "위키 내보내기에 실패했습니다.")))
      .finally(() => setIsExporting(false));
  }

  return (
    <aside className="agent-panel" onClick={(event) => event.stopPropagation()}>
      <AgentHeader sessionTitle={sessionTitle} onClose={onClose} />
      <AgentBody
        messages={messages}
        isLoading={isSubmitting}
        activeTurn={activeTurn}
        queryErrorMessage={agentTurnErrorMessage ?? queryErrorMessage}
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedMessageId}
        onOpenWikiPage={onOpenWikiPage}
        onOpenSourceBlocks={onOpenSourceBlocks}
        nodes={nodes}
      />
      {agentTurnResponse
        && agentTurnResponse.result.action !== "markdown_edit"
        && agentTurnResponse.result.action !== "markdown_create" && (
        <div className="agent-turn-notice" role="status">
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
        <div className="agent-turn-notice" role="alert">
          <strong>{editPreviewState.validationError}</strong>
          <span>원문은 변경되지 않았습니다. 최신 문서에서 다시 요청해주세요.</span>
        </div>
      )}
      {agentTurnSuccessMessage && (
        <div className="agent-turn-notice" role="status">
          <strong>{agentTurnSuccessMessage}</strong>
        </div>
      )}
      {hasAssistantMessage && (
        <div className="wiki-export-trigger">
          <button type="button" disabled={isExporting} onClick={openExportPreview}>
            논문 작업 자료 바로 만들어줘
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
          title="채팅 내용을 문서로 내보낼까요?"
          previewContent={exportPreview}
          isSubmitting={isExporting}
          onCancel={() => setExportPreview(null)}
          onAccept={acceptExport}
        />
      )}
    </aside>
  );
}
