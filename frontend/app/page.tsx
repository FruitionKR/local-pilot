"use client";

import type {
  ChangeEvent as ReactChangeEvent,
  DragEvent as ReactDragEvent,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent,
  RefObject
} from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AgentPanel } from "./_components/AgentPanel";
import { RailNavigation, railItems, type RailView } from "./_components/RailNavigation";
import { SourcePreviewPanel } from "./_components/SourcePreviewPanel";
import { TopBar } from "./_components/TopBar";
import {
  archiveIcon,
  arrowIcon,
  conceptPageIcon,
  fileIcon,
  lightningIcon,
  rawPageIcon,
  sideboxIcon,
  sourcePageIcon,
  SvgIcon,
  switchIcon
} from "./_components/SvgIcon";

type TreeItem = {
  id: string;
  label: string;
  type?: "folder" | "file" | "wiki";
  wikiKind?: "source" | "concept";
  generated?: boolean;
  customLabel?: boolean;
  status?: "uploading" | DocumentStatus;
  errorMessage?: string;
  documentId?: string;
  mimeType?: string;
  byteSize?: number;
  sourceUri?: string;
  uploadedAt?: string;
  graphNodeId?: string;
  active?: boolean;
  children?: TreeItem[];
};

type Project = {
  id: string;
  title: string;
  items: TreeItem[];
};

type DropPosition = "before" | "inside" | "after";

type DropTarget = {
  projectId: string;
  targetId: string;
  position: DropPosition;
};

type ContextMenuState = {
  projectId: string;
  itemId: string | null;
  x: number;
  y: number;
};

type EditingState = {
  projectId: string;
  itemId: string | null;
  label: string;
};

type FileDropTarget = {
  projectId: string;
  folderId: string | null;
};

type UploadPickerTarget = {
  projectId: string;
  folderId: string | null;
};

type DocumentStatus = "uploaded" | "processing" | "completed" | "failed";

type DocumentUploadResponse = {
  id: string;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: DocumentStatus;
  source_uri: string;
  uploaded_at: string;
};

type DocumentItemResponse = DocumentUploadResponse & {
  extracted_text_uri?: string;
  processed_at?: string;
  error_message?: string;
};

type DocumentListResponse = {
  documents: DocumentItemResponse[];
};

type WikiGraphNodeResponse = {
  id: string;
  page_type: "source" | "concept" | string;
  title: string;
  slug: string;
  summary?: string;
  status: string;
};

type WikiGraphEdgeResponse = {
  from_page_id: string;
  to_page_id: string;
  link_type: string;
  label?: string | null;
  confidence: number;
};

type WikiGraphResponse = {
  nodes: WikiGraphNodeResponse[];
  edges: WikiGraphEdgeResponse[];
};

type BackendData = {
  documents: DocumentItemResponse[];
  graph: WikiGraphResponse;
};

type GraphNode = {
  id: string;
  label: string;
  size?: number;
  kind?: "source" | "concept" | "raw" | "progress";
  loading?: boolean;
};

type GraphLink = {
  from: string;
  to: string;
  dashed?: boolean;
  active?: boolean;
};

type NodePosition = { x: number; y: number };
type NodePositionMap = Record<string, NodePosition>;
type GraphCache = {
  signature: string;
  positions: NodePositionMap;
  pan: NodePosition;
  zoom: number;
};

const GRAPH_WIDTH = 746;
const GRAPH_HEIGHT = 568;
const GRAPH_CACHE_KEY = "fruition.graph.layout.v2";
const NODE_REVEAL_INTERVAL_MS = 180;
const GRAPH_WORLD_MIN_WIDTH = 3200;
const FOCUS_TRANSITION_MS = 500;
const GRAPH_CENTER = { x: GRAPH_WIDTH / 2, y: GRAPH_HEIGHT / 2 };
const GRAPH_ZOOM = {
  min: 0.62,
  max: 2.25,
  wheelSensitivity: 0.0018
};
const GRAPH_PHYSICS = {
  centerStrength: 0.0014,
  damping: 0.42,
  settleThreshold: 0.02,
  revealCenterBoost: 1.7,
  revealLinkBoost: 2.8,
  revealDamping: 0.72,
  originStrength: 0,
  repulsionStrength: 0.0025,
  repulsionRange: 145,
  collisionRadiusMultiplier: 0.32,
  nodeDistanceMultiplier: 0.125,
  sourceNodeDistanceMultiplier: 2.5,
  linkStrength: 0.032,
  linkDistanceMultiplier: 0.25,
  linkDistance: {
    source: 178,
    raw: 92,
    progress: 132,
    sourceConcept: 88,
    concept: 98,
    fallback: 118
  }
};

const initialProjects: Project[] = [
  {
    id: "project-uploaded-documents",
    title: "업로드 문서",
    items: []
  }
];

function linkKey(nodeAId: string, nodeBId: string) {
  return nodeAId < nodeBId ? `${nodeAId}:${nodeBId}` : `${nodeBId}:${nodeAId}`;
}

function graphNodeKind(node: GraphNode) {
  return node.kind ?? "concept";
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min);
}

function itemContainsId(item: TreeItem, itemId: string): boolean {
  if (item.id === itemId) return true;
  return item.children?.some((child) => itemContainsId(child, itemId)) ?? false;
}

function isFileItem(item: TreeItem) {
  return item.type === "file";
}

function isWikiItem(item: TreeItem) {
  return item.type === "wiki";
}

function canDragTreeItem(item: TreeItem) {
  return !item.generated && !isWikiItem(item);
}

function isGeneratedGroup(item: TreeItem, groupId: string) {
  return item.generated && item.id === groupId;
}

function isSupportedUploadFile(file: File) {
  const name = file.name.toLowerCase();
  return name.endsWith(".pdf") || name.endsWith(".md");
}

function getDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return Array.from(event.dataTransfer.files).filter(isSupportedUploadFile);
}

function hasDroppedFiles(event: ReactDragEvent<HTMLElement>) {
  return event.dataTransfer.types.includes("Files");
}

function createClientId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function removeTreeItem(items: TreeItem[], itemId: string): { items: TreeItem[]; removed: TreeItem | null } {
  let removed: TreeItem | null = null;
  const nextItems: TreeItem[] = [];

  for (const item of items) {
    if (item.id === itemId) {
      removed = item;
      continue;
    }

    if (item.children?.length) {
      const result = removeTreeItem(item.children, itemId);
      if (result.removed) {
        removed = result.removed;
        nextItems.push({ ...item, children: result.items });
        continue;
      }
    }

    nextItems.push(item);
  }

  return { items: nextItems, removed };
}

function replaceTreeItem(items: TreeItem[], itemId: string, replacement: TreeItem): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return replacement;
    if (item.children?.length) return { ...item, children: replaceTreeItem(item.children, itemId, replacement) };
    return item;
  });
}

function insertTreeItem(items: TreeItem[], movedItem: TreeItem, target: DropTarget): TreeItem[] {
  return items.flatMap((item) => {
    if (item.id === target.targetId) {
      if (target.position === "before") return [movedItem, item];
      if (target.position === "after") return [item, movedItem];
      return [{ ...item, children: [...(item.children ?? []), movedItem] }];
    }

    if (item.children?.length) {
      return [{ ...item, children: insertTreeItem(item.children, movedItem, target) }];
    }

    return [item];
  });
}

function moveTreeItem(items: TreeItem[], itemId: string, target: DropTarget): TreeItem[] {
  const result = removeTreeItem(items, itemId);
  if (!result.removed) return items;
  if (result.removed.id === target.targetId || itemContainsId(result.removed, target.targetId)) return items;
  return insertTreeItem(result.items, result.removed, target);
}

function mergeTreeItemsIntoFolder(items: TreeItem[], draggedId: string, targetId: string): TreeItem[] {
  const draggedItem = findTreeItem(items, draggedId);
  const targetItem = findTreeItem(items, targetId);
  if (!draggedItem || !targetItem || !isFileItem(draggedItem) || !isFileItem(targetItem)) return items;

  const result = removeTreeItem(items, draggedId);
  if (!result.removed) return items;

  const folder: TreeItem = {
    id: createClientId("merged-folder"),
    label: "새 문서 묶음",
    type: "folder",
    children: [targetItem, result.removed]
  };

  return replaceTreeItem(result.items, targetId, folder);
}

function updateTreeItemLabel(items: TreeItem[], itemId: string, label: string): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return { ...item, label, customLabel: true };
    if (item.children?.length) return { ...item, children: updateTreeItemLabel(item.children, itemId, label) };
    return item;
  });
}

