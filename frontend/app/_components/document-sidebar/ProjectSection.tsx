import type { MouseEvent as ReactMouseEvent } from "react";
import { useState } from "react";
import { cx } from "../../_lib/classNames";
import type { Project } from "../../_lib/types";
import { arrowIcon, SvgIcon } from "../SvgIcon";
import { InlineEditInput } from "./InlineEditInput";
import { SidebarTree } from "./SidebarTree";
import { useFileDropZone } from "./useFileDropZone";
import type { TreeInteractionProps } from "./types";

export function ProjectSection({
  project,
  isPrimary,
  useFullSidebarDropZone,
  onUploadToProject,
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
  /** 첫 번째 프로젝트는 시안처럼 밝은 pill 헤더 + 업로드 버튼을 표시한다 */
  isPrimary?: boolean;
  useFullSidebarDropZone?: boolean;
  onUploadToProject: (projectId: string) => void;
  onContextMenuProject: (event: ReactMouseEvent<HTMLElement>, projectId: string) => void;
} & TreeInteractionProps) {
  const [isOpen, setIsOpen] = useState(true);
  const isRootFileDropTarget = fileDropTarget?.projectId === project.id && fileDropTarget.folderId === null;
  const isProjectEditing = editing?.projectId === project.id && editing.itemId === null;
  const { handleDragOver, handleDragLeave, handleDrop } = useFileDropZone({
    projectId: project.id,
    folderId: null,
    onFileDragOver,
    onFileDragLeave,
    onDropFiles
  });

  return (
    <section
      className={cx(
        "project-section",
        isPrimary && "is-primary",
        !useFullSidebarDropZone && isRootFileDropTarget && "is-file-drop-target"
      )}
      onContextMenu={(event) => onContextMenuProject(event, project.id)}
      onDragOver={useFullSidebarDropZone ? undefined : handleDragOver}
      onDragLeave={useFullSidebarDropZone ? undefined : handleDragLeave}
      onDrop={useFullSidebarDropZone ? undefined : handleDrop}
    >
      <div className="project-title">
        <button
          type="button"
          className="project-toggle"
          aria-expanded={isOpen}
          onClick={() => setIsOpen((open) => !open)}
        >
          {isProjectEditing ? (
            <InlineEditInput
              value={editing.label}
              onChange={onEditingChange}
              onCommit={onCommitEditing}
              onCancel={onCancelEditing}
            />
          ) : (
            <>
              <SvgIcon src={arrowIcon} className={`project-arrow ${isOpen ? "is-open" : ""}`} />
              <span>{project.title}</span>
            </>
          )}
        </button>
        {isPrimary && (
          <button
            type="button"
            className="project-add-file"
            aria-label="파일 업로드"
            onClick={(event) => {
              event.stopPropagation();
              onUploadToProject(project.id);
            }}
          >
            +
          </button>
        )}
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
