import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import { agentPlanStatusLabel, buildPlanPreviewTree, canApproveAgentPlan, describePlanOperations, shouldPollAgentPlan } from "../src/features/agent-chat/lib/agentPlan.ts";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("@/")) return nextResolve(new URL(`../src/${specifier.slice(2)}.ts`, import.meta.url).href, context);
    return nextResolve(specifier, context);
  }
});
const { fetchAgentPlanRun, decideAgentPlan } = await import("../src/features/agent-chat/api/agentPlan.ts");
const plan = { id: "plan-1", version: 2, operation_hash: "a".repeat(64), status: "awaiting_approval", operations: [] };

test("채팅 기록의 turn ID에서 실제 승인 대상 run을 조회한다", async (t) => {
  const paths = [];
  t.mock.method(globalThis, "fetch", async (path) => {
    paths.push(path);
    return Response.json(paths.length === 1
      ? { status: "completed", result: { run_id: "autonomous-1" } }
      : { id: "autonomous-1", status: "awaiting_approval", plan });
  });
  assert.equal((await fetchAgentPlanRun("ws_test", "turn-1")).plan.version, 2);
  assert.deepEqual(paths, ["/api/workspaces/ws_test/agent/turn/turn-1", "/api/workspaces/ws_test/agent/runs/autonomous-1"]);
});

test("승인은 화면에서 검토한 버전·해시를 전송하고 충돌을 성공으로 처리하지 않는다", async (t) => {
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.equal(path, "/api/workspaces/ws_test/agent/runs/autonomous-1/approve");
    assert.equal(init.method, "POST");
    assert.ok(init.headers.get("Idempotency-Key"));
    assert.deepEqual(JSON.parse(init.body), { plan_version: 2, operation_hash: "a".repeat(64) });
    return Response.json({ detail: "계획이 변경되었습니다." }, { status: 409 });
  });
  await assert.rejects(decideAgentPlan("ws_test", "autonomous-1", "approve", plan), /계획이 변경/);
});

test("거절은 승인 endpoint를 호출하지 않는다", async (t) => {
  t.mock.method(globalThis, "fetch", async (path, init) => {
    assert.ok(path.endsWith("/reject"));
    assert.equal(init.body, undefined);
    return Response.json({ status: "rejected", plan: null });
  });
  assert.equal((await decideAgentPlan("ws_test", "autonomous-1", "reject", plan)).status, "rejected");
});

test("계획 준비 중에는 폴링하고 누락된 실행 ID는 오류로 알린다", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json({ status: "queued", result: null }));
  assert.equal(await fetchAgentPlanRun("ws_test", "turn-1"), null);
  t.mock.method(globalThis, "fetch", async () => Response.json({ status: "completed", result: {} }));
  await assert.rejects(fetchAgentPlanRun("ws_test", "turn-1"), /계획 정보를 찾을 수 없습니다/);
});

test("승인 대기를 완료와 구분하고 종료·충돌 상태에서는 승인하지 않는다", () => {
  assert.equal(agentPlanStatusLabel("awaiting_approval"), "승인 대기");
  assert.equal(canApproveAgentPlan({ status: "awaiting_approval", plan }), true);
  for (const status of ["completed", "failed", "partial_failed", "conflicted", "rejected", "cancelled", "clarification_required"]) {
    assert.equal(canApproveAgentPlan({ status, plan }), false);
    assert.equal(shouldPollAgentPlan(status), false);
  }
  assert.equal(canApproveAgentPlan({ status: "awaiting_approval", plan: { ...plan, status: "approved" } }), false);
  assert.equal(shouldPollAgentPlan("executing"), true);
});

test("작업 순서대로 새 폴더 참조와 문서 이동 경로를 이름으로 표시한다", () => {
  const operations = [
    { id: "move", sequence: 3, tool_name: "move_document", target_id: "doc-1", arguments: { folder_id: { $operation_result: "child", field: "id" } } },
    { id: "child", sequence: 2, tool_name: "create_folder", arguments: { name: "관수", parent_folder_id: { $operation_result: "parent", field: "id" } } },
    { id: "parent", sequence: 1, tool_name: "create_folder", arguments: { name: "재배 관리", parent_folder_id: null } }
  ];
  const rows = describePlanOperations({ ...plan, operations }, [{ id: "old", name: "미분류", children: [{ id: "doc-1", name: "토마토.pdf" }] }]);
  assert.deepEqual(rows.map((row) => row.description), [
    "폴더 생성: 재배 관리", "폴더 생성: 재배 관리 / 관수", "미분류 / 토마토.pdf → 재배 관리 / 관수"
  ]);
});

test("새 폴더 아래에 이동 문서를 묶고 한글 표시만 정규화한다", () => {
  const filename = "운영 가이드.md".normalize("NFD");
  const tree = [{ id: "doc-1", name: filename }, { id: "other", name: "무관한 문서" }];
  const operations = [
    { id: "move", sequence: 2, tool_name: "move_document", target_id: "doc-1", arguments: { folder_id: { $operation_result: "create", field: "id" } } },
    { id: "create", sequence: 1, tool_name: "create_folder", arguments: { name: "운영", parent_folder_id: null } }
  ];
  const preview = buildPlanPreviewTree({ ...plan, operations }, tree);
  assert.equal(preview.summary, "폴더 1개 생성 · 문서 1개 이동");
  assert.equal(preview.nodes.length, 1);
  assert.equal(preview.nodes[0].name, "운영");
  assert.equal(preview.nodes[0].isNew, true);
  assert.equal(preview.nodes[0].children[0].name, "운영 가이드.md");
  assert.equal(tree[0].name, filename);
});

test("이름이 같은 기존 폴더도 ID로 구분하고 중첩 폴더·최상위 이동을 표시한다", () => {
  const tree = [
    { id: "a", name: "자료", children: [{ id: "doc-1", name: "문서.md" }] },
    { id: "b", name: "자료", children: [] }
  ];
  const operations = [
    { id: "create", sequence: 1, tool_name: "create_folder", arguments: { name: "분류", parent_folder_id: "b" } },
    { id: "move", sequence: 2, tool_name: "move_document", target_id: "doc-1", arguments: { folder_id: null } }
  ];
  const preview = buildPlanPreviewTree({ ...plan, operations }, tree);
  assert.deepEqual(preview.nodes.map((node) => node.id), ["b", "doc-1"]);
  assert.equal(preview.nodes[0].children[0].name, "분류");
});
