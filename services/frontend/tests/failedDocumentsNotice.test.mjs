import assert from "node:assert/strict";
import test from "node:test";
import { buildFailedDocumentsNotice } from "../src/features/document-notifications/model/failedDocumentsNotice.ts";

test("실패 1건은 파일명과 실패 사유를 그대로 보여준다", () => {
  const notice = buildFailedDocumentsNotice([
    { filename: "report.pdf", error_message: "페이지를 추출하지 못했습니다." }
  ]);

  assert.equal(notice.kind, "failed");
  assert.equal(notice.title, "문서 처리 실패");
  assert.equal(notice.message, '"report.pdf" 처리에 실패했습니다. 페이지를 추출하지 못했습니다.');
});

test("실패 사유가 없으면 기본 안내 문구로 대체한다", () => {
  const notice = buildFailedDocumentsNotice([{ filename: "report.pdf" }]);

  assert.equal(notice.message, '"report.pdf" 처리에 실패했습니다. 실패 사유를 확인해 주세요.');
});

test("여러 건 실패는 카드 하나로 묶고 개수와 파일명을 보여준다", () => {
  const notice = buildFailedDocumentsNotice([
    { filename: "a.pdf", error_message: "이유 A" },
    { filename: "b.pdf", error_message: "이유 B" }
  ]);

  assert.equal(notice.kind, "failed");
  assert.equal(
    notice.message,
    '2개 문서를 처리하지 못했습니다. ("a.pdf", "b.pdf") 실패 사유는 각 문서에서 확인해 주세요.'
  );
});

test("파일명이 많으면 3개까지만 나열하고 나머지는 외 N개로 줄인다", () => {
  const notice = buildFailedDocumentsNotice(
    ["a.pdf", "b.pdf", "c.pdf", "d.pdf", "e.pdf"].map((filename) => ({ filename }))
  );

  assert.equal(
    notice.message,
    '5개 문서를 처리하지 못했습니다. ("a.pdf", "b.pdf", "c.pdf" 외 2개) 실패 사유는 각 문서에서 확인해 주세요.'
  );
});
