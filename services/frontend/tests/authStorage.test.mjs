import assert from "node:assert/strict";
import test from "node:test";

import {
  clearAuth,
  getAccessToken,
  isPublicAuthPath,
  saveAccessToken,
  withAuthRefreshLock
} from "../src/shared/lib/auth.ts";

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

test("인증 화면과 랜딩 화면은 공개 경로로 판별한다", () => {
  assert.equal(isPublicAuthPath("/"), true);
  assert.equal(isPublicAuthPath("/login"), true);
  assert.equal(isPublicAuthPath("/signup/verify"), true);
  assert.equal(isPublicAuthPath("/home"), false);
  assert.equal(isPublicAuthPath("/workspaces"), false);
});

test("refresh 작업은 origin 공유 Web Lock 안에서 실행한다", async () => {
  const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, "navigator");
  const events = [];
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: {
      locks: {
        request: async (name, refresh) => {
          events.push(`lock:${name}`);
          return refresh();
        }
      }
    }
  });

  try {
    const result = await withAuthRefreshLock(async () => {
      events.push("refresh");
      return true;
    });

    assert.equal(result, true);
    assert.deepEqual(events, ["lock:fruition.auth.refresh", "refresh"]);
  } finally {
    if (originalNavigator) {
      Object.defineProperty(globalThis, "navigator", originalNavigator);
    } else {
      delete globalThis.navigator;
    }
  }
});
