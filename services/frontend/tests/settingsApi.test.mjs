import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

// Node 테스트에서도 앱의 TypeScript 경로 별칭을 같은 소스로 해석한다.
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("@/")) {
      return nextResolve(new URL(`../src/${specifier.slice(2)}.ts`, import.meta.url).href, context);
    }
    return nextResolve(specifier, context);
  }
});

const { changePassword, updateDisplayName, requestEmailVerification, confirmEmailVerification, changeEmail, fetchSessions, revokeSession } = await import("../src/entities/user/api/auth.ts");
const { updateWorkspaceIcon, uploadWorkspaceIcon, fetchWorkspaceIcon } = await import("../src/entities/workspace/api/workspace.ts");
const { fetchMembers, changeMemberRole, inviteMember, removeMember } = await import("../src/entities/workspace/api/members.ts");
const { saveAccessToken } = await import("../src/shared/lib/auth.ts");
const { fetchInvitation, acceptInvitation } = await import("../src/entities/workspace/api/invitations.ts");
const { loginWithEmail, exchangeOAuthCode, loginWithMfa, fetchMfaStatus, registerMfa, activateMfa, disableMfa } = await import("../src/entities/user/api/auth.ts");

test("닉네임 저장은 인증된 PATCH와 서버 프로필 응답을 사용한다", async (t) => {
  saveAccessToken("test-access");
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.equal(path, "/api/auth/me");
    assert.equal(init.method, "PATCH");
    assert.equal(init.headers.get("Authorization"), "Bearer test-access");
    assert.deepEqual(JSON.parse(init.body), { display_name: "새 이름" });
    return Response.json({ id: "user_test", display_name: "새 이름" });
  });
  assert.equal((await updateDisplayName("새 이름")).display_name, "새 이름");
});

test("잘못된 현재 비밀번호는 refresh 없이 서버 오류를 표시한다", async (t) => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async () => {
    calls++;
    return Response.json({ error: { code: "INVALID_CREDENTIALS", message: "현재 비밀번호 불일치" } }, { status: 401 });
  });
  await assert.rejects(changePassword("wrong", "new-password"), /현재 비밀번호 불일치/);
  assert.equal(calls, 1);
});

for (const mismatch of [false, true]) {
  test(`만료된 토큰 갱신 후 비밀번호 변경 결과를 처리한다: ${mismatch ? "불일치 오류" : "204 성공"}`, async (t) => {
    let calls = 0;
    t.mock.method(globalThis, "fetch", async (path, init) => {
      calls++;
      if (calls === 1) return new Response(null, { status: 401 });
      if (calls === 2) {
        assert.equal(path, "/api/auth/refresh");
        return Response.json({ access_token: "refreshed-test-access" });
      }
      assert.equal(path, "/api/auth/me/password");
      assert.equal(init.method, "PUT");
      assert.equal(init.headers.get("Authorization"), "Bearer refreshed-test-access");
      assert.deepEqual(JSON.parse(init.body), { current_password: "current-password", new_password: "new-password" });
      return mismatch
        ? Response.json({ error: { code: "INVALID_CREDENTIALS", message: "현재 비밀번호 불일치" } }, { status: 401 })
        : new Response(null, { status: 204 });
    });
    if (mismatch) await assert.rejects(changePassword("current-password", "new-password"), /현재 비밀번호 불일치/);
    else await changePassword("current-password", "new-password");
    assert.equal(calls, 3);
  });
}

test("멤버 조회·역할 변경·초대·제거 계약과 204 응답을 처리한다", async (t) => {
  const calls = [];
  const member = { user_id: "user_test", role: "MEMBER" };
  t.mock.method(globalThis, "fetch", async (path, init) => {
    calls.push([path, init.method ?? "GET", init.body ? JSON.parse(init.body) : null]);
    if (init.method === "DELETE") return new Response(null, { status: 204 });
    return Response.json(init.method === "PATCH" ? { ...member, role: "OWNER" } : { members: [member] });
  });
  assert.deepEqual(await fetchMembers("ws_test"), [member]);
  assert.equal((await changeMemberRole("ws_test", "user_test", "OWNER")).role, "OWNER");
  await inviteMember("ws_test", "member@example.test", "MEMBER");
  await removeMember("ws_test", "user_test");
  assert.deepEqual(calls, [
    ["/api/workspaces/ws_test/members", "GET", null],
    ["/api/workspaces/ws_test/members/user_test", "PATCH", { role: "OWNER" }],
    ["/api/workspaces/ws_test/invitations", "POST", { email: "member@example.test", role: "MEMBER" }],
    ["/api/workspaces/ws_test/members/user_test", "DELETE", null]
  ]);
});

test("멤버 권한 거부는 성공으로 처리하지 않는다", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: { message: "OWNER 권한이 필요합니다." } }, { status: 403 }));
  await assert.rejects(changeMemberRole("ws_test", "user_test", "OWNER"), /OWNER 권한/);
  await assert.rejects(removeMember("ws_test", "user_test"), /OWNER 권한/);
});

