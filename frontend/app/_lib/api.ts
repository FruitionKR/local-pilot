import { getAccessToken, getSelectedWorkspaceId } from "./auth";
import type { BackendData, ChatMessagesResponse, ChatSessionListResponse, ChatSessionResponse, DocumentBlocksResponse, DocumentListResponse, DocumentUploadResponse, QueryResponse, WikiGraphResponse, WikiPageDetailResponse, WorkspaceListResponse, WorkspaceResponse } from "./types";

// 공통 에러 메시지 상수
const ERROR_MESSAGES = {
  loginRequired: "로그인이 필요합니다.",
  loginFailed: "로그인에 실패했습니다.",
  signupFailed: "회원가입에 실패했습니다.",
  workspaceRequired: "워크스페이스를 선택해주세요.",
  workspaceCreateFailed: "워크스페이스 생성에 실패했습니다.",
  uploadFailed: "문서 업로드에 실패했습니다.",
  queryFailed: "질의에 실패했습니다.",
  chatLoadFailed: "채팅 기록을 불러오지 못했습니다.",
  chatSessionFailed: "채팅 세션을 준비하지 못했습니다.",
  documentBlocksLoadFailed: "원본 문서 block을 불러오지 못했습니다.",
  documentsLoadFailed: "문서 목록을 불러오지 못했습니다.",
  wikiGraphLoadFailed: "Wiki graph를 불러오지 못했습니다.",
  wikiPageLoadFailed: "Wiki page를 불러오지 못했습니다.",
  workspaceLoadFailed: "워크스페이스를 불러오지 못했습니다."
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

export async function fetchBackendData(): Promise<BackendData> {
  const workspaceId = getWorkspaceId();
  const [documentsResponse, graphResponse] = await Promise.all([
    apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/documents`, { cache: "no-store" }),
    apiFetch("/api/wiki/graph", { cache: "no-store" })
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

export async function fetchWikiPage(pageId: string): Promise<WikiPageDetailResponse> {
  const response = await apiFetch(`/api/wiki/pages/${encodeURIComponent(pageId)}`, { cache: "no-store" });

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

export async function fetchChatMessages(): Promise<ChatMessagesResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<ChatMessagesResponse>(response, ERROR_MESSAGES.chatLoadFailed);
}
