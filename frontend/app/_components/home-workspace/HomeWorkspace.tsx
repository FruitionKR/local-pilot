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
  const [activeView, setActiveView] = useState<RailView>("home");
  const [sourcePreviewWidth, setSourcePreviewWidth] = useState(400);
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
    const resize = sourcePreviewResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    const nextWidth = Math.min(640, Math.max(300, resize.startWidth + event.clientX - resize.startX));
    setSourcePreviewWidth(nextWidth);
  }

  function stopSourcePreviewResize(event: ReactPointerEvent<HTMLElement>) {
    const resize = sourcePreviewResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    sourcePreviewResizeRef.current = null;
  }

  return (
    <main
      className={`workspace ${isHomeView && !isAgentPanelOpen ? "is-agent-collapsed" : ""} ${hasSourcePreview ? "has-source-preview" : ""}`}
      style={{ "--source-preview-width": `${sourcePreviewWidth}px` } as CSSProperties}
      onClick={selection.clearTreeGraphSelection}
      onPointerMove={updateSourcePreviewResize}
      onPointerUp={stopSourcePreviewResize}
      onPointerCancel={stopSourcePreviewResize}
    >
      <TopBar />
      <RailNavigation activeView={activeView} onViewChange={setActiveView} />

      {isHomeView ? (
        <>
          <DocumentSidebar
            projects={projectTree.projects}
            draggedItemId={projectTree.draggedItem?.itemId ?? null}
            selectedItemId={selection.selectedTreeItemId}
            dropTarget={projectTree.dropTarget}
            fileDropTarget={projectTree.fileDropTarget}
            editing={projectTree.editing}
            contextMenu={projectTree.contextMenu}
            uploadInputRef={upload.uploadInputRef}
            onOpenUploadPicker={upload.openUploadPicker}
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
            onSelectGraphNode={(nodeId, itemId) => selection.selectTreeGraphNode(itemId, nodeId)}
            onEditingChange={projectTree.onEditingChange}
            onCommitEditing={projectTree.commitEditing}
            onCancelEditing={projectTree.cancelEditing}
            onRenameContextTarget={projectTree.renameContextTarget}
            onAddFolderFromContext={projectTree.addFolderFromContext}
            onDeleteContextTarget={projectTree.deleteContextTarget}
          />

          {selection.selectedDocumentTitle && (
            <SourcePreviewPanel
              title={selection.selectedDocumentTitle}
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

          {isAgentPanelOpen && <AgentPanel onClose={() => setIsAgentPanelOpen(false)} />}
        </>
      ) : (
        <section className="blank-view" aria-label={`${railItems.find((item) => item.id === activeView)?.label ?? ""} 빈 화면`} />
      )}
    </main>
  );
}
