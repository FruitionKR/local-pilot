import assert from "node:assert/strict";
import test from "node:test";
import { buildMarkdownEditorSnapshot } from "../app/_lib/markdownEditContext.ts";

test("문자 selection을 포함하는 line 전체 범위로 변환한다", () => {
  const markdown = "첫째 줄\n둘째 줄\n셋째 줄";
  const from = markdown.indexOf("둘째");
  const to = markdown.indexOf("줄", markdown.indexOf("셋째")) + 1;

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, from, to), {
    markdown,
    target: { type: "selection", startLine: 2, endLine: 3 }
  });
});

test("selection 끝이 다음 line 시작이면 이전 line까지만 포함한다", () => {
  const markdown = "첫째 줄\n둘째 줄\n셋째 줄";
  const from = markdown.indexOf("둘째");
  const to = markdown.indexOf("셋째");

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, from, to).target, {
    type: "selection",
    startLine: 2,
    endLine: 2
  });
});

test("cursor가 속한 가장 가까운 heading section을 계산한다", () => {
  const markdown = [
    "# 문서",
    "소개",
    "## 설치",
    "설치 안내",
    "### 상세",
    "상세 안내",
    "## 실행",
    "실행 안내"
  ].join("\n");
  const cursor = markdown.indexOf("상세 안내");

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, cursor, cursor).target, {
    type: "current_section",
    startLine: 5,
    endLine: 6
  });
});

test("상위 section은 하위 heading을 포함하고 다음 같은 단계 전에 끝난다", () => {
  const markdown = [
    "# 문서",
    "소개",
    "## 설치",
    "설치 안내",
    "### 상세",
    "상세 안내",
    "## 실행"
  ].join("\n");
  const cursor = markdown.indexOf("설치 안내");

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, cursor, cursor).target, {
    type: "current_section",
    startLine: 3,
    endLine: 6
  });
});

test("code fence 내부 heading 표시는 section 경계로 사용하지 않는다", () => {
  const markdown = [
    "## 예제",
    "```markdown",
    "# 코드 안 제목",
    "```",
    "설명",
    "## 다음"
  ].join("\n");
  const cursor = markdown.indexOf("설명");

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, cursor, cursor).target, {
    type: "current_section",
    startLine: 1,
    endLine: 5
  });
});

test("cursor 앞에 heading이 없으면 문서 전체를 대상으로 한다", () => {
  const markdown = "제목 없는 문서\n본문";

  assert.deepEqual(buildMarkdownEditorSnapshot(markdown, 0, 0).target, {
    type: "whole_document",
    startLine: 1,
    endLine: 2
  });
});
