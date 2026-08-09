import { makeRawId } from "@/entities/graph/lib/graph";
import type { Project, TreeItem } from "@/entities/tree/model/tree";
import type { DocumentItemResponse } from "@/entities/document/model/document";
import type { WikiGraphResponse } from "@/entities/wiki/model/wiki";

// 백엔드 wiki graph에서 자동 생성되는 그룹 폴더 ID
const WIKI_SOURCE_GROUP_ID = "wiki-source-pages";
const WIKI_CONCEPT_GROUP_ID = "wiki-concept-pages";

function isGeneratedGroup(item: TreeItem, groupId: string) {
  return item.generated && item.id === groupId;
}

function removeGeneratedWikiGroups(items: TreeItem[]): TreeItem[] {
  return items
    .filter((item) => !isGeneratedGroup(item, WIKI_SOURCE_GROUP_ID) && !isGeneratedGroup(item, WIKI_CONCEPT_GROUP_ID))
    .map((item) => item.children?.length ? { ...item, children: removeGeneratedWikiGroups(item.children) } : item);
}

function syncDocumentItems(items: TreeItem[], documents: DocumentItemResponse[]): TreeItem[] {
  const documentById = new Map(documents.map((document) => [document.id, document]));
  return items.map((item) => {
    const document = item.documentId ? documentById.get(item.documentId) : null;
    const nextItem = document ? {
      ...item,
      label: item.customLabel ? item.label : document.filename,
      status: document.status,
      processingState: document.processing_state,
      processingStage: document.processing_stage,
      errorMessage: document.error_message,
      mimeType: document.mime_type,
      byteSize: document.byte_size,
      sourceUri: document.source_uri,
      uploadedAt: document.uploaded_at,
      updatedAt: document.updated_at
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

function areTreeItemsEqual(left: TreeItem[], right: TreeItem[]): boolean {
  if (left.length !== right.length) return false;
  return left.every((leftItem, index) => areTreeItemsShallowEqual(leftItem, right[index]));
}

function areTreeItemsShallowEqual(left: TreeItem, right: TreeItem): boolean {
  return left.id === right.id
    && left.label === right.label
    && left.type === right.type
    && left.wikiKind === right.wikiKind
    && left.generated === right.generated
    && left.customLabel === right.customLabel
    && left.status === right.status
    && left.processingState === right.processingState
    && left.processingStage === right.processingStage
    && left.errorMessage === right.errorMessage
    && left.documentId === right.documentId
    && left.mimeType === right.mimeType
    && left.byteSize === right.byteSize
    && left.sourceUri === right.sourceUri
    && left.uploadedAt === right.uploadedAt
    && left.updatedAt === right.updatedAt
    && left.graphNodeId === right.graphNodeId
    && left.active === right.active
    && areTreeItemsEqual(left.children ?? [], right.children ?? []);
}

export function mergeBackendDataIntoProjects(projects: Project[], documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const backendDocumentIds = new Set(documents.map((document) => document.id));
  const removeMissingDocuments = (items: TreeItem[]): TreeItem[] => items.flatMap((item) => {
    if (item.documentId && !backendDocumentIds.has(item.documentId) && item.status !== "uploading") return [];
    if (!item.children?.length) return [item];
    return [{ ...item, children: removeMissingDocuments(item.children) }];
  });
  const reconciledProjects = projects.map((project) => ({
    ...project,
    items: removeMissingDocuments(project.items)
  }));
  const knownDocumentIds = collectDocumentIds(reconciledProjects.flatMap((project) => project.items));
  const missingDocuments = documents.filter((document) => !knownDocumentIds.has(document.id));
  const backendItems = missingDocuments.map((document) => ({
    id: `document-file-${document.id}`,
    label: document.filename,
    type: "file" as const,
    status: document.status,
    processingState: document.processing_state,
    processingStage: document.processing_stage,
    documentId: document.id,
    graphNodeId: makeRawId(document.id),
    mimeType: document.mime_type,
    byteSize: document.byte_size,
    sourceUri: document.source_uri,
    uploadedAt: document.uploaded_at,
    updatedAt: document.updated_at,
    errorMessage: document.error_message
  }));
  const nextProjects = reconciledProjects.map((project, index) => {
    const syncedItems = syncDocumentItems(removeGeneratedWikiGroups(project.items), documents);
    const nextItems = index === 0 ? [...syncedItems, ...backendItems] : syncedItems;
    if (areTreeItemsEqual(project.items, nextItems)) return project;
    return { ...project, items: nextItems };
  });

  return nextProjects.every((project, index) => areTreeItemsEqual(project.items, projects[index]?.items ?? []))
    ? projects
    : nextProjects;
}
