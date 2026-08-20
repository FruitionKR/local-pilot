import { apiFetch, throwIfNotOk, parseJsonOrThrow, getWorkspaceId, workspacePath, ERROR_MESSAGES } from "@/shared/api/client";
import type { DocumentItemResponse, DocumentRole, DocumentUploadResponse } from "@/entities/document/model/document";

export async function uploadDocumentFile(file: File) {
  const workspaceId = getWorkspaceId();
  const formData = new FormData();
  formData.append("file", file);

  const response = await apiFetch(workspacePath(workspaceId, "documents"), {
    method: "POST",
    headers: { "Idempotency-Key": crypto.randomUUID() },
    body: formData
  });

  return parseJsonOrThrow<DocumentUploadResponse>(response, ERROR_MESSAGES.uploadFailed);
}

/** 문서 ingest를 시작한다. 편집 가능 Markdown 전용이라 reflectDocumentToWiki를 거쳐 호출한다. */
async function startDocumentIngest(documentId: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "documents", documentId, "ingest"),
    { method: "POST" }
  );
  await throwIfNotOk(response, "문서 분석 시작에 실패했습니다.");
}

/**
 * PDF 원본 문서의 Markdown 변환을 요청한다.
 * 202와 함께 processing 상태로 생성된 markdown 문서 요약을 반환한다.
 */
export async function convertDocumentToMarkdown(documentId: string) {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "documents", documentId, "convert-markdown"),
    {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() }
    }
  );
  return parseJsonOrThrow<DocumentItemResponse>(response, ERROR_MESSAGES.documentConvertFailed);
}

/**
 * 위키 반영 진입점.
 * ingest는 편집 가능 Markdown만 받으므로(그 외 400) PDF 등 원본 문서는 Markdown 변환으로 보낸다.
 */
export async function reflectDocumentToWiki(
  documentId: string,
  documentRole: DocumentRole | undefined
): Promise<void> {
  if (documentRole === "EDITABLE") {
    await startDocumentIngest(documentId);
    return;
  }
  await convertDocumentToMarkdown(documentId);
}

/** 문서를 삭제한다. 성공 시 204를 반환한다. */
export async function deleteDocument(documentId: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "documents", documentId),
    { method: "DELETE" }
  );
  await throwIfNotOk(response, ERROR_MESSAGES.documentDeleteFailed);
}

/** 문서 표시명을 변경한다. */
export async function renameDocument(documentId: string, filename: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "documents", documentId, "rename"),
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename })
    }
  );
  await throwIfNotOk(response, ERROR_MESSAGES.documentRenameFailed);
}

/** 현재 워크스페이스의 원본 문서를 인증된 요청으로 가져온다. */
export async function fetchDocumentOriginal(documentId: string): Promise<Blob> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "documents", documentId, "original"),
    { cache: "no-store" }
  );

  await throwIfNotOk(response, ERROR_MESSAGES.documentOriginalLoadFailed);
  return response.blob();
}
