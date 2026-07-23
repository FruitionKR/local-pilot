import { apiFetch, parseErrorResponse, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { NoteContentResponse } from "../types";

export class NoteContentConflictError extends Error {}

/** local profile mock에 저장된 노트 draft를 조회한다. 없으면 원본 문서를 사용하도록 null을 반환한다. */
export async function fetchNoteDraft(documentId: string): Promise<NoteContentResponse | null> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/content`,
    { cache: "no-store" }
  );
  if (response.status === 404) return null;
  return parseJsonOrThrow<NoteContentResponse>(response, ERROR_MESSAGES.noteDraftLoadFailed);
}

export async function saveNoteDraft(
  documentId: string,
  markdown: string,
  expectedContentVersion: number
): Promise<NoteContentResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/content`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        markdown,
        expected_content_version: expectedContentVersion
      })
    }
  );
  if (response.status === 409) {
    throw new NoteContentConflictError(await parseErrorResponse(response, "다른 편집 내용이 먼저 저장되었습니다."));
  }
  return parseJsonOrThrow<NoteContentResponse>(response, ERROR_MESSAGES.noteDraftSaveFailed);
}
