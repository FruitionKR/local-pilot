"use client";

import { MoreHorizontal, PanelRight } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import { AgentPanel } from "../agent-panel/AgentPanel";
import { DocumentSidebar } from "../document-sidebar/DocumentSidebar";
import { Graph } from "../graph/Graph";
import { railItems, type RailView } from "../RailNavigation";
import { UploadErrorModal } from "../modals/UploadErrorModal";
import { SourcePreviewPanel } from "../SourcePreviewPanel";
import { sideboxIcon, SvgIcon } from "../SvgIcon";
import { cx } from "../../_lib/classNames";
import { useBackendData } from "../../_hooks/useBackendData";
import { useDocumentUpload } from "../../_hooks/useDocumentUpload";
import { useProjectTree } from "../../_hooks/useProjectTree";
import { useTreeSelection } from "../../_hooks/useTreeSelection";
import { buildGraphFromBackend, makeRawId } from "../../_lib/graph";
import type { ActiveMarkdownEditContext } from "../../_lib/markdownEditContext";
import { useResizeHandle } from "./useResizeHandle";
import type { SourceBlockHighlight } from "../../_lib/types";

const SIDEBAR_DEFAULT_WIDTH = 320;
const SIDEBAR_MIN_WIDTH = 320;
const SIDEBAR_MAX_WIDTH = 460;
const SOURCE_PREVIEW_DEFAULT_WIDTH = 400;
const SOURCE_PREVIEW_MIN_WIDTH = 300;
const SOURCE_PREVIEW_MAX_FLOOR = 360;
const AGENT_PANEL_WIDTH = 360;
const AGENT_PANEL_COLLAPSED_WIDTH = 24;
const RESIZE_SAFETY_MARGIN = 120;

export function HomeWorkspace() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [isDocumentSidebarOpen, setIsDocumentSidebarOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [markdownEditContext, setMarkdownEditContext] = useState<ActiveMarkdownEditContext | null>(null);
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

  // 가장 최근 업로드된 문서(Obsidian처럼 홈 진입 시 기본으로 열 대상)
  const latestDocument = useMemo(() => {
    if (documents.length === 0) return null;
    return [...documents].sort((left, right) =>
      (right.uploaded_at ?? "").localeCompare(left.uploaded_at ?? "")
    )[0];
  }, [documents]);

  // 홈 진입 후 선택이 비어 있으면 최근 문서를 자동으로 연다(최초 1회).
  const didAutoOpenRef = useRef(false);
  useEffect(() => {
    if (!isHomeView || didAutoOpenRef.current || !latestDocument) return;
    if (selection.selectedDocumentId || selection.selectedPreviewTarget) return;
    didAutoOpenRef.current = true;
    selection.selectTreeGraphNode({
      id: makeRawId(latestDocument.id),
      label: latestDocument.filename,
      documentId: latestDocument.id
    });
  }, [isHomeView, latestDocument, selection]);

  function openSourceBlocks(documentId: string, title: string, highlights: SourceBlockHighlight[]) {
    const documentTitle = documents.find((document) => document.id === documentId)?.filename ?? title;
    selection.openSourceBlockPreview(documentId, documentTitle, highlights);
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
        !isDocumentSidebarOpen && "is-sidebar-collapsed",
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
      {isDocumentSidebarOpen ? (
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
          onViewChange={setActiveView}
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
          onAddFolderFromContext={projectTree.addFolderFromContext}
          onAddMarkdownFromContext={() => {
            const target = projectTree.takeMarkdownTargetFromContext();
            if (target) upload.createMarkdownFile(target.projectId, target.folderId);
          }}
          onDeleteContextTarget={projectTree.deleteContextTarget}
        />
      ) : (
        <button
          type="button"
          className="sidebar-restore"
          aria-label="자료 관리 보이기"
          onClick={(event) => {
            event.stopPropagation();
            setIsDocumentSidebarOpen(true);
          }}
        >
          <SvgIcon src={sideboxIcon} />
        </button>
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
            fillMain
          />
        ) : (
          <section className="blank-view" aria-label="홈 빈 화면" />
        )
      )}

      {/* 그래프: 메뉴에서 그래프를 선택했을 때만 그래프 화면을 보여준다. */}
      {isGraphView && (
        <>
          <header className="graph-topbar">
            <div className="graph-topbar-actions">
              <span>마지막 편집</span>
              <button type="button" aria-label="패널 보기"><PanelRight size={14} /></button>
              <button type="button" aria-label="그래프 옵션"><MoreHorizontal size={16} /></button>
            </div>
          </header>
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
          markdownEditContext={markdownEditContext}
          nodes={graphData.nodes}
        />
      )}

      {!isHomeView && !isGraphView && (
        <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
      )}

      {upload.hasRejectedFiles && <UploadErrorModal onConfirm={upload.clearRejectedFiles} />}
    </main>
  );
}
