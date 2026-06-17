import type { MouseEvent as ReactMouseEvent } from "react";
import { useEffect, useRef, useState } from "react";
import {
  appendFolderToFolder,
  findTreeItem,
  initialProjects,
  isFileItem,
  isWikiItem,
  mergeTreeItemsIntoFolder,
  moveTreeItem,
  removeTreeItem,
  updateTreeItemLabel
} from "../_lib/tree";
import type { ContextMenuState, DropTarget, EditingState, FileDropTarget, Project } from "../_lib/types";

export function useProjectTree() {
  const [projects, setProjects] = useState<Project[]>(initialProjects);
  const [draggedItem, setDraggedItem] = useState<{ projectId: string; itemId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<DropTarget | null>(null);
  const [fileDropTarget, setFileDropTarget] = useState<FileDropTarget | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [editing, setEditing] = useState<EditingState | null>(null);
  const editingCancelRef = useRef(false);

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
        title: `새 프로젝트 ${current.length + 1}`,
        items: []
      }
    ]);
    editingCancelRef.current = false;
    setContextMenu(null);
    setEditing({ projectId, itemId: null, label: `새 프로젝트 ${projects.length + 1}` });
  }

  function addFolder(projectId: string, folderId: string | null = null) {
    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      const parent = folderId ? findTreeItem(project.items, folderId) : null;
      const siblingCount = parent?.children?.length ?? project.items.length;
      const nextFolder = {
        id: `${project.id}-folder-${Date.now()}`,
        label: `새 폴더 ${siblingCount + 1}`,
        type: "folder" as const
      };
      return {
        ...project,
        items: appendFolderToFolder(project.items, folderId, nextFolder)
      };
    }));
  }

  function moveTreeEntry(projectId: string, itemId: string, target: DropTarget) {
    if (draggedItem?.projectId !== projectId || draggedItem.projectId !== target.projectId) {
      setDropTarget(null);
      return;
    }

    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      const dragged = findTreeItem(project.items, itemId);
      const targetItem = findTreeItem(project.items, target.targetId);
      if (target.position === "inside" && dragged && targetItem && isFileItem(dragged) && isFileItem(targetItem)) {
        return { ...project, items: mergeTreeItemsIntoFolder(project.items, itemId, target.targetId) };
      }
      const normalizedTarget = target.position === "inside" && targetItem && isFileItem(targetItem)
        ? { ...target, position: "after" as const }
        : target;
      return { ...project, items: moveTreeItem(project.items, itemId, normalizedTarget) };
    }));
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
    const project = projects.find((candidate) => candidate.id === contextMenu.projectId);
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

  function addFolderFromContext() {
    if (!contextMenu) return;
    const project = projects.find((candidate) => candidate.id === contextMenu.projectId);
    const item = contextMenu.itemId && project ? findTreeItem(project.items, contextMenu.itemId) : null;
    addFolder(contextMenu.projectId, item && !isFileItem(item) && !isWikiItem(item) ? item.id : null);
    setContextMenu(null);
  }

  function deleteContextTarget() {
    if (!contextMenu || contextMenu.itemId === null) return;
    const itemId = contextMenu.itemId;
    setProjects((current) => current.map((project) => {
      if (project.id !== contextMenu.projectId) return project;
      return { ...project, items: removeTreeItem(project.items, itemId).items };
    }));
    setContextMenu(null);
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
        setProjects((current) => current.map((project) => {
          if (project.id !== editing.projectId) return project;
          return { ...project, items: updateTreeItemLabel(project.items, itemId, nextLabel) };
        }));
      }
    }
    setEditing(null);
  }

  function cancelEditing() {
    editingCancelRef.current = true;
    setEditing(null);
  }

  return {
    projects,
    setProjects,
    draggedItem,
    dropTarget,
    fileDropTarget,
    contextMenu,
    editing,
    setFileDropTarget,
    addProject,
    moveTreeEntry,
    openFolderMenu,
    openProjectMenu,
    renameContextTarget,
    addFolderFromContext,
    deleteContextTarget,
    commitEditing,
    cancelEditing,
    onDragStart: (projectId: string, itemId: string) => {
      setDraggedItem({ projectId, itemId });
      setContextMenu(null);
    },
    onDragOverItem: (target: DropTarget) => {
      if (draggedItem?.projectId === target.projectId) setDropTarget(target);
    },
    onFileDragLeave: () => setFileDropTarget(null),
    onDragEnd: () => {
      setDraggedItem(null);
      setDropTarget(null);
      setFileDropTarget(null);
    },
    onEditingChange: (label: string) => {
      setEditing((current) => current ? { ...current, label } : current);
    }
  };
}
