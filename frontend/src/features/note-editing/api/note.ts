import { apiFetch, parseErrorResponse, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { NoteContentResponse } from "@/entities/document/model/document";

export class NoteContentConflictError extends Error {}

type DocumentDetailContentResponse = {
  id: string;
  markdown?: string | null;
  current_version: number;
  updated_at: string;
};

type DocumentContentSaveResponse = {
  document_id: string;
  current_version: number;
  updated_at: string;
};

/** 문서 상세에서 최신 Markdown 편집본을 조회한다. 편집본이 없으면 원본 문서를 사용하도록 null을 반환한다. */
export async function fetchNoteDraft(documentId: string): Promise<NoteContentResponse | null> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}`,
    { cache: "no-store" }
  );
  if (response.status === 404) return null;
  const detail = await parseJsonOrThrow<DocumentDetailContentResponse>(
    response,
    ERROR_MESSAGES.noteDraftLoadFailed
  );
  if (typeof detail.markdown !== "string") return null;
  return {
    document_id: detail.id,
    markdown: detail.markdown,
    content_version: detail.current_version,
    updated_at: detail.updated_at
  };
}

export async function saveNoteDraft(
  documentId: string,
  markdown: string,
  expectedContentVersion: number
): Promise<NoteContentResponse> {
  const workspaceId = getWorkspaceId();
  const formData = new FormData();
  formData.append("markdown", markdown);
  formData.append("base_version", String(expectedContentVersion));
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/content`,
    {
      method: "PUT",
      body: formData
    }
  );
  if (response.status === 409) {
    throw new NoteContentConflictError(await parseErrorResponse(response, "다른 편집 내용이 먼저 저장되었습니다."));
  }
  const saved = await parseJsonOrThrow<DocumentContentSaveResponse>(
    response,
    ERROR_MESSAGES.noteDraftSaveFailed
  );
  return {
    document_id: saved.document_id,
    markdown,
    content_version: saved.current_version,
    updated_at: saved.updated_at
  };
}
