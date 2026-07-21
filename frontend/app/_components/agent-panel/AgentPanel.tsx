"use client";

import { useState } from "react";
import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";
import { useChatThread } from "./useChatThread";
import { WikiExportConfirmCard } from "../modals/WikiExportConfirmCard";
import { exportChatWiki, fetchChatWikiExportPreview, requestAgentTurn } from "../../_lib/api";
import { getErrorMessage } from "../../_lib/errors";
import { buildAgentTurnRequest, describeAgentTurnResult } from "../../_lib/markdownAgent";
import type { AgentTurnResponse } from "../../_lib/markdownAgent";
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
  markdownEditContext,
  nodes
}: {
  onClose: () => void;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  markdownEditContext?: ActiveMarkdownEditContext | null;
  nodes?: GraphNode[];
}) {
  const [composerValue, setComposerValue] = useState("");
  const [exportPreview, setExportPreview] = useState<string | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportErrorMessage, setExportErrorMessage] = useState<string | null>(null);
  const [agentTurnResponse, setAgentTurnResponse] = useState<AgentTurnResponse | null>(null);
  const [agentTurnErrorMessage, setAgentTurnErrorMessage] = useState<string | null>(null);
  const [isAgentTurnLoading, setIsAgentTurnLoading] = useState(false);
  const { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, submitQuery } = useChatThread();
  const isSubmitting = isLoading || isAgentTurnLoading;
  const hasAssistantMessage = messages.some((message) => message.role !== "user");
  const sessionTitle = buildSessionTitle(activeTurn?.question ?? findLastUserMessage(messages)?.content);
  const composerPlaceholder = markdownEditContext
    ? `${markdownEditContext.editorSnapshot.target.type === "selection" ? "선택 영역" : markdownEditContext.editorSnapshot.target.type === "current_section" ? "현재 섹션" : "문서 전체"}을 어떻게 편집할까요?`
    : "AI 에이전트에게 무엇이든 물어보세요.";

  function handleSubmit() {
    const question = composerValue.trim();
    if (!question || isSubmitting) return;
    setComposerValue("");
    if (markdownEditContext) {
      setAgentTurnResponse(null);
      setAgentTurnErrorMessage(null);
      setIsAgentTurnLoading(true);
      requestAgentTurn(buildAgentTurnRequest(question, markdownEditContext))
        .then(setAgentTurnResponse)
        .catch((error: unknown) => {
          setAgentTurnErrorMessage(getErrorMessage(error, "AI 편집 요청에 실패했습니다."));
        })
        .finally(() => setIsAgentTurnLoading(false));
      return;
    }
    void submitQuery(question);
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
      {agentTurnResponse && (
        <div className="agent-turn-notice" role="status">
          <strong>{describeAgentTurnResult(agentTurnResponse.result)}</strong>
          {agentTurnResponse.result.action === "markdown_edit" && (
            <span>편집 결과는 아직 원문에 적용되지 않았습니다.</span>
          )}
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