function findTreeItem(items: TreeItem[], itemId: string): TreeItem | null {
  for (const item of items) {
    if (item.id === itemId) return item;
    const found = item.children ? findTreeItem(item.children, itemId) : null;
    if (found) return found;
  }
  return null;
}

function appendItemsToFolder(items: TreeItem[], folderId: string | null, nextItems: TreeItem[]): TreeItem[] {
  if (!folderId) return [...items, ...nextItems];

  return items.map((item) => {
    if (item.id === folderId) {
      return { ...item, children: [...(item.children ?? []), ...nextItems] };
    }
    if (item.children?.length) return { ...item, children: appendItemsToFolder(item.children, folderId, nextItems) };
    return item;
  });
}

function appendFolderToFolder(items: TreeItem[], folderId: string | null, folder: TreeItem): TreeItem[] {
  return appendItemsToFolder(items, folderId, [folder]);
}

function updateTreeItemStatus(items: TreeItem[], itemId: string, status: TreeItem["status"], errorMessage?: string): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) return { ...item, status, errorMessage };
    if (item.children?.length) return { ...item, children: updateTreeItemStatus(item.children, itemId, status, errorMessage) };
    return item;
  });
}

function applyUploadedDocument(items: TreeItem[], itemId: string, document: DocumentUploadResponse): TreeItem[] {
  return items.map((item) => {
    if (item.id === itemId) {
      return {
        ...item,
        label: document.filename,
        status: document.status,
        errorMessage: undefined,
        documentId: document.id,
        graphNodeId: `raw:${document.id}`,
        mimeType: document.mime_type,
        byteSize: document.byte_size,
        sourceUri: document.source_uri,
        uploadedAt: document.uploaded_at
      };
    }
    if (item.children?.length) return { ...item, children: applyUploadedDocument(item.children, itemId, document) };
    return item;
  });
}

function removeGeneratedWikiGroups(items: TreeItem[]): TreeItem[] {
  return items
    .filter((item) => !isGeneratedGroup(item, "wiki-source-pages") && !isGeneratedGroup(item, "wiki-concept-pages"))
    .map((item) => item.children?.length ? { ...item, children: removeGeneratedWikiGroups(item.children) } : item);
}

function buildWikiTreeGroups(graph: WikiGraphResponse): TreeItem[] {
  const sourceItems = (graph.nodes ?? [])
    .filter((node) => node.page_type === "source")
    .map((node) => ({
      id: `wiki-item-${node.id}`,
      label: node.title || node.slug || node.id,
      type: "wiki" as const,
      wikiKind: "source" as const,
      graphNodeId: node.id,
      generated: true
    }));

  const conceptItems = (graph.nodes ?? [])
    .filter((node) => node.page_type === "concept")
    .map((node) => ({
      id: `wiki-item-${node.id}`,
      label: node.title || node.slug || node.id,
      type: "wiki" as const,
      wikiKind: "concept" as const,
      graphNodeId: node.id,
      generated: true
    }));

  const groups: TreeItem[] = [];
  if (sourceItems.length > 0) {
    groups.push({
      id: "wiki-source-pages",
      label: "Source 문서",
      type: "folder",
      generated: true,
      children: sourceItems
    });
  }
  if (conceptItems.length > 0) {
    groups.push({
      id: "wiki-concept-pages",
      label: "Concept 문서",
      type: "folder",
      generated: true,
      children: conceptItems
    });
  }
  return groups;
}

async function uploadDocumentFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch("/api/documents", {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    let message = "문서 업로드에 실패했습니다.";
    try {
      const body = await response.json();
      message = body?.error?.message || message;
    } catch {
      // JSON 오류 본문이 없으면 기본 메시지를 유지합니다.
    }
    throw new Error(message);
  }

  return response.json() as Promise<DocumentUploadResponse>;
}

async function fetchBackendData(): Promise<BackendData> {
  const [documentsResponse, graphResponse] = await Promise.all([
    fetch("/api/documents", { cache: "no-store" }),
    fetch("/api/wiki/graph", { cache: "no-store" })
  ]);

  if (!documentsResponse.ok) throw new Error("문서 목록을 불러오지 못했습니다.");
  if (!graphResponse.ok) throw new Error("Wiki graph를 불러오지 못했습니다.");

  const documents = await documentsResponse.json() as DocumentListResponse;
  const graph = await graphResponse.json() as WikiGraphResponse;
  return { documents: documents.documents ?? [], graph };
}

function buildGraphFromBackend(documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const backendSourceByDocumentId = new Map(
    (graph.nodes ?? [])
      .filter((node) => node.page_type === "source" && node.id.startsWith("source:"))
      .map((node) => [node.id.replace("source:", ""), node])
  );
  const rawNodes: GraphNode[] = documents.map((document) => ({
    id: `raw:${document.id}`,
    label: document.filename,
    kind: "raw" as const,
    loading: document.status === "processing" || document.status === "uploaded"
  }));
  const sourceNodes: GraphNode[] = documents.map((document) => {
    const backendSource = backendSourceByDocumentId.get(document.id);
    return {
      id: `source:${document.id}`,
      label: backendSource?.title || document.filename,
      kind: "source" as const,
      size: 32,
      loading: document.status === "processing" || document.status === "uploaded" || document.status === "failed"
    };
  });
  const conceptNodes: GraphNode[] = (graph.nodes ?? [])
    .filter((node) => node.page_type !== "source")
    .map((node) => ({
      id: node.id,
      label: node.title || node.slug || node.id,
      kind: "concept" as const
    }));

  const rawSourceLinks: GraphLink[] = documents.map((document) => ({
    from: `raw:${document.id}`,
    to: `source:${document.id}`,
    dashed: document.status !== "completed"
  }));
  const graphLinks: GraphLink[] = (graph.edges ?? []).map((edge) => ({
    from: edge.from_page_id,
    to: edge.to_page_id,
    active: edge.link_type === "source_mentions_concept"
  }));

  return {
    nodes: [...rawNodes, ...sourceNodes, ...conceptNodes],
    links: [...rawSourceLinks, ...graphLinks]
  };
}

function syncDocumentItems(items: TreeItem[], documents: DocumentItemResponse[]): TreeItem[] {
  const documentById = new Map(documents.map((document) => [document.id, document]));
  return items.map((item) => {
    const document = item.documentId ? documentById.get(item.documentId) : null;
    const nextItem = document ? {
      ...item,
      label: item.customLabel ? item.label : document.filename,
      status: document.status,
      errorMessage: document.error_message,
      mimeType: document.mime_type,
      byteSize: document.byte_size,
      sourceUri: document.source_uri,
      uploadedAt: document.uploaded_at
    } : item;
    if (nextItem.children?.length) return { ...nextItem, children: syncDocumentItems(nextItem.children, documents) };
    return nextItem;
  });
}

function collectDocumentIds(items: TreeItem[], ids = new Set<string>()) {
  for (const item of items) {
    if (item.documentId) ids.add(item.documentId);
    if (item.children?.length) collectDocumentIds(item.children, ids);
  }
  return ids;
}

function mergeBackendDataIntoProjects(projects: Project[], documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const knownDocumentIds = collectDocumentIds(projects.flatMap((project) => project.items));
  const missingDocuments = documents.filter((document) => !knownDocumentIds.has(document.id));
  const backendItems = missingDocuments.map((document) => ({
    id: `document-file-${document.id}`,
    label: document.filename,
    type: "file" as const,
    status: document.status,
    documentId: document.id,
    graphNodeId: `raw:${document.id}`,
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at,
    errorMessage: document.error_message
  }));
  const wikiGroups = buildWikiTreeGroups(graph);

  return projects.map((project, index) => {
    const syncedItems = syncDocumentItems(removeGeneratedWikiGroups(project.items), documents);
    if (index !== 0) return { ...project, items: syncedItems };
    return { ...project, items: [...syncedItems, ...backendItems, ...wikiGroups] };
  });
}

function resolveDropPosition(event: ReactDragEvent<HTMLButtonElement>): DropPosition {
  const rect = event.currentTarget.getBoundingClientRect();
  const offsetY = event.clientY - rect.top;
  if (offsetY < rect.height * 0.28) return "before";
  if (offsetY > rect.height * 0.72) return "after";
  return "inside";
}

function setLightDragPreview(event: ReactDragEvent<HTMLButtonElement>) {
  const source = event.currentTarget;
  const rect = source.getBoundingClientRect();
  const preview = source.cloneNode(true) as HTMLElement;

  preview.style.position = "fixed";
  preview.style.top = "-1000px";
  preview.style.left = "-1000px";
  preview.style.width = `${rect.width}px`;
  preview.style.height = `${rect.height}px`;
  preview.style.opacity = "0.06";
  preview.style.background = "rgba(255, 255, 255, 0.24)";
  preview.style.border = "1px solid rgba(207, 215, 227, 0.18)";
  preview.style.boxShadow = "none";
  preview.style.pointerEvents = "none";
  preview.style.zIndex = "-1";
  document.body.appendChild(preview);

  event.dataTransfer.setDragImage(preview, 14, Math.min(18, rect.height / 2));
  window.setTimeout(() => preview.remove(), 0);
}

