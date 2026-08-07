import type { MouseEvent as ReactMouseEvent, MutableRefObject } from "react";
import { useEffect, useRef, useState } from "react";
import { convertDocumentToMarkdown, deleteDocument, renameDocument } from "@/entities/document";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";
import {
  findTreeItem,
  findTreeItemByDocumentId,
  initialProjects,
  isFileItem,
  isWikiItem,
  moveProjectTreeItem,
  removeTreeItem,
  updateDocumentItemLabel,
  updateTreeItemLabel
} from "@/entities/tree";
import type { ContextMenuState, DropTarget, EditingState, FileDropTarget, Project, TreeItem } from "@/entities/tree";

const PROJECT_TREE_STORAGE_PREFIX = "fruition.project_tree.";

/** 삭제 확인 모달이 필요로 하는 대상 정보. contextMenu가 닫힌 뒤에도 삭제를 실행할 수 있도록 스냅샷한다. */
type DeleteConfirmTarget = {
  projectId: string;
  itemId: string | null;
  documentId?: string;
  label: string;
  kind: "folder" | "document";
};

function isPersistedTreeItem(value: unknown): value is TreeItem {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<TreeItem>;
  return typeof item.id === "string"
    && typeof item.label === "string"
    && (item.children === undefined || (Array.isArray(item.children) && item.children.every(isPersistedTreeItem)));
}

function isPersistedProject(value: unknown): value is Project {
  if (!value || typeof value !== "object") return false;
  const project = value as Partial<Project>;
  return typeof project.id === "string"
    && typeof project.title === "string"
    && Array.isArray(project.items)
    && project.items.every(isPersistedTreeItem);
}

function projectTreeStorageKey(): string | null {
  const workspaceId = getSelectedWorkspaceId();
  return workspaceId ? `${PROJECT_TREE_STORAGE_PREFIX}${workspaceId}` : null;
}

function loadPersistedProjects(): Project[] | null {
  const key = projectTreeStorageKey();
  if (!key) return null;
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(key) ?? "null");
    return Array.isArray(parsed) && parsed.length > 0 && parsed.every(isPersistedProject) ? parsed : null;
  } catch {
    return null;
  }
}

function persistProjects(projects: Project[]) {
  const key = projectTreeStorageKey();
  if (!key) return;
  try {
    window.localStorage.setItem(key, JSON.stringify(projects));
  } catch {
    // 저장 공간을 사용할 수 없어도 현재 세션의 트리 편집은 유지한다.
  }
}

