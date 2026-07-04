"use client";

import { useMemo, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import { AgentPanel } from "../agent-panel/AgentPanel";
import { DocumentSidebar } from "../document-sidebar/DocumentSidebar";
import { Graph } from "../graph/Graph";
import { RailNavigation, railItems, type RailView } from "../RailNavigation";
import { SourcePreviewPanel } from "../SourcePreviewPanel";
import { sideboxIcon, SvgIcon } from "../SvgIcon";
import { TopBar } from "../TopBar";
import { cx } from "../../_lib/classNames";
import { useBackendData } from "../../_hooks/useBackendData";
import { useDocumentUpload } from "../../_hooks/useDocumentUpload";
import { useProjectTree } from "../../_hooks/useProjectTree";
import { useTreeSelection } from "../../_hooks/useTreeSelection";
import { buildGraphFromBackend } from "../../_lib/graph";
import { useResizeHandle } from "./useResizeHandle";
import type { SourceBlockHighlight } from "../../_lib/types";

const SIDEBAR_DEFAULT_WIDTH = 260;
const SIDEBAR_MIN_WIDTH = 220;
const SIDEBAR_MAX_WIDTH = 460;
const SOURCE_PREVIEW_DEFAULT_WIDTH = 400;
const SOURCE_PREVIEW_MIN_WIDTH = 300;
const SOURCE_PREVIEW_MAX_FLOOR = 360;
const AGENT_PANEL_WIDTH = 430;
const AGENT_PANEL_COLLAPSED_WIDTH = 24;
const RESIZE_SAFETY_MARGIN = 120;

export function HomeWorkspace() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [isDocumentSidebarOpen, setIsDocumentSidebarOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const sidebarResize = useResizeHandle(SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH, () => SIDEBAR_MAX_WIDTH);
  const sourcePreviewResize = useResizeHandle(
    SOURCE_PREVIEW_DEFAULT_WIDTH,
    SOURCE_PREVIEW_MIN_WIDTH,
    () => Math.max(
      SOURCE_PREVIEW_MAX_FLOOR,
      window.innerWidth - sidebarResize.width - (isAgentPanelOpen ? AGENT_PANEL_WIDTH : AGENT_PANEL_COLLAPSED_WIDTH) - RESIZE_SAFETY_MARGIN
    )
  );
  const projectTree = useProjectTree();
  const {
    documents,
    setDocuments,
    wikiGraph,
    isGraphLoading,
    apiError,
    refreshBackendData
  } = useBackendData({ setProjects: projectTree.setProjects });
  const upload = useDocumentUpload({
    setProjects: projectTree.setProjects,
    setDocuments,
    setFileDropTarget: projectTree.setFileDropTarget,
    refreshBackendData
  });
  const selection = useTreeSelection(projectTree.projects);
  const isHomeView = activeView === "home";
  const graphData = useMemo(() => buildGraphFromBackend(documents, wikiGraph), [documents, wikiGraph]);
  const hasSourcePreview = Boolean(selection.selectedDocumentTitle);

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
        isHomeView && !isAgentPanelOpen && "is-agent-collapsed",
        isHomeView && !isDocumentSidebarOpen && "is-sidebar-collapsed",
        hasSourcePreview && "has-source-preview"
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
      <TopBar />
      <RailNavigation activeView={activeView} onViewChange={setActiveView} />

      {isHomeView ? (
        <>
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

          {selection.selectedDocumentTitle && (
            <SourcePreviewPanel
              title={selection.selectedDocumentTitle}
              pageId={selection.selectedPreviewTarget?.pageId ?? null}
              pageType={selection.selectedPreviewTarget?.pageType ?? null}
              documentId={selection.selectedDocumentId}
              sourceBlockHighlights={selection.selectedPreviewTarget?.sourceBlockHighlights ?? []}
              width={sourcePreviewResize.width}
              onResizeStart={sourcePreviewResize.start}
            />
          )}

          <Graph
            nodes={graphData.nodes}
            links={graphData.links}
            rawDocumentCount={documents.length}
            focusedNodeId={selection.focusedGraphNodeId}
            onOpenNodePreview={(node) => selection.openGraphNodePreview(node.id, node.label)}
            onRestoreAgentPanel={!isAgentPanelOpen ? () => setIsAgentPanelOpen(true) : undefined}
            loading={isGraphLoading}
            errorMessage={apiError}
          />

          {isAgentPanelOpen && (
            <AgentPanel
              onClose={() => setIsAgentPanelOpen(false)}
              onOpenWikiPage={selection.openWikiPagePreview}
              onOpenSourceBlocks={openSourceBlocks}
              nodes={graphData.nodes}
            />
          )}
        </>
      ) : (
        <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
      )}
    </main>
  );
}
