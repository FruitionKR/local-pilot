import type { BackendData, DocumentListResponse, DocumentUploadResponse, WikiGraphResponse } from "./types";

export async function uploadDocumentFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch("/api/documents", {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    let message = "문서 업로드에 실패했습니다.";
    try {
      const body = await response.json();
      message = body?.error?.message || message;
    } catch {
      // JSON 오류 본문이 없으면 기본 메시지를 유지합니다.
    }
    throw new Error(message);
  }

  return response.json() as Promise<DocumentUploadResponse>;
}

export async function fetchBackendData(): Promise<BackendData> {
  const [documentsResponse, graphResponse] = await Promise.all([
    fetch("/api/documents", { cache: "no-store" }),
    fetch("/api/wiki/graph", { cache: "no-store" })
  ]);

  if (!documentsResponse.ok) throw new Error("문서 목록을 불러오지 못했습니다.");
  if (!graphResponse.ok) throw new Error("Wiki graph를 불러오지 못했습니다.");

  const documents = await documentsResponse.json() as DocumentListResponse;
  const graph = await graphResponse.json() as WikiGraphResponse;
  return { documents: documents.documents ?? [], graph };
}
