import assert from "node:assert/strict";
import test from "node:test";
import { buildAgentTurnRequest, describeAgentTurnResult } from "../app/_lib/markdownAgent.ts";

const markdownEditContext = {
  documentId: "document-1",
  baseVersion: 3,
  editorSnapshot: {
    markdown: "# 제목\n\n본문",
    target: {
      type: "current_section",
      startLine: 1,
      endLine: 3
    }
  }
};

test("Agent turn 요청에 문서 version과 editor snapshot을 고정한다", () => {
  assert.deepEqual(buildAgentTurnRequest("본문을 다듬어줘", markdownEditContext), {
    documentId: "document-1",
    baseVersion: 3,
    message: "본문을 다듬어줘",
    editorSnapshot: markdownEditContext.editorSnapshot
  });
});

test("Markdown 편집 응답의 summary를 안내 문구로 사용한다", () => {
  assert.equal(describeAgentTurnResult({
    action: "markdown_edit",
    message: null,
    chat: null,
    edit: {
      operation: "replace",
      target: {
        type: "current_section",
        start_line: 1,
        end_line: 3
      },
      summary: "현재 섹션을 간결하게 정리했습니다.",
      replacement_markdown: "# 제목\n\n짧은 본문"
    },
    generated_markdown: null
  }), "현재 섹션을 간결하게 정리했습니다.");
});

test("clarify와 reject 응답은 서버 message를 안내 문구로 사용한다", () => {
  assert.equal(describeAgentTurnResult({
    action: "clarify",
    message: "편집할 범위를 선택해주세요.",
    chat: null,
    edit: null,
    generated_markdown: null
  }), "편집할 범위를 선택해주세요.");
});
