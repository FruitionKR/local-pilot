import assert from "node:assert/strict";
import test from "node:test";
import {
  isWikiReflectEligible,
  selectActiveIngestDocuments
} from "../src/features/wiki-ingest/model/wikiReflectState.ts";
import { formatLintProgressLabel } from "../src/features/wiki-ingest/model/activeLintOperation.ts";

function makeDocument(overrides) {
  return {
    id: "doc-1",
    filename: "note.md",
    mime_type: "text/markdown",
    byte_size: 10,
    status: "completed",
    source_uri: "s3://bucket/doc-1",
    uploaded_at: "2026-08-16T00:00:00Z",
    document_role: "EDITABLE",
    ...overrides
  };
}

function makeLog(overrides) {
  return {
    operation_id: "op_1",
    operation_type: "lint",
    status: "processing",
    target_document_id: null,
    summary: null,
    changed_resource_count: 0,
    restored_from: null,
    created_at: "2026-08-17T00:00:00Z",
    completed_at: null,
    ...overrides
  };
}

test("진행 중인 문서를 모두 돌려준다", () => {
  const documents = [
    makeDocument({ id: "doc-1", filename: "a.pdf", status: "processing" }),
    makeDocument({ id: "doc-2", filename: "b.md", processing_state: "running" }),
    makeDocument({ id: "doc-3", filename: "c.md", status: "completed" })
  ];
  assert.deepEqual(
    selectActiveIngestDocuments(documents).map((document) => document.filename),
    ["a.pdf", "b.md"]
  );
});

test("진행 중인 문서가 없으면 빈 목록이다", () => {
  const documents = [
    makeDocument({ status: "uploaded" }),
    makeDocument({ id: "doc-2", status: "failed" })
  ];
  assert.deepEqual(selectActiveIngestDocuments(documents), []);
});

test("heartbeat가 끊긴 stalled 문서도 진행 중으로 본다", () => {
  const stalled = makeDocument({ filename: "stalled.md", processing_state: "stalled" });
  assert.deepEqual(
    selectActiveIngestDocuments([stalled]).map((document) => document.filename),
    ["stalled.md"]
  );
});

test("stalled 문서는 반영 요청을 다시 받지 않는다", () => {
  const stalled = makeDocument({ processing_state: "stalled", needs_reingest: true });
  assert.equal(isWikiReflectEligible(stalled), false);
});

test("진행 중인 lint 로그가 있으면 진행 라벨을 만든다", () => {
  assert.equal(formatLintProgressLabel(makeLog({}), false), "위키 다듬기 진행 중");
});

test("로그가 아직 안 보여도 방금 보낸 요청은 진행 중으로 표시한다", () => {
  assert.equal(formatLintProgressLabel(null, true), "위키 다듬기 진행 중");
});

test("진행 중인 lint가 없으면 라벨이 없다", () => {
  assert.equal(formatLintProgressLabel(null, false), null);
});
