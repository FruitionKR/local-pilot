import assert from "node:assert/strict";
import test from "node:test";
import { buildOperationLogQuery } from "../src/entities/operation-log/model/operationLogQuery.ts";

test("조건이 없으면 쿼리스트링을 붙이지 않는다", () => {
  assert.equal(buildOperationLogQuery(), "");
  assert.equal(buildOperationLogQuery({}), "");
});

test("커서만 있으면 cursor만 보낸다", () => {
  assert.equal(buildOperationLogQuery({ cursor: "2026-08-17T00:00:00Z" }), "?cursor=2026-08-17T00%3A00%3A00Z");
});

test("유형만 있으면 type만 보낸다", () => {
  assert.equal(buildOperationLogQuery({ type: "lint" }), "?type=lint");
});

test("유형과 커서를 함께 보낸다", () => {
  assert.equal(
    buildOperationLogQuery({ cursor: "2026-08-17T00:00:00Z", type: "ingest" }),
    "?type=ingest&cursor=2026-08-17T00%3A00%3A00Z"
  );
});

test("진행 중인 lint 조회는 type·status·size를 함께 보낸다", () => {
  assert.equal(
    buildOperationLogQuery({ type: "lint", status: "processing", size: 1 }),
    "?type=lint&status=processing&size=1"
  );
});

test("size 0도 조건으로 보낸다(백엔드가 기본값으로 보정한다)", () => {
  assert.equal(buildOperationLogQuery({ size: 0 }), "?size=0");
});

test("빈 문자열 커서는 조건으로 보내지 않는다", () => {
  assert.equal(buildOperationLogQuery({ cursor: "" }), "");
});

test("로그 사이드바 목록은 유형 조건 없이 size만 보낸다", () => {
  assert.equal(buildOperationLogQuery({ size: 30 }), "?size=30");
});

test("로그 사이드바 더 보기는 커서와 size를 함께 보낸다", () => {
  assert.equal(
    buildOperationLogQuery({ cursor: "2026-08-17T00:00:00Z", size: 30 }),
    "?cursor=2026-08-17T00%3A00%3A00Z&size=30"
  );
});
