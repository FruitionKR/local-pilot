import type { DocumentProcessingState, DocumentStatus } from "@/entities/tree/model/tree";

/** 백엔드 DocumentRole. EDITABLE은 편집 가능 Markdown, ORIGINAL은 업로드 원본(PDF 등)이다. */
export type DocumentRole = "EDITABLE" | "ORIGINAL";

export type DocumentUploadResponse = {
  id: string;
  filename: string;
  mime_type: string;
  byte_size: number;
  status: DocumentStatus;
  source_uri: string;
  uploaded_at: string;
  document_role: DocumentRole;
};

export type DocumentItemResponse = DocumentUploadResponse & {
  extracted_text_uri?: string;
  processed_at?: string;
  updated_at?: string;
  error_message?: string;
  processing_state?: DocumentProcessingState;
  processing_stage?: string;
  /** 마지막 ingest 이후 편집본이 바뀌어 재분석이 필요한지 */
  needs_reingest?: boolean;
};

export type DocumentListResponse = {
  documents: DocumentItemResponse[];
};

export type NoteContentResponse = {
  document_id: string;
  markdown: string;
  content_version: number;
  updated_at: string;
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
