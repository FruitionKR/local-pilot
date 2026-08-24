"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import { AgentPanel } from "@/widgets/agent-panel/ui/AgentPanel";
import { DocumentSidebar } from "@/widgets/document-sidebar/ui/DocumentSidebar";
import { Graph } from "@/widgets/graph/ui/Graph";
import { SchemaWorkspace } from "@/features/schema-manage/ui/SchemaWorkspace";
import { LogView } from "@/widgets/log-view";
import { SettingsPanel } from "@/features/settings";
import {
  DocumentProcessingNotifications,
  fetchWikiMaintenanceStatus,
  publishNotice,
  requestWikiLint
} from "@/features/document-notifications";
import {
  shouldRequestWikiLint,
  WIKI_UP_TO_DATE_NOTICE
} from "@/features/document-notifications/model/wikiLintStatus";
import { railItems, type RailView } from "@/widgets/rail-navigation/ui/RailNavigation";
import { UploadErrorModal } from "@/features/document-upload/ui/UploadErrorModal";
import { DeleteConfirmModal } from "@/shared/ui/DeleteConfirmModal";
import { SourcePreviewPanel } from "@/widgets/source-preview/ui/SourcePreviewPanel";
import { cx } from "@/shared/lib/classNames";
import { useBackendData } from "../model/useBackendData";
import { useDocumentUpload } from "@/features/document-upload/model/useDocumentUpload";
import { useProjectTree } from "../model/useProjectTree";
import { useTreeSelection } from "../model/useTreeSelection";
import { buildGraphFromBackend } from "@/entities/graph/lib/graph";
import { reflectDocumentToWiki, subscribeConvertStarted, uploadDocumentFile } from "@/entities/document";
import { getErrorMessage } from "@/shared/lib/errors";
import { buildGeneratedMarkdownFilename } from "@/features/agent-chat/lib/markdownAgent";
import type { GeneratedMarkdownDraft } from "@/features/agent-chat/lib/markdownAgent";
import type { ActiveMarkdownEditContext } from "@/features/agent-chat/lib/markdownEditContext";
import { createClientId, findTreeItemByDocumentId } from "@/entities/tree";
import { useOperationLogFeed } from "../model/useOperationLogFeed";
import { useResizeHandle } from "../model/useResizeHandle";
import { canShowAgentPanel, isAgentPanelVisible } from "../lib/workspaceLayout";
import type { DocumentItemResponse, SourceBlockHighlight } from "@/entities/document";
import type { TreeItem } from "@/entities/tree";
import type { ChatWikiExportResponse } from "@/features/wiki-export";

const SIDEBAR_DEFAULT_WIDTH = 320;
const SIDEBAR_MIN_WIDTH = 320;
const SIDEBAR_MAX_WIDTH = 460;
const SOURCE_PREVIEW_DEFAULT_WIDTH = 400;
const SOURCE_PREVIEW_MIN_WIDTH = 300;
const SOURCE_PREVIEW_MAX_FLOOR = 360;
const AGENT_PANEL_WIDTH = 360;
const AGENT_PANEL_COLLAPSED_WIDTH = 24;
const RESIZE_SAFETY_MARGIN = 120;

function findParentLabel(items: TreeItem[], itemId: string, parentLabel: string): string | null {
  for (const item of items) {
    if (item.id === itemId) return parentLabel;
    const nestedLabel = item.children?.length
      ? findParentLabel(item.children, itemId, item.label)
      : null;
    if (nestedLabel) return nestedLabel;
  }
  return null;
}

function findTreeItemInProjects(projects: ReadonlyArray<{ items: TreeItem[] }>, documentId: string): TreeItem | null {
  for (const project of projects) {
    const item = findTreeItemByDocumentId(project.items, documentId);
    if (item) return item;
  }
  return null;
}

function findFirstSelectableNote(items: TreeItem[], documentIds: Set<string>): TreeItem | null {
  for (const item of items) {
    if ((item.documentId && documentIds.has(item.documentId)) || (!item.documentId && item.graphNodeId)) return item;
    const nested = item.children?.length ? findFirstSelectableNote(item.children, documentIds) : null;
    if (nested) return nested;
  }
  return null;
}

