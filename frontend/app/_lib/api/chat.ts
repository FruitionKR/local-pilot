import { apiFetch, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "./client";
import type { ChatMessagesResponse, ChatSessionListResponse, ChatSessionResponse } from "../types";

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

/** 워크스페이스별 최근 채팅 세션 컨텍스트를 확보한다. query/export 등에서 공유한다. */
export async function getSessionContext(): Promise<{ workspaceId: string; sessionId: string }> {
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
