import { getAccessToken, getSelectedWorkspaceId } from "./auth";
import type { AgentTurnRequest, AgentTurnResponse } from "./markdownAgent";
import type { BackendData, ChatMessagesResponse, ChatSessionListResponse, ChatSessionResponse, DocumentBlocksResponse, DocumentListResponse, DocumentUploadResponse, NoteContentResponse, QueryResponse, UserMeResponse, WikiGraphResponse, WikiPageDetailResponse, WorkspaceListResponse, WorkspaceResponse } from "./types";

// 공통 에러 메시지 상수
const ERROR_MESSAGES = {
  loginRequired: "로그인이 필요합니다.",
  loginFailed: "로그인에 실패했습니다.",
  signupFailed: "회원가입에 실패했습니다.",
  workspaceRequired: "워크스페이스를 선택해주세요.",
  workspaceCreateFailed: "워크스페이스 생성에 실패했습니다.",
  uploadFailed: "문서 업로드에 실패했습니다.",
  documentDeleteFailed: "문서 삭제에 실패했습니다.",
  documentRenameFailed: "문서 이름 변경에 실패했습니다.",
  noteDraftLoadFailed: "노트 draft를 불러오지 못했습니다.",
  noteDraftSaveFailed: "노트 draft를 저장하지 못했습니다.",
  documentOriginalLoadFailed: "원본 문서를 불러오지 못했습니다.",
  queryFailed: "질의에 실패했습니다.",
  agentTurnFailed: "AI 편집 요청에 실패했습니다.",
  chatLoadFailed: "채팅 기록을 불러오지 못했습니다.",
  chatSessionFailed: "채팅 세션을 준비하지 못했습니다.",
  documentBlocksLoadFailed: "원본 문서 block을 불러오지 못했습니다.",
  documentsLoadFailed: "문서 목록을 불러오지 못했습니다.",
  wikiExportFailed: "위키 내보내기에 실패했습니다.",
  wikiGraphLoadFailed: "Wiki graph를 불러오지 못했습니다.",
  wikiPageLoadFailed: "Wiki page를 불러오지 못했습니다.",
  workspaceLoadFailed: "워크스페이스를 불러오지 못했습니다.",
  meLoadFailed: "사용자 정보를 불러오지 못했습니다."
} as const;

// HTTP 응답에서 에러 메시지를 추출하는 공통 헬퍼
async function parseErrorResponse(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json() as {
      error?: { message?: string };
      detail?: string | { message?: string };
    } | undefined;
    const detailMessage = typeof body?.detail === "string" ? body.detail : body?.detail?.message;
    return body?.error?.message || detailMessage || fallback;
  } catch {
    return fallback;
  }
}

/** Bearer 토큰을 부착하는 공통 fetch. 401이면 로그인 필요 에러를 던진다. */
async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  const headers = new Headers(init?.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });
  if (response.status === 401) {
    throw new Error(ERROR_MESSAGES.loginRequired);
  }
  return response;
}

async function parseJsonOrThrow<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, fallback));
  }
  return response.json() as Promise<T>;
}

export type AuthTokensResponse = {
  access_token: string;
  refresh_token: string;
};

export type OAuthProvider = "google" | "naver" | "kakao";

export async function loginWithEmail(email: string, password: string): Promise<AuthTokensResponse> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  });

  return parseJsonOrThrow<AuthTokensResponse>(response, ERROR_MESSAGES.loginFailed);
}

export function getOAuthAuthorizationUrl(provider: OAuthProvider): string {
  const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080";
  return `${backendUrl}/oauth2/authorization/${provider}`;
}

export async function exchangeOAuthCode(code: string): Promise<AuthTokensResponse> {
  const response = await fetch("/api/auth/oauth/exchange", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code })
  });

  return parseJsonOrThrow<AuthTokensResponse>(response, ERROR_MESSAGES.loginFailed);
}

export async function signupWithEmail(email: string, password: string): Promise<void> {
  const response = await fetch("/api/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  });

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.signupFailed));
  }
}

/** 로그인한 사용자 정보를 가져온다. */
export async function fetchMe(): Promise<UserMeResponse> {
  const response = await apiFetch("/api/auth/me", { cache: "no-store" });
  return parseJsonOrThrow<UserMeResponse>(response, ERROR_MESSAGES.meLoadFailed);
}

export async function fetchWorkspaces(): Promise<WorkspaceListResponse> {
  const response = await apiFetch("/api/workspaces", { cache: "no-store" });
  return parseJsonOrThrow<WorkspaceListResponse>(response, ERROR_MESSAGES.workspaceLoadFailed);
}