export function useProjectTree({ refreshRef }: { refreshRef: MutableRefObject<() => Promise<void>> }) {
  const [projects, setProjects] = useState<Project[]>(initialProjects);
  const [draggedItem, setDraggedItem] = useState<{ projectId: string; itemId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<DropTarget | null>(null);
  const [fileDropTarget, setFileDropTarget] = useState<FileDropTarget | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [editing, setEditing] = useState<EditingState | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<DeleteConfirmTarget | null>(null);
  const [isPersistenceReady, setIsPersistenceReady] = useState(false);
  const editingCancelRef = useRef(false);

  useEffect(() => {
    const persisted = loadPersistedProjects();
    if (persisted) setProjects(persisted);
    setIsPersistenceReady(true);
  }, []);

  useEffect(() => {
    if (isPersistenceReady) persistProjects(projects);
  }, [isPersistenceReady, projects]);

  useEffect(() => {
    if (!contextMenu) return;

    function closeContextMenu() {
      setContextMenu(null);
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") closeContextMenu();
    }

    window.addEventListener("click", closeContextMenu);
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("click", closeContextMenu);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [contextMenu]);

  function updateProjectTitle(projectId: string, title: string) {
    setProjects((current) => current.map((project) => (
      project.id === projectId ? { ...project, title } : project
    )));
  }

  function addProject() {
    const projectId = `project-${Date.now()}`;
    setProjects((current) => [
      ...current,
      {
        id: projectId,
        title: `새 폴더 ${current.length + 1}`,
        items: []
      }
    ]);
    editingCancelRef.current = false;
    setContextMenu(null);
    setEditing({ projectId, itemId: null, label: `새 폴더 ${projects.length + 1}` });
  }

  function moveTreeEntry(target: DropTarget) {
    if (!draggedItem) {
      setDropTarget(null);
      return;
    }
    setProjects((current) => moveProjectTreeItem(
      current,
      draggedItem.projectId,
      draggedItem.itemId,
      target
    ));
    setDropTarget(null);
    setDraggedItem(null);
  }

  function openFolderMenu(event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({ projectId, itemId, x: event.clientX, y: event.clientY });
  }

  function openProjectMenu(event: ReactMouseEvent<HTMLElement>, projectId: string) {
    event.preventDefault();
    setContextMenu({ projectId, itemId: null, x: event.clientX, y: event.clientY });
  }

  function renameContextTarget() {
    if (!contextMenu) return;
    const project = projects.find((project) => project.id === contextMenu.projectId);
    if (!project) return;
    editingCancelRef.current = false;
    if (contextMenu.itemId === null) {
      setEditing({ projectId: contextMenu.projectId, itemId: null, label: project.title });
    } else {
      const item = findTreeItem(project.items, contextMenu.itemId);
      if (!item || item.generated) return;
      setEditing({ projectId: contextMenu.projectId, itemId: contextMenu.itemId, label: item.label });
    }
    setContextMenu(null);
  }

  function takeMarkdownTargetFromContext(): FileDropTarget | null {
    if (!contextMenu) return null;
    const project = projects.find((project) => project.id === contextMenu.projectId);
    const item = contextMenu.itemId && project ? findTreeItem(project.items, contextMenu.itemId) : null;
    const target = {
      projectId: contextMenu.projectId,
      folderId: item && !isFileItem(item) && !isWikiItem(item) ? item.id : null
    };
    setContextMenu(null);
    return target;
  }

  // 컨텍스트 메뉴 대상이 PDF 원본 문서일 때만 Markdown 변환 메뉴를 노출한다.
  const contextMenuProject = contextMenu ? projects.find((project) => project.id === contextMenu.projectId) : null;
  const contextMenuItem = contextMenu?.itemId && contextMenuProject
    ? findTreeItem(contextMenuProject.items, contextMenu.itemId)
    : null;
  const convertContextTarget = contextMenuItem?.documentId && contextMenuItem.mimeType === "application/pdf"
    ? {
      // 원본이 아직 처리 중이면 변환을 시작할 수 없어 비활성화한다.
      isDisabled: contextMenuItem.status === "uploading" || contextMenuItem.status === "processing"
    }
    : null;

  // Markdown 변환을 요청한다. 성공·실패 모두 서버 상태로 재동기화해
  // 새 문서가 '변환 중' 상태로 목록에 나타나게 한다.
  function convertContextTargetToMarkdown() {
    const documentId = contextMenuItem?.documentId;
    setContextMenu(null);
    if (!documentId) return;
    void convertDocumentToMarkdown(documentId)
      .then(() => refreshRef.current())
      .catch(() => refreshRef.current());
  }

  // 컨텍스트 메뉴의 삭제는 즉시 실행하지 않고 확인 모달을 연다. 실제 삭제는 confirmDelete에서 수행한다.
  function deleteContextTarget() {
    if (!contextMenu) return;
    const projectId = contextMenu.projectId;
    const project = projects.find((project) => project.id === projectId);
    if (contextMenu.itemId === null) {
      setDeleteConfirm({ projectId, itemId: null, label: project?.title ?? "폴더", kind: "folder" });
      setContextMenu(null);
      return;
    }
    const item = project ? findTreeItem(project.items, contextMenu.itemId) : null;
    const isFolder = item ? (!isFileItem(item) && !isWikiItem(item)) : false;
    setDeleteConfirm({
      projectId,
      itemId: contextMenu.itemId,
      documentId: item?.documentId,
      label: item?.label ?? "항목",
      kind: isFolder ? "folder" : "document"
    });
    setContextMenu(null);
  }

  function confirmDelete() {
    if (!deleteConfirm) return;
    const { projectId, itemId, documentId } = deleteConfirm;
    if (itemId === null) {
      setProjects((current) => current.filter((project) => project.id !== projectId));
      setDeleteConfirm(null);
      return;
    }
    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      return { ...project, items: removeTreeItem(project.items, itemId).items };
    }));
    // 실제 문서면 서버에서도 삭제한다. 성공·실패 모두 서버 상태로 재동기화한다
    // (실패 시 문서가 트리에 다시 나타난다).
    if (documentId) {
      void deleteDocument(documentId)
        .then(() => refreshRef.current())
        .catch(() => refreshRef.current());
    }
    setDeleteConfirm(null);
  }

  function cancelDelete() {
    setDeleteConfirm(null);
  }

  function commitEditing() {
    if (editingCancelRef.current) {
      editingCancelRef.current = false;
      setEditing(null);
      return;
    }
    if (!editing) return;
    const nextLabel = editing.label.trim();
    if (nextLabel) {
      if (editing.itemId === null) {
        updateProjectTitle(editing.projectId, nextLabel);
      } else {
        const itemId = editing.itemId;
        const projectId = editing.projectId;
        const project = projects.find((project) => project.id === projectId);
        const target = project ? findTreeItem(project.items, itemId) : null;
        const documentId = target?.documentId;
        const previousLabel = target?.label ?? nextLabel;
        setProjects((current) => current.map((project) => {
          if (project.id !== projectId) return project;
          return { ...project, items: updateTreeItemLabel(project.items, itemId, nextLabel) };
        }));
        // 실제 문서면 서버 표시명도 변경한다. 실패 시 이전 이름으로 원복한다.
        if (documentId) {
          void renameDocument(documentId, nextLabel)
            .then(() => refreshRef.current())
            .catch(() => {
              setProjects((current) => current.map((project) => {
                if (project.id !== projectId) return project;
                return { ...project, items: updateTreeItemLabel(project.items, itemId, previousLabel) };
              }));
            });
        }
      }
    }
    setEditing(null);
  }

  function cancelEditing() {
    editingCancelRef.current = true;
    setEditing(null);
  }

  async function renameDocumentById(documentId: string, nextLabel: string) {
    const previousLabel = projects
      .map((project) => findTreeItemByDocumentId(project.items, documentId)?.label)
      .find((label): label is string => Boolean(label));
    setProjects((current) => current.map((project) => ({
      ...project,
      items: updateDocumentItemLabel(project.items, documentId, nextLabel)
    })));
    try {
      await renameDocument(documentId, nextLabel);
      await refreshRef.current();
    } catch (error) {
      if (previousLabel) {
        setProjects((current) => current.map((project) => ({
          ...project,
          items: updateDocumentItemLabel(project.items, documentId, previousLabel)
        })));
      }
      throw error;
    }
  }

  function onDragStart(projectId: string, itemId: string) {
    setDraggedItem({ projectId, itemId });
    setContextMenu(null);
  }

  function onDragOverItem(target: DropTarget) {
    if (draggedItem) setDropTarget(target);
  }

  function onFileDragLeave() {
    setFileDropTarget(null);
  }

  function onDragEnd() {
    setDraggedItem(null);
    setDropTarget(null);
    setFileDropTarget(null);
  }

  function onEditingChange(label: string) {
    setEditing((current) => current ? { ...current, label } : current);
  }

  return {
    projects,
    setProjects,
    draggedItem,
    dropTarget,
    fileDropTarget,
    contextMenu,
    editing,
    deleteConfirm,
    setFileDropTarget,
    addProject,
    moveTreeEntry,
    openFolderMenu,
    openProjectMenu,
    renameContextTarget,
    takeMarkdownTargetFromContext,
    convertContextTarget,
    convertContextTargetToMarkdown,
    deleteContextTarget,
    confirmDelete,
    cancelDelete,
    commitEditing,
    cancelEditing,
    renameDocumentById,
    onDragStart,
    onDragOverItem,
    onFileDragLeave,
    onDragEnd,
    onEditingChange
  };
}
