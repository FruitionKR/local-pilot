import type { RailView } from "@/widgets/rail-navigation/ui/RailNavigation";

/** AgentPanel을 렌더할 수 있는 뷰. 홈과 그래프에서만 채팅 패널을 함께 띄운다. */
export function canShowAgentPanel(view: RailView): boolean {
  return view === "home" || view === "graph";
}

/** 패널이 실제로 화면에 있는가. 렌더 조건과 우측 여백 계산의 단일 기준. */
export function isAgentPanelVisible(view: RailView, isOpen: boolean): boolean {
  return canShowAgentPanel(view) && isOpen;
}
