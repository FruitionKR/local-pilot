"use client";

import { useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import { AgentPanel } from "../agent-panel/AgentPanel";
import { DocumentSidebar } from "../document-sidebar/DocumentSidebar";
import { Graph } from "../graph/Graph";
import { RailNavigation, railItems, type RailView } from "../RailNavigation";
import { SourcePreviewPanel } from "../SourcePreviewPanel";
import { sideboxIcon, SvgIcon } from "../SvgIcon";
import { TopBar } from "../TopBar";
import { useBackendData } from "../../_hooks/useBackendData";
import { useDocumentUpload } from "../../_hooks/useDocumentUpload";
import { useProjectTree } from "../../_hooks/useProjectTree";
import { useTreeSelection } from "../../_hooks/useTreeSelection";
import { buildGraphFromBackend } from "../../_lib/graph";

export function HomeWorkspace() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [isDocumentSidebarOpen, setIsDocumentSidebarOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [sidebarWidth, setSidebarWidth] = useState(260);
  const [sourcePreviewWidth, setSourcePreviewWidth] = useState(400);
  const sidebarResizeRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null);
  const sourcePreviewResizeRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null);
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

  function startSidebarResize(event: ReactPointerEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    sidebarResizeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: sidebarWidth
    };
  }

  function startSourcePreviewResize(event: ReactPointerEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    sourcePreviewResizeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: sourcePreviewWidth
    };
  }

  function updateSourcePreviewResize(event: ReactPointerEvent<HTMLElement>) {
    const sidebarResize = sidebarResizeRef.current;
    if (sidebarResize && sidebarResize.pointerId === event.pointerId) {
      const nextWidth = Math.min(460, Math.max(220, sidebarResize.startWidth + event.clientX - sidebarResize.startX));
      setSidebarWidth(nextWidth);
      return;
    }

    const sourceResize = sourcePreviewResizeRef.current;
    if (!sourceResize || sourceResize.pointerId !== event.pointerId) return;
    const maxWidth = Math.max(360, window.innerWidth - sidebarWidth - (isAgentPanelOpen ? 430 : 24) - 120);
    const nextWidth = Math.min(maxWidth, Math.max(300, sourceResize.startWidth + event.clientX - sourceResize.startX));
    setSourcePreviewWidth(nextWidth);
  }

  function stopSourcePreviewResize(event: ReactPointerEvent<HTMLElement>) {
    const sidebarResize = sidebarResizeRef.current;
    if (sidebarResize?.pointerId === event.pointerId) {
      sidebarResizeRef.current = null;
      return;
    }

    const resize = sourcePreviewResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    sourcePreviewResizeRef.current = null;
  }

  return (
    <main
      className={[
        "workspace",
        isHomeView && !isAgentPanelOpen ? "is-agent-collapsed" : "",
        isHomeView && !isDocumentSidebarOpen ? "is-sidebar-collapsed" : "",
        hasSourcePreview ? "has-source-preview" : ""
      ].filter(Boolean).join(" ")}
      style={{
        "--sidebar-width": `${sidebarWidth}px`,
        "--source-preview-width": `${sourcePreviewWidth}px`
      } as CSSProperties}
      onClick={selection.clearTreeGraphSelection}
      onPointerMove={updateSourcePreviewResize}
      onPointerUp={stopSourcePreviewResize}
      onPointerCancel={stopSourcePreviewResize}
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
              onResizeStart={startSidebarResize}
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
              width={sourcePreviewWidth}
              onResizeStart={startSourcePreviewResize}
            />
          )}

          <Graph
            nodes={graphData.nodes}
            links={graphData.links}
            rawDocumentCount={documents.length}
            focusedNodeId={selection.focusedGraphNodeId}
            onOpenNodePreview={(node) => selection.openGraphNodePreview(node.id, node.label)}
            loading={isGraphLoading}
            errorMessage={apiError}
          />

          {!isAgentPanelOpen && (
            <button className="agent-restore" aria-label="Agent 패널 보이기" onClick={() => setIsAgentPanelOpen(true)}>
              <SvgIcon src={sideboxIcon} />
            </button>
          )}

          {isAgentPanelOpen && (
            <AgentPanel
              onClose={() => setIsAgentPanelOpen(false)}
              onOpenWikiPage={selection.openWikiPagePreview}
            />
          )}
        </>
      ) : (
        <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
      )}
    </main>
  );
}
