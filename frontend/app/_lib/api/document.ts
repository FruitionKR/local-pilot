import { apiFetch, parseErrorResponse, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { DocumentBlocksResponse, DocumentUploadResponse } from "../types";

export async function uploadDocumentFile(file: File) {
  const workspaceId = getWorkspaceId();
  const formData = new FormData();
  formData.append("file", file);

  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/documents`, {
    method: "POST",
    body: formData
  });

  return parseJsonOrThrow<DocumentUploadResponse>(response, ERROR_MESSAGES.uploadFailed);
}

/** 문서를 삭제한다. 성공 시 204를 반환한다. */
export async function deleteDocument(documentId: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}`,
    { method: "DELETE" }
  );
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.documentDeleteFailed));
  }
}

/** 문서 표시명을 변경한다. */
export async function renameDocument(documentId: string, filename: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/rename`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename })
    }
  );
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.documentRenameFailed));
  }
}

export async function fetchDocumentBlocks(documentId: string): Promise<DocumentBlocksResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/blocks`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<DocumentBlocksResponse>(response, ERROR_MESSAGES.documentBlocksLoadFailed);
}

/** 현재 워크스페이스의 원본 문서를 인증된 요청으로 가져온다. */
export async function fetchDocumentOriginal(documentId: string): Promise<Blob> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}/original`,
    { cache: "no-store" }
  );

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.documentOriginalLoadFailed));
  }
  return response.blob();
}
