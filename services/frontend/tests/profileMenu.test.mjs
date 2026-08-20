import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("프로필 메뉴는 설정과 로그아웃만 제공한다", async () => {
  const source = await readFile(
    new URL("../src/widgets/document-sidebar/ui/SidebarProfile.tsx", import.meta.url),
    "utf8"
  );

  assert.doesNotMatch(source, /Wiki 관리|NotificationsPanel|isNotificationsOpen/);
  assert.match(source, />\s*설정\s*</);
  assert.match(source, />\s*로그아웃\s*</);
});