function TreeNode({ item, depth, openIds, onToggle, projectId, draggedItemId, selectedItemId, dropTarget, fileDropTarget, editing, onDragStart, onDragOverItem, onFileDragOver, onFileDragLeave, onDropItem, onDropFiles, onDragEnd, onContextMenuItem, onSelectGraphNode, onEditingChange, onCommitEditing, onCancelEditing }: {
  item: TreeItem;
  depth: number;
  openIds: Set<string>;
  onToggle: (id: string) => void;
  projectId: string;
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDropItem: (target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onSelectGraphNode: (nodeId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
}) {
  const hasChildren = Boolean(item.children?.length);
  const isOpen = openIds.has(item.id);
  const isDropTarget = dropTarget?.projectId === projectId && dropTarget.targetId === item.id;
  const isFileDropTarget = fileDropTarget?.projectId === projectId && fileDropTarget.folderId === item.id;
  const isEditing = editing?.projectId === projectId && editing.itemId === item.id;
  const canNestChildren = !isFileItem(item);
  const canDrag = canDragTreeItem(item);

  function handleEditingKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") onCommitEditing();
    if (event.key === "Escape") onCancelEditing();
  }

  return (
    <>
      <button
        type="button"
        className={[
          "tree-row",
          item.active ? "is-active" : "",
          selectedItemId === item.id ? "is-selected" : "",
          draggedItemId === item.id ? "is-dragging" : "",
          isFileDropTarget ? "is-file-drop-target" : "",
          isDropTarget ? `is-drop-${dropTarget.position}` : ""
        ].filter(Boolean).join(" ")}
        style={{ paddingLeft: 10 + depth * 17 }}
        title={item.errorMessage ?? item.sourceUri}
        aria-expanded={hasChildren ? isOpen : undefined}
        draggable={!isEditing && canDrag}
        onDragStart={(event) => {
          if (!canDrag) return;
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", item.id);
          setLightDragPreview(event);
          onDragStart(projectId, item.id);
        }}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = hasDroppedFiles(event) ? "copy" : "move";
          if (hasDroppedFiles(event) && !isFileItem(item) && !isWikiItem(item)) {
            event.stopPropagation();
            onFileDragOver({ projectId, folderId: item.id });
            return;
          }
          if (isWikiItem(item) || item.generated) return;
          const position = resolveDropPosition(event);
          onDragOverItem({ projectId, targetId: item.id, position });
        }}
        onDragLeave={(event) => {
          if (!hasDroppedFiles(event)) return;
          event.stopPropagation();
          const nextTarget = event.relatedTarget;
          if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
          onFileDragLeave();
        }}
        onDrop={(event) => {
          event.preventDefault();
          if (hasDroppedFiles(event)) {
            event.stopPropagation();
            onFileDragLeave();
          onDropFiles(projectId, canNestChildren && !isWikiItem(item) ? item.id : null, getDroppedFiles(event));
          return;
          }
          if (!isWikiItem(item) && !item.generated) {
            onDropItem({ projectId, targetId: item.id, position: resolveDropPosition(event) });
          }
        }}
        onDragEnd={onDragEnd}
        onContextMenu={(event) => onContextMenuItem(event, projectId, item.id)}
        onClick={(event) => {
          event.stopPropagation();
          if (!isEditing && item.graphNodeId) onSelectGraphNode(item.graphNodeId, item.id);
          if (!isEditing && hasChildren) onToggle(item.id);
        }}
      >
        {isFileItem(item) ? (
          <SvgIcon src={fileIcon} className="tree-asset" />
        ) : item.wikiKind === "source" ? (
          <SvgIcon src={sourcePageIcon} className="tree-asset source" />
        ) : item.wikiKind === "concept" ? (
          <SvgIcon src={conceptPageIcon} className="tree-asset" />
        ) : hasChildren ? (
          <SvgIcon src={arrowIcon} className={`tree-arrow ${isOpen ? "is-open" : ""}`} />
        ) : (
          <SvgIcon src={archiveIcon} className="tree-asset" />
        )}
        {isEditing ? (
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
            <span>{item.label}</span>
            {isFileDropTarget && <small className="tree-drop-hint">여기에 추가</small>}
            {item.status && (
              <small className={`tree-status ${item.status}`}>
                {(item.status === "processing" || item.status === "uploading" || item.status === "uploaded") && <i />}
                {item.status === "processing" || item.status === "uploading" || item.status === "uploaded" ? "" : item.status}
              </small>
            )}
          </>
        )}
      </button>
      {hasChildren && isOpen && item.children?.map((child) => (
        <TreeNode
          key={child.id}
          item={child}
          depth={depth + 1}
          openIds={openIds}
          onToggle={onToggle}
          projectId={projectId}
          draggedItemId={draggedItemId}
          selectedItemId={selectedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          onDragStart={onDragStart}
          onDragOverItem={onDragOverItem}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={onDropItem}
          onDropFiles={onDropFiles}
          onDragEnd={onDragEnd}
          onContextMenuItem={onContextMenuItem}
          onSelectGraphNode={onSelectGraphNode}
          onEditingChange={onEditingChange}
          onCommitEditing={onCommitEditing}
          onCancelEditing={onCancelEditing}
        />
      ))}
    </>
  );
}

