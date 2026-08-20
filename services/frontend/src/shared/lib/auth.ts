// access token은 페이지 수명 동안 메모리에만 보관한다.
// refresh token은 백엔드가 발급하는 HttpOnly 쿠키에 있어 JavaScript에서 읽을 수 없다.
const ACCESS_TOKEN_STORAGE_KEY = "fruition.access_token";
const REFRESH_TOKEN_STORAGE_KEY = "fruition.refresh_token";
const WORKSPACE_STORAGE_KEY = "fruition.workspace_id";
const AUTH_REFRESH_LOCK_NAME = "fruition.auth.refresh";
const PUBLIC_AUTH_PATHS = new Set([
  "/",
  "/forgot-password",
  "/login",
  "/oauth/callback",
  "/reset-password",
  "/signup",
  "/signup/verify"
]);

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

export function isPublicAuthPath(pathname: string): boolean {
  return PUBLIC_AUTH_PATHS.has(pathname);
}

/** 같은 origin의 여러 탭이 refresh 쿠키를 동시에 회전하지 않도록 직렬화한다. */
export async function withAuthRefreshLock<T>(refresh: () => Promise<T>): Promise<T> {
  if (typeof navigator === "undefined" || !navigator.locks) return refresh();
  return await navigator.locks.request(AUTH_REFRESH_LOCK_NAME, refresh);
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
