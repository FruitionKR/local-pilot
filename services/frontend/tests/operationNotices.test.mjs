import assert from "node:assert/strict";
import test from "node:test";
import {
  collectTerminalNotices,
  nextKnownStatuses
} from "../src/features/document-notifications/model/operationNotices.ts";

const ALL_ON = { lint: true, restore: true };

function log(operation_id, operation_type, status, summary = null) {
  return { operation_id, operation_type, status, summary, changed_resource_count: 1 };
}

test("첫 폴링은 기준선만 잡고 알림을 발행하지 않는다", () => {
  const notices = collectTerminalNotices(null, [log("op1", "lint", "succeeded")], ALL_ON);

  assert.deepEqual(notices, []);
});

test("진행 중이던 작업이 종결되면 알림을 발행한다", () => {
  const previous = new Map([["op1", "processing"]]);

  const notices = collectTerminalNotices(previous, [log("op1", "lint", "succeeded")], ALL_ON);

  assert.equal(notices.length, 1);
  assert.equal(notices[0].kind, "completed");
  assert.equal(notices[0].title, "Lint 완료");
});

test("폴링 사이에 새로 나타나 이미 종결된 작업도 발행한다", () => {
  const previous = new Map();

  const notices = collectTerminalNotices(previous, [log("op1", "restore", "succeeded")], ALL_ON);

  assert.equal(notices.length, 1);
  assert.equal(notices[0].title, "복구 완료");
});

test("이미 종결로 알고 있던 작업은 다시 발행하지 않는다", () => {
  const previous = new Map([["op1", "succeeded"]]);

  const notices = collectTerminalNotices(previous, [log("op1", "lint", "succeeded")], ALL_ON);

  assert.deepEqual(notices, []);
});

test("실패와 충돌은 failed 알림으로 발행하고 요약을 그대로 쓴다", () => {
  const previous = new Map([["op1", "processing"], ["op2", "applying"]]);

  const notices = collectTerminalNotices(previous, [
    log("op1", "lint", "failed", "Wiki 정합성 검사에 실패했습니다."),
    log("op2", "restore", "conflict", "미리보기가 낡았습니다.")
  ], ALL_ON);

  assert.equal(notices.length, 2);
  assert.equal(notices[0].kind, "failed");
  assert.equal(notices[0].title, "Lint 실패");
  assert.equal(notices[0].message, "Wiki 정합성 검사에 실패했습니다.");
  assert.equal(notices[1].kind, "failed");
  assert.equal(notices[1].title, "복구 실패");
});

test("요약이 비어 있으면 기본 문구를 쓴다", () => {
  const previous = new Map([["op1", "processing"]]);

  const notices = collectTerminalNotices(previous, [log("op1", "lint", "failed", null)], ALL_ON);

  assert.equal(notices[0].message, "Lint 작업이 실패했습니다.");
});

test("꺼 둔 유형과 감시 대상이 아닌 유형은 발행하지 않는다", () => {
  const previous = new Map([["op1", "processing"], ["op2", "processing"], ["op3", "processing"]]);

  const notices = collectTerminalNotices(previous, [
    log("op1", "lint", "succeeded"),
    log("op2", "ingest", "succeeded"),
    log("op3", "document_edit", "succeeded")
  ], { lint: false, restore: true });

  assert.deepEqual(notices, []);
});

test("모든 조회가 성공하면 기준을 받은 것으로 교체한다", () => {
  const previous = new Map([["op_gone", "succeeded"]]);

  const next = nextKnownStatuses(previous, [log("op1", "lint", "succeeded")], true);

  assert.deepEqual([...next], [["op1", "succeeded"]]);
});

test("일부 조회가 실패하면 이전 기준을 남겨 중복 알림을 막는다", () => {
  // 실패 조회가 빠져 op_failed를 못 본 폴링. 기준에서 지우면 다음 폴링에서
  // "새로 나타난 종결"로 잡혀 같은 알림이 다시 뜬다.
  const previous = new Map([["op_failed", "failed"]]);

  const next = nextKnownStatuses(previous, [log("op1", "lint", "succeeded")], false);

  assert.equal(next.get("op_failed"), "failed");
  assert.equal(next.get("op1"), "succeeded");
  assert.deepEqual(collectTerminalNotices(next, [log("op_failed", "lint", "failed")], ALL_ON), []);
});
