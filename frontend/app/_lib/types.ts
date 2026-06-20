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

export type QueryRelatedPageResponse = {
  id: string;
  page_type: string;
  title: string;
  slug: string;
  relevance_score: number;
  role: string;
  depth: number;
};

export type QueryEvidenceSnippetResponse = {
  page_id: string;
  page_type: string;
  page_title: string;
  page_slug: string;
  page_url: string;
  page_role: string;
  text: string;
  score: number;
  rank: number;
  paragraph_index: number;
  sentence_index: number;
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
  wiki_page_id?: string;
  document_id?: string;
  page_role?: string;
  relevance_score: number;
  rank?: number;
  page_number?: number;
  paragraph_index?: number;
  sentence_index?: number;
  quote?: string;
};

export type ChatMessageRelatedPageResponse = {
  wiki_page_id: string;
  page_type: string;
  title: string;
  slug: string;
  relevance_score: number;
  role: string;
  depth: number;
  rank: number;
};

export type ChatMessageResponse = QueryMessageSummary & {
  related_pages?: ChatMessageRelatedPageResponse[];
  references: ChatMessageReferenceResponse[];
};

export type ChatMessagesResponse = {
  messages: ChatMessageResponse[];
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
