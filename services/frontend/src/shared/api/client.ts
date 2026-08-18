import { getAccessToken, getRefreshToken, getSelectedWorkspaceId, saveTokens } from "@/shared/lib/auth";

// 공통 에러 메시지 상수
export const ERROR_MESSAGES = {
  loginRequired: "로그인이 필요합니다.",
  loginFailed: "로그인에 실패했습니다.",
  signupFailed: "회원가입에 실패했습니다.",
  workspaceRequired: "워크스페이스를 선택해주세요.",
  workspaceCreateFailed: "워크스페이스 생성에 실패했습니다.",
  uploadFailed: "문서 업로드에 실패했습니다.",
  documentDeleteFailed: "문서 삭제에 실패했습니다.",
  documentRenameFailed: "문서 이름 변경에 실패했습니다.",
  documentConvertFailed: "Markdown 변환 요청에 실패했습니다.",
  noteDraftLoadFailed: "노트 draft를 불러오지 못했습니다.",
  noteDraftSaveFailed: "노트 draft를 저장하지 못했습니다.",
  documentOriginalLoadFailed: "원본 문서를 불러오지 못했습니다.",
  queryFailed: "질의에 실패했습니다.",
  aiModelsLoadFailed: "AI 모델 목록을 불러오지 못했습니다.",
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
export async function parseErrorResponse(response: Response, fallback: string): Promise<string> {
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

// 동시 401들이 refresh를 중복 호출하지 않도록 진행 중인 재발급을 공유한다.
let refreshPromise: Promise<boolean> | null = null;

async function tryRefreshTokens(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const response = await fetch("/api/auth/refresh", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refresh_token: refreshToken })
        });
        if (!response.ok) return false;
        const body = await response.json() as { access_token?: string; refresh_token?: string };
        if (!body.access_token || !body.refresh_token) return false;
        saveTokens(body.access_token, body.refresh_token);
        return true;
      } catch {
        return false;
      }
    })().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function fetchWithToken(path: string, init?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  const headers = new Headers(init?.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(path, { ...init, headers });
}

/**
 * Bearer 토큰을 부착하는 공통 fetch.
 * access token 만료(401) 시 refresh token으로 재발급을 1회 시도하고 원요청을 재시도한다.
 * 재발급까지 실패하면 로그인 필요 에러를 던진다.
 */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetchWithToken(path, init);
  if (response.status !== 401) return response;
  // 인증 엔드포인트 자체의 401(비밀번호 오류 등)은 재발급 대상이 아니다.
  if (!path.startsWith("/api/auth/") && await tryRefreshTokens()) {
    const retried = await fetchWithToken(path, init);
    if (retried.status !== 401) return retried;
  }
  throw new Error(ERROR_MESSAGES.loginRequired);
}

export async function parseJsonOrThrow<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, fallback));
  }
  return response.json() as Promise<T>;
}

/** 워크스페이스 선택 화면에서 저장한 workspace id를 사용한다. */
export function getWorkspaceId(): string {
  const selected = getSelectedWorkspaceId();
  if (!selected) {
    throw new Error(ERROR_MESSAGES.workspaceRequired);
  }
  return selected;
}
