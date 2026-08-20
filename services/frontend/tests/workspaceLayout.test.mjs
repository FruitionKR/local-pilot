import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { canShowAgentPanel, isAgentPanelVisible } from "../src/widgets/workspace/lib/workspaceLayout.ts";

const sidebarProfilePath = new URL(
  "../src/widgets/document-sidebar/ui/SidebarProfile.tsx",
  import.meta.url
);
const documentSidebarPath = new URL(
  "../src/widgets/document-sidebar/ui/DocumentSidebar.tsx",
  import.meta.url
);
const notificationsIndexPath = new URL(
  "../src/features/document-notifications/index.ts",
  import.meta.url
);
const noticeBusPath = new URL(
  "../src/features/document-notifications/model/noticeBus.ts",
  import.meta.url
);

test("그래프 뷰에서도 채팅 패널을 띄운다", () => {
  assert.equal(canShowAgentPanel("graph"), true);
  assert.equal(isAgentPanelVisible("graph", true), true);
});

test("그래프 채팅은 채팅 시작 전에는 닫혀 있다", () => {
  assert.equal(isAgentPanelVisible("graph", false), false);
});

test("홈 뷰 패널 동작은 그대로 유지한다", () => {
  assert.equal(isAgentPanelVisible("home", true), true);
  assert.equal(isAgentPanelVisible("home", false), false);
});

test("로그·규칙·설정 뷰는 채팅 패널을 렌더하지 않는다", () => {
  for (const view of ["logs", "rules", "settings"]) {
    assert.equal(canShowAgentPanel(view), false);
    assert.equal(isAgentPanelVisible(view, true), false);
  }
});

test("그래프에서 패널을 닫으면 접힘으로 본다(그래프가 우측까지 확장되고 알림이 우측에 붙는다)", () => {
  assert.equal(isAgentPanelVisible("graph", false), false);
});

test("프로필 메뉴는 설정과 로그아웃만 제공한다", async () => {
  const source = await readFile(sidebarProfilePath, "utf8");

  assert.doesNotMatch(source, /Wiki 관리/);
  assert.doesNotMatch(source, /NotificationsPanel/);
  assert.doesNotMatch(source, /isNotificationsOpen/);
  assert.match(source, />\s*설정\s*</);
  assert.match(source, />\s*로그아웃\s*</);
});

test("프로필은 Wiki 관리에만 필요했던 프로젝트 목록을 받지 않는다", async () => {
  const [profileSource, sidebarSource] = await Promise.all([
    readFile(sidebarProfilePath, "utf8"),
    readFile(documentSidebarPath, "utf8")
  ]);

  assert.match(profileSource, /export function SidebarProfile\(\)/);
  assert.match(sidebarSource, /<SidebarProfile\s*\/>/);
  assert.doesNotMatch(sidebarSource, /<SidebarProfile projects=/);
});

test("문서 알림 공개 API에서 Wiki 관리 패널을 제거한다", async () => {
  const [indexSource, busSource] = await Promise.all([
    readFile(notificationsIndexPath, "utf8"),
    readFile(noticeBusPath, "utf8")
  ]);

  assert.doesNotMatch(indexSource, /NotificationsPanel/);
  assert.doesNotMatch(busSource, /NoticeRecord|NoticeHistory|HISTORY_LIMIT/);
});