function SidebarTree({ items, projectId, draggedItemId, selectedItemId, dropTarget, fileDropTarget, editing, onMoveItem, onDropFiles, onDragStart, onDragOverItem, onFileDragOver, onFileDragLeave, onDragEnd, onContextMenuItem, onSelectGraphNode, onEditingChange, onCommitEditing, onCancelEditing, defaultOpenIds = [] }: {
  items: TreeItem[];
  projectId: string;
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onMoveItem: (projectId: string, itemId: string, target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onSelectGraphNode: (nodeId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
  defaultOpenIds?: string[];
}) {
  const [openIds, setOpenIds] = useState(() => new Set(defaultOpenIds));
  const generatedFolderIds = useMemo(
    () => items.filter((item) => item.generated && item.children?.length).map((item) => item.id),
    [items]
  );

  useEffect(() => {
    if (generatedFolderIds.length === 0) return;
    setOpenIds((current) => {
      const next = new Set(current);
      generatedFolderIds.forEach((id) => next.add(id));
      return next;
    });
  }, [generatedFolderIds]);

  function toggleNode(id: string) {
    setOpenIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function handleDropItem(target: DropTarget) {
    if (!draggedItemId) return;
    onMoveItem(projectId, draggedItemId, target);
  }

  return (
    <>
      {items.map((item) => (
        <TreeNode
          key={item.id}
          item={item}
          depth={0}
          openIds={openIds}
          onToggle={toggleNode}
          projectId={projectId}
          draggedItemId={draggedItemId}
          selectedItemId={selectedItemId}
          dropTarget={dropTarget}
          fileDropTarget={fileDropTarget}
          editing={editing}
          onDragStart={onDragStart}
          onDragOverItem={(target) => {
            onDragOverItem(target);
          }}
          onFileDragOver={onFileDragOver}
          onFileDragLeave={onFileDragLeave}
          onDropItem={handleDropItem}
          onDropFiles={onDropFiles}
          onDragEnd={onDragEnd}
          onContextMenuItem={onContextMenuItem}
          onSelectGraphNode={onSelectGraphNode}
          onEditingChange={onEditingChange}
          onCommitEditing={onCommitEditing}
          onCancelEditing={onCancelEditing}
        />
      ))}
    </>
  );
}

function ProjectSection({
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
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
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
}) {
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

function Graph({ nodes = [], links = [], rawDocumentCount, processingDocumentCount, focusedNodeId, loading = false, errorMessage = null }: {
  nodes: GraphNode[];
  links: GraphLink[];
  rawDocumentCount: number;
  processingDocumentCount: number;
  focusedNodeId: string | null;
  loading?: boolean;
  errorMessage?: string | null;
}) {
  const graphSignature = useMemo(
    () => `api-layout-v1:${nodes.map((node) => node.id).sort().join("|")}`,
    [nodes]
  );
  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);
  const nodeDegrees = useMemo(() => nodes.reduce<Record<string, number>>((degrees, node) => {
    degrees[node.id] = links.filter((link) => link.from === node.id || link.to === node.id).length;
    return degrees;
  }, {}), [links, nodes]);
  const nodeSizes = useMemo(() => nodes.reduce<Record<string, number>>((sizes, node) => {
    const degree = nodeDegrees[node.id] ?? 0;
    if (node.kind === "raw") sizes[node.id] = Math.min(26, 16 + degree * 4);
    else if (node.kind === "source") sizes[node.id] = Math.min(44, 22 + degree * 3.2);
    else if (node.kind === "progress") sizes[node.id] = Math.min(38, 22 + degree * 4);
    else sizes[node.id] = Math.min(32, 15 + degree * 3.5);
    return sizes;
  }, {}), [nodeDegrees, nodes]);
  const nodePairs = useMemo(() => nodes.flatMap((nodeA, index) =>
    nodes.slice(index + 1).map((nodeB) => ({ nodeA, nodeB }))
  ), [nodes]);
  const linkedNodePairs = useMemo(() => new Set(links.map((link) => linkKey(link.from, link.to))), [links]);

  function areNodesLinked(nodeAId: string, nodeBId: string) {
    return linkedNodePairs.has(linkKey(nodeAId, nodeBId));
  }

  function isRawSourceLink(link: GraphLink) {
    const from = nodeById.get(link.from);
    const to = nodeById.get(link.to);
    const kinds = [from?.kind, to?.kind];
    return kinds.includes("raw") && kinds.includes("source");
  }

  function nodeSize(node: GraphNode) {
    return nodeSizes[node.id] ?? 20;
  }

  function physicsNodeRadius(node: GraphNode) {
    return (nodeSize(node) / 2) * GRAPH_PHYSICS.collisionRadiusMultiplier;
  }

  function idealLinkDistanceValue(link: GraphLink) {
    const from = nodes.find((node) => node.id === link.from);
    const to = nodes.find((node) => node.id === link.to);
    if (!from || !to) return GRAPH_PHYSICS.linkDistance.fallback * GRAPH_PHYSICS.linkDistanceMultiplier;

    const kinds = [graphNodeKind(from), graphNodeKind(to)];
    let distance = GRAPH_PHYSICS.linkDistance.concept;
    if (kinds.every((kind) => kind === "source")) distance = GRAPH_PHYSICS.linkDistance.source;
    else if (kinds.includes("raw")) distance = GRAPH_PHYSICS.linkDistance.raw;
    else if (kinds.includes("progress")) distance = GRAPH_PHYSICS.linkDistance.progress;
    else if (kinds.includes("source") && kinds.includes("concept")) distance = GRAPH_PHYSICS.linkDistance.sourceConcept;
    return distance * GRAPH_PHYSICS.linkDistanceMultiplier;
  }

  const linkForces = useMemo(() => links.map((link) => ({
    ...link,
    idealDistance: idealLinkDistanceValue(link),
    weight: 1 + Math.min(0.85, ((nodeDegrees[link.from] ?? 0) + (nodeDegrees[link.to] ?? 0)) * 0.035)
  })), [links, nodeDegrees, nodes]); // eslint-disable-line react-hooks/exhaustive-deps

  function pairDistanceValue(nodeA: GraphNode, nodeB: GraphNode) {
    if (areNodesLinked(nodeA.id, nodeB.id)) return 0;

    const base = physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB);
    const kindA = graphNodeKind(nodeA);
    const kindB = graphNodeKind(nodeB);
    let distance = base + 48;
    if (kindA === "source" && kindB === "source") distance = base + 118;
    else if (kindA === "source" || kindB === "source") distance = base + 58;
    else if (kindA === "raw" || kindB === "raw") distance = base + 42;
    const typeMultiplier = kindA === "source" && kindB === "source"
      ? GRAPH_PHYSICS.sourceNodeDistanceMultiplier
      : 1;
    return distance * GRAPH_PHYSICS.nodeDistanceMultiplier * typeMultiplier;
  }

  const pairForces = useMemo(() => nodePairs.map(({ nodeA, nodeB }) => ({
    nodeA,
    nodeB,
    linked: areNodesLinked(nodeA.id, nodeB.id),
    desiredDistance: pairDistanceValue(nodeA, nodeB),
    minDistance: physicsNodeRadius(nodeA) + physicsNodeRadius(nodeB)
  })), [linkedNodePairs, nodePairs, nodeSizes]); // eslint-disable-line react-hooks/exhaustive-deps

  function createRandomNodePositionsForCurrentGraph() {
    const outerRadiusX = GRAPH_WIDTH * 0.18;
    const outerRadiusY = GRAPH_HEIGHT * 0.17;
    const innerRadiusX = GRAPH_WIDTH * 0.08;
    const innerRadiusY = GRAPH_HEIGHT * 0.075;
    const primaryNodeId = nodes.find((node) => node.kind === "source")?.id ?? nodes[0]?.id;

    return Object.fromEntries(
      nodes.map((node) => {
        if (node.id === primaryNodeId) return [node.id, GRAPH_CENTER];

        const isPrimaryNode = node.kind === "source" || node.kind === "progress";
        const angle = randomBetween(0, Math.PI * 2);
        const ringIndex = isPrimaryNode || Math.random() > 0.58 ? 0 : 1;
        const radiusX = ringIndex === 0 ? outerRadiusX : innerRadiusX;
        const radiusY = ringIndex === 0 ? outerRadiusY : innerRadiusY;
        const jitterX = randomBetween(-22, 22);
        const jitterY = randomBetween(-18, 18);

        return [node.id, {
          x: GRAPH_CENTER.x + Math.cos(angle) * radiusX + jitterX,
          y: GRAPH_CENTER.y + Math.sin(angle) * radiusY + jitterY
        }];
      })
    );
  }

  const initialNodePositions = useMemo(
    () => createRandomNodePositionsForCurrentGraph(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [graphSignature]
  );

  function readGraphCacheForCurrentGraph() {
    if (typeof window === "undefined") return null;

    try {
      const rawCache = window.localStorage.getItem(GRAPH_CACHE_KEY);
      if (!rawCache) return null;

      const cache = JSON.parse(rawCache) as Partial<GraphCache>;
      if (cache.signature !== graphSignature || !cache.positions || !cache.pan || typeof cache.zoom !== "number") {
        return null;
      }

      const hasEveryNode = nodes.every((node) => {
        const position = cache.positions?.[node.id];
        return typeof position?.x === "number" && typeof position?.y === "number";
      });

      return hasEveryNode ? cache as GraphCache : null;
    } catch {
      return null;
    }
  }

  function cachedOrInitialPositionsForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.positions ?? initialNodePositions;
  }

  function cachedOrInitialPanForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.pan ?? { x: 0, y: 0 };
  }

  function cachedOrInitialZoomForCurrentGraph() {
    return readGraphCacheForCurrentGraph()?.zoom ?? 1;
  }

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);
  const [visibleNodeCount, setVisibleNodeCount] = useState(0);
  const [graphZoom, setGraphZoom] = useState(cachedOrInitialZoomForCurrentGraph);
  const [graphPan, setGraphPan] = useState<NodePosition>(cachedOrInitialPanForCurrentGraph);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const nodePositionsRef = useRef<NodePositionMap>(cachedOrInitialPositionsForCurrentGraph());
  const selectedNodeIdRef = useRef(selectedNodeId);
  const externalFocusedNodeIdRef = useRef<string | null>(focusedNodeId);
  const draggingNodeIdRef = useRef(draggingNodeId);
  const visibleNodeCountRef = useRef(visibleNodeCount);
  const graphZoomRef = useRef(graphZoom);
  const graphPanRef = useRef(graphPan);
  const panDragRef = useRef<{ pointerId: number; button: number; startX: number; startY: number; startPan: NodePosition } | null>(null);
  const focusTransitionRef = useRef<{ from: string | null; to: string | null; startedAt: number }>({ from: null, to: null, startedAt: 0 });
  const isPointerHeldRef = useRef(false);
  const activePointerIdRef = useRef<number | null>(null);
  const cacheWriteRef = useRef<number | null>(null);
  const tickGraphRef = useRef<(positions: NodePositionMap, anchorId: string | null) => NodePositionMap>((positions) => positions);
  const drawGraphRef = useRef<() => void>(() => {});
  const isRevealingGraph = visibleNodeCount < nodes.length;

  useEffect(() => {
    const nextPositions = cachedOrInitialPositionsForCurrentGraph();
    const nextPan = cachedOrInitialPanForCurrentGraph();
    const nextZoom = cachedOrInitialZoomForCurrentGraph();
    nodePositionsRef.current = nextPositions;
    graphPanRef.current = nextPan;
    graphZoomRef.current = nextZoom;
    setGraphPan(nextPan);
    setGraphZoom(nextZoom);
    setVisibleNodeCount(0);
    setSelectedNodeId(null);
    selectedNodeIdRef.current = null;
    drawGraphRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [graphSignature, initialNodePositions]);

  useEffect(() => {
    selectedNodeIdRef.current = selectedNodeId;
  }, [selectedNodeId]);

  useEffect(() => {
    externalFocusedNodeIdRef.current = focusedNodeId;
  }, [focusedNodeId]);

  useEffect(() => {
    draggingNodeIdRef.current = draggingNodeId;
  }, [draggingNodeId]);

  useEffect(() => {
    visibleNodeCountRef.current = visibleNodeCount;
    drawGraphRef.current();
  }, [visibleNodeCount, selectedNodeId]);

  useEffect(() => {
    graphZoomRef.current = graphZoom;
    drawGraphRef.current();
  }, [graphZoom]);

  useEffect(() => {
    graphPanRef.current = graphPan;
    drawGraphRef.current();
  }, [graphPan]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    drawGraphRef.current();
    const observer = new ResizeObserver(() => drawGraphRef.current());
    observer.observe(canvas);
    return () => observer.disconnect();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      event.stopPropagation();
      const nextZoom = clampZoom(graphZoomRef.current * Math.exp(-event.deltaY * GRAPH_ZOOM.wheelSensitivity));
      graphZoomRef.current = nextZoom;
      setGraphZoom(nextZoom);
      scheduleGraphCacheWrite();
    };

    canvas.addEventListener("wheel", handleWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", handleWheel);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let nextIndex = 0;
    setVisibleNodeCount(0);
    const intervalId = window.setInterval(() => {
      nextIndex += 1;
      setVisibleNodeCount(Math.min(nodes.length, nextIndex));
      if (nextIndex >= nodes.length) window.clearInterval(intervalId);
    }, NODE_REVEAL_INTERVAL_MS);

    return () => window.clearInterval(intervalId);
  }, [graphSignature, nodes.length]);

  function scheduleGraphCacheWrite() {
    if (typeof window === "undefined") return;
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);

    cacheWriteRef.current = window.setTimeout(() => {
      const cache: GraphCache = {
        signature: graphSignature,
        positions: nodePositionsRef.current,
        pan: graphPanRef.current,
        zoom: graphZoomRef.current
      };

      window.localStorage.setItem(GRAPH_CACHE_KEY, JSON.stringify(cache));
      cacheWriteRef.current = null;
    }, 700);
  }

  useEffect(() => () => {
    if (cacheWriteRef.current !== null) window.clearTimeout(cacheWriteRef.current);
  }, []);

  const setFocusedNode = useCallback((nodeId: string | null) => {
    if (selectedNodeIdRef.current === nodeId) return;
    focusTransitionRef.current = {
      from: selectedNodeIdRef.current,
      to: nodeId,
      startedAt: performance.now()
    };
    selectedNodeIdRef.current = nodeId;
    setSelectedNodeId(nodeId);
    drawGraphRef.current();
  }, []);

  useEffect(() => {
    if (!focusedNodeId) {
      setFocusedNode(null);
      return;
    }
    if (!nodeById.has(focusedNodeId)) return;
    setFocusedNode(focusedNodeId);
  }, [focusedNodeId, nodeById, setFocusedNode]);

  const stopDragging = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && activePointerIdRef.current !== pointerId) return;
    isPointerHeldRef.current = false;
    activePointerIdRef.current = null;
    if (!externalFocusedNodeIdRef.current) setFocusedNode(null);
    setDraggingNodeId(null);
    draggingNodeIdRef.current = null;
  }, [setFocusedNode]);

  const stopPanning = useCallback((pointerId?: number) => {
    if (pointerId !== undefined && panDragRef.current?.pointerId !== pointerId) return;
    panDragRef.current = null;
  }, []);

  useEffect(() => {
    const stopActiveDrag = (event?: PointerEvent) => {
      stopDragging(event?.pointerId);
      stopPanning(event?.pointerId);
    };
    const stopDragOnBlur = () => {
      stopDragging();
      stopPanning();
    };

    window.addEventListener("pointerup", stopActiveDrag);
    window.addEventListener("pointercancel", stopActiveDrag);
    window.addEventListener("blur", stopDragOnBlur);
    return () => {
      window.removeEventListener("pointerup", stopActiveDrag);
      window.removeEventListener("pointercancel", stopActiveDrag);
      window.removeEventListener("blur", stopDragOnBlur);
    };
  }, [stopDragging, stopPanning]);

  function getGraphDistances(sourceId: string) {
    const distances: Record<string, number> = { [sourceId]: 0 };
    const queue = [sourceId];

    for (let index = 0; index < queue.length; index += 1) {
      const current = queue[index];
      const nextDistance = distances[current] + 1;

      links.forEach((link) => {
        const neighbor = link.from === current ? link.to : link.to === current ? link.from : null;
        if (!neighbor || distances[neighbor] !== undefined) return;
        distances[neighbor] = nextDistance;
        queue.push(neighbor);
      });
    }

    return distances;
  }

  function influenceForDistance(distance: number | undefined) {
    if (distance === 0) return 1;
    if (distance === 1) return 0.34;
    if (distance === 2) return 0.16;
    if (distance === 3) return 0.07;
    if (distance !== undefined) return 0.03;
    return 0;
  }

  function canvasWorldScale(canvas: HTMLCanvasElement) {
    const cssWidth = canvas.clientWidth || canvas.width;
    const worldWidth = Math.max(GRAPH_WORLD_MIN_WIDTH, cssWidth * 2 + 1040);
    return (worldWidth / GRAPH_WIDTH) * graphZoomRef.current;
  }

  function graphToCanvas(position: NodePosition, canvas: HTMLCanvasElement) {
    const scale = canvasWorldScale(canvas);
    const pan = graphPanRef.current;
    return {
      x: canvas.clientWidth / 2 + pan.x + (position.x - GRAPH_CENTER.x) * scale,
      y: canvas.clientHeight / 2 + pan.y + (position.y - GRAPH_CENTER.y) * scale
    };
  }

  function canvasToGraph(clientX: number, clientY: number, canvas: HTMLCanvasElement) {
    const rect = canvas.getBoundingClientRect();
    const scale = canvasWorldScale(canvas);
    const pan = graphPanRef.current;
    return {
      x: GRAPH_CENTER.x + (clientX - rect.left - canvas.clientWidth / 2 - pan.x) / scale,
      y: GRAPH_CENTER.y + (clientY - rect.top - canvas.clientHeight / 2 - pan.y) / scale
    };
  }

  function hitTestNode(clientX: number, clientY: number) {
    const canvas = canvasRef.current;
    if (!canvas) return null;

    const visibleCount = visibleNodeCountRef.current;
    for (let index = visibleCount - 1; index >= 0; index -= 1) {
      const node = nodes[index];
      const position = nodePositionsRef.current[node.id] ?? initialNodePositions[node.id];
      const screenPosition = graphToCanvas(position, canvas);
      const radius = nodeSize(node) / 2;
      const distance = Math.hypot(clientX - canvas.getBoundingClientRect().left - screenPosition.x, clientY - canvas.getBoundingClientRect().top - screenPosition.y);
      if (distance <= Math.max(12, radius + 6)) return node;
    }

    return null;
  }

  function drawNodeLabel(context: CanvasRenderingContext2D, node: GraphNode, x: number, y: number, radius: number) {
    const labelY = y + radius + 16;
    context.font = node.kind === "source" ? "600 12px Inter, sans-serif" : "11px Inter, sans-serif";
    context.textAlign = "center";
    context.textBaseline = "middle";

    if (node.kind === "source") {
      context.fillStyle = "#f0f0f0";
      context.fillText(node.label, x, labelY);
      return;
    }

    context.fillStyle = "#8a8a8a";
    context.fillText(node.label, x, labelY);
  }

  function drawGraph() {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const pixelRatio = window.devicePixelRatio || 1;
    const cssWidth = canvas.clientWidth;
    const cssHeight = canvas.clientHeight;
    const nextWidth = Math.max(1, Math.floor(cssWidth * pixelRatio));
    const nextHeight = Math.max(1, Math.floor(cssHeight * pixelRatio));
    if (canvas.width !== nextWidth || canvas.height !== nextHeight) {
      canvas.width = nextWidth;
      canvas.height = nextHeight;
    }

    const context = canvas.getContext("2d");
    if (!context) return;

    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    context.clearRect(0, 0, cssWidth, cssHeight);
    const visibleNodeIds = new Set(nodes.slice(0, visibleNodeCountRef.current).map((node) => node.id));
    const positions = nodePositionsRef.current;
    const transition = focusTransitionRef.current;
    const rawProgress = Math.min(1, Math.max(0, (performance.now() - transition.startedAt) / FOCUS_TRANSITION_MS));
    const transitionProgress = rawProgress * rawProgress * (3 - rawProgress * 2);

    function directNodeIds(focusNodeId: string | null) {
      const focusedNodeIds = new Set<string>();
      if (!focusNodeId || !visibleNodeIds.has(focusNodeId)) return focusedNodeIds;
      focusedNodeIds.add(focusNodeId);
      for (const link of links) {
        if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
        if (link.from !== focusNodeId && link.to !== focusNodeId) continue;
        focusedNodeIds.add(link.from === focusNodeId ? link.to : link.from);
      }
      return focusedNodeIds;
    }

    const previousFocusedNodeIds = directNodeIds(transition.from);
    const nextFocusedNodeIds = directNodeIds(transition.to);

    function mix(previous: number, next: number) {
      return previous + (next - previous) * transitionProgress;
    }

    function nodeFocusAmount(nodeId: string) {
      const previous = transition.from ? (previousFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
      const next = transition.to ? (nextFocusedNodeIds.has(nodeId) ? 1 : 0) : 1;
      return mix(previous, next);
    }

    function nodeSelectedAmount(nodeId: string) {
      return mix(transition.from === nodeId ? 1 : 0, transition.to === nodeId ? 1 : 0);
    }

    function linkFocusAmount(link: GraphLink) {
      const previous = transition.from ? (link.from === transition.from || link.to === transition.from ? 1 : 0) : 1;
      const next = transition.to ? (link.from === transition.to || link.to === transition.to ? 1 : 0) : 1;
      return mix(previous, next);
    }

    function linkHighlightAmount(link: GraphLink) {
      return mix(
        transition.from && (link.from === transition.from || link.to === transition.from) ? 1 : 0,
        transition.to && (link.from === transition.to || link.to === transition.to) ? 1 : 0
      );
    }

    for (const link of links) {
      if (!visibleNodeIds.has(link.from) || !visibleNodeIds.has(link.to)) continue;
      const from = positions[link.from] ?? initialNodePositions[link.from];
      const to = positions[link.to] ?? initialNodePositions[link.to];
      const fromScreen = graphToCanvas(from, canvas);
      const toScreen = graphToCanvas(to, canvas);
      const rawSourceLink = isRawSourceLink(link);
      const focusAmount = linkFocusAmount(link);
      const highlightAmount = linkHighlightAmount(link);
      const baseAlpha = rawSourceLink ? 0.68 : 0.5;
      const fadedAlpha = rawSourceLink ? 0.14 : 0.08;

      context.save();
      context.globalAlpha = fadedAlpha + (baseAlpha - fadedAlpha) * focusAmount;
      context.beginPath();
      context.moveTo(fromScreen.x, fromScreen.y);
      context.lineTo(toScreen.x, toScreen.y);
      context.setLineDash(link.dashed ? [4, 4] : []);
      context.lineWidth = rawSourceLink ? 1.25 : 1.15;
      context.strokeStyle = rawSourceLink ? "#5a5a5a" : "#4f4f4f";
      context.stroke();

      if (highlightAmount > 0.01) {
        context.globalAlpha = 0.98 * highlightAmount;
        context.setLineDash([]);
        context.lineWidth = 2.6;
        context.strokeStyle = "#ffc117";
        context.beginPath();
        context.moveTo(fromScreen.x, fromScreen.y);
        context.lineTo(toScreen.x, toScreen.y);
        context.stroke();
      }

      context.restore();
    }
    context.setLineDash([]);

    for (let index = 0; index < visibleNodeCountRef.current; index += 1) {
      const node = nodes[index];
      const position = positions[node.id] ?? initialNodePositions[node.id];
      const screenPosition = graphToCanvas(position, canvas);
      const radius = nodeSize(node) / 2;
      const selectedAmount = nodeSelectedAmount(node.id);
      const focusAmount = nodeFocusAmount(node.id);

      context.save();
      context.globalAlpha = 0.16 + 0.84 * focusAmount;

      if (selectedAmount > 0.01) {
        context.fillStyle = node.kind === "raw" ? "rgba(138, 138, 138, 0.14)" : "rgba(255, 193, 23, 0.08)";
        context.globalAlpha = selectedAmount;
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius + 26, 0, Math.PI * 2);
        context.fill();
        context.fillStyle = node.kind === "source" || node.loading ? "rgba(255, 193, 23, 0.28)" : "rgba(138, 138, 138, 0.18)";
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius + 12, 0, Math.PI * 2);
        context.fill();
        context.globalAlpha = 0.16 + 0.84 * focusAmount;
      }

      context.beginPath();
      context.arc(screenPosition.x, screenPosition.y, radius, 0, Math.PI * 2);
      if (node.kind === "source") {
        context.fillStyle = "#ffc117";
        context.fill();
        if (node.loading) {
          const spin = performance.now() / 720;
          context.strokeStyle = "rgba(255, 255, 255, 0.58)";
          context.lineWidth = 3;
          context.beginPath();
          context.arc(screenPosition.x, screenPosition.y, radius - 5, spin, spin + Math.PI * 1.35);
          context.stroke();
        }
      } else if (node.kind === "raw") {
        context.strokeStyle = "#6c6c6c";
        context.lineWidth = 1.2;
        context.setLineDash([4, 4]);
        context.stroke();
        context.setLineDash([]);
        if (node.loading) {
          const spin = performance.now() / 720;
          context.strokeStyle = "#ffc117";
          context.lineWidth = 2.4;
          context.beginPath();
          context.arc(screenPosition.x, screenPosition.y, radius - 2, spin, spin + Math.PI * 1.35);
          context.stroke();
        }
      } else if (node.kind === "progress") {
        const spin = performance.now() / 720;
        context.fillStyle = "#1e1e1e";
        context.fill();
        context.strokeStyle = "rgba(255, 193, 23, 0.24)";
        context.lineWidth = 2;
        context.stroke();
        context.strokeStyle = "#ffc117";
        context.lineWidth = 4;
        context.beginPath();
        context.arc(screenPosition.x, screenPosition.y, radius - 3, spin, spin + Math.PI * 1.35);
        context.stroke();
      } else {
        context.fillStyle = "#646464";
        context.fill();
      }

      drawNodeLabel(context, node, screenPosition.x, screenPosition.y, radius);
      context.restore();
    }
  }

  drawGraphRef.current = drawGraph;

  function clampZoom(nextZoom: number) {
    return Math.min(GRAPH_ZOOM.max, Math.max(GRAPH_ZOOM.min, nextZoom));
  }

  function clampPosition(position: NodePosition, nodeId?: string) {
    const node = nodes.find((candidate) => candidate.id === nodeId);
    const margin = node ? nodeSize(node) / 2 + 4 : 0;
    return {
      x: Math.min(GRAPH_WIDTH - margin, Math.max(margin, position.x)),
      y: Math.min(GRAPH_HEIGHT - margin, Math.max(margin, position.y))
    };
  }

  function resolveCollisions(positions: NodePositionMap, anchorId: string | null) {
    const next: NodePositionMap = Object.fromEntries(
      nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
    );

    for (let iteration = 0; iteration < 4; iteration += 1) {
      for (const pair of pairForces) {
        const { nodeA, nodeB, minDistance } = pair;
        const posA = next[nodeA.id];
        const posB = next[nodeB.id];
        const dx = posB.x - posA.x;
        const dy = posB.y - posA.y;
        const distance = Math.hypot(dx, dy) || 0.01;
        const overlap = minDistance - distance;

        if (overlap <= 0) continue;

        const pushX = (dx / distance) * overlap;
        const pushY = (dy / distance) * overlap;

        if (anchorId && nodeA.id === anchorId) {
          next[nodeB.id] = clampPosition({ x: posB.x + pushX, y: posB.y + pushY }, nodeB.id);
        } else if (anchorId && nodeB.id === anchorId) {
          next[nodeA.id] = clampPosition({ x: posA.x - pushX, y: posA.y - pushY }, nodeA.id);
        } else {
          next[nodeA.id] = clampPosition({ x: posA.x - pushX / 2, y: posA.y - pushY / 2 }, nodeA.id);
          next[nodeB.id] = clampPosition({ x: posB.x + pushX / 2, y: posB.y + pushY / 2 }, nodeB.id);
        }
      }
    }

    return next;
  }

  function tickGraph(positions: NodePositionMap, anchorId: string | null) {
    const next: NodePositionMap = Object.fromEntries(
      nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
    );
    const deltas: Record<string, NodePosition> = Object.fromEntries(
      nodes.map((node) => [node.id, { x: 0, y: 0 }])
    );

    linkForces.forEach((link) => {
      const from = next[link.from];
      const to = next[link.to];
      if (!from || !to) return;

      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distance = Math.hypot(dx, dy) || 0.01;
      const revealBoost = isRevealingGraph ? GRAPH_PHYSICS.revealLinkBoost : 1;
      const force = (distance - link.idealDistance) * GRAPH_PHYSICS.linkStrength * link.weight * revealBoost;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;

      if (link.from !== anchorId) {
        deltas[link.from].x += fx;
        deltas[link.from].y += fy;
      }

      if (link.to !== anchorId) {
        deltas[link.to].x -= fx;
        deltas[link.to].y -= fy;
      }
    });

    for (const pair of pairForces) {
      const { nodeA, nodeB, linked, desiredDistance } = pair;
      const posA = next[nodeA.id];
      const posB = next[nodeB.id];
      const dx = posB.x - posA.x;
      const dy = posB.y - posA.y;
      const distance = Math.hypot(dx, dy) || 0.01;
      if (linked || distance >= desiredDistance || distance >= GRAPH_PHYSICS.repulsionRange) continue;

      const proximity = 1 - distance / GRAPH_PHYSICS.repulsionRange;
      const force = (desiredDistance - distance) * GRAPH_PHYSICS.repulsionStrength * proximity;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;

      if (nodeA.id !== anchorId) {
        deltas[nodeA.id].x -= fx;
        deltas[nodeA.id].y -= fy;
      }

      if (nodeB.id !== anchorId) {
        deltas[nodeB.id].x += fx;
        deltas[nodeB.id].y += fy;
      }
    }

    nodes.forEach((node) => {
      if (node.id === anchorId) return;
      const initialPosition = initialNodePositions[node.id];
      const position = next[node.id] ?? initialPosition;
      const centerStrength = GRAPH_PHYSICS.centerStrength * (isRevealingGraph ? GRAPH_PHYSICS.revealCenterBoost : 1);
      deltas[node.id].x += (GRAPH_CENTER.x - position.x) * centerStrength;
      deltas[node.id].y += (GRAPH_CENTER.y - position.y) * centerStrength;
      deltas[node.id].x += (initialPosition.x - position.x) * GRAPH_PHYSICS.originStrength;
      deltas[node.id].y += (initialPosition.y - position.y) * GRAPH_PHYSICS.originStrength;
    });

    const nextPositions = resolveCollisions(Object.fromEntries(
      nodes.map((node) => {
        const position = next[node.id] ?? initialNodePositions[node.id];
        const delta = deltas[node.id];
        if (node.id === anchorId) return [node.id, clampPosition(position, node.id)];
        const damping = isRevealingGraph ? GRAPH_PHYSICS.revealDamping : GRAPH_PHYSICS.damping;

        return [node.id, clampPosition({
          x: position.x + delta.x * damping,
          y: position.y + delta.y * damping
        }, node.id)];
      })
    ), anchorId);

    if (!isRevealingGraph && !anchorId) {
      const maxMovement = nodes.reduce((movement, node) => {
        const previous = positions[node.id] ?? initialNodePositions[node.id];
        const current = nextPositions[node.id] ?? previous;
        return Math.max(movement, Math.hypot(current.x - previous.x, current.y - previous.y));
      }, 0);

      if (maxMovement < GRAPH_PHYSICS.settleThreshold) return positions;
    }

    return nextPositions;
  }

  tickGraphRef.current = tickGraph;

  useEffect(() => {
    let frameId = 0;
    let lastFrame = 0;

    const animate = (time: number) => {
      if (time - lastFrame > 32) {
        lastFrame = time;
        const anchorId = draggingNodeIdRef.current;
        const next = tickGraphRef.current(nodePositionsRef.current, anchorId);
        if (next !== nodePositionsRef.current) {
          nodePositionsRef.current = next;
          scheduleGraphCacheWrite();
        }
        drawGraphRef.current();
      }

      frameId = requestAnimationFrame(animate);
    };

    frameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(frameId);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function moveNode(event: ReactPointerEvent<HTMLDivElement>, node: GraphNode) {
    if (!isPointerHeldRef.current || activePointerIdRef.current !== event.pointerId) return;
    if (event.pointerType === "mouse" && (event.buttons & 1) !== 1) return;
    if (
      event.clientX < 0 ||
      event.clientY < 0 ||
      event.clientX > window.innerWidth ||
      event.clientY > window.innerHeight
    ) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
      stopDragging(event.pointerId);
      return;
    }

    const canvas = canvasRef.current;
    if (!canvas) return;

    const nextPosition = clampPosition(canvasToGraph(event.clientX, event.clientY, canvas), node.id);
    const distances = getGraphDistances(node.id);
    const current = nodePositionsRef.current;
    const draggedPosition = current[node.id] ?? initialNodePositions[node.id];
    const moved = {
      ...current,
      ...Object.fromEntries(
        nodes.map((candidate) => {
          const currentPosition = current[candidate.id] ?? initialNodePositions[candidate.id];
          const influence = influenceForDistance(distances[candidate.id]);
          const delta = {
            x: (nextPosition.x - draggedPosition.x) * influence,
            y: (nextPosition.y - draggedPosition.y) * influence
          };

          return [candidate.id, clampPosition({
            x: currentPosition.x + delta.x,
            y: currentPosition.y + delta.y
          }, candidate.id)];
        })
      )
    };

    nodePositionsRef.current = resolveCollisions(moved, node.id);
    scheduleGraphCacheWrite();
    drawGraphRef.current();
  }

  function startPanning(event: ReactPointerEvent<HTMLDivElement>) {
    const isPrimaryButton = event.button === 0;
    const isMiddleButton = event.button === 1;
    if (!isPrimaryButton && !isMiddleButton) return;

    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    const hitNode = isPrimaryButton ? hitTestNode(event.clientX, event.clientY) : null;
    if (hitNode) {
      isPointerHeldRef.current = true;
      activePointerIdRef.current = event.pointerId;
      setFocusedNode(hitNode.id);
      setDraggingNodeId(hitNode.id);
      draggingNodeIdRef.current = hitNode.id;
      drawGraphRef.current();
      return;
    }

    panDragRef.current = {
      pointerId: event.pointerId,
      button: event.button,
      startX: event.clientX,
      startY: event.clientY,
      startPan: graphPanRef.current
    };
  }

  function updatePanning(event: ReactPointerEvent<HTMLDivElement>) {
    const draggedNodeId = draggingNodeIdRef.current;
    if (draggedNodeId) {
      const node = nodes.find((candidate) => candidate.id === draggedNodeId);
      if (node) moveNode(event, node);
      return;
    }

    const panDrag = panDragRef.current;
    if (!panDrag || panDrag.pointerId !== event.pointerId) return;
    const expectedButtonMask = panDrag.button === 1 ? 4 : 1;
    if (event.pointerType === "mouse" && (event.buttons & expectedButtonMask) !== expectedButtonMask) {
      stopPanning(event.pointerId);
      return;
    }

    const nextPan = {
      x: panDrag.startPan.x + event.clientX - panDrag.startX,
      y: panDrag.startPan.y + event.clientY - panDrag.startY
    };
    graphPanRef.current = nextPan;
    setGraphPan(nextPan);
    scheduleGraphCacheWrite();
  }

  function updateHover(event: ReactPointerEvent<HTMLDivElement>) {
    if (draggingNodeIdRef.current || panDragRef.current) return;
    if (externalFocusedNodeIdRef.current) return;
    setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
  }

  function stopCanvasPanning(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    const wasDragging = activePointerIdRef.current === event.pointerId;
    if (wasDragging) {
      stopDragging(event.pointerId);
      if (!externalFocusedNodeIdRef.current) {
        setFocusedNode(hitTestNode(event.clientX, event.clientY)?.id ?? null);
      }
    }
    if (panDragRef.current?.pointerId !== event.pointerId) return;
    stopPanning(event.pointerId);
  }

  const sourceNodeCount = nodes.filter((node) => node.kind === "source").length;
  const conceptNodeCount = nodes.filter((node) => !node.kind || node.kind === "concept").length;

  return (
    <section className="graph-stage" aria-label="자료 관계 그래프">
      <div className="filter-chips">
        <span><SvgIcon src={rawPageIcon} className="chip-icon raw" />원본 raw {rawDocumentCount}</span>
        <span><SvgIcon src={sourcePageIcon} className="chip-icon source" />source page {sourceNodeCount}</span>
        <span><SvgIcon src={conceptPageIcon} className="chip-icon concept" />concept page {conceptNodeCount}</span>
        {processingDocumentCount > 0 && <span><SvgIcon src={lightningIcon} className="chip-icon source" />processing {processingDocumentCount}</span>}
      </div>

      <div
        className="graph-canvas"
        onPointerDown={startPanning}
        onPointerMove={(event) => {
          updatePanning(event);
          updateHover(event);
        }}
        onPointerUp={stopCanvasPanning}
        onPointerCancel={stopCanvasPanning}
        onAuxClick={(event) => event.preventDefault()}
        onPointerLeave={() => {
          if (!draggingNodeIdRef.current && !panDragRef.current && !externalFocusedNodeIdRef.current) {
            setFocusedNode(null);
          }
        }}
        onLostPointerCapture={(event) => {
          stopDragging(event.pointerId);
          stopPanning(event.pointerId);
        }}
      >
        <canvas ref={canvasRef} className="graph-surface" aria-label="자료 관계 그래프 캔버스" />
        {nodes.length === 0 && (
          <div className={`graph-empty ${errorMessage ? "is-error" : ""}`}>
            {errorMessage ?? (loading ? "그래프를 불러오는 중입니다." : "표시할 Wiki node가 없습니다.")}
          </div>
        )}
      </div>
    </section>
  );
}

