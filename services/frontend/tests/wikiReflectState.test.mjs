import assert from "node:assert/strict";
import test from "node:test";
import {
  getWikiReflectLabel,
  getWikiReflectState,
  isLintActionEnabled,
  isWikiReflectEligible
} from "../src/features/wiki-ingest/model/wikiReflectState.ts";

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

test("status가 processing이면 진행 중이라 반영을 요청할 수 없다", () => {
  const document = makeDocument({ status: "processing" });
  assert.equal(getWikiReflectState(document), "processing");
  assert.equal(isWikiReflectEligible(document), false);
});

test("processing_state가 starting이면 진행 중이다", () => {
  const document = makeDocument({ processing_state: "starting" });
  assert.equal(getWikiReflectState(document), "processing");
  assert.equal(isWikiReflectEligible(document), false);
});

test("processing_state가 running이면 진행 중이다", () => {
  const document = makeDocument({ status: "uploaded", processing_state: "running" });
  assert.equal(getWikiReflectState(document), "processing");
  assert.equal(isWikiReflectEligible(document), false);
});

test("진행 중이면 needs_reingest가 켜져 있어도 요청할 수 없다", () => {
  const document = makeDocument({ status: "processing", needs_reingest: true });
  assert.equal(getWikiReflectState(document), "processing");
  assert.equal(isWikiReflectEligible(document), false);
});

test("needs_reingest가 true면 변경 상태이고 재반영할 수 있다", () => {
  const document = makeDocument({ status: "completed", needs_reingest: true });
  assert.equal(getWikiReflectState(document), "changed");
  assert.equal(getWikiReflectLabel(document), "수정됨");
  assert.equal(isWikiReflectEligible(document), true);
});

test("status가 uploaded면 아직 미반영이고 반영할 수 있다", () => {
  const document = makeDocument({ status: "uploaded" });
  assert.equal(getWikiReflectState(document), "not-included");
  assert.equal(getWikiReflectLabel(document), "신규");
  assert.equal(isWikiReflectEligible(document), true);
});

test("status가 failed면 재시도할 수 있다", () => {
  const document = makeDocument({ status: "failed" });
  assert.equal(getWikiReflectState(document), "retry");
  assert.equal(getWikiReflectLabel(document), "재시도");
  assert.equal(isWikiReflectEligible(document), true);
});

test("completed이고 needs_reingest가 없으면 최신이라 요청할 수 없다", () => {
  const document = makeDocument({ status: "completed" });
  assert.equal(getWikiReflectState(document), "up-to-date");
  assert.equal(isWikiReflectEligible(document), false);
});

test("completed이고 needs_reingest가 false면 최신이다", () => {
  const document = makeDocument({ status: "completed", needs_reingest: false });
  assert.equal(getWikiReflectState(document), "up-to-date");
  assert.equal(isWikiReflectEligible(document), false);
});

test("processing_state가 completed면 진행 중으로 보지 않는다", () => {
  const document = makeDocument({ status: "completed", processing_state: "completed" });
  assert.equal(getWikiReflectState(document), "up-to-date");
  assert.equal(isWikiReflectEligible(document), false);
});

test("새 Wiki 내용이 있고 진행 중인 작업이 없을 때만 lint할 수 있다", () => {
  assert.equal(isLintActionEnabled({ needsLint: true, isIngestActive: false, isLintActive: false }), true);
  assert.equal(isLintActionEnabled({ needsLint: false, isIngestActive: false, isLintActive: false }), false);
  assert.equal(isLintActionEnabled({ needsLint: true, isIngestActive: true, isLintActive: false }), false);
  assert.equal(isLintActionEnabled({ needsLint: true, isIngestActive: false, isLintActive: true }), false);
});
