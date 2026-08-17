import type { DocumentItemResponse } from "@/entities/document/model/document";
import type { GraphLink, GraphNode, WikiGraphResponse } from "@/entities/wiki/model/wiki";

// 노드 ID 접두사 상수 (raw 노드만 프론트에서 합성한다. source/concept 노드는 백엔드 wiki page ID를 그대로 쓴다)
export const NODE_PREFIX = {
  raw: "raw:"
} as const;

/**
 * documentId로 raw 노드 ID를 생성합니다.
 */
export function makeRawId(documentId: string): string {
  return `${NODE_PREFIX.raw}${documentId}`;
}

/**
 * documentId에 연결된 source 노드를 찾습니다.
 */
export function findSourceNodeByDocumentId(nodes: GraphNode[] | undefined, documentId: string): GraphNode | null {
  return nodes?.find((node) => node.kind === "source" && node.documentId === documentId) ?? null;
}

/**
 * raw 노드 ID에서 documentId를 복원합니다.
 */
export function rawNodeIdToDocumentId(nodeId: string): string | null {
  if (!nodeId.startsWith(NODE_PREFIX.raw)) return null;
  return nodeId.slice(NODE_PREFIX.raw.length) || null;
}

/**
 * 그래프 노드 ID로 연결된 원본 문서 ID를 찾습니다.
 * concept 노드처럼 문서가 없는 노드거나 미선택이면 null.
 */
export function resolveNodeDocumentId(nodes: GraphNode[], nodeId: string | null): string | null {
  if (!nodeId) return null;
  if (nodeId.startsWith(NODE_PREFIX.raw)) return rawNodeIdToDocumentId(nodeId);
  return nodes.find((node) => node.id === nodeId)?.documentId ?? null;
}

export const GRAPH_WIDTH = 746;
export const GRAPH_HEIGHT = 568;
export const GRAPH_CACHE_KEY = "fruition.graph.layout.v8";
export const GRAPH_CENTER = { x: GRAPH_WIDTH / 2, y: GRAPH_HEIGHT / 2 };
export const GRAPH_ZOOM = {
  min: 0.62,
  max: 3.2
};
// 힘 세기 튜닝 값은 graphPhysics.ts의 SIM_FORCES에 있다. 여기는 링크 목표 거리만 관리한다.
export const GRAPH_PHYSICS = {
  linkDistanceMultiplier: 0.85,
  linkDistance: {
    source: 178,
    raw: 92,
    sourceConcept: 88,
    concept: 98,
    fallback: 118
  }
};

export function linkKey(nodeAId: string, nodeBId: string) {
  return nodeAId < nodeBId ? `${nodeAId}:${nodeBId}` : `${nodeBId}:${nodeAId}`;
}

export function buildGraphFromBackend(documents: DocumentItemResponse[], graph: WikiGraphResponse) {
  const documentById = new Map(documents.map((document) => [document.id, document]));
  const rawNodes: GraphNode[] = documents.map((document) => ({
    id: makeRawId(document.id),
    label: document.filename,
    kind: "raw" as const,
    documentId: document.id
  }));
  // source/concept 노드는 백엔드에 실제로 생성된 wiki page만 표시한다.
  const sourceNodes: GraphNode[] = (graph.nodes ?? [])
    .filter((node) => node.page_type === "source")
    .map((node) => ({
      id: node.id,
      label: node.title || documentById.get(node.source_document?.id ?? "")?.filename || node.slug || node.id,
      kind: "source" as const,
      documentId: node.source_document?.id
    }));
  const conceptNodes: GraphNode[] = (graph.nodes ?? [])
    .filter((node) => node.page_type !== "source")
    .map((node) => ({
      id: node.id,
      label: node.title || node.slug || node.id,
      kind: "concept" as const
    }));

  const rawSourceLinks: GraphLink[] = sourceNodes
    .filter((node) => node.documentId && documentById.has(node.documentId))
    .map((node) => ({
      from: makeRawId(node.documentId as string),
      to: node.id,
      dashed: documentById.get(node.documentId as string)?.status !== "completed"
    }));
  const nodeIds = new Set([...rawNodes, ...sourceNodes, ...conceptNodes].map((node) => node.id));
  const graphLinks: GraphLink[] = (graph.edges ?? [])
    .filter((edge) => nodeIds.has(edge.from_page_id) && nodeIds.has(edge.to_page_id))
    .map((edge) => ({
      from: edge.from_page_id,
      to: edge.to_page_id,
      active: edge.link_type === "source_mentions_concept"
    }));

  return {
    nodes: [...rawNodes, ...sourceNodes, ...conceptNodes],
    links: [...rawSourceLinks, ...graphLinks]
  };
}
