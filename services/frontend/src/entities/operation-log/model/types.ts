// AI 작업 로그 (document-svc /ai-operation-logs 응답 계약)

export type OperationType = "document_edit" | "ingest" | "lint" | "restore";

/** document-svc OperationStatus 열거값. 목록 status 필터에 그대로 쓴다. */
export type OperationStatus =
  | "processing"
  | "applying"
  | "notify_pending"
  | "rebuilding"
  | "succeeded"
  | "partially_succeeded"
  | "failed"
  | "conflict";

export interface OperationLogItem {
  operation_id: string;
  operation_type: OperationType;
  status: string;
  target_document_id: string | null;
  target_display_name: string | null;
  summary: string | null;
  changed_resource_count: number;
  restored_from: string | null;
  created_at: string;
  completed_at: string | null;
}

export interface OperationLogListResponse {
  logs: OperationLogItem[];
  next_cursor: string | null;
}

export interface DiffLine {
  type: "CONTEXT" | "DELETE" | "ADD";
  old_line: number | null;
  new_line: number | null;
  content: string;
}

export interface DiffHunk {
  old_start: number;
  old_lines: number;
  new_start: number;
  new_lines: number;
  lines: DiffLine[];
}

export interface OperationChange {
  id: number;
  resource_type: string;
  resource_id: string;
  resource_display_name: string | null;
  before_revision: number | null;
  after_revision: number | null;
  change_type: string;
  change_summary: string | null;
  additions: number | null;
  deletions: number | null;
  hunks?: DiffHunk[];
  diff_too_large?: boolean;
}

export interface OperationLogDetail extends OperationLogItem {
  changes: OperationChange[];
}

export interface RestorePreviewResponse {
  operation_id: string;
  delete_count: number;
  restore_count: number;
  rebuild_count: number;
  pages: Array<{
    page_id: string;
    action: "delete" | "restore" | "rebuild";
    target_revision: number | null;
    contribution_count: number;
  }>;
  document?: {
    document_id: string;
    from_version: number;
    to_version: number;
  };
  preview_token: string;
}

export interface RestoreExecuteResponse {
  operation_id: string;
  restored_from: string;
  status: "succeeded" | "rebuilding" | "notify_pending" | "queued";
}
