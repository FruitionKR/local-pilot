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

test("목록 항목 내부의 code block과 인용문을 같은 문맥으로 유지한다", () => {
  const markdown = [
    "1. 설치하기",
    "",
    "   ```bash",
    "   npm install",
    "   ```",
    "",
    "   > 설치 전에 Node.js가 필요합니다.",
    "2. 실행하기"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
});

test("목록 항목 내부의 여러 줄 본문을 같은 문맥으로 유지한다", () => {
  const markdown = [
    "- 준비",
    "",
    "  필요한 패키지를 먼저 확인합니다.",
    "  설치 경로도 함께 확인합니다.",
    "- 실행"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
});

test("목록 뒤의 일반 문단은 별도 문맥으로 분리한다", () => {
  const markdown = [
    "- 목록 항목",
    "",
    "목록 밖 문단"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: "- 목록 항목" },
    { kind: "markdown", content: "목록 밖 문단" }
  ]);
});
