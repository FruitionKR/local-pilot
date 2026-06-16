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

export type GraphNode = {
  id: string;
  label: string;
  size?: number;
  kind?: "source" | "concept" | "raw" | "progress";
  loading?: boolean;
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
