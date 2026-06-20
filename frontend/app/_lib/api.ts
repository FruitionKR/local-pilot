import type { BackendData, ChatMessagesResponse, DocumentListResponse, DocumentUploadResponse, QueryResponse, WikiGraphResponse, WikiPageDetailResponse } from "./types";

// 공통 에러 메시지 상수
const ERROR_MESSAGES = {
  uploadFailed: "문서 업로드에 실패했습니다.",
  queryFailed: "질의에 실패했습니다.",
  chatLoadFailed: "채팅 기록을 불러오지 못했습니다."
} as const;

// HTTP 응답에서 에러 메시지를 추출하는 공통 헬퍼
async function parseErrorResponse(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json() as { error?: { message?: string }; detail?: string } | undefined;
    return body?.error?.message || body?.detail || fallback;
  } catch {
    return fallback;
  }
}

export async function uploadDocumentFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch("/api/documents", {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.uploadFailed));
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

export async function queryWiki(question: string): Promise<QueryResponse> {
  const response = await fetch("/api/query", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question })
  });

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.queryFailed));
  }

  return response.json() as Promise<QueryResponse>;
}

export async function fetchWikiPage(pageId: string): Promise<WikiPageDetailResponse> {
  const response = await fetch(`/api/wiki/pages/${encodeURIComponent(pageId)}`, { cache: "no-store" });

  if (!response.ok) {
    throw new Error("Wiki page를 불러오지 못했습니다.");
  }

  return response.json() as Promise<WikiPageDetailResponse>;
}

export async function fetchChatMessages(): Promise<ChatMessagesResponse> {
  const response = await fetch("/api/chat/messages", { cache: "no-store" });

  if (!response.ok) {
    throw new Error(ERROR_MESSAGES.chatLoadFailed);
  }

  return response.json() as Promise<ChatMessagesResponse>;
}