export function HomeWorkspace() {
  const [isHomeAgentPanelOpen, setIsHomeAgentPanelOpen] = useState(true);
  const [isGraphAgentPanelOpen, setIsGraphAgentPanelOpen] = useState(false);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [markdownEditContext, setMarkdownEditContext] = useState<ActiveMarkdownEditContext | null>(null);
  const [pendingExportDocumentId, setPendingExportDocumentId] = useState<string | null>(null);
  const [pendingConvertDocumentIds, setPendingConvertDocumentIds] = useState<readonly string[]>([]);
  const [wikiActionPending, setWikiActionPending] = useState<"ingest" | "lint" | null>(null);
  const operationLogFeed = useOperationLogFeed(activeView === "logs");
  const sidebarResize = useResizeHandle(SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH, () => SIDEBAR_MAX_WIDTH);
  const sourcePreviewResize = useResizeHandle(
    SOURCE_PREVIEW_DEFAULT_WIDTH,
    SOURCE_PREVIEW_MIN_WIDTH,
    () => Math.max(
      SOURCE_PREVIEW_MAX_FLOOR,
      window.innerWidth - sidebarResize.width - (isHomeAgentPanelOpen ? AGENT_PANEL_WIDTH : AGENT_PANEL_COLLAPSED_WIDTH) - RESIZE_SAFETY_MARGIN
    )
  );
  // useProjectTree가 useBackendData보다 먼저 생성되므로 refreshBackendData를 ref로 주입한다.
  const refreshRef = useRef<() => Promise<void>>(async () => {});
  const projectTree = useProjectTree({ refreshRef });
  const {
    documents,
    setDocuments,
    wikiGraph,
    isGraphLoading,
    apiError,
    refreshBackendData
  } = useBackendData({ setProjects: projectTree.setProjects });
  const documentTitles = useMemo(
    () => new Map(documents.map((document) => [document.id, document.filename])),
    [documents]
  );
  useEffect(() => {
    refreshRef.current = refreshBackendData;
  }, [refreshBackendData]);
  const upload = useDocumentUpload({
    setProjects: projectTree.setProjects,
    setDocuments,
    setFileDropTarget: projectTree.setFileDropTarget,
    refreshBackendData
  });
  const graphData = useMemo(() => buildGraphFromBackend(documents, wikiGraph), [documents, wikiGraph]);
  const selection = useTreeSelection(projectTree.projects, graphData.nodes);
  const isHomeView = activeView === "home";
  const isGraphView = activeView === "graph";
  // 패널이 실제로 화면에 있는지. 렌더 조건·그래프 우측 여백·알림 위치가 모두 이 값을 기준으로 한다.
  const isAgentPanelShown = isAgentPanelVisible(
    activeView,
    isGraphView ? isGraphAgentPanelOpen : isHomeAgentPanelOpen
  );
  const hasSourcePreview = Boolean(selection.selectedDocumentTitle);
  // 홈에서 문서가 메인 영역을 채우는 상태(Obsidian식 최근 문서 열람)
  const isDocumentMain = isHomeView && hasSourcePreview;

  useEffect(() => {
    if (!pendingExportDocumentId) return;
    const exportedDocument = documents.find((document) => document.id === pendingExportDocumentId);
    if (!exportedDocument) return;
    const exportedTreeItem = findTreeItemInProjects(projectTree.projects, pendingExportDocumentId);
    if (!exportedTreeItem) return;
    setPendingExportDocumentId(null);
    setActiveView("home");
    selection.selectTreeGraphNode(exportedTreeItem);
  }, [documents, pendingExportDocumentId, projectTree.projects, selection]);

  // 어떤 경로로 변환을 시작했든(사이드바 메뉴·그래프/문서 화면 위키 반영) 시작 이벤트로 추적한다.
  // 병렬 변환도 각각 자동으로 열리도록 대기 목록으로 쌓는다.
  useEffect(() => subscribeConvertStarted((documentId) => {
    setPendingConvertDocumentIds((ids) => (ids.includes(documentId) ? ids : [...ids, documentId]));
  }), []);

  // PDF→Markdown 변환 완료를 폴링 결과로 감지해 변환된 markdown 문서를 홈 화면에 연다.
  // 한 실행에 하나만 열고, 나머지 완료 건은 대기 목록 변경으로 재실행될 때 처리한다.
  useEffect(() => {
    for (const pendingDocumentId of pendingConvertDocumentIds) {
      const convertedDocument = documents.find((document) => document.id === pendingDocumentId);
      if (!convertedDocument) continue;
      // 실패 시 열기는 포기한다. 실패 알림은 DocumentProcessingNotifications가 담당한다.
      if (convertedDocument.status === "failed") {
        setPendingConvertDocumentIds((ids) => ids.filter((id) => id !== pendingDocumentId));
        continue;
      }
      if (convertedDocument.status !== "completed") continue;
      const convertedTreeItem = findTreeItemInProjects(projectTree.projects, pendingDocumentId);
      if (!convertedTreeItem) continue;
      setPendingConvertDocumentIds((ids) => ids.filter((id) => id !== pendingDocumentId));
      setActiveView("home");
      selection.selectTreeGraphNode(convertedTreeItem);
      return;
    }
  }, [documents, pendingConvertDocumentIds, projectTree.projects, selection]);

  const selectedDocumentParentLabel = useMemo(() => {
    if (!selection.selectedTreeItemId) return "업로드 문서";
    for (const project of projectTree.projects) {
      const parentLabel = findParentLabel(project.items, selection.selectedTreeItemId, project.title);
      if (parentLabel) return parentLabel;
    }
    return "업로드 문서";
  }, [projectTree.projects, selection.selectedTreeItemId]);

  const selectedDocumentEditedAt = useMemo(() => {
    if (!selection.selectedDocumentId) return null;
    const document = documents.find((item) => item.id === selection.selectedDocumentId);
    return document?.processed_at ?? document?.uploaded_at ?? null;
  }, [documents, selection.selectedDocumentId]);
  const selectedDocumentRole = useMemo(() => {
    if (!selection.selectedDocumentId) return undefined;
    return documents.find((item) => item.id === selection.selectedDocumentId)?.document_role;
  }, [documents, selection.selectedDocumentId]);
  const selectedDocumentStatus = useMemo(() => {
    if (!selection.selectedDocumentId) return undefined;
    return documents.find((item) => item.id === selection.selectedDocumentId)?.status;
  }, [documents, selection.selectedDocumentId]);
  const firstSidebarNote = useMemo(() => {
    const documentIds = new Set(documents.map((document) => document.id));
    for (const project of projectTree.projects) {
      const note = findFirstSelectableNote(project.items, documentIds);
      if (note) return note;
    }
    return null;
  }, [documents, projectTree.projects]);

  // 최초 데이터 로딩 후 선택이 비어 있으면 사이드바에서 가장 위에 있는 노트를 연다.
  const didAutoOpenRef = useRef(false);
  useEffect(() => {
    if (!isHomeView || isGraphLoading || didAutoOpenRef.current || !firstSidebarNote) return;
    if (selection.selectedDocumentId || selection.selectedPreviewTarget) return;
    didAutoOpenRef.current = true;
    selection.selectTreeGraphNode(firstSidebarNote);
  }, [firstSidebarNote, isGraphLoading, isHomeView, selection]);

  function handleViewChange(view: RailView) {
    // 다른 화면에서 열어 둔 문서(홈 자동 열기 포함)의 포커스가 그래프 선택으로 이어지지 않게 한다.
    // 그래프 화면 안에서의 노드 선택(사이드바·더블클릭)은 전환 이후 동작이라 영향 없다.
    if (view === "graph") selection.clearGraphFocus();
    setActiveView(view);
  }

  function openSourceBlocks(documentId: string, title: string, highlights: SourceBlockHighlight[]) {
    const documentTitle = documents.find((document) => document.id === documentId)?.filename ?? title;
    selection.openSourceBlockPreview(documentId, documentTitle, highlights);
  }

  async function createGeneratedMarkdownDocument(draft: GeneratedMarkdownDraft) {
    const noteId = createClientId("ai-note");
    const body = draft.markdown.endsWith("\n") ? draft.markdown : `${draft.markdown}\n`;
    const file = new File(
      [`<!-- fruition-note: ${noteId} -->\n${body}`],
      buildGeneratedMarkdownFilename(draft.title),
      { type: "text/markdown" }
    );
    const created = await uploadDocumentFile(file);
    setDocuments((current) => [
      ...current.filter((document) => document.id !== created.id),
      created
    ]);
    void refreshBackendData();
    setActiveView("home");
    selection.openSourceBlockPreview(created.id, created.filename, []);
  }

  async function handleChatDocumentExported(response: ChatWikiExportResponse) {
    setPendingExportDocumentId(response.exportDocumentId);
    await refreshBackendData({ throwOnError: true });
  }

  // --- 그래프 사이드바 위키 액션 ---
  async function handleGraphIngest(targets: DocumentItemResponse[]) {
    if (wikiActionPending || targets.length === 0) return;
    setWikiActionPending("ingest");
    // 한 문서가 실패해도 나머지 문서의 요청은 계속 보낸다.
    const results = await Promise.allSettled(
      targets.map((target) => reflectDocumentToWiki(target.id, target.document_role))
    );
    // 실패 사유는 백엔드 원문을 그대로 보여준다. 개수만 알려주면 원인을 알 수 없다.
    const failures = results.flatMap((result, index) =>
      result.status === "rejected"
        ? [`${targets[index].filename}: ${getErrorMessage(result.reason, "요청에 실패했습니다.")}`]
        : []
    );
    const startedCount = targets.length - failures.length;

    if (startedCount > 0) {
      await refreshBackendData();
      publishNotice({
        kind: "completed",
        title: "위키 반영 요청",
        message: `${startedCount}개 문서 처리를 시작했습니다.`
      });
    }
    if (failures.length > 0) {
      publishNotice({
        kind: "failed",
        title: "위키 반영 실패",
        message: failures.join(" / ")
      });
    }
    setWikiActionPending(null);
  }

  async function handleGraphLint() {
    if (wikiActionPending) return;
    setWikiActionPending("lint");
    try {
      // 마지막 다듬기 이후 위키 변경이 있을 때만 실제 lint를 보낸다.
      const { needs_lint } = await fetchWikiMaintenanceStatus();
      if (!shouldRequestWikiLint(needs_lint)) {
        publishNotice({ kind: "info", ...WIKI_UP_TO_DATE_NOTICE });
        return;
      }
      const { changedPageCount } = await requestWikiLint(false);
      void refreshBackendData();
      publishNotice({
        kind: "completed",
        title: "Lint 완료",
        message: `${changedPageCount}개 페이지를 다듬었습니다.`
      });
    } catch (error: unknown) {
      publishNotice({
        kind: "failed",
        title: "Lint 실패",
        message: getErrorMessage(error, "Lint 요청에 실패했습니다.")
      });
    } finally {
      setWikiActionPending(null);
    }
  }

  function handleResizePointerMove(event: ReactPointerEvent<HTMLElement>) {
    if (sidebarResize.update(event)) return;
    sourcePreviewResize.update(event);
  }

  function handleResizePointerEnd(event: ReactPointerEvent<HTMLElement>) {
    if (sidebarResize.stop(event)) return;
    sourcePreviewResize.stop(event);
  }

  return (
    <main
      className={cx(
        "workspace",
        !isAgentPanelShown && "is-agent-collapsed",
        hasSourcePreview && "has-source-preview",
        isDocumentMain && "is-document-main"
      )}
      style={{
        "--sidebar-width": `${sidebarResize.width}px`,
        "--source-preview-width": `${sourcePreviewResize.width}px`
      } as CSSProperties}
      onClick={(event) => {
        // main 배경을 직접 클릭했을 때만 선택 해제. 자식(사이드바 여백·폴더 헤더 등)에서
        // 버블된 클릭까지 해제하면 노트 선택이 풀려 편집기가 비어 보인다.
        if (event.target === event.currentTarget) selection.clearTreeGraphSelection();
      }}
      onPointerMove={handleResizePointerMove}
      onPointerUp={handleResizePointerEnd}
      onPointerCancel={handleResizePointerEnd}
    >
      <DocumentSidebar
        projects={projectTree.projects}
        draggedItemId={projectTree.draggedItem?.itemId ?? null}
        selectedItemId={selection.selectedTreeItemId}
        dropTarget={projectTree.dropTarget}
        fileDropTarget={projectTree.fileDropTarget}
        editing={projectTree.editing}
        contextMenu={projectTree.contextMenu}
        convertContextTarget={projectTree.convertContextTarget}
        canRenameContextTarget={projectTree.canRenameContextTarget}
        uploadInputRef={upload.uploadInputRef}
        activeView={activeView}
        graphActions={{
          documents,
          pending: wikiActionPending,
          onIngestDocuments: (selected) => void handleGraphIngest(selected),
          onLint: () => void handleGraphLint()
        }}
        logEntries={{ ...operationLogFeed, documentTitles }}
        onViewChange={handleViewChange}
        onStartChat={() => {
          // 그래프 채팅은 명시적으로 시작했을 때만 연다. 다른 뷰에서는 홈 채팅으로 이동한다.
          if (isGraphView) {
            setIsGraphAgentPanelOpen(true);
            return;
          }
          if (!canShowAgentPanel(activeView)) setActiveView("home");
          setIsHomeAgentPanelOpen(true);
        }}
        onUploadToProject={(projectId) => upload.openUploadPicker(projectId, null)}
        onAddProject={projectTree.addProject}
        onResizeStart={sidebarResize.start}
        onUploadPickerChange={upload.handleUploadPickerChange}
        onMoveItem={projectTree.moveTreeEntry}
        onDropFiles={upload.dropUploadFiles}
        onDragStart={projectTree.onDragStart}
        onDragOverItem={projectTree.onDragOverItem}
        onFileDragOver={projectTree.setFileDropTarget}
        onFileDragLeave={projectTree.onFileDragLeave}
        onDragEnd={projectTree.onDragEnd}
        onContextMenuProject={projectTree.openProjectMenu}
        onContextMenuItem={projectTree.openFolderMenu}
        onSelectGraphNode={selection.selectTreeGraphNode}
        onEditingChange={projectTree.onEditingChange}
        onCommitEditing={projectTree.commitEditing}
        onCancelEditing={projectTree.cancelEditing}
        onRenameContextTarget={projectTree.renameContextTarget}
        onAddMarkdownFromContext={() => {
          const target = projectTree.takeMarkdownTargetFromContext();
          if (target) upload.createMarkdownFile(target.projectId, target.folderId);
        }}
        onConvertContextTarget={projectTree.convertContextTargetToMarkdown}
        onDeleteContextTarget={projectTree.deleteContextTarget}
      />

      {isHomeView && apiError && (
        <div className="workspace-api-error" role="alert">
          <span>{apiError}</span>
          <button type="button" onClick={() => void refreshBackendData()}>다시 시도</button>
        </div>
      )}

      {/* 홈: 최근 문서를 메인으로 여는 문서 열람 화면. 문서가 없으면 빈 화면. */}
      {isHomeView && (
        selection.selectedDocumentTitle ? (
          <SourcePreviewPanel
            title={selection.selectedDocumentTitle}
            pageId={selection.selectedPreviewTarget?.pageId ?? null}
            pageType={selection.selectedPreviewTarget?.pageType ?? null}
            documentId={selection.selectedDocumentId}
            sourceBlockHighlights={selection.selectedPreviewTarget?.sourceBlockHighlights ?? []}
            width={sourcePreviewResize.width}
            onResizeStart={sourcePreviewResize.start}
            onMarkdownEditContextChange={setMarkdownEditContext}
            onRenameDocument={projectTree.renameDocumentById}
            onRefreshDocuments={() => void refreshBackendData()}
            documentRole={selectedDocumentRole}
            documentStatus={selectedDocumentStatus}
            parentLabel={selectedDocumentParentLabel}
            editedAt={selectedDocumentEditedAt}
            isAgentPanelOpen={isHomeAgentPanelOpen}
            onOpenAgentPanel={() => setIsHomeAgentPanelOpen(true)}
            fillMain
          />
        ) : (
          <section className="blank-view" aria-label="홈 빈 화면" />
        )
      )}

      {/* 그래프: 메뉴에서 그래프를 선택했을 때만 그래프 화면을 보여준다. */}
      {isGraphView && (
        <>
          <Graph
            nodes={graphData.nodes}
            links={graphData.links}
            rawDocumentCount={documents.length}
            focusedNodeId={selection.focusedGraphNodeId}
            onOpenNodePreview={selection.openGraphNodePreview}
            onClearNodeFocus={selection.clearGraphFocus}
            loading={isGraphLoading}
            errorMessage={apiError}
          />
        </>
      )}

      {isAgentPanelShown && (
        <AgentPanel
          onClose={() => {
            if (isGraphView) setIsGraphAgentPanelOpen(false);
            else setIsHomeAgentPanelOpen(false);
          }}
          onOpenWikiPage={selection.openWikiPagePreview}
          onOpenSourceBlocks={openSourceBlocks}
          onCreateMarkdownDocument={createGeneratedMarkdownDocument}
          markdownEditContext={markdownEditContext}
          onDocumentExported={handleChatDocumentExported}
          nodes={graphData.nodes}
        />
      )}

      {!isHomeView && !isGraphView && (
        activeView === "rules" ? (
          <SchemaWorkspace />
        ) : activeView === "logs" ? (
          <LogView
            operationId={operationLogFeed.selectedOperationId}
            restoredOperationIds={operationLogFeed.restoredOperationIds}
            documentTitles={documentTitles}
            onOpenTargetDocument={(documentId, title) => {
              selection.openSourceBlockPreview(documentId, title, []);
              setActiveView("home");
            }}
            onRestoreComplete={operationLogFeed.refresh}
          />
        ) : activeView === "settings" ? (
          <SettingsPanel />
        ) : (
          <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
        )
      )}

      <DocumentProcessingNotifications documents={documents} />
      {upload.hasRejectedFiles && <UploadErrorModal onConfirm={upload.clearRejectedFiles} />}
      {projectTree.deleteConfirm && (
        <DeleteConfirmModal
          target={projectTree.deleteConfirm}
          onConfirm={projectTree.confirmDelete}
          onCancel={projectTree.cancelDelete}
        />
      )}
    </main>
  );
}
