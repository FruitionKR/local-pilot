import assert from "node:assert/strict";
import test from "node:test";
import { buildDocumentCommandSteps, buildStatusSteps } from "../src/features/agent-chat/lib/agentData.ts";

test("일반 Query는 Wiki 검색과 근거 답변 단계를 유지한다", () => {
  assert.deepEqual(buildStatusSteps(false, true), [
    ["질문 분석", "done"],
    ["관련 Wiki page 검색", "done"],
    ["근거 기반 답변 작성", "done"]
  ]);
});

test("문서 편집 명령은 LLM 생성과 편집 단계를 표시한다", () => {
  assert.deepEqual(buildDocumentCommandSteps("markdown_edit", false, true), [
    ["질문 분석", "done"],
    ["LLM 답변 생성", "done"],
    ["문서 편집", "done"]
  ]);
});

test("새 문서 명령은 마지막 단계를 생성으로 구분한다", () => {
  assert.deepEqual(buildDocumentCommandSteps("markdown_create", true, false), [
    ["질문 분석", "done"],
    ["LLM 답변 생성", "active"],
    ["새 문서 생성", "pending"]
  ]);
});
