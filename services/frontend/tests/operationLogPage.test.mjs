import assert from "node:assert/strict";
import test from "node:test";
import {
  appendLogPage,
  collectRestoredOperationIds,
  pickSelectedOperationId
} from "../src/entities/operation-log/model/operationLogPage.ts";
import { OPERATION_TYPE_LABELS } from "../src/entities/operation-log/model/operationType.ts";

function log(operationId, operationType = "document_edit") {
  return { operation_id: operationId, operation_type: operationType };
}

test("다음 페이지를 최신순 뒤에 이어붙인다", () => {
  const merged = appendLogPage([log("op1"), log("op2")], [log("op3"), log("op4")]);
  assert.deepEqual(merged.map((item) => item.operation_id), ["op1", "op2", "op3", "op4"]);
});

test("커서 경계에서 겹친 operation_id는 다시 붙이지 않는다", () => {
  const merged = appendLogPage([log("op1"), log("op2")], [log("op2"), log("op3")]);
  assert.deepEqual(merged.map((item) => item.operation_id), ["op1", "op2", "op3"]);
});

test("새 항목이 없으면 이전 배열을 그대로 돌려준다", () => {
  const previous = [log("op1")];
  assert.equal(appendLogPage(previous, [log("op1")]), previous);
  assert.equal(appendLogPage(previous, []), previous);
});

test("이전 배열을 변형하지 않는다", () => {
  const previous = [log("op1")];
  appendLogPage(previous, [log("op2")]);
  assert.deepEqual(previous.map((item) => item.operation_id), ["op1"]);
});

test("유형이 섞인 목록도 시간순 그대로 이어붙인다", () => {
  const merged = appendLogPage(
    [log("op1", "ingest")],
    [log("op2", "lint"), log("op3", "restore")]
  );
  assert.deepEqual(merged.map((item) => item.operation_type), ["ingest", "lint", "restore"]);
});

test("선택이 없으면 가장 최근 작업을 고른다", () => {
  assert.equal(pickSelectedOperationId([log("op1"), log("op2")], null), "op1");
});

test("이미 고른 작업이 목록에 있으면 유지한다", () => {
  assert.equal(pickSelectedOperationId([log("op1"), log("op2")], "op2"), "op2");
});

test("고른 작업이 목록에서 사라지면 가장 최근 작업으로 되돌린다", () => {
  assert.equal(pickSelectedOperationId([log("op1"), log("op2")], "gone"), "op1");
});

test("목록이 비면 선택이 없다", () => {
  assert.equal(pickSelectedOperationId([], "op1"), null);
  assert.equal(pickSelectedOperationId([], null), null);
});

test("restore 로그에서 이미 롤백한 원본 작업 ID를 수집한다", () => {
  const restoredIds = collectRestoredOperationIds([
    { ...log("restore-1", "restore"), restored_from: "op-1" },
    { ...log("op-2"), restored_from: null }
  ]);

  assert.deepEqual([...restoredIds], ["op-1"]);
});

test("작업 유형 라벨은 AI Edit·Ingest·Lint·Restore 4종이다", () => {
  assert.deepEqual(OPERATION_TYPE_LABELS, {
    document_edit: "AI Edit",
    ingest: "Ingest",
    lint: "Lint",
    restore: "Restore"
  });
});
