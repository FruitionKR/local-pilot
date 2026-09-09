import assert from "node:assert/strict";
import test from "node:test";

import { getErrorMessage, isErrorMessage } from "../src/shared/lib/errors.ts";

test("AI 설정 오류와 분류 오류를 사용자 입력 오류로 안내하지 않는다", () => {
  assert.equal(getErrorMessage(new Error("agent_not_configured"), "실패"),
    "서버 설정으로 인해 AI 작업 기능을 사용할 수 없습니다. 관리자에게 문의해 주세요.");
  assert.equal(getErrorMessage(new Error("agent_turn_route_contract_failed"), "실패"),
    "AI의 작업 분류 응답을 처리하지 못했습니다. 다시 시도해 주세요.");
  assert.equal(getErrorMessage(new Error("다른 오류"), "실패"), "다른 오류");
  assert.equal(getErrorMessage(null, "실패"), "실패");
});

test("일치하는 Error 메시지만 인증 오류로 판별한다", () => {
  assert.equal(isErrorMessage(new Error("로그인이 필요합니다."), "로그인이 필요합니다."), true);
});

test("다른 오류와 Error가 아닌 값은 인증 오류로 판별하지 않는다", () => {
  assert.equal(isErrorMessage(new Error("서버에 연결할 수 없습니다."), "로그인이 필요합니다."), false);
  assert.equal(isErrorMessage("로그인이 필요합니다.", "로그인이 필요합니다."), false);
});
