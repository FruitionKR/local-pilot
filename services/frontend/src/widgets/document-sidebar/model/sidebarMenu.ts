import type { RailView } from "@/widgets/rail-navigation/ui/RailNavigation";

export function canCreateProjectFromView(activeView: RailView) {
  return activeView === "home";
}

/**
 * 트리 밖 빈 영역 우클릭 시 컨텍스트 메뉴를 열 프로젝트 id를 결정한다.
 * graph/logs 뷰, 이미 열린 메뉴(defaultPrevented), 프로젝트 0개면 null을 반환한다.
 */
export function getBlankAreaContextProjectId(
  activeView: RailView,
  isMenuAlreadyOpened: boolean,
  projects: readonly { id: string }[]
) {
  if (activeView === "graph" || activeView === "logs") return null;
  if (isMenuAlreadyOpened || projects.length === 0) return null;
  return projects[0].id;
}