test("초대 확인은 공개 조회, 수락은 인증 요청으로 처리한다", async (t) => {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (path, init) => {
    calls.push([path, init.method ?? "GET", init.headers?.get("Authorization")]);
    return Response.json({ workspace_id: "ws_invited" });
  });
  saveAccessToken("invitation-test-access");
  await fetchInvitation("test/token");
  assert.equal((await acceptInvitation("test/token")).workspace_id, "ws_invited");
  assert.deepEqual(calls, [
    ["/api/invitations/test%2Ftoken", "GET", undefined],
    ["/api/invitations/test%2Ftoken/accept", "POST", "Bearer invitation-test-access"]
  ]);
});

test("이메일 변경은 전용 목적의 인증번호를 확인한 뒤 인증된 PUT으로 확정한다", async (t) => {
  saveAccessToken("email-test-access");
  const calls = [];
  t.mock.method(globalThis, "fetch", async (path, init) => {
    calls.push([path, init.method, JSON.parse(init.body)]);
    if (path === "/api/auth/email-verifications") return Response.json({ verification_id: "verify_test", retry_after: 60, expires_in: 300 });
    if (path.endsWith("/confirm")) return Response.json({ verification_token: "verified-test-token", expires_in: 300 });
    assert.equal(init.headers.get("Authorization"), "Bearer email-test-access");
    return Response.json({ id: "user_test", email: "new@example.test" });
  });
  const sent = await requestEmailVerification("new@example.test", "email_change");
  const confirmed = await confirmEmailVerification(sent.verification_id, "123456");
  assert.equal((await changeEmail("new@example.test", confirmed.verification_token)).email, "new@example.test");
  assert.deepEqual(calls, [
    ["/api/auth/email-verifications", "POST", { email: "new@example.test", purpose: "email_change" }],
    ["/api/auth/email-verifications/verify_test/confirm", "POST", { code: "123456" }],
    ["/api/auth/me/email", "PUT", { new_email: "new@example.test", verification_token: "verified-test-token" }]
  ]);
});

test("이메일 중복과 만료된 인증은 성공으로 처리하지 않는다", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: { message: "이미 사용 중인 이메일입니다." } }, { status: 409 }));
  await assert.rejects(changeEmail("used@example.test", "test-token"), /이미 사용 중/);
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: { message: "인증이 만료되었습니다." } }, { status: 400 }));
  await assert.rejects(confirmEmailVerification("expired", "123456"), /인증이 만료/);
});

test("세션 목록은 현재 기기와 알 수 없는 기기를 보존하고 숫자 ID로 폐기한다", async (t) => {
  const sessions = [{ session_id: 42, user_agent: null, current: true, created_at: "2026-09-08T00:00:00Z", expires_at: "2026-09-22T00:00:00Z" }];
  t.mock.method(globalThis, "fetch", async (path, init) => {
    if (init.method === "DELETE") {
      assert.equal(path, "/api/auth/me/sessions/42");
      return new Response(null, { status: 204 });
    }
    assert.equal(path, "/api/auth/me/sessions");
    return Response.json({ sessions });
  });
  assert.deepEqual(await fetchSessions(), sessions);
  await revokeSession(42);
});

test("아이콘 이모지·초기화·multipart 업로드·인증 이미지 조회 계약을 따른다", async (t) => {
  saveAccessToken("icon-test-access");
  const file = new File([new Uint8Array([137, 80, 78, 71])], "icon.png", { type: "image/png" });
  const calls = [];
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.equal(init.headers.get("Authorization"), "Bearer icon-test-access");
    calls.push([path, init.method ?? "GET"]);
    if (init.body instanceof FormData) {
      assert.equal(init.headers.has("Content-Type"), false);
      assert.equal(init.body.get("file").name, "icon.png");
      assert.equal(init.body.get("file").size, file.size);
    } else if (init.body) {
      assert.deepEqual(JSON.parse(init.body), { icon_emoji: calls.length === 1 ? "📁" : null });
    } else {
      return new Response(file, { headers: { "Content-Type": "image/png" } });
    }
    return Response.json({ id: "ws_test", icon_emoji: null, icon_url: null });
  });
  await updateWorkspaceIcon("ws_test", "📁");
  await updateWorkspaceIcon("ws_test", null);
  await uploadWorkspaceIcon("ws_test", file);
  const blob = await fetchWorkspaceIcon("ws_test");
  assert.equal(blob.type, "image/png");
  assert.equal(blob.size, file.size);
  assert.deepEqual(calls, [
    ["/api/workspaces/ws_test/icon", "PUT"],
    ["/api/workspaces/ws_test/icon", "PUT"],
    ["/api/workspaces/ws_test/icon/image", "PUT"],
    ["/api/workspaces/ws_test/icon/image", "GET"]
  ]);
});

test("아이콘 조회 실패와 이미 폐기된 세션은 오류로 처리한다", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: { message: "찾을 수 없습니다." } }, { status: 404 }));
  await assert.rejects(fetchWorkspaceIcon("missing"), /찾을 수 없습니다/);
  await assert.rejects(revokeSession(42), /찾을 수 없습니다/);
});

