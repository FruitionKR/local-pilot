"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import { AgentPanel } from "../agent-panel/AgentPanel";
import { DocumentSidebar } from "../document-sidebar/DocumentSidebar";
import { Graph } from "../graph/Graph";
import { HistoryPanel } from "../history/HistoryPanel";
import { useSnapshots } from "../history/useSnapshots";
import type { DocumentSnapshot } from "../history/snapshotStore";
import { SchemaWorkspace } from "../schema/SchemaWorkspace";
import { railItems, type RailView } from "../RailNavigation";
import { UploadErrorModal } from "../modals/UploadErrorModal";
import { DeleteConfirmModal } from "../modals/DeleteConfirmModal";
import { SourcePreviewPanel } from "../SourcePreviewPanel";
import { cx } from "../../_lib/classNames";
import { useBackendData } from "../../_hooks/useBackendData";
import { useDocumentUpload } from "../../_hooks/useDocumentUpload";
import { useProjectTree } from "../../_hooks/useProjectTree";
import { useTreeSelection } from "../../_hooks/useTreeSelection";
import { buildGraphFromBackend } from "../../_lib/graph";
import { uploadDocumentFile } from "../../_lib/api";
import { buildGeneratedMarkdownFilename } from "../../_lib/markdownAgent";
import type { GeneratedMarkdownDraft } from "../../_lib/markdownAgent";
import type { ActiveMarkdownEditContext } from "../../_lib/markdownEditContext";
import { createClientId } from "../../_lib/tree";
import { useResizeHandle } from "./useResizeHandle";
import type { NoteEditState, SourceBlockHighlight, TreeItem } from "../../_lib/types";

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

function findFirstSelectableNote(items: TreeItem[], documentIds: Set<string>): TreeItem | null {
  for (const item of items) {
    if ((item.documentId && documentIds.has(item.documentId)) || (!item.documentId && item.graphNodeId)) return item;
    const nested = item.children?.length ? findFirstSelectableNote(item.children, documentIds) : null;
    if (nested) return nested;
  }
  return null;
}

