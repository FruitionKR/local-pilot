import type { DocumentStatus } from "./tree";

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
