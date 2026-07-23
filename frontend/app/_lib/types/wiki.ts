import type { DocumentItemResponse } from "./document";
import type { RelatedPageBase } from "./shared";

export type WikiGraphNodeResponse = {
  id: string;
  page_type: "source" | "concept" | string;
  title: string;
  slug: string;
  summary?: string;
  status: string;
  // source page가 연결된 원본 문서 정보 (page_type=source일 때만 내려온다)
  source_document?: {
    id: string;
    filename?: string | null;
  };
};

export type WikiGraphEdgeResponse = {
  from_page_id: string;
  to_page_id: string;
  link_type: string;
  label?: string | null;
  confidence: number;
};

export type WikiGraphResponse = {
  nodes: WikiGraphNodeResponse[];
  edges: WikiGraphEdgeResponse[];
};

export type BackendData = {
  documents: DocumentItemResponse[];
  graph: WikiGraphResponse;
};

export type QueryRelatedPageResponse = RelatedPageBase & {
  id: string;
};

export type QueryEvidenceSnippetResponse = {
  rank: number;
  source_document_id: string;
  source_block_ids: string[];
  text: string;
};

export type QueryMessageSummary = {
  id: string;
  role: "user" | "assistant";
  content: string;
  status: string;
  created_at: string;
};

export type QueryResponse = {
  user_message: QueryMessageSummary;
  assistant_message: QueryMessageSummary;
  related_pages: QueryRelatedPageResponse[];
  evidence_snippets: QueryEvidenceSnippetResponse[];
};

export type WikiPageSourceDocument = {
  id: string;
  filename: string;
  source_uri: string;
  relation_type: string;
  confidence: number;
};

export type WikiPageRelatedPage = {
  id: string;
  page_type: string;
  title: string;
  slug: string;
  link_type: string;
  label?: string | null;
  confidence: number;
};

export type WikiPageDetailResponse = {
  id: string;
  page_type: string;
  title: string;
  slug: string;
  summary?: string;
  markdown_uri?: string;
  markdown?: string;
  status: string;
  source_documents: WikiPageSourceDocument[];
  related_pages: WikiPageRelatedPage[];
};

export type GraphNode = {
  id: string;
  label: string;
  kind?: "source" | "concept" | "raw";
  // raw/source 노드가 연결된 문서 ID
  documentId?: string;
};

export type GraphLink = {
  from: string;
  to: string;
  dashed?: boolean;
  active?: boolean;
};

export type NodePosition = { x: number; y: number };
export type NodePositionMap = Record<string, NodePosition>;
export type GraphCache = {
  signature: string;
  positions: NodePositionMap;
  pan: NodePosition;
  zoom: number;
};
