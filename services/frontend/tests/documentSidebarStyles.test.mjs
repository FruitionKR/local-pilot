import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const sidebarStyles = readFileSync(
  new URL("../src/widgets/document-sidebar/ui/DocumentSidebar.module.css", import.meta.url),
  "utf8"
);

test("폴더 행의 파일 업로드 버튼은 idle 상태에서 숨긴다", () => {
  assert.match(
    sidebarStyles,
    /\.project-add-file\s*\{[^}]*opacity:\s*0;[^}]*pointer-events:\s*none;/s
  );
});

test("폴더 행 hover 또는 keyboard focus 상태에서 파일 업로드 버튼을 표시한다", () => {
  assert.match(
    sidebarStyles,
    /\.project-title:hover \.project-add-file,\s*\.project-title:focus-within \.project-add-file\s*\{[^}]*opacity:\s*1;[^}]*pointer-events:\s*auto;/s
  );
});
