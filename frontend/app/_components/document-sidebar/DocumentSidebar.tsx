import type { ChangeEvent as ReactChangeEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, RefObject } from "react";
import { cx } from "../../_lib/classNames";
import type { ContextMenuState, DropTarget, EditingState, FileDropTarget, Project } from "../../_lib/types";
import { chatBubbleIcon, SvgIcon } from "../SvgIcon";
import type { RailView } from "../RailNavigation";
import { ContextMenu } from "./ContextMenu";
import { ProjectSection } from "./ProjectSection";
import { SidebarMenuRow } from "./SidebarMenuRow";
import { SidebarProfile } from "./SidebarProfile";
import { SidebarWorkspaceHeader } from "./SidebarWorkspaceHeader";
import type { SelectableTreeItem } from "./types";
import { useFileDropZone } from "./useFileDropZone";

export function DocumentSidebar({
  projects,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  contextMenu,
  uploadInputRef,
  activeView,
  onViewChange,
  onStartChat,
  onUploadToProject,
  onAddProject,
  onResizeStart,
  onUploadPickerChange,
  onMoveItem,
  onDropFiles,
  onDragStart,
  onDragOverItem,
  onFileDragOver,
  onFileDragLeave,
  onDragEnd,
  onContextMenuProject,
  onContextMenuItem,
  onSelectGraphNode,
  onEditingChange,
  onCommitEditing,
  onCancelEditing,
  onRenameContextTarget,
  onAddFolderFromContext,
  onDeleteContextTarget
}: {
  projects: Project[];
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  contextMenu: ContextMenuState | null;
  uploadInputRef: RefObject<HTMLInputElement>;
  activeView: RailView;
  onViewChange: (view: RailView) => void;
  onStartChat: () => void;
  onUploadToProject: (projectId: string) => void;
  onAddProject: () => void;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  onUploadPickerChange: (event: ReactChangeEvent<HTMLInputElement>) => void;
  onMoveItem: (projectId: string, itemId: string, target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuProject: (event: ReactMouseEvent<HTMLElement>, projectId: string) => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onSelectGraphNode: (item: SelectableTreeItem) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
  onRenameContextTarget: () => void;
  onAddFolderFromContext: () => void;
  onDeleteContextTarget: () => void;
}) {
  const onlyProject = projects.length === 1 ? projects[0] : null;
  const isSidebarFileDropTarget = Boolean(
    onlyProject
    && fileDropTarget?.projectId === onlyProject.id
    && fileDropTarget.folderId === null
  );
  const { handleDragOver, handleDragLeave, handleDrop } = useFileDropZone({
    projectId: onlyProject?.id ?? "",
    folderId: null,
    onFileDragOver,
    onFileDragLeave,
    onDropFiles
  });

  return (
    <aside
      className={cx("sidebar", isSidebarFileDropTarget && "is-file-drop-target")}
      onDragOver={onlyProject ? handleDragOver : undefined}
      onDragLeave={onlyProject ? handleDragLeave : undefined}
      onDrop={onlyProject ? handleDrop : undefined}
    >
      <SidebarWorkspaceHeader />
      <SidebarMenuRow activeView={activeView} onViewChange={onViewChange} onAddProject={onAddProject} />
      <input
        ref={uploadInputRef}
        className="upload-picker"
        type="file"
        accept=".pdf,.md,application/pdf,text/markdown,text/plain"
        multiple
        onChange={onUploadPickerChange}
      />

      <div className="sidebar-content">
        {projects.map((project, index) => (
          <ProjectSection
            key={project.id}
            project={project}
            isPrimary={index === 0}
            useFullSidebarDropZone={Boolean(onlyProject)}
            onUploadToProject={onUploadToProject}
            draggedItemId={draggedItemId}
            selectedItemId={selectedItemId}
            dropTarget={dropTarget}
            fileDropTarget={fileDropTarget}
            editing={editing}
            onMoveItem={onMoveItem}
            onDropFiles={onDropFiles}
            onDragStart={onDragStart}
            onDragOverItem={onDragOverItem}
            onFileDragOver={onFileDragOver}
            onFileDragLeave={onFileDragLeave}
            onDragEnd={onDragEnd}
            onContextMenuProject={onContextMenuProject}
            onContextMenuItem={onContextMenuItem}
            onSelectGraphNode={onSelectGraphNode}
            onEditingChange={onEditingChange}
            onCommitEditing={onCommitEditing}
            onCancelEditing={onCancelEditing}
          />
        ))}
        {contextMenu && (
          <ContextMenu
            contextMenu={contextMenu}
            onRenameContextTarget={onRenameContextTarget}
            onAddFolderFromContext={onAddFolderFromContext}
            onDeleteContextTarget={onDeleteContextTarget}
          />
        )}
      </div>
      <button
        type="button"
        className="sidebar-chat-start"
        onClick={(event) => {
          event.stopPropagation();
          onStartChat();
        }}
      >
        <SvgIcon src={chatBubbleIcon} />
        채팅 시작
      </button>
      <SidebarProfile />
      <button
        type="button"
        className="sidebar-resize-handle"
        aria-label="자료 관리 사이드바 폭 조절"
        onPointerDown={onResizeStart}
      />
    </aside>
  );
}