test("아이콘 프록시는 document-svc 기본 경로보다 먼저 access-svc로 전달한다", async () => {
  const { default: config } = await import("../next.config.mjs");
  const rules = await config.rewrites();
  const iconIndex = rules.findIndex((rule) => rule.source === "/api/workspaces/:wid/icon/:path*");
  const defaultIndex = rules.findIndex((rule) => rule.source === "/api/:path*");
  assert.ok(iconIndex >= 0 && iconIndex < defaultIndex);
  assert.equal(rules[iconIndex].destination, `${process.env.NEXT_PUBLIC_ACCESS_URL || "http://localhost:8081"}/api/workspaces/:wid/icon/:path*`);
});

for (const oauth of [false, true]) {
  test(`${oauth ? "OAuth" : "이메일"} 로그인은 MFA 요구 응답을 유지하고 코드 확인 후 토큰을 받는다`, async (t) => {
    const calls = [];
    t.mock.method(globalThis, "fetch", async (path, init) => {
      calls.push([path, JSON.parse(init.body)]);
      return path === "/api/auth/login/mfa"
        ? Response.json({ access_token: "mfa-verified-test-access" })
        : Response.json({ mfa_required: true, mfa_token: "test-challenge" });
    });
    const first = oauth ? await exchangeOAuthCode("test-oauth-code") : await loginWithEmail("mfa@example.test", "test-password");
    assert.equal(first.mfa_required, true);
    assert.equal(first.access_token, undefined);
    assert.equal((await loginWithMfa(first.mfa_token, "RECOVERYTESTCODE")).access_token, "mfa-verified-test-access");
    assert.deepEqual(calls, [
      oauth ? ["/api/auth/oauth/exchange", { code: "test-oauth-code" }] : ["/api/auth/login", { email: "mfa@example.test", password: "test-password" }],
      ["/api/auth/login/mfa", { mfa_token: "test-challenge", code: "RECOVERYTESTCODE" }]
    ]);
  });
}

test("MFA 상태·등록·활성화·복구 코드 해제 계약을 따른다", async (t) => {
  saveAccessToken("mfa-settings-test-access");
  const calls = [];
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.equal(init.headers.get("Authorization"), "Bearer mfa-settings-test-access");
    calls.push([path, init.method ?? "GET", init.body ? JSON.parse(init.body) : null]);
    if (path.endsWith("/activate") || init.method === "DELETE") return new Response(null, { status: 204 });
    return Response.json(init.method === "POST"
      ? { secret: "TESTSECRET", otpauth_uri: "otpauth://totp/test", recovery_codes: ["TESTRECOVERY"] }
      : { enabled: false, activated_at: null, remaining_recovery_codes: 0 });
  });
  assert.equal((await fetchMfaStatus()).enabled, false);
  assert.deepEqual((await registerMfa()).recovery_codes, ["TESTRECOVERY"]);
  await activateMfa("123456");
  await disableMfa("TESTRECOVERY");
  assert.deepEqual(calls, [
    ["/api/auth/me/mfa", "GET", null],
    ["/api/auth/me/mfa", "POST", null],
    ["/api/auth/me/mfa/activate", "POST", { code: "123456" }],
    ["/api/auth/me/mfa", "DELETE", { code: "TESTRECOVERY" }]
  ]);
});

for (const mutate of [activateMfa, disableMfa]) {
  test(`${mutate.name}: 잘못된 MFA 코드는 refresh나 중복 검증 없이 오류를 표시한다`, async (t) => {
    let calls = 0;
    t.mock.method(globalThis, "fetch", async () => {
      calls++;
      return Response.json({ error: { code: "INVALID_MFA_CODE", message: "인증 코드가 올바르지 않습니다." } }, { status: 401 });
    });
    await assert.rejects(mutate("000000"), /인증 코드가 올바르지/);
    assert.equal(calls, 1);
  });
}

test("인증 토큰 만료는 갱신하고 이후 잘못된 MFA 코드는 그대로 표시한다", async (t) => {
  let calls = 0;
  t.mock.method(globalThis, "fetch", async (path) => {
    calls++;
    if (calls === 1) return new Response(null, { status: 401 });
    if (calls === 2) {
      assert.equal(path, "/api/auth/refresh");
      return Response.json({ access_token: "refreshed-mfa-test-access" });
    }
    return Response.json({ error: { code: "INVALID_MFA_CODE", message: "인증 코드 오류" } }, { status: 401 });
  });
  await assert.rejects(activateMfa("000000"), /인증 코드 오류/);
  assert.equal(calls, 3);
});

for (const [status, code] of [[400, "INVALID_MFA_CHALLENGE"], [401, "INVALID_MFA_CODE"], [429, "MFA_RATE_LIMITED"]]) {
  test(`MFA 로그인 ${code}는 자동 재시도 없이 서버 오류를 전달한다`, async (t) => {
    let calls = 0;
    t.mock.method(globalThis, "fetch", async () => {
      calls++;
      return Response.json({ error: { code, message: code } }, { status });
    });
    await assert.rejects(loginWithMfa("test-challenge", "000000"), new RegExp(code));
    assert.equal(calls, 1);
  });
}
