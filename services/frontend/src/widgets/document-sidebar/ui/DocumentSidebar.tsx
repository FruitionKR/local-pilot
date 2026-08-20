import { useState, type ChangeEvent as ReactChangeEvent, type ComponentProps, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent, type RefObject } from "react";
import { cx } from "@/shared/lib/classNames";
import type { ContextMenuState, DropTarget, EditingState, FileDropTarget, Project } from "@/entities/tree";
import { chatBubbleIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import type { RailView } from "@/widgets/rail-navigation/ui/RailNavigation";
import { ContextMenu } from "./ContextMenu";
import { GraphSidebarActions } from "./GraphSidebarActions";
import { LogSidebarEntries } from "./LogSidebarEntries";
import { ProjectSection } from "./ProjectSection";
import { SidebarMenuRow } from "./SidebarMenuRow";
import { SidebarProfile } from "./SidebarProfile";
import { SidebarWorkspaceHeader } from "./SidebarWorkspaceHeader";
import { DocumentSearch } from "@/features/document-search/ui/DocumentSearch";
import type { SelectableTreeItem } from "../model/types";
import { canCreateProjectFromView } from "../model/sidebarMenu";
import { useFileDropZone } from "../lib/useFileDropZone";
import styles from "./DocumentSidebar.module.css";

export function DocumentSidebar({
  projects,
  draggedItemId,
  selectedItemId,
  dropTarget,
  fileDropTarget,
  editing,
  contextMenu,
  convertContextTarget,
  canRenameContextTarget,
  uploadInputRef,
  activeView,
  graphActions,
  logEntries,
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
  onAddMarkdownFromContext,
  onConvertContextTarget,
  onDeleteContextTarget
}: {
  projects: Project[];
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  contextMenu: ContextMenuState | null;
  convertContextTarget: { isDisabled: boolean } | null;
  /** PDF 원본은 편집 불가 문서라 이름 변경 메뉴를 숨긴다. */
  canRenameContextTarget: boolean;
  uploadInputRef: RefObject<HTMLInputElement | null>;
  activeView: RailView;
  /** 그래프 뷰에서 문서 트리 대신 보여줄 위키 액션. */
  graphActions?: ComponentProps<typeof GraphSidebarActions>;
  /** 로그 뷰에서 문서 트리 대신 보여줄 최신순 작업 목록. */
  logEntries?: ComponentProps<typeof LogSidebarEntries>;
  onViewChange: (view: RailView) => void;
  onStartChat: () => void;
  onUploadToProject: (projectId: string) => void;
  onAddProject: () => void;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  onUploadPickerChange: (event: ReactChangeEvent<HTMLInputElement>) => void;
  onMoveItem: (target: DropTarget) => void;
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
  onAddMarkdownFromContext: () => void;
  onConvertContextTarget: () => void;
  onDeleteContextTarget: () => void;
}) {
  const [isSearchOpen, setIsSearchOpen] = useState(false);
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
      className={cx(styles.sidebar, isSidebarFileDropTarget && styles["is-file-drop-target"])}
      onDragOver={onlyProject ? handleDragOver : undefined}
      onDragLeave={onlyProject ? handleDragLeave : undefined}
      onDrop={onlyProject ? handleDrop : undefined}
    >
      <SidebarWorkspaceHeader />
      <SidebarMenuRow
        activeView={activeView}
        isSearchOpen={isSearchOpen}
        onViewChange={onViewChange}
        onToggleSearch={() => setIsSearchOpen((open) => !open)}
        onAddProject={onAddProject}
      />
      {isSearchOpen && (
        <DocumentSearch
          projects={projects}
          onSelectGraphNode={onSelectGraphNode}
          onClose={() => setIsSearchOpen(false)}
        />
      )}
      <input
        ref={uploadInputRef}
        className={styles["upload-picker"]}
        type="file"
        accept=".pdf,.md,application/pdf,text/markdown,text/plain"
        multiple
        onChange={onUploadPickerChange}
      />

      <div className={styles["sidebar-content"]}>
        {activeView === "graph" && graphActions ? (
          <GraphSidebarActions {...graphActions} />
        ) : activeView === "logs" && logEntries ? (
          <LogSidebarEntries {...logEntries} />
        ) : (
          <>
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
                canCreateProject={canCreateProjectFromView(activeView)}
                convertTarget={convertContextTarget}
                canRenameTarget={canRenameContextTarget}
                onRenameContextTarget={onRenameContextTarget}
                onAddProject={onAddProject}
                onAddMarkdownFromContext={onAddMarkdownFromContext}
                onConvertContextTarget={onConvertContextTarget}
                onDeleteContextTarget={onDeleteContextTarget}
              />
            )}
          </>
        )}
      </div>
      <button
        type="button"
        className={styles["sidebar-chat-start"]}
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
        className={styles["sidebar-resize-handle"]}
        aria-label="자료 관리 사이드바 폭 조절"
        onPointerDown={onResizeStart}
      />
    </aside>
  );
}