export async function createWorkspace(name: string): Promise<WorkspaceResponse> {
  const response = await apiFetch("/api/workspaces", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name })
  });
  return parseJsonOrThrow<WorkspaceResponse>(response, ERROR_MESSAGES.workspaceCreateFailed);
}

/** 워크스페이스 선택 화면에서 저장한 workspace id를 사용한다. */
function getWorkspaceId(): string {
  const selected = getSelectedWorkspaceId();
  if (!selected) {
    throw new Error(ERROR_MESSAGES.workspaceRequired);
  }
  return selected;
}

// 세션 선택 UI 도입 전까지 가장 최근 세션을 사용한다(없으면 생성).
// 워크스페이스가 바뀌면 캐시를 새로 만든다.
let sessionCache: { workspaceId: string; promise: Promise<string> } | null = null;

/** 로그아웃 시 이전 계정의 세션 캐시가 재사용되지 않도록 초기화한다. */
export function clearSessionCache() {
  sessionCache = null;
}

/** 현재 워크스페이스의 채팅 세션 목록을 가져온다. */
export async function fetchChatSessions(): Promise<ChatSessionListResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions`, { cache: "no-store" });
  return parseJsonOrThrow<ChatSessionListResponse>(response, ERROR_MESSAGES.chatSessionFailed);
}

async function resolveSessionId(workspaceId: string): Promise<string> {
  const listResponse = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions`, { cache: "no-store" });
  const list = await parseJsonOrThrow<ChatSessionListResponse>(listResponse, ERROR_MESSAGES.chatSessionFailed);
  const latest = list.sessions?.[0];
  if (latest) return latest.id;

  const createResponse = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({})
  });
  const created = await parseJsonOrThrow<ChatSessionResponse>(createResponse, ERROR_MESSAGES.chatSessionFailed);
  return created.id;
}

async function getSessionContext(): Promise<{ workspaceId: string; sessionId: string }> {
  const workspaceId = getWorkspaceId();
  if (sessionCache?.workspaceId !== workspaceId) {
    const promise = resolveSessionId(workspaceId).catch((error: unknown) => {
      sessionCache = null;
      throw error;
    });
    sessionCache = { workspaceId, promise };
  }
  return { workspaceId, sessionId: await sessionCache.promise };
}

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

export async function fetchBackendData(): Promise<BackendData> {
  const workspaceId = getWorkspaceId();
  const [documentsResponse, graphResponse] = await Promise.all([
    apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/documents`, { cache: "no-store" }),
    apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/wiki/graph`, { cache: "no-store" })
  ]);

  const documents = await parseJsonOrThrow<DocumentListResponse>(documentsResponse, ERROR_MESSAGES.documentsLoadFailed);
  const graph = await parseJsonOrThrow<WikiGraphResponse>(graphResponse, ERROR_MESSAGES.wikiGraphLoadFailed);
  return { documents: documents.documents ?? [], graph };
}

export async function queryWiki(question: string): Promise<QueryResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/query`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question })
    }
  );

  return parseJsonOrThrow<QueryResponse>(response, ERROR_MESSAGES.queryFailed);
}

export async function requestAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/agent/turn`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request)
  });
  return parseJsonOrThrow<AgentTurnResponse>(response, ERROR_MESSAGES.agentTurnFailed);
}

export async function fetchWikiPage(pageId: string): Promise<WikiPageDetailResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/wiki/pages/${encodeURIComponent(pageId)}`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<WikiPageDetailResponse>(response, ERROR_MESSAGES.wikiPageLoadFailed);
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

export type ChatWikiExportResponse = {
  exportDocumentId: string;
  status: string;
};

/** 현재 채팅 세션 내용을 위키 문서로 내보내기 전 미리보기 Markdown을 받는다. */
export async function fetchChatWikiExportPreview(): Promise<string> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/wiki/preview`,
    { method: "POST" }
  );
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.wikiExportFailed));
  }
  return response.text();
}

/** 미리보기를 수락하면 채팅 내용을 위키 문서로 내보낸다. */
export async function exportChatWiki(): Promise<ChatWikiExportResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/wiki`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ selection_mode: "full", pair_ids: [] })
    }
  );
  return parseJsonOrThrow<ChatWikiExportResponse>(response, ERROR_MESSAGES.wikiExportFailed);
}

export async function fetchChatMessages(): Promise<ChatMessagesResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<ChatMessagesResponse>(response, ERROR_MESSAGES.chatLoadFailed);
}
