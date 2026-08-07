import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAgentTurnRequest,
  buildGeneratedMarkdownFilename,
  describeAgentTurnResult,
  prepareMarkdownEditPreview,
  validateMarkdownEditApplication
} from "../src/features/agent-chat/lib/markdownAgent.ts";

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
      requested_target: {
        type: "current_section",
        start_line: 1,
        end_line: 3
      },
      actual_target: {
        type: "current_section",
        start_line: 1,
        end_line: 3
      },
      scope_expanded: false,
      changed: true,
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

test("AI 생성 문서 제목을 안전한 Markdown 파일명으로 변환한다", () => {
  assert.equal(buildGeneratedMarkdownFilename(" API/계약: 초안? "), "API-계약-초안.md");
  assert.equal(buildGeneratedMarkdownFilename("회의록.md"), "회의록.md");
  assert.equal(buildGeneratedMarkdownFilename("   "), "AI 문서.md");
});

function markdownEditResponse(overrides = {}) {
  return {
    documentId: "document-1",
    baseVersion: 3,
    requestId: "request-1",
    result: {
      action: "markdown_edit",
      route: {
        action: "markdown_edit",
        confidence: 0.95,
        reason: "편집 요청",
        edit_goal: "cleanup"
      },
      message: null,
      chat: null,
      edit: {
        operation: "replace",
        requested_target: {
          type: "current_section",
          start_line: 1,
          end_line: 3
        },
        actual_target: {
          type: "current_section",
          start_line: 1,
          end_line: 3
        },
        scope_expanded: false,
        changed: true,
        summary: "본문을 정리했습니다.",
        replacement_markdown: "# 제목\n\n정리한 본문"
      },
      generated_markdown: null
    },
    ...overrides
  };
}

test("검증된 replace 응답으로 line diff와 다음 Markdown을 만든다", () => {
  const request = buildAgentTurnRequest("본문을 다듬어줘", markdownEditContext);
  const preview = prepareMarkdownEditPreview(request, markdownEditResponse());

  assert.equal(preview.nextMarkdown, "# 제목\n\n정리한 본문");
  assert.deepEqual(preview.diffLines, [
    { type: "context", text: "# 제목" },
    { type: "context", text: "" },
    { type: "delete", text: "본문" },
    { type: "insert", text: "정리한 본문" }
  ]);
});

test("insert_after는 현재 section 뒤에 새 Markdown을 추가한다", () => {
  const request = buildAgentTurnRequest("다음 절을 추가해줘", markdownEditContext);
  const response = markdownEditResponse({
    result: {
      ...markdownEditResponse().result,
      edit: {
        operation: "insert_after",
        requested_target: {
          type: "current_section",
          start_line: 1,
          end_line: 3
        },
        actual_target: {
          type: "current_section",
          start_line: 1,
          end_line: 3
        },
        scope_expanded: false,
        changed: true,
        summary: "절을 추가했습니다.",
        replacement_markdown: "\n## 다음\n\n추가 본문"
      }
    }
  });

  const preview = prepareMarkdownEditPreview(request, response);

  assert.equal(preview.nextMarkdown, "# 제목\n\n본문\n\n## 다음\n\n추가 본문");
  assert.deepEqual(preview.diffLines, [
    { type: "insert", text: "" },
    { type: "insert", text: "## 다음" },
    { type: "insert", text: "" },
    { type: "insert", text: "추가 본문" }
  ]);
});

test("응답 requested target이 요청 target과 다르면 preview를 만들지 않는다", () => {
  const request = buildAgentTurnRequest("본문을 다듬어줘", markdownEditContext);
  const response = markdownEditResponse({
    result: {
      ...markdownEditResponse().result,
      edit: {
        ...markdownEditResponse().result.edit,
        requested_target: {
          type: "selection",
          start_line: 2,
          end_line: 3
        }
      }
    }
  });

  assert.throws(
    () => prepareMarkdownEditPreview(request, response),
    /편집 대상이 요청 범위와 일치하지 않습니다/
  );
});

test("확장된 actual target을 기준으로 preview를 만든다", () => {
  const context = {
    ...markdownEditContext,
    editorSnapshot: {
      ...markdownEditContext.editorSnapshot,
      target: {
        type: "selection",
        startLine: 3,
        endLine: 3
      }
    }
  };
  const request = buildAgentTurnRequest("문맥을 포함해 다듬어줘", context);
  const response = markdownEditResponse({
    result: {
      ...markdownEditResponse().result,
      edit: {
        ...markdownEditResponse().result.edit,
        requested_target: {
          type: "selection",
          start_line: 3,
          end_line: 3
        },
        actual_target: {
          type: "selection",
          start_line: 1,
          end_line: 3
        },
        scope_expanded: true,
        replacement_markdown: "# 제목\n\n문맥을 포함해 정리한 본문"
      }
    }
  });

  assert.equal(
    prepareMarkdownEditPreview(request, response).nextMarkdown,
    "# 제목\n\n문맥을 포함해 정리한 본문"
  );
});

test("요청 이후 editor Markdown이 바뀌면 오래된 결과를 거절한다", () => {
  const request = buildAgentTurnRequest("본문을 다듬어줘", markdownEditContext);
  const changedContext = {
    ...markdownEditContext,
    editorSnapshot: {
      ...markdownEditContext.editorSnapshot,
      markdown: "# 제목\n\n사용자가 바꾼 본문"
    }
  };

  assert.throws(
    () => validateMarkdownEditApplication(request, markdownEditResponse(), changedContext),
    /요청 이후 문서가 변경되었습니다/
  );
});

test("응답의 문서 version이 요청과 다르면 결과를 거절한다", () => {
  const request = buildAgentTurnRequest("본문을 다듬어줘", markdownEditContext);

  assert.throws(
    () => prepareMarkdownEditPreview(request, markdownEditResponse({ baseVersion: 4 })),
    /문서 version이 요청과 일치하지 않습니다/
  );
});

const markdownReplacementFixtures = [
  ["요약", "핵심 내용만 남긴 요약입니다."],
  ["번역", "This paragraph was translated."],
  ["문체 변경", "본 기능은 안정적인 편집 경험을 제공합니다."],
  ["Markdown 정리", "## 정리된 제목\n\n정리된 본문"],
  ["표 변환", "| 항목 | 상태 |\n| --- | --- |\n| 편집 | 완료 |"],
  ["checklist 변환", "- [x] 계약 확인\n- [ ] diff 검토"],
  ["회의록 변환", "## 회의 결과\n\n- 결정: diff 승인 후 적용\n- 담당: Frontend"]
];

for (const [name, replacementMarkdown] of markdownReplacementFixtures) {
  test(`${name} Markdown 결과를 변형 없이 다음 buffer에 반영한다`, () => {
    const request = buildAgentTurnRequest(`${name}해줘`, markdownEditContext);
    const response = markdownEditResponse({
      result: {
        ...markdownEditResponse().result,
        edit: {
          ...markdownEditResponse().result.edit,
          replacement_markdown: replacementMarkdown
        }
      }
    });

    assert.equal(prepareMarkdownEditPreview(request, response).nextMarkdown, replacementMarkdown);
  });
}
