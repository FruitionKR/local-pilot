import assert from "node:assert/strict";
import test from "node:test";
import {
  applyRequiredAgentSource,
  mergePendingNoteSave,
  recoverPendingNoteSaveAfterAgentFailure
} from "../src/features/note-editing/model/pendingSave.ts";

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

test("AI 저장 실패 시 대기 중인 최신 본문을 agent 저장으로 승격한다", () => {
  const recovery = recoverPendingNoteSaveAfterAgentFailure({
    markdown: "AI 편집 후 사용자 수정",
    revision: 3
  });

  assert.deepEqual(recovery, {
    pending: {
      markdown: "AI 편집 후 사용자 수정",
      revision: 3,
      source: "agent"
    },
    retryRequired: false
  });
});

test("AI 저장 실패 시 대기 본문이 없으면 다음 저장에 agent source가 필요함을 표시한다", () => {
  const recovery = recoverPendingNoteSaveAfterAgentFailure(null);

  assert.deepEqual(recovery, {
    pending: null,
    retryRequired: true
  });
});

test("AI 저장 실패 전에 만들어진 debounce 후보도 flush 시 agent 저장으로 승격한다", () => {
  const candidate = {
    markdown: "AI 편집 후 사용자 수정",
    revision: 3
  };

  assert.deepEqual(applyRequiredAgentSource(candidate, true), {
    ...candidate,
    source: "agent"
  });
});
