import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent } from "react";
import { useState } from "react";
import type { Project } from "../../_lib/types";
import { getDroppedFiles, hasDroppedFiles } from "../../_lib/tree";
import { arrowIcon, SvgIcon } from "../SvgIcon";
import { SidebarTree } from "./SidebarTree";
import type { TreeInteractionProps } from "./types";

export function ProjectSection({
  project,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
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
  onCancelEditing
}: {
  project: Project;
  onContextMenuProject: (event: ReactMouseEvent<HTMLElement>, projectId: string) => void;
} & TreeInteractionProps) {
  const [isOpen, setIsOpen] = useState(true);
  const isRootFileDropTarget = fileDropTarget?.projectId === project.id && fileDropTarget.folderId === null;
  const isProjectEditing = editing?.projectId === project.id && editing.itemId === null;

  function handleEditingKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") onCommitEditing();
    if (event.key === "Escape") onCancelEditing();
  }

  return (
    <section
      className={`project-section ${isRootFileDropTarget ? "is-file-drop-target" : ""}`}
      onContextMenu={(event) => onContextMenuProject(event, project.id)}
      onDragOver={(event) => {
        if (!hasDroppedFiles(event)) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
        onFileDragOver({ projectId: project.id, folderId: null });
      }}
      onDragLeave={(event) => {
        if (!hasDroppedFiles(event)) return;
        const nextTarget = event.relatedTarget;
        if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
        onFileDragLeave();
      }}
      onDrop={(event) => {
        if (!hasDroppedFiles(event)) return;
        event.preventDefault();
        onFileDragLeave();
        onDropFiles(project.id, null, getDroppedFiles(event));
      }}
    >
      <div className="project-title">
        <button
          type="button"
          className="project-toggle"
          aria-expanded={isOpen}
          onClick={() => setIsOpen((open) => !open)}
        >
          {isProjectEditing ? (
            <input
              className="tree-edit-input"
              value={editing.label}
              autoFocus
              onChange={(event) => onEditingChange(event.target.value)}
              onBlur={onCommitEditing}
              onClick={(event) => event.stopPropagation()}
              onKeyDown={handleEditingKeyDown}
            />
          ) : (
            <>
              <span>{project.title}</span>
              <SvgIcon src={arrowIcon} className={`project-arrow ${isOpen ? "is-open" : ""}`} />
            </>
          )}
        </button>
      </div>
      {isOpen && (
        project.items.length > 0
          ? (
            <SidebarTree
              items={project.items}
              projectId={project.id}
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
              onContextMenuItem={onContextMenuItem}
              onSelectGraphNode={onSelectGraphNode}
              onEditingChange={onEditingChange}
              onCommitEditing={onCommitEditing}
              onCancelEditing={onCancelEditing}
            />
          )
          : <p className="project-empty">폴더가 없습니다.</p>
      )}
    </section>
  );
}
