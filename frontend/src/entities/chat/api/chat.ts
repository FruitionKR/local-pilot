import { apiFetch, parseErrorResponse, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { ChatMessagesResponse, ChatSessionListResponse, ChatSessionResponse } from "@/entities/chat/model/chat";

// 세션 선택 UI 도입 전까지 가장 최근 세션을 사용한다(없으면 생성).
// 워크스페이스가 바뀌면 캐시를 새로 만든다.
let sessionCache: { workspaceId: string; promise: Promise<string> } | null = null;

// 드롭다운에서 사용자가 명시적으로 고른 세션. 있으면 자동 최신 선택보다 우선한다.
let selectedSession: { workspaceId: string; sessionId: string } | null = null;

/** 드롭다운에서 고른 세션을 활성 세션으로 지정한다. query·messages·export가 이 세션을 대상으로 한다. */
export function setActiveChatSession(sessionId: string) {
  selectedSession = { workspaceId: getWorkspaceId(), sessionId };
}

/** 로그아웃 시 이전 계정의 세션 캐시가 재사용되지 않도록 초기화한다. */
export function clearSessionCache() {
  sessionCache = null;
  selectedSession = null;
}

/** 새 채팅 세션을 만든다. 생성된 세션을 반환한다(활성 전환은 호출측에서 처리). */
export async function createChatSession(title?: string): Promise<ChatSessionResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(title ? { title } : {})
  });
  return parseJsonOrThrow<ChatSessionResponse>(response, ERROR_MESSAGES.chatSessionFailed);
}

/** 채팅 세션을 삭제한다. 삭제한 세션이 활성 세션이면 캐시도 정리한다. */
export async function deleteChatSession(sessionId: string): Promise<void> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}`,
    { method: "DELETE" }
  );
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.chatSessionFailed));
  }
  if (selectedSession?.sessionId === sessionId) selectedSession = null;
  if (sessionCache?.workspaceId === workspaceId) sessionCache = null;
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

/** 워크스페이스별 최근 채팅 세션 컨텍스트를 확보한다. query/export 등에서 공유한다. */
export async function getSessionContext(): Promise<{ workspaceId: string; sessionId: string }> {
  const workspaceId = getWorkspaceId();
  if (selectedSession?.workspaceId === workspaceId) {
    return { workspaceId, sessionId: selectedSession.sessionId };
  }
  if (sessionCache?.workspaceId !== workspaceId) {
    const promise = resolveSessionId(workspaceId).catch((error: unknown) => {
      sessionCache = null;
      throw error;
    });
    sessionCache = { workspaceId, promise };
  }
  return { workspaceId, sessionId: await sessionCache.promise };
}

/** 세션 드롭다운에서 현재 사용 중인 채팅을 표시한다. */
export async function fetchCurrentChatSessionId(): Promise<string> {
  return (await getSessionContext()).sessionId;
}

export async function fetchChatMessages(): Promise<ChatMessagesResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<ChatMessagesResponse>(response, ERROR_MESSAGES.chatLoadFailed);
}
