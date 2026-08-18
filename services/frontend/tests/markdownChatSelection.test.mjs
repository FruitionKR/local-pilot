import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyChatExportPairs,
  EMPTY_CHAT_PAIR_RANGE_SELECTION,
  selectChatPairRange
} from "../src/features/agent-chat/lib/chatPairSelection.ts";
import { getChatExportSuccessMessage } from "../src/features/wiki-export/lib/exportStatus.ts";

const pairIds = ["pair-1", "pair-2", "pair-3", "pair-4"];

test("문서 편집 명령은 편입 후보에서 제외하고 일반 답변은 유지한다", () => {
  const messages = [
    { pair_id: "pair-1", role: "user", content: "질문", status: "completed" },
    { pair_id: "pair-1", role: "assistant", content: "답변", status: "completed", action: "chat_answer" },
    { pair_id: "pair-2", role: "user", content: "고쳐줘", status: "completed" },
    { pair_id: "pair-2", role: "assistant", content: "수정", status: "completed", action: "markdown_edit" },
    { pair_id: "pair-3", role: "user", content: "예전 질문", status: "completed" },
    { pair_id: "pair-3", role: "assistant", content: "예전 답변", status: "completed" }
  ];

  assert.deepEqual(classifyChatExportPairs(messages), {
    selectablePairIds: ["pair-1", "pair-3"],
    excludedPairIds: ["pair-2"]
  });
});

test("비동기 문서 작업과 Skill 작업도 편입 후보에서 제외한다", () => {
  const actions = [
    "folder_organize",
    "workspace_workflow",
    "skill_authoring",
    "skill_draft_proposal",
    "clarify",
    "reject"
  ];
  const messages = actions.flatMap((action, index) => [
    { pair_id: `pair-${index}`, role: "user", content: "작업해줘", status: "completed" },
    { pair_id: `pair-${index}`, role: "assistant", content: "작업 시작", status: "completed", action }
  ]);

  assert.deepEqual(classifyChatExportPairs(messages), {
    selectablePairIds: [],
    excludedPairIds: ["pair-0", "pair-1", "pair-2", "pair-3", "pair-4", "pair-5"]
  });
});

test("첫 클릭은 해당 문답 하나만 선택한다", () => {
  assert.deepEqual(selectChatPairRange(pairIds, EMPTY_CHAT_PAIR_RANGE_SELECTION, "pair-2"), {
    anchorPairId: "pair-2",
    endPairId: "pair-2",
    selectedPairIds: ["pair-2"]
  });
});

test("두 번째 클릭은 첫 클릭부터 마지막 클릭까지 선택한다", () => {
  const first = selectChatPairRange(pairIds, EMPTY_CHAT_PAIR_RANGE_SELECTION, "pair-1");

  assert.deepEqual(selectChatPairRange(pairIds, first, "pair-3").selectedPairIds, [
    "pair-1",
    "pair-2",
    "pair-3"
  ]);
});

test("뒤에서 앞으로 선택해도 결과는 대화 시간순을 유지한다", () => {
  const first = selectChatPairRange(pairIds, EMPTY_CHAT_PAIR_RANGE_SELECTION, "pair-4");

  assert.deepEqual(selectChatPairRange(pairIds, first, "pair-2").selectedPairIds, [
    "pair-2",
    "pair-3",
    "pair-4"
  ]);
});

test("추가 클릭은 첫 기준점을 유지한 채 마지막 범위를 갱신한다", () => {
  const first = selectChatPairRange(pairIds, EMPTY_CHAT_PAIR_RANGE_SELECTION, "pair-2");
  const second = selectChatPairRange(pairIds, first, "pair-4");

  assert.deepEqual(selectChatPairRange(pairIds, second, "pair-1"), {
    anchorPairId: "pair-2",
    endPairId: "pair-1",
    selectedPairIds: ["pair-1", "pair-2"]
  });
});

test("현재 대화에 없는 문답 클릭은 선택을 바꾸지 않는다", () => {
  const current = selectChatPairRange(pairIds, EMPTY_CHAT_PAIR_RANGE_SELECTION, "pair-2");

  assert.equal(selectChatPairRange(pairIds, current, "missing-pair"), current);
});

test("채팅 Export 결과가 skipped면 기존 문서를 열었다고 안내한다", () => {
  assert.equal(
    getChatExportSuccessMessage("skipped"),
    "동일한 내용의 원문 문서가 있어 기존 문서를 열었습니다."
  );
  assert.match(getChatExportSuccessMessage("processing"), /AI 처리 파이프라인/);
});
