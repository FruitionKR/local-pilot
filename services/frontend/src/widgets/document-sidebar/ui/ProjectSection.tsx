import type { DragEvent as ReactDragEvent, MouseEvent as ReactMouseEvent } from "react";
import { useState } from "react";
import { cx } from "@/shared/lib/classNames";
import { hasDroppedFiles } from "@/entities/tree";
import type { Project } from "@/entities/tree";
import { arrowIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { InlineEditInput } from "./InlineEditInput";
import { SidebarTree } from "./SidebarTree";
import { useFileDropZone } from "../lib/useFileDropZone";
import type { TreeInteractionProps } from "../model/types";
import styles from "./DocumentSidebar.module.css";

export function ProjectSection({
  project,
  isPrimary,
  useFullSidebarDropZone,
  onUploadToProject,
  onContextMenuProject,
  interaction
}: {
  project: Project;
  /** 첫 번째 프로젝트는 시안처럼 밝은 pill 헤더 + 업로드 버튼을 표시한다 */
  isPrimary?: boolean;
  useFullSidebarDropZone?: boolean;
  onUploadToProject: (projectId: string) => void;
  onContextMenuProject: (event: ReactMouseEvent<HTMLElement>, projectId: string) => void;
  /** 트리 상호작용 상태·핸들러 묶음 */
  interaction: TreeInteractionProps;
}) {
  const [isOpen, setIsOpen] = useState(true);
  const { fileDropTarget, dropTarget, editing } = interaction;
  const isRootFileDropTarget = fileDropTarget?.projectId === project.id && fileDropTarget.folderId === null;
  const isTreeRootDropTarget = dropTarget?.projectId === project.id && dropTarget.targetId === null;
  const isProjectEditing = editing?.projectId === project.id && editing.itemId === null;
  const { handleDragOver, handleDragLeave, handleDrop } = useFileDropZone({
    projectId: project.id,
    folderId: null,
    onFileDragOver: interaction.onFileDragOver,
    onFileDragLeave: interaction.onFileDragLeave,
    onDropFiles: interaction.onDropFiles
  });

  function handleTreeDragOver(event: ReactDragEvent<HTMLDivElement>) {
    if (hasDroppedFiles(event)) return;
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = "move";
    interaction.onDragOverItem({ projectId: project.id, targetId: null, position: "inside" });
  }

  function handleTreeDrop(event: ReactDragEvent<HTMLDivElement>) {
    if (hasDroppedFiles(event) || !interaction.draggedItemId) return;
    event.preventDefault();
    event.stopPropagation();
    interaction.onMoveItem({ projectId: project.id, targetId: null, position: "inside" });
  }

  return (
    <section
      className={cx(
        styles["project-section"],
        isPrimary && styles["is-primary"],
        !useFullSidebarDropZone && isRootFileDropTarget && styles["is-file-drop-target"]
      )}
      onContextMenu={(event) => onContextMenuProject(event, project.id)}
      onDragOver={useFullSidebarDropZone ? undefined : handleDragOver}
      onDragLeave={useFullSidebarDropZone ? undefined : handleDragLeave}
      onDrop={useFullSidebarDropZone ? undefined : handleDrop}
    >
      <div
        className={cx(styles["project-title"], isTreeRootDropTarget && styles["is-tree-drop-target"])}
        onDragOver={handleTreeDragOver}
        onDrop={handleTreeDrop}
      >
        <button
          type="button"
          className={styles["project-toggle"]}
          aria-expanded={isOpen}
          onClick={() => setIsOpen((open) => !open)}
        >
          {isProjectEditing ? (
            <InlineEditInput
              value={editing.label}
              onChange={interaction.onEditingChange}
              onCommit={interaction.onCommitEditing}
              onCancel={interaction.onCancelEditing}
            />
          ) : (
            <>
              <SvgIcon src={arrowIcon} className={cx(styles["project-arrow"], isOpen && styles["is-open"])} />
              <span>{project.title}</span>
            </>
          )}
        </button>
        <button
          type="button"
          className={styles["project-add-file"]}
          aria-label={`${project.title}에 파일 업로드`}
          onClick={(event) => {
            event.stopPropagation();
            onUploadToProject(project.id);
          }}
        >
          +
        </button>
      </div>
      {isOpen && (
        project.items.length > 0
          ? (
            <SidebarTree
              items={project.items}
              projectId={project.id}
              interaction={interaction}
            />
          )
          : <p className={styles["project-empty"]}>폴더가 없습니다.</p>
      )}
    </section>
  );
}
