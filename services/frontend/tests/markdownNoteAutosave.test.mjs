import assert from "node:assert/strict";
import test from "node:test";
import {
  applyRequiredAgentSource,
  mergePendingNoteSave,
  planAgentRetryAfterFailure,
  recoverPendingNoteSaveAfterAgentFailure,
  selectDetachedSaveCandidate
} from "../src/features/note-editing/model/pendingSave.ts";
import {
  trackPendingDocumentSave,
  waitForPendingDocumentSave
} from "../src/features/note-editing/model/pendingDocumentSave.ts";

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

test("AI 저장 대기 중 일반 편집이 이어져도 apply operation id를 보존한다", () => {
  const pending = mergePendingNoteSave(
    { markdown: "AI 편집 결과", revision: 2, source: "agent", applyOperationId: "op-1" },
    { markdown: "AI 편집 후 사용자 수정", revision: 3 }
  );

  assert.deepEqual(pending, {
    markdown: "AI 편집 후 사용자 수정",
    revision: 3,
    source: "agent",
    applyOperationId: "op-1"
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

test("밀린 저장이 없으면 사용자 편집 없이도 AI 저장을 다시 보낸다", () => {
  const recovered = recoverPendingNoteSaveAfterAgentFailure(null);

  assert.deepEqual(planAgentRetryAfterFailure(recovered, 0, 3, 1000), {
    shouldRetry: true,
    delayMs: 1000,
    attempts: 1
  });
});

test("재시도 간격은 시도할수록 늘어난다", () => {
  const recovered = recoverPendingNoteSaveAfterAgentFailure(null);

  assert.equal(planAgentRetryAfterFailure(recovered, 1, 3, 1000).delayMs, 2000);
  assert.equal(planAgentRetryAfterFailure(recovered, 2, 3, 1000).delayMs, 4000);
});

test("밀린 저장이 있으면 그쪽이 실어 가므로 재시도를 걸지 않는다", () => {
  const recovered = recoverPendingNoteSaveAfterAgentFailure({
    markdown: "AI 편집 후 사용자 수정",
    revision: 3
  });

  assert.deepEqual(planAgentRetryAfterFailure(recovered, 0, 3, 1000), {
    shouldRetry: false,
    delayMs: 0,
    attempts: 0
  });
});

test("재시도 횟수를 다 쓰면 더 보내지 않는다", () => {
  const recovered = recoverPendingNoteSaveAfterAgentFailure(null);

  assert.deepEqual(planAgentRetryAfterFailure(recovered, 3, 3, 1000), {
    shouldRetry: false,
    delayMs: 0,
    attempts: 3
  });
});

test("문서 이동 시 AI 재시도 후보를 마지막 저장 대상으로 보존한다", () => {
  const candidate = selectDetachedSaveCandidate(null, {
    markdown: "AI 편집 결과",
    revision: 2,
    source: "agent",
    applyOperationId: "op-1"
  });

  assert.deepEqual(candidate, {
    markdown: "AI 편집 결과",
    revision: 2,
    source: "agent",
    applyOperationId: "op-1"
  });
});

test("같은 문서를 다시 열 때 진행 중인 저장 완료를 기다린다", async () => {
  let resolveSave;
  const save = new Promise((resolve) => {
    resolveSave = resolve;
  });
  trackPendingDocumentSave("doc-1", save);

  let reopened = false;
  const reopen = waitForPendingDocumentSave("doc-1").then(() => {
    reopened = true;
  });
  await Promise.resolve();
  assert.equal(reopened, false);

  resolveSave();
  await reopen;
  assert.equal(reopened, true);
});
