import assert from "node:assert/strict";
import test from "node:test";

import { clearAuth, getAccessToken, saveAccessToken } from "../src/shared/lib/auth.ts";

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value)
  };
}

test("access token은 메모리에만 저장하고 기존 localStorage 토큰을 제거한다", () => {
  const localStorage = createStorage({
    "fruition.access_token": "legacy-access",
    "fruition.refresh_token": "legacy-refresh"
  });
  globalThis.window = { localStorage };

  saveAccessToken("memory-access");

  assert.equal(getAccessToken(), "memory-access");
  assert.equal(localStorage.getItem("fruition.access_token"), null);
  assert.equal(localStorage.getItem("fruition.refresh_token"), null);
});

test("클라이언트 인증 초기화 시 메모리 토큰과 워크스페이스 선택을 제거한다", () => {
  const localStorage = createStorage({ "fruition.workspace_id": "ws_1" });
  globalThis.window = { localStorage };
  saveAccessToken("memory-access");

  clearAuth();

  assert.equal(getAccessToken(), null);
  assert.equal(localStorage.getItem("fruition.workspace_id"), null);
});