export default function HomePage() {
  const [isAgentPanelOpen, setIsAgentPanelOpen] = useState(true);
  const [activeView, setActiveView] = useState<RailView>("home");
  const [projects, setProjects] = useState<Project[]>(initialProjects);
  const [draggedItem, setDraggedItem] = useState<{ projectId: string; itemId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<DropTarget | null>(null);
  const [fileDropTarget, setFileDropTarget] = useState<FileDropTarget | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [editing, setEditing] = useState<EditingState | null>(null);
  const [documents, setDocuments] = useState<DocumentItemResponse[]>([]);
  const [wikiGraph, setWikiGraph] = useState<WikiGraphResponse>({ nodes: [], edges: [] });
  const [isGraphLoading, setIsGraphLoading] = useState(true);
  const [apiError, setApiError] = useState<string | null>(null);
  const [focusedGraphNodeId, setFocusedGraphNodeId] = useState<string | null>(null);
  const [selectedTreeItemId, setSelectedTreeItemId] = useState<string | null>(null);
  const editingCancelRef = useRef(false);
  const uploadPickerTargetRef = useRef<UploadPickerTarget | null>(null);
  const uploadInputRef = useRef<HTMLInputElement | null>(null);
  const isHomeView = activeView === "home";
  const graphData = useMemo(() => buildGraphFromBackend(documents, wikiGraph), [documents, wikiGraph]);
  const hasProcessingDocuments = documents.some((document) => document.status === "processing" || document.status === "uploaded");
  const processingDocumentCount = documents.filter((document) => document.status === "processing" || document.status === "uploaded").length;
  const selectedDocumentTitle = useMemo(() => {
    if (!selectedTreeItemId) return null;
    for (const project of projects) {
      const item = findTreeItem(project.items, selectedTreeItemId);
      if (item) return item.label;
    }
    return null;
  }, [projects, selectedTreeItemId]);

  const refreshBackendData = useCallback(async () => {
    try {
      const nextData = await fetchBackendData();
      setDocuments(nextData.documents);
      setWikiGraph(nextData.graph);
      setProjects((current) => mergeBackendDataIntoProjects(current, nextData.documents, nextData.graph));
      setApiError(null);
    } catch (error) {
      setApiError(error instanceof Error ? error.message : "백엔드 데이터를 불러오지 못했습니다.");
    } finally {
      setIsGraphLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshBackendData();
  }, [refreshBackendData]);

  useEffect(() => {
    if (!hasProcessingDocuments) return;
    const intervalId = window.setInterval(() => {
      void refreshBackendData();
    }, 3000);
    return () => window.clearInterval(intervalId);
  }, [hasProcessingDocuments, refreshBackendData]);

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

  function createProject() {
    setProjects((current) => {
      const nextIndex = current.length + 1;
      return [
        ...current,
        {
          id: `project-${Date.now()}`,
          title: `새 프로젝트 ${nextIndex}`,
          items: []
        }
      ];
    });
  }

  function updateProjectTitle(projectId: string, title: string) {
    setProjects((current) => current.map((project) => (
      project.id === projectId ? { ...project, title } : project
    )));
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

  function openUploadPicker(projectId: string, folderId: string | null) {
    uploadPickerTargetRef.current = { projectId, folderId };
    uploadInputRef.current?.click();
  }

  function handleUploadPickerChange(event: ReactChangeEvent<HTMLInputElement>) {
    const target = uploadPickerTargetRef.current;
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!target || files.length === 0) return;
    dropUploadFiles(target.projectId, target.folderId, files);
  }

  function dropUploadFiles(projectId: string, folderId: string | null, files: File[]) {
    const uploadFiles = files.filter(isSupportedUploadFile);
    setFileDropTarget(null);
    if (uploadFiles.length === 0) return;

    const uploadItems = uploadFiles.map((file) => ({
      id: createClientId("upload"),
      label: file.name,
      type: "file" as const,
      status: "uploading" as const
    }));

    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      return { ...project, items: appendItemsToFolder(project.items, folderId, uploadItems) };
    }));

    uploadItems.forEach((item, index) => {
      const file = uploadFiles[index];
      void uploadDocumentFile(file)
        .then((response) => {
          setDocuments((current) => {
            const withoutCurrent = current.filter((document) => document.id !== response.id);
            return [...withoutCurrent, response];
          });
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: applyUploadedDocument(project.items, item.id, response) };
          }));
          void refreshBackendData();
        })
        .catch((error: Error) => {
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: updateTreeItemStatus(project.items, item.id, "failed", error.message) };
          }));
        });
    });
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

  function selectTreeGraphNode(itemId: string, nodeId: string) {
    setSelectedTreeItemId(itemId);
    setFocusedGraphNodeId(nodeId);
  }

  function clearTreeGraphSelection() {
    setSelectedTreeItemId(null);
    setFocusedGraphNodeId(null);
  }

  return (
    <main
      className={`workspace ${isHomeView && !isAgentPanelOpen ? "is-agent-collapsed" : ""} ${selectedDocumentTitle ? "has-source-preview" : ""}`}
      onClick={clearTreeGraphSelection}
    >
      <TopBar />
      <RailNavigation activeView={activeView} onViewChange={setActiveView} />

      {isHomeView ? (
        <>
          <DocumentSidebar
            projects={projects}
            draggedItemId={draggedItem?.itemId ?? null}
            selectedItemId={selectedTreeItemId}
            dropTarget={dropTarget}
            fileDropTarget={fileDropTarget}
            editing={editing}
            contextMenu={contextMenu}
            uploadInputRef={uploadInputRef}
            onOpenUploadPicker={openUploadPicker}
            onUploadPickerChange={handleUploadPickerChange}
            onMoveItem={moveTreeEntry}
            onDropFiles={dropUploadFiles}
            onDragStart={(projectId, itemId) => {
              setDraggedItem({ projectId, itemId });
              setContextMenu(null);
            }}
            onDragOverItem={(target) => {
              if (draggedItem?.projectId === target.projectId) setDropTarget(target);
            }}
            onFileDragOver={setFileDropTarget}
            onFileDragLeave={() => setFileDropTarget(null)}
            onDragEnd={() => {
              setDraggedItem(null);
              setDropTarget(null);
              setFileDropTarget(null);
            }}
            onContextMenuProject={openProjectMenu}
            onContextMenuItem={openFolderMenu}
            onSelectGraphNode={(nodeId, itemId) => selectTreeGraphNode(itemId, nodeId)}
            onEditingChange={(label) => {
              setEditing((current) => current ? { ...current, label } : current);
            }}
            onCommitEditing={commitEditing}
            onCancelEditing={cancelEditing}
            onRenameContextTarget={renameContextTarget}
            onAddFolderFromContext={addFolderFromContext}
            onDeleteContextTarget={deleteContextTarget}
          />

          {selectedDocumentTitle && <SourcePreviewPanel title={selectedDocumentTitle} />}

          <Graph
            nodes={graphData.nodes}
            links={graphData.links}
            rawDocumentCount={documents.length}
            processingDocumentCount={processingDocumentCount}
            focusedNodeId={focusedGraphNodeId}
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

function DocumentSidebar({
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
        <div
          className="folder-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(event) => event.stopPropagation()}
        >
          <button type="button" onClick={onRenameContextTarget}>이름 변경</button>
          <button type="button" onClick={onAddFolderFromContext}>새 폴더</button>
          {contextMenu.itemId !== null && <button type="button" className="danger" onClick={onDeleteContextTarget}>삭제</button>}
        </div>
      )}
    </aside>
  );
}
