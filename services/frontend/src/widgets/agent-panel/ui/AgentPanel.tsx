"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { RotateCcw } from "lucide-react";
import { AgentBody } from "@/features/agent-chat/ui/AgentBody";
import { AgentComposer, type AiModelCatalogStatus } from "@/features/agent-chat/ui/AgentComposer";
import { AgentHeader } from "@/features/agent-chat/ui/AgentHeader";
import { MarkdownCreatePreview } from "@/features/agent-chat/ui/MarkdownCreatePreview";
import { MarkdownEditPreview } from "@/features/agent-chat/ui/MarkdownEditPreview";
import { useChatThread } from "@/features/agent-chat/model/useChatThread";
import { fetchAiModels, resolveInitialModel, type AiModel } from "@/entities/ai";
import { fetchCurrentChatSessionId } from "@/entities/chat";
import { useUserPreferences } from "@/entities/user";
import { requestAgentTurn } from "@/features/agent-chat";
import { publishNotice } from "@/features/document-notifications";
import { exportChatWiki, getChatExportSuccessMessage, type ChatWikiExportResponse } from "@/features/wiki-export";
import { getErrorMessage } from "@/shared/lib/errors";
import {
  buildAgentTurnRequest,
  prepareMarkdownEditPreview,
  resolveChatTurnPresentation,
  validateMarkdownEditApplication
} from "@/features/agent-chat/lib/markdownAgent";
import type { AgentTurnRequest, AgentTurnResponse, GeneratedMarkdownDraft, MarkdownEditPreview as MarkdownEditPreviewData } from "@/features/agent-chat/lib/markdownAgent";
import { findLastUserMessage } from "@/shared/lib/messages";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import {
  classifyChatExportPairs,
  EMPTY_CHAT_PAIR_RANGE_SELECTION,
  selectChatPairRange,
  type ChatPairRangeSelection
} from "@/features/agent-chat/lib/chatPairSelection";
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
  const [isExporting, setIsExporting] = useState(false);
  const [exportErrorMessage, setExportErrorMessage] = useState<string | null>(null);
  const [isPairSelectionMode, setIsPairSelectionMode] = useState(false);
  const [pairSelection, setPairSelection] = useState<ChatPairRangeSelection>(EMPTY_CHAT_PAIR_RANGE_SELECTION);
  const [agentTurnResponse, setAgentTurnResponse] = useState<AgentTurnResponse | null>(null);
  const [agentTurnRequest, setAgentTurnRequest] = useState<AgentTurnRequest | null>(null);
  const [agentTurnErrorMessage, setAgentTurnErrorMessage] = useState<string | null>(null);
  const [isAgentTurnLoading, setIsAgentTurnLoading] = useState(false);
  const [isCreatingMarkdown, setIsCreatingMarkdown] = useState(false);
  const [markdownCreateErrorMessage, setMarkdownCreateErrorMessage] = useState<string | null>(null);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [activeSessionTitle, setActiveSessionTitle] = useState<string | null>(null);
  const [aiModels, setAiModels] = useState<AiModel[]>([]);
  const [selectedModel, setSelectedModel] = useState<AiModel | null>(null);
  const [aiModelCatalogStatus, setAiModelCatalogStatus] = useState<AiModelCatalogStatus>("loading");
  const [aiModelsErrorMessage, setAiModelsErrorMessage] = useState<string | null>(null);
  const { preferences, preferencesReady, updatePreferences } = useUserPreferences();
  const {
    messages,
    queryErrorMessage,
    chatLoadErrorMessage,
    animatedMessageId,
    activeTurn,
    isLoading,
    queryStages,
    refreshMessages,
    submitQuery
  } = useChatThread(activeSessionId);
  const isSubmitting = isLoading || isAgentTurnLoading || isCreatingMarkdown;
  const { selectablePairIds: exportPairIds, excludedPairIds } = useMemo(
    () => classifyChatExportPairs(messages),
    [messages]
  );
  const selectedPairIds = pairSelection.selectedPairIds;
  // 마운트 시 현재 활성 세션을 확보해 헤더 강조·메시지 로드의 기준으로 삼는다.
  useEffect(() => {
    fetchCurrentChatSessionId().then(setActiveSessionId).catch(() => undefined);
  }, []);
  // 선택 가능한 모델은 백엔드 카탈로그가 정한다. 프론트에 목록을 고정하지 않는다.
  useEffect(() => {
    fetchAiModels()
      .then((models) => {
        setAiModels(models);
        if (models.length === 0) {
          setAiModelCatalogStatus("empty");
          setAiModelsErrorMessage("사용 가능한 AI 모델이 없습니다.");
          return;
        }
        setAiModelCatalogStatus("ready");
      })
      .catch((error: unknown) => {
        setAiModelCatalogStatus("error");
        setAiModelsErrorMessage(getErrorMessage(error, "AI 모델 목록을 불러오지 못했습니다."));
      });
  }, []);
  // 카탈로그와 저장된 설정이 모두 준비된 뒤 초기 선택을 확정한다.
  useEffect(() => {
    if (!preferencesReady || aiModels.length === 0 || selectedModel) return;
    setSelectedModel(resolveInitialModel(aiModels, preferences.aiModel));
  }, [aiModels, preferences.aiModel, preferencesReady, selectedModel]);
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
  const editPreviewErrorMessage = editPreviewState && !editPreviewState.preview
    ? `${editPreviewState.validationError ?? "편집 결과를 적용할 수 없습니다."} 원문은 변경되지 않았습니다. 최신 문서에서 다시 요청해주세요.`
    : null;

  const submitAgentTurn = useCallback((
    question: string,
    context: ActiveMarkdownEditContext,
    sessionId: string,
    model: AiModel,
    conversationPairIds: string[]
  ) => {
    const request = buildAgentTurnRequest(question, context, {
      sessionId,
      selectedModel: { provider: model.provider, model: model.model },
      selectedPairIds: conversationPairIds
    });
    setAgentTurnRequest(request);
    setAgentTurnResponse(null);
    setAgentTurnErrorMessage(null);
    setMarkdownCreateErrorMessage(null);
    setIsAgentTurnLoading(true);
    requestAgentTurn(request)
      .then(async (response) => {
        await refreshMessages({ animateLatest: true });
        setAgentTurnResponse(
          resolveChatTurnPresentation(response.result.action).kind === "document-command" ? response : null
        );
      })
      .catch((error: unknown) => {
        setAgentTurnErrorMessage(getErrorMessage(error, "AI 편집 요청에 실패했습니다."));
      })
      .finally(() => setIsAgentTurnLoading(false));
  }, [refreshMessages]);

  function handleSubmit() {
    const question = composerValue.trim();
    if (!question || isSubmitting) return;
    if (markdownEditContext) {
      if (!activeSessionId || !selectedModel) return;
      setComposerValue("");
      submitAgentTurn(question, markdownEditContext, activeSessionId, selectedModel, selectedPairIds);
      return;
    }
    // 질의 경로는 provider/model 쌍이 반드시 필요하다. 카탈로그를 못 받았으면 입력을 지우지 않고 멈춘다.
    if (!selectedModel) return;
    setComposerValue("");
    void submitQuery(question, { provider: selectedModel.provider, model: selectedModel.model });
  }

  function handleModelChange(model: AiModel) {
    setSelectedModel(model);
    updatePreferences((current) => ({
      ...current,
      aiModel: { provider: model.provider, model: model.model }
    }));
  }

  function dismissAgentTurnResult() {
    setAgentTurnRequest(null);
    setAgentTurnResponse(null);
    setAgentTurnErrorMessage(null);
    setMarkdownCreateErrorMessage(null);
  }

  function regenerateAgentTurn() {
    if (!agentTurnRequest || !markdownEditContext || !activeSessionId || !selectedModel) {
      setAgentTurnErrorMessage("편집 중인 문서를 다시 열고 재생성해주세요.");
      return;
    }
    submitAgentTurn(agentTurnRequest.message, markdownEditContext, activeSessionId, selectedModel, selectedPairIds);
  }

  function applyMarkdownEdit() {
    if (!agentTurnRequest || !agentTurnResponse || !markdownEditContext || !editPreviewState?.preview) return;
    try {
      validateMarkdownEditApplication(agentTurnRequest, agentTurnResponse, markdownEditContext);
      const applied = markdownEditContext.applyMarkdown(
        agentTurnRequest.editorSnapshot.markdown,
        editPreviewState.preview.nextMarkdown,
        agentTurnResponse.apply_operation_id
      );
      if (!applied) throw new Error("적용 직전에 문서가 변경되었습니다. 최신 내용으로 재생성해주세요.");
      setAgentTurnRequest(null);
      setAgentTurnResponse(null);
      setAgentTurnErrorMessage(null);
    } catch (error) {
      setAgentTurnErrorMessage(getErrorMessage(error, "편집 결과를 적용하지 못했습니다."));
    }
  }

  function openMarkdownEditAsNewDocument() {
    if (!editPreviewState?.preview) return;
    const preview = editPreviewState.preview;
    const draft: GeneratedMarkdownDraft = {
      title: preview.summary.trim() || "AI 편집안",
      summary: preview.summary,
      markdown: preview.nextMarkdown
    };
    setIsCreatingMarkdown(true);
    setAgentTurnErrorMessage(null);
    onCreateMarkdownDocument(draft)
      .then(() => {
        setAgentTurnRequest(null);
        setAgentTurnResponse(null);
      })
      .catch((error: unknown) => {
        setAgentTurnErrorMessage(getErrorMessage(error, "편집안을 새 Markdown 문서로 만들지 못했습니다."));
      })
      .finally(() => setIsCreatingMarkdown(false));
  }

  function createMarkdownDocument() {
    const draft = agentTurnResponse?.result.generated_markdown;
    if (!draft) return;
    setIsCreatingMarkdown(true);
    setMarkdownCreateErrorMessage(null);
    onCreateMarkdownDocument(draft)
      .then(() => {
        setAgentTurnRequest(null);
        setAgentTurnResponse(null);
      })
      .catch((error: unknown) => {
        setMarkdownCreateErrorMessage(getErrorMessage(error, "새 Markdown 문서를 만들지 못했습니다."));
      })
      .finally(() => setIsCreatingMarkdown(false));
  }

  function startPairSelection() {
    if (isSubmitting || isExporting || exportPairIds.length === 0) return;
    setExportErrorMessage(null);
    setPairSelection(EMPTY_CHAT_PAIR_RANGE_SELECTION);
    setIsPairSelectionMode(true);
  }

  function selectPair(pairId: string) {
    if (isExporting) return;
    setPairSelection((current) => selectChatPairRange(exportPairIds, current, pairId));
  }

  function cancelPairSelection() {
    setPairSelection(EMPTY_CHAT_PAIR_RANGE_SELECTION);
    setIsPairSelectionMode(false);
  }

  async function acceptExport() {
    if (selectedPairIds.length === 0) return;
    setExportErrorMessage(null);
    setIsExporting(true);
    try {
      const response = await exportChatWiki(selectedPairIds);
      setPairSelection(EMPTY_CHAT_PAIR_RANGE_SELECTION);
      setIsPairSelectionMode(false);
      try {
        await onDocumentExported?.(response);
        publishNotice({
          kind: "completed",
          title: "채팅 편입 완료",
          message: `${getChatExportSuccessMessage(response.status)} 원문 문서를 생성하고 Ingest를 요청했습니다.`
        });
      } catch (error: unknown) {
        const detail = getErrorMessage(error, "워크스페이스 목록 갱신에 실패했습니다.");
        const message = `문서는 저장했지만 워크스페이스 목록을 갱신하지 못했습니다. 페이지를 새로고침해 주세요. (${detail})`;
        setExportErrorMessage(message);
        publishNotice({ kind: "failed", title: "문서 목록 갱신 실패", message });
      }
    } catch (error: unknown) {
      const message = getErrorMessage(error, "채팅을 문서로 편입하지 못했습니다.");
      setExportErrorMessage(message);
      publishNotice({
        kind: "failed",
        title: "채팅 편입 실패",
        message: `${message} 처리 결과가 불확실할 수 있으니 문서 목록을 확인해 주세요.`
      });
    } finally {
      setIsExporting(false);
    }
  }

  return (
    <aside
      className={`agent-panel${editPreviewState?.preview ? " is-markdown-reviewing" : ""}${isPairSelectionMode ? " is-chat-selecting" : ""}`}
      onClick={(event) => event.stopPropagation()}
    >
      <AgentHeader
        sessionTitle={sessionTitle}
        onClose={onClose}
        activeSessionId={activeSessionId}
        isInteractionLocked={isSubmitting || isExporting}
        canStartWikiExport={exportPairIds.length > 0 && !isSubmitting && !isExporting}
        onStartWikiExport={startPairSelection}
        onSelectSession={(sessionId, title) => {
          setActiveSessionId(sessionId);
          setActiveSessionTitle(title);
          // 이전 세션에서 고른 부분 편입 선택이 새 세션으로 새어나가지 않도록 초기화한다.
          setPairSelection(EMPTY_CHAT_PAIR_RANGE_SELECTION);
          setIsPairSelectionMode(false);
        }}
      />
      <AgentBody
        messages={messages}
        isLoading={isLoading}
        isDocumentCommandLoading={isAgentTurnLoading}
        documentCommandQuestion={isAgentTurnLoading ? agentTurnRequest?.message ?? null : null}
        activeSessionId={activeSessionId}
        activeTurn={activeTurn}
        queryErrorMessage={
          agentTurnErrorMessage
          ?? editPreviewErrorMessage
          ?? (markdownEditContext ? null : aiModelsErrorMessage)
          ?? queryErrorMessage
        }
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedMessageId}
        queryStages={queryStages}
        onOpenWikiPage={onOpenWikiPage}
        onOpenSourceBlocks={onOpenSourceBlocks}
        isPairSelectionMode={isPairSelectionMode}
        selectablePairIds={exportPairIds}
        excludedPairIds={excludedPairIds}
        selectedPairIds={selectedPairIds}
        onSelectPair={selectPair}
        nodes={nodes}
      />
      {isPairSelectionMode ? (
        <div className={styles["chat-selection-actions"]}>
          <div className={styles["chat-selection-buttons"]}>
            <button
              type="button"
              className={styles["chat-selection-reset"]}
              aria-label="채팅 선택 다시 하기"
              disabled={isExporting || selectedPairIds.length === 0}
              onClick={() => setPairSelection(EMPTY_CHAT_PAIR_RANGE_SELECTION)}
            >
              <RotateCcw size={16} />
            </button>
            <button type="button" className={styles["chat-selection-cancel"]} disabled={isExporting} onClick={cancelPairSelection}>
              취소
            </button>
            <button
              type="button"
              className={styles["chat-selection-confirm"]}
              aria-label={selectedPairIds.length > 0 ? `${selectedPairIds.length}개 문답 편입 확인` : "편입할 문답을 선택하세요"}
              disabled={isExporting || selectedPairIds.length === 0}
              onClick={() => void acceptExport()}
            >
              확인
            </button>
          </div>
          {exportErrorMessage && <p className={styles["chat-selection-error"]} role="alert">{exportErrorMessage}</p>}
        </div>
      ) : (
        <>
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
              isLoading={isAgentTurnLoading || isCreatingMarkdown}
              onApply={applyMarkdownEdit}
              onCancel={dismissAgentTurnResult}
              onOpenAsNewDocument={openMarkdownEditAsNewDocument}
              onRegenerate={regenerateAgentTurn}
            />
          )}
        </>
      )}
      <div className={styles["composer-region"]}>
        <AgentComposer
          value={composerValue}
          isLoading={isSubmitting || isPairSelectionMode}
          placeholder={composerPlaceholder}
          models={aiModels}
          selectedModel={selectedModel}
          modelCatalogStatus={aiModelCatalogStatus}
          canSubmit={!isPairSelectionMode && activeSessionId !== null && selectedModel !== null}
          onModelChange={handleModelChange}
          onChange={setComposerValue}
          onSubmit={handleSubmit}
        />
      </div>
    </aside>
  );
}
