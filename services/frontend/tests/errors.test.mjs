import assert from "node:assert/strict";
import test from "node:test";

import { isErrorMessage } from "../src/shared/lib/errors.ts";

test("일치하는 Error 메시지만 인증 오류로 판별한다", () => {
  assert.equal(isErrorMessage(new Error("로그인이 필요합니다."), "로그인이 필요합니다."), true);
});

test("다른 오류와 Error가 아닌 값은 인증 오류로 판별하지 않는다", () => {
  assert.equal(isErrorMessage(new Error("서버에 연결할 수 없습니다."), "로그인이 필요합니다."), false);
  assert.equal(isErrorMessage("로그인이 필요합니다.", "로그인이 필요합니다."), false);
});
