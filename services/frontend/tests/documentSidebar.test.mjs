import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { canCreateProjectFromView } from "../src/widgets/document-sidebar/model/sidebarMenu.ts";

test("새 폴더 버튼은 홈 뷰에서만 표시한다", () => {
  assert.equal(canCreateProjectFromView("home"), true);

  for (const view of ["graph", "logs", "rules", "settings"]) {
    assert.equal(canCreateProjectFromView(view), false);
  }
});

test("문서 컨텍스트 메뉴는 사이드바 스태킹 컨텍스트를 벗어나 body에 렌더한다", async () => {
  const source = await readFile(
    new URL("../src/widgets/document-sidebar/ui/ContextMenu.tsx", import.meta.url),
    "utf8"
  );

  assert.match(source, /createPortal\s*\(/);
  assert.match(source, /document\.body/);
});
