import assert from "node:assert/strict";
import test from "node:test";
import { mergePendingNoteSave } from "../src/features/note-editing/model/pendingSave.ts";

test("AI 저장 대기 중 일반 편집이 이어져도 agent source를 보존한다", () => {
  const pending = mergePendingNoteSave(
    { markdown: "AI 편집 결과", revision: 2, source: "agent" },
    { markdown: "AI 편집 후 사용자 수정", revision: 3 }
  );

  assert.deepEqual(pending, {
    markdown: "AI 편집 후 사용자 수정",
    revision: 3,
    source: "agent"
  });
});

test("일반 저장 대기 중 AI 편집이 적용되면 최신 본문을 agent 저장으로 승격한다", () => {
  const pending = mergePendingNoteSave(
    { markdown: "사용자 수정", revision: 2 },
    { markdown: "AI 편집 결과", revision: 3, source: "agent" }
  );

  assert.deepEqual(pending, {
    markdown: "AI 편집 결과",
    revision: 3,
    source: "agent"
  });
});
