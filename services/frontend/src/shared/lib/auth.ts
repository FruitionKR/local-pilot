// access token은 페이지 수명 동안 메모리에만 보관한다.
// refresh token은 백엔드가 발급하는 HttpOnly 쿠키에 있어 JavaScript에서 읽을 수 없다.
const ACCESS_TOKEN_STORAGE_KEY = "fruition.access_token";
const REFRESH_TOKEN_STORAGE_KEY = "fruition.refresh_token";
const WORKSPACE_STORAGE_KEY = "fruition.workspace_id";

let accessToken: string | null = null;

function readStorage(key: string): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(key);
}

export function getAccessToken(): string | null {
  removeLegacyStoredTokens();
  return accessToken;
}

export function saveAccessToken(token: string) {
  removeLegacyStoredTokens();
  accessToken = token;
}

export function clearAuth() {
  accessToken = null;
  removeLegacyStoredTokens();
  window.localStorage.removeItem(WORKSPACE_STORAGE_KEY);
}

function removeLegacyStoredTokens() {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
}

export function getSelectedWorkspaceId(): string | null {
  return readStorage(WORKSPACE_STORAGE_KEY);
}

export function setSelectedWorkspaceId(workspaceId: string) {
  window.localStorage.setItem(WORKSPACE_STORAGE_KEY, workspaceId);
}
