import assert from "node:assert/strict";
import test from "node:test";
import {
  buildMarkdownDocumentFilename,
  composeEditableNoteMarkdown,
  getMarkdownDocumentTitle,
  splitEditableNoteMarkdown
} from "../app/_lib/note.ts";

test("note marker를 편집 본문에서 분리한다", () => {
  assert.deepEqual(splitEditableNoteMarkdown("<!-- fruition-note: note-1 -->\n# 제목\n"), {
    marker: "<!-- fruition-note: note-1 -->",
    body: "# 제목\n"
  });
});

test("backend workspace marker도 편집 가능한 문서로 인식한다", () => {
  assert.deepEqual(splitEditableNoteMarkdown("<!-- fruition-workspace: workspace-1 -->\n# 제목\n"), {
    marker: "<!-- fruition-workspace: workspace-1 -->",
    body: "# 제목\n"
  });
});

test("marker가 없는 Markdown은 일반 미리보기 문서로 유지한다", () => {
  assert.equal(splitEditableNoteMarkdown("# 일반 문서\n"), null);
  assert.equal(
    composeEditableNoteMarkdown("<!-- fruition-workspace: workspace-1 -->", "# 수정한 제목"),
    "<!-- fruition-workspace: workspace-1 -->\n# 수정한 제목\n"
  );
});

test("문서 제목에서는 Markdown 확장자를 숨기고 이름 변경 시 기존 확장자를 유지한다", () => {
  assert.equal(getMarkdownDocumentTitle("새 노트.md"), "새 노트");
  assert.equal(buildMarkdownDocumentFilename("회의록", "새 노트.md"), "회의록.md");
  assert.equal(buildMarkdownDocumentFilename("회의록.md", "새 노트.markdown"), "회의록.markdown");
  assert.equal(buildMarkdownDocumentFilename("  ", "새 노트.md"), "새 노트.md");
});
