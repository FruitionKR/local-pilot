import assert from "node:assert/strict";
import test from "node:test";
import { canShowAgentPanel, isAgentPanelVisible } from "../src/widgets/workspace/lib/workspaceLayout.ts";

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
