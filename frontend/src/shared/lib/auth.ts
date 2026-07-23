// 임시 로그인 흐름용 토큰/워크스페이스 선택 저장 헬퍼.
// 정식 인증 화면 도입 시 교체 대상.
// 알려진 트레이드오프: 토큰을 localStorage에 저장하므로 XSS 발생 시 탈취 가능.
// 정식 도입 시 백엔드와 협의해 httpOnly 쿠키 방식으로 전환한다.
// TODO: refresh token은 저장만 하고 아직 갱신 흐름이 없다. apiFetch 401 시
// refresh 재발급을 구현하기 전까지는 토큰 만료 시 재로그인이 필요하다.
const ACCESS_TOKEN_STORAGE_KEY = "fruition.access_token";
const REFRESH_TOKEN_STORAGE_KEY = "fruition.refresh_token";
const WORKSPACE_STORAGE_KEY = "fruition.workspace_id";

function readStorage(key: string): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(key);
}

export function getAccessToken(): string | null {
  return readStorage(ACCESS_TOKEN_STORAGE_KEY);
}

export function saveTokens(accessToken: string, refreshToken: string) {
  window.localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, accessToken);
  window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
}

export function clearAuth() {
  window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(WORKSPACE_STORAGE_KEY);
}

export function getSelectedWorkspaceId(): string | null {
  return readStorage(WORKSPACE_STORAGE_KEY);
}

export function setSelectedWorkspaceId(workspaceId: string) {
  window.localStorage.setItem(WORKSPACE_STORAGE_KEY, workspaceId);
}
