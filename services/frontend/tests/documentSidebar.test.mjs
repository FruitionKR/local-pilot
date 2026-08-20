import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { canCreateProjectFromView } from "../src/widgets/document-sidebar/model/sidebarMenu.ts";

const sidebarMenuRowPath = new URL(
  "../src/widgets/document-sidebar/ui/SidebarMenuRow.tsx",
  import.meta.url
);
const contextMenuPath = new URL(
  "../src/widgets/document-sidebar/ui/ContextMenu.tsx",
  import.meta.url
);
const documentSidebarPath = new URL(
  "../src/widgets/document-sidebar/ui/DocumentSidebar.tsx",
  import.meta.url
);

test("새 폴더 버튼은 홈 뷰에서만 표시한다", () => {
  assert.equal(canCreateProjectFromView("home"), true);

  for (const view of ["graph", "logs", "rules", "settings"]) {
    assert.equal(canCreateProjectFromView(view), false);
  }
});

test("메뉴 행의 새 폴더 버튼은 canCreateProjectFromView로 감싼다", async () => {
  const source = await readFile(sidebarMenuRowPath, "utf8");

  // 조건을 지우거나 다른 조건으로 바꾸면 실패하도록, 게이트와 버튼의 연결을 검증한다.
  assert.match(
    source,
    /canCreateProjectFromView\(activeView\)\s*&&\s*\(\s*<button[^>]*\n(?:.*\n)*?\s*aria-label="새 폴더 생성"/
  );
});

test("컨텍스트 메뉴의 새 폴더도 같은 생성 정책을 따른다", async () => {
  const [menuSource, sidebarSource] = await Promise.all([
    readFile(contextMenuPath, "utf8"),
    readFile(documentSidebarPath, "utf8")
  ]);

  assert.match(menuSource, /canCreateProject\s*&&\s*\(\s*<button[^>]*>새 폴더<\/button>/);
  assert.match(sidebarSource, /canCreateProject=\{canCreateProjectFromView\(activeView\)\}/);
});

test("문서 컨텍스트 메뉴는 사이드바 스태킹 컨텍스트를 벗어나 body에 렌더한다", async () => {
  const source = await readFile(contextMenuPath, "utf8");

  // createPortal(<JSX>, document.body) 형태로 실제 포탈 대상이 body인지까지 검증한다.
  assert.match(source, /return createPortal\(/);
  assert.match(source, /,\s*document\.body\s*\)/);
});