export function HomeWorkspace() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [markdownEditContext, setMarkdownEditContext] = useState<ActiveMarkdownEditContext | null>(null);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const snapshots = useSnapshots(markdownEditContext?.documentId ?? null);
  const [noteEditStates, setNoteEditStates] = useState<Record<string, NoteEditState>>({});
  const [lintRequest, setLintRequest] = useState<{
    id: number;
    message: string;
    context: ActiveMarkdownEditContext;
  } | null>(null);
  const lintRequestIdRef = useRef(0);
  const sidebarResize = useResizeHandle(SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH, () => SIDEBAR_MAX_WIDTH);
  const sourcePreviewResize = useResizeHandle(
    SOURCE_PREVIEW_DEFAULT_WIDTH,
    SOURCE_PREVIEW_MIN_WIDTH,
    () => Math.max(
      SOURCE_PREVIEW_MAX_FLOOR,
      window.innerWidth - sidebarResize.width - (isAgentPanelOpen ? AGENT_PANEL_WIDTH : AGENT_PANEL_COLLAPSED_WIDTH) - RESIZE_SAFETY_MARGIN
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
  const hasSourcePreview = Boolean(selection.selectedDocumentTitle);
  // 홈에서 문서가 메인 영역을 채우는 상태(Obsidian식 최근 문서 열람)
  const isDocumentMain = isHomeView && hasSourcePreview;

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

  // AgentPanel에 넘길 편집 컨텍스트를 감싸, AI 편집이 적용되기 직전 원본을 스냅샷으로 남긴다.
  const agentEditContext = useMemo<ActiveMarkdownEditContext | null>(() => {
    if (!markdownEditContext) return null;
    const raw = markdownEditContext;
    return {
      ...raw,
      applyMarkdown: (expectedMarkdown: string, nextMarkdown: string) => {
        const applied = raw.applyMarkdown(expectedMarkdown, nextMarkdown);
        if (applied) snapshots.capture(expectedMarkdown, "AI 편집 전");
        return applied;
      }
    };
  }, [markdownEditContext, snapshots]);

  // 스냅샷 시점으로 롤백. 롤백 직전 현재 본문도 스냅샷으로 남기고 raw applyMarkdown으로 복원한다.
  const handleRestoreSnapshot = useCallback((snapshot: DocumentSnapshot) => {
    if (!markdownEditContext) return;
    const current = markdownEditContext.editorSnapshot.markdown;
    if (current === snapshot.markdown) return;
    snapshots.capture(current, "롤백 전");
    markdownEditContext.applyMarkdown(current, snapshot.markdown);
  }, [markdownEditContext, snapshots]);

  function handleViewChange(view: RailView) {
    setActiveView(view);
    if (view === "home" && firstSidebarNote) selection.selectTreeGraphNode(firstSidebarNote);
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

  function requestDocumentLint(context: ActiveMarkdownEditContext) {
    lintRequestIdRef.current += 1;
    setLintRequest({
      id: lintRequestIdRef.current,
      message: "문서 전체의 문법, 문장 흐름, Markdown 구조를 점검하고 필요한 부분만 교정해줘.",
      context
    });
    setIsAgentPanelOpen(true);
  }

  const handleNoteEditStateChange = useCallback((documentId: string, state: NoteEditState | null) => {
    setNoteEditStates((current) => {
      if (state) {
        const previous = current[documentId];
        if (previous?.saveStatus === state.saveStatus && previous.needsReview === state.needsReview) return current;
        return { ...current, [documentId]: state };
      }
      if (!current[documentId]) return current;
      const next = { ...current };
      delete next[documentId];
      return next;
    });
  }, []);

  async function handleChatDocumentExported() {
    await refreshBackendData();
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
        isGraphView && "is-graph-view",
        (isHomeView || isGraphView) && !isAgentPanelOpen && "is-agent-collapsed",
        hasSourcePreview && "has-source-preview",
        isDocumentMain && "is-document-main"
      )}
      style={{
        "--sidebar-width": `${sidebarResize.width}px`,
        "--source-preview-width": `${sourcePreviewResize.width}px`
      } as CSSProperties}
      onClick={selection.clearTreeGraphSelection}
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
        uploadInputRef={upload.uploadInputRef}
        activeView={activeView}
        noteEditStates={noteEditStates}
        onViewChange={handleViewChange}
        onStartChat={() => setIsAgentPanelOpen(true)}
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
        onDeleteContextTarget={projectTree.deleteContextTarget}
      />

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
            onRequestLint={requestDocumentLint}
            onRenameDocument={projectTree.renameDocumentById}
            onNoteEditStateChange={handleNoteEditStateChange}
            parentLabel={selectedDocumentParentLabel}
            editedAt={selectedDocumentEditedAt}
            onExitDocument={selection.clearTreeGraphSelection}
            isAgentPanelOpen={isAgentPanelOpen}
            onOpenAgentPanel={() => setIsAgentPanelOpen(true)}
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
            onRestoreAgentPanel={!isAgentPanelOpen ? () => setIsAgentPanelOpen(true) : undefined}
            loading={isGraphLoading}
            errorMessage={apiError}
          />
        </>
      )}

      {(isHomeView || isGraphView) && isAgentPanelOpen && (
        <AgentPanel
          onClose={() => setIsAgentPanelOpen(false)}
          onOpenWikiPage={selection.openWikiPagePreview}
          onOpenSourceBlocks={openSourceBlocks}
          onCreateMarkdownDocument={createGeneratedMarkdownDocument}
          markdownEditContext={agentEditContext}
          lintRequest={lintRequest}
          onDocumentExported={handleChatDocumentExported}
          nodes={graphData.nodes}
        />
      )}

      {!isHomeView && !isGraphView && (
        activeView === "rules" ? (
          <SchemaWorkspace />
        ) : (
          <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
        )
      )}

      {isDocumentMain && (
        <button type="button" className="history-trigger" onClick={() => setIsHistoryOpen((open) => !open)}>
          변경 기록
        </button>
      )}
      {isDocumentMain && isHistoryOpen && markdownEditContext && (
        <HistoryPanel
          snapshots={snapshots.snapshots}
          currentMarkdown={markdownEditContext.editorSnapshot.markdown}
          onRestore={handleRestoreSnapshot}
          onClose={() => setIsHistoryOpen(false)}
        />
      )}

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
