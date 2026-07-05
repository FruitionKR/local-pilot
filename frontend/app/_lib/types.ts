export type TreeItem = {
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

export type Project = {
  id: string;
  title: string;
  items: TreeItem[];
};

export type DropPosition = "before" | "inside" | "after";

export type DropTarget = {
  projectId: string;
  targetId: string;
  position: DropPosition;
};

export type ContextMenuState = {
  projectId: string;
  itemId: string | null;
  x: number;
  y: number;
};

export type EditingState = {
  projectId: string;
  itemId: string | null;
  label: string;
};

export type FileDropTarget = {
  projectId: string;
  folderId: string | null;
};

export type UploadPickerTarget = {
  projectId: string;
  folderId: string | null;
};

export type DocumentStatus = "uploaded" | "processing" | "completed" | "failed";

export type WorkspaceResponse = {
  id: string;
  name: string;
  created_at: string;
  updated_at: string;
};

export type WorkspaceListResponse = {
  workspaces: WorkspaceResponse[];
};

export type ChatSessionResponse = {
  id: string;
  title: string | null;
  created_at: string;
  last_message_at: string;
};

export type ChatSessionListResponse = {
  sessions: ChatSessionResponse[];
};

export type DocumentUploadResponse = {
  id: string;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: DocumentStatus;
  source_uri: string;
  uploaded_at: string;
};

export type DocumentItemResponse = DocumentUploadResponse & {
  extracted_text_uri?: string;
  processed_at?: string;
  error_message?: string;
};

export type DocumentListResponse = {
  documents: DocumentItemResponse[];
};

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

// 질의/채팅 응답이 공유하는 관련 페이지 공통 필드
type RelatedPageBase = {
  page_type: string;
  title: string;
  slug: string;
  relevance_score: number;
  role: string;
  depth: number;
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

export type ChatMessageReferenceResponse = {
  id: number;
  reference_type: string;
  rank?: number;
  source_document_id?: string;
  source_block_ids?: string[];
  text?: string;
};

export type ChatMessageRelatedPageResponse = RelatedPageBase & {
  wiki_page_id: string;
  rank: number;
};

export type ChatMessageResponse = QueryMessageSummary & {
  related_pages?: ChatMessageRelatedPageResponse[];
  references: ChatMessageReferenceResponse[];
};

export type ChatMessagesResponse = {
  messages: ChatMessageResponse[];
};

export type SourceBlockHighlight = {
  block_id: string;
  rank: number;
};

export type DocumentBlockResponse = {
  block_id: string;
  text: string;
};

export type DocumentBlocksResponse = {
  document_id: string;
  blocks: DocumentBlockResponse[];
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
