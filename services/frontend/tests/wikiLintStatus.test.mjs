import assert from "node:assert/strict";
import test from "node:test";
import {
  shouldRequestWikiLint,
  WIKI_UP_TO_DATE_NOTICE
} from "../src/features/document-notifications/model/wikiLintStatus.ts";

test("Wiki가 최신이면 lint 실행 요청을 만들지 않는다", () => {
  assert.equal(shouldRequestWikiLint(false), false);
  assert.deepEqual(WIKI_UP_TO_DATE_NOTICE, {
    title: "Wiki 최신 상태",
    message: "Wiki가 이미 최신 상태입니다."
  });
});

test("Wiki 변경이 있으면 lint 실행 요청을 허용한다", () => {
  assert.equal(shouldRequestWikiLint(true), true);
});
