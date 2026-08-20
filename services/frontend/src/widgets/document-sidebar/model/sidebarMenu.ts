import type { RailView } from "@/widgets/rail-navigation/ui/RailNavigation";

export function canCreateProjectFromView(activeView: RailView) {
  return activeView === "home";
}
