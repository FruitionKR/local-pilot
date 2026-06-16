import type { ChangeEvent as ReactChangeEvent, MouseEvent as ReactMouseEvent, RefObject } from "react";
import type { ContextMenuState, DropTarget, EditingState, FileDropTarget, Project } from "../../_lib/types";
import { switchIcon, SvgIcon } from "../SvgIcon";
import { ContextMenu } from "./ContextMenu";
import { ProjectSection } from "./ProjectSection";

export function DocumentSidebar({
  projects,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  contextMenu,
  uploadInputRef,
  onOpenUploadPicker,
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
  onOpenUploadPicker: (projectId: string, folderId: string | null) => void;
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
  onSelectGraphNode: (nodeId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
  onRenameContextTarget: () => void;
  onAddFolderFromContext: () => void;
  onDeleteContextTarget: () => void;
}) {
  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h1>자료 관리</h1>
        <button
          type="button"
          className="sidebar-upload-button"
          aria-label="문서 업로드"
          onClick={(event) => {
            event.stopPropagation();
            if (projects[0]) onOpenUploadPicker(projects[0].id, null);
          }}
        >
          <SvgIcon src={switchIcon} className="sidebar-upload-icon" />
        </button>
      </div>
      <input
        ref={uploadInputRef}
        className="upload-picker"
        type="file"
        accept=".pdf,.md,application/pdf,text/markdown,text/plain"
        multiple
        onChange={onUploadPickerChange}
      />

      {projects.map((project) => (
        <ProjectSection
          key={project.id}
          project={project}
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
    </aside>
  );
}
