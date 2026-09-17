import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("@/")) {
      return nextResolve(new URL(`../src/${specifier.slice(2)}.ts`, import.meta.url).href, context);
    }
    return nextResolve(specifier, context);
  }
});

const { cancelQueryRun, QueryCancelledError, runQueryStream } = await import("../src/entities/wiki/api/wiki.ts");
const { setActiveChatSession } = await import("../src/entities/chat/api/chat.ts");
const { saveAccessToken } = await import("../src/shared/lib/auth.ts");
const run = { workspaceId: "ws_test", requestId: "query_test" };
const model = { provider: "gemini", model: "test-model" };

function setup(t) {
  globalThis.window = { localStorage: { getItem: () => run.workspaceId, removeItem() {} } };
  t.after(() => { delete globalThis.window; });
  saveAccessToken("query-test-access");
  setActiveChatSession("session_test");
}

function events(text) {
  return new Response(text, { headers: { "Content-Type": "text/event-stream" } });
}

test("취소는 생성한 작업의 workspace와 ID를 사용하고 복구 완료까지 조회한다", async (t) => {
  setup(t);
  t.mock.method(globalThis, "setTimeout", (callback) => { callback(); return 0; });
  const calls = [];
  const states = ["cancel_requested", "rolling_back", "cancelled"];
  const signal = new AbortController().signal;
  t.mock.method(globalThis, "fetch", async (path, init) => {
    calls.push([path, init.method ?? "GET"]);
    assert.equal(init.signal, signal);
    assert.equal(init.headers.get("Authorization"), "Bearer query-test-access");
    return Response.json({ id: run.requestId, status: states.shift(), error_code: "" });
  });
  await cancelQueryRun(run, signal);
  assert.deepEqual(calls, [
    ["/api/workspaces/ws_test/ai/tasks/query_test/cancel", "POST"],
    ["/api/workspaces/ws_test/ai/tasks/query_test", "GET"],
    ["/api/workspaces/ws_test/ai/tasks/query_test", "GET"]
  ]);
});

test("복구 실패는 취소 완료로 처리하지 않고 같은 작업으로 재시도한다", async (t) => {
  setup(t);
  let attempts = 0;
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.equal(path, "/api/workspaces/ws_test/ai/tasks/query_test/cancel");
    assert.equal(init.method, "POST");
    return Response.json({ status: ++attempts === 1 ? "rollback_failed" : "cancelled", error_code: "rollback_conflict" });
  });
  const signal = new AbortController().signal;
  await assert.rejects(cancelQueryRun(run, signal), /복구하지 못했습니다/);
  await cancelQueryRun(run, signal);
  assert.equal(attempts, 2);
});

test("취소 권한 오류는 성공으로 처리하지 않는다", async (t) => {
  setup(t);
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: { message: "취소 권한이 없습니다." } }, { status: 403 }));
  await assert.rejects(cancelQueryRun(run, new AbortController().signal), /취소 권한/);
});

for (const terminal of ["query.cancelled", "query.completed", "query.failed"]) {
  test(`SSE ${terminal}을 구분하고 생성된 run을 스트림 구독 전에 전달한다`, async (t) => {
    setup(t);
    let started = null;
    let statusCalls = 0;
    const stages = [];
    const signal = new AbortController().signal;
    t.mock.method(globalThis, "fetch", async (path, init) => {
      assert.equal(init.signal, signal);
      if (init.method === "POST") return Response.json({ request_id: run.requestId, status: "queued" });
      assert.deepEqual(started, run);
      if (path.endsWith("/events")) {
        return events(`event: query.log\ndata: {"stage":"search","message":"검색 중","sequence":1}\n\nevent: ${terminal}\ndata: {}\n\n`);
      }
      statusCalls++;
      return Response.json({ status: "completed", result: { answer: "답변" } });
    });
    const result = runQueryStream("질문", model, { onStage: (stage) => stages.push(stage), onStarted: (value) => { started = value; }, signal });
    if (terminal === "query.cancelled") await assert.rejects(result, QueryCancelledError);
    else if (terminal === "query.failed") await assert.rejects(result, /질의에 실패/);
    else assert.equal((await result).answer, "답변");
    assert.equal(statusCalls, terminal === "query.completed" ? 1 : 0);
    assert.equal(stages.length, 1);
  });
}

test("SSE 연결 종료 후 상태 조회가 cancelled이면 실패 알림으로 바꾸지 않는다", async (t) => {
  setup(t);
  t.mock.method(globalThis, "fetch", async (path, init) => {
    if (init.method === "POST") return Response.json({ request_id: run.requestId });
    if (path.endsWith("/events")) return events(": heartbeat\n\n");
    return Response.json({ status: "cancelled", result: null });
  });
  await assert.rejects(runQueryStream("질문", model, { onStage() {} }), QueryCancelledError);
});
