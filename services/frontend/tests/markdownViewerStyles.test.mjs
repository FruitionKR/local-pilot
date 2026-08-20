import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const markdownStyles = readFileSync(
  new URL("../src/app/styles/markdown.css", import.meta.url),
  "utf8"
);
const sourcePreviewStyles = readFileSync(
  new URL("../src/widgets/source-preview/ui/SourcePreviewPanel.module.css", import.meta.url),
  "utf8"
);

test("읽기 모드의 표 외곽선과 셀 경계를 표시한다", () => {
  assert.match(
    markdownStyles,
    /\.markdown-viewer table\s*\{[^}]*border:\s*1px solid var\(--line\);[^}]*border-collapse:\s*collapse;/s
  );
  assert.match(
    markdownStyles,
    /\.markdown-viewer th,\s*\.markdown-viewer td\s*\{[^}]*border:\s*1px solid var\(--line\);/s
  );
});

test("읽기 모드 링크는 어두운 기본 파란색 대신 밝고 낮은 채도의 파란색을 사용한다", () => {
  assert.match(
    markdownStyles,
    /\.markdown-viewer a,\s*\.markdown-wikilink\s*\{[^}]*color:\s*#8ab4e8;/s
  );
});

test("원문 읽기 모드의 굵은 글씨는 뒤따르는 콜론과 본문을 같은 줄에 둔다", () => {
  const strongRule = sourcePreviewStyles.match(/\.source-preview-content strong\s*\{([^}]*)\}/s);

  assert.ok(strongRule);
  assert.doesNotMatch(strongRule[1], /display:\s*block;/);
  assert.match(strongRule[1], /font-weight:\s*700;/);
});
