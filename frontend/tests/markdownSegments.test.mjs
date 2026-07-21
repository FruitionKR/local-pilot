import assert from "node:assert/strict";
import test from "node:test";
import { splitMarkdownBlocks } from "../app/_lib/markdownSegments.ts";

test("비순서 중첩 목록의 들여쓰기를 보존한다", () => {
  const markdown = [
    "- 상위 항목",
    "  - 하위 항목",
    "    - 세부 항목"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
});

test("순서·비순서가 섞인 중첩 목록을 하나의 문맥으로 유지한다", () => {
  const markdown = [
    "1. 준비",
    "   - 설치",
    "     1. 확인",
    "2. 실행"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
});

test("중첩 체크리스트의 들여쓰기를 보존한다", () => {
  const markdown = [
    "- [ ] 상위 작업",
    "  - [x] 하위 작업"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
});
